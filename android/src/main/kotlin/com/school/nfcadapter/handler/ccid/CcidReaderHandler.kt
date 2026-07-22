package com.school.nfcadapter.handler.ccid

import com.school.nfcadapter.NfcModuleConfig
import com.school.nfcadapter.handler.NfcReaderHandler
import com.school.nfcadapter.transport.TransferFailureException
import com.school.nfcadapter.transport.UsbTransport
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive

/**
 * Generic CCID (smart-card class) engine. Pure Kotlin + coroutines over the
 * UsbTransport seam — no Android imports, so the complete tap state machine is
 * exercised by JVM tests with scripted byte streams.
 *
 * Edge-triggered: a UID is emitted exactly once per absent -> present
 * transition; a dwelling card does not re-fire. All bulk transfers use bounded
 * timeouts so cancellation is observed within one poll cycle.
 */
class CcidReaderHandler(
    private val transport: UsbTransport,
    private val config: NfcModuleConfig = NfcModuleConfig()
) : NfcReaderHandler {

    private var seq: Byte = 0
    private fun nextSeq(): Byte = seq++

    override suspend fun initialize(): Boolean = transport.claim()

    override suspend fun runPollingLoop(emit: suspend (ByteArray) -> Unit) {
        val inBuf = ByteArray(config.readBufferSize)
        var cardPresent = false
        var consecutiveFailures = 0

        while (currentCoroutineContext().isActive) {
            val present = pollPresence(inBuf)
            if (present == null) {
                consecutiveFailures++
                if (consecutiveFailures >= config.maxConsecutiveTransferFailures) {
                    throw TransferFailureException(
                        "CCID: $consecutiveFailures consecutive transfer failures"
                    )
                }
                delay(config.absentPollDelayMs)
                continue
            }
            consecutiveFailures = 0

            when {
                present && !cardPresent -> {                     // EDGE: the tap
                    when (val r = readUidValidated(inBuf)) {
                        is CcidProtocol.UidResult.Ok -> {
                            emit(r.uid)
                            cardPresent = true
                        }
                        CcidProtocol.UidResult.CardGone -> {
                            // Card flicked away mid-read: stay disarmed, silently
                            // wait for the next stable tap ("no beep = tap again").
                            config.logger("[CCID] card left field mid-read (flick) — discarded")
                        }
                        is CcidProtocol.UidResult.Reject -> {
                            // Arm anyway: one attempt per presence episode, so an
                            // unsupported card can't cause a retry storm.
                            cardPresent = true
                            config.logger("[CCID] read rejected: ${r.reason}")
                        }
                    }
                }
                !present && cardPresent -> cardPresent = false   // re-arm for next tap
            }
            delay(if (cardPresent) config.presentPollDelayMs else config.absentPollDelayMs)
        }
    }

    override fun close() = transport.release()

    /** @return present / absent, or null on transfer failure or malformed frame. */
    private fun pollPresence(inBuf: ByteArray): Boolean? {
        val seq = nextSeq()
        if (transport.bulkOut(CcidProtocol.buildGetSlotStatus(seq), config.statusTimeoutMs) < 0) return null
        val n = transport.bulkIn(inBuf, config.statusTimeoutMs)
        if (n < 0) return null
        // Stale frame from an earlier timed-out command: treat as a transfer
        // failure so the failure counter resyncs (or restarts) the session
        // instead of livelocking one response behind the reader.
        if (n >= CcidProtocol.HEADER_LEN && inBuf[6] != seq) return null
        return CcidProtocol.parseSlotStatusPresent(inBuf, n)
    }

    private fun readUidValidated(inBuf: ByteArray): CcidProtocol.UidResult {
        // 1) Power the card on (returns ATR in a DataBlock). Absence here = flick.
        val seqPowerOn = nextSeq()
        if (transport.bulkOut(CcidProtocol.buildIccPowerOn(seqPowerOn), config.transferTimeoutMs) < 0) {
            return CcidProtocol.UidResult.Reject("power-on write failed")
        }
        val nAtr = transport.bulkIn(inBuf, config.transferTimeoutMs)
        if (nAtr < 0) return CcidProtocol.UidResult.Reject("power-on read failed")
        if (nAtr >= CcidProtocol.HEADER_LEN && inBuf[6] != seqPowerOn) {
            return CcidProtocol.UidResult.Reject("power-on seq mismatch (stale frame)")
        }
        when (CcidProtocol.parseSlotStatusPresent(inBuf, nAtr)) {
            false -> return CcidProtocol.UidResult.CardGone
            null -> return CcidProtocol.UidResult.Reject("malformed power-on response")
            true -> Unit
        }
        config.logger("[CCID] ATR received (${nAtr - CcidProtocol.HEADER_LEN} bytes): ${sensAtr(inBuf, CcidProtocol.HEADER_LEN, nAtr)} — parsed ok, card active")

        // 2) GET DATA (UID), fully validated.
        val first = xfrGetUid(inBuf)
        if (first !is CcidProtocol.UidResult.Ok || !config.strictReRead) return first

        // 3) Strict mode: immediate second read must match bit-for-bit.
        return when (val second = xfrGetUid(inBuf)) {
            is CcidProtocol.UidResult.Ok ->
                if (second.uid.contentEquals(first.uid)) first
                else CcidProtocol.UidResult.Reject("strict re-read mismatch (RF corruption)")
            else -> CcidProtocol.UidResult.CardGone   // vanished between reads = flick
        }
    }

    private fun xfrGetUid(inBuf: ByteArray): CcidProtocol.UidResult {
        val seq = nextSeq()
        val cmd = CcidProtocol.buildXfrBlock(seq, CcidProtocol.GET_UID_APDU)
        if (transport.bulkOut(cmd, config.transferTimeoutMs) < 0) {
            return CcidProtocol.UidResult.Reject("XfrBlock write failed")
        }
        val n = transport.bulkIn(inBuf, config.transferTimeoutMs)
        if (n < 0) return CcidProtocol.UidResult.Reject("XfrBlock read failed")
        if (n >= CcidProtocol.HEADER_LEN && inBuf[6] != seq) {
            return CcidProtocol.UidResult.Reject("XfrBlock seq mismatch (stale frame)")
        }
        return CcidProtocol.parseUidResponse(inBuf, n)
    }

    private fun hex(buf: ByteArray, from: Int, to: Int) =
        (from until to).joinToString(" ") { "%02X".format(buf[it]) }

    /** ATR bytes are masked in normal logs; full hex only when
     *  config.logSensitiveValues is enabled (DEBUG/LOCAL ONLY). */
    private fun sensAtr(buf: ByteArray, from: Int, to: Int) =
        if (config.logSensitiveValues) hex(buf, from, to) else "<${to - from}B masked>"
}
