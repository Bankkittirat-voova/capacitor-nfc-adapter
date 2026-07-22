package com.school.nfcadapter.handler.serial

import com.school.nfcadapter.NfcModuleConfig
import com.school.nfcadapter.transport.TransferFailureException
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive

/**
 * Bidirectional seam over a serial link so the whole command/response engine is
 * JVM-testable with a scripted implementation (no device, no usb-serial). Real
 * implementation (UsbSerialPn532Link) wraps a usb-serial-for-android port.
 * read(): bytes read, 0 on timeout with no data, < 0 on link failure.
 */
interface Pn532Link {
    fun write(data: ByteArray): Boolean
    fun read(dst: ByteArray, timeoutMs: Int): Int
    fun close()
}

/**
 * The command-driven PN532 engine core: handshake -> (optional) SAM config ->
 * edge-triggered InListPassiveTarget poll. Pure Kotlin + coroutines; the caller
 * supplies the clock so tests control timing.
 *
 * HANG-SAFETY (a hard requirement for this reader class):
 *  - exactly one command in flight at a time,
 *  - the input buffer is drained before every command,
 *  - InListPassiveTarget BLOCKS the chip until a card enters the field, so on a
 *    read timeout we send an ACK frame (which ABORTS the in-flight command) —
 *    this is why closing the port mid-poll can never leave the PN532 wedged,
 *  - N consecutive timeouts raise TransferFailureException("pn532_timeout...") so
 *    the ConnectionManager tears the session down instead of looping forever.
 */
class Pn532ReadEngine(
    private val link: Pn532Link,
    private val config: NfcModuleConfig = NfcModuleConfig(),
    private val clock: () -> Long = { System.nanoTime() / 1_000_000 }
) {
    private val rx = ByteArray(config.readBufferSize)

    /**
     * Wake the chip, prove it is a PN532 (GetFirmwareVersion IC == 0x32), then
     * put it in normal read mode. @return true only on a positive handshake —
     * this is the gate that stops the profile from claiming a non-PN532 serial
     * device that happens to share the VID/PID route.
     */
    suspend fun handshakeAndConfigure(): Boolean {
        link.write(Pn532Frame.WAKEUP)
        drain(60)

        if (!link.write(Pn532Frame.GET_FIRMWARE_VERSION)) {
            config.logger("[PN532] handshake write failed")
            return false
        }
        config.logger("[PN532] TX GetFirmwareVersion ${hex(Pn532Frame.GET_FIRMWARE_VERSION)}")
        val fw = readFrame(config.statusTimeoutMs)
        if (fw !is Rx.Frame) {
            config.logger("[PN532] handshake: no response (not a PN532 / wrong baud)")
            return false
        }
        val ic = Pn532Frame.firmwareIc(fw.payload)
        config.logger("[PN532] RX firmware payload ${hex(fw.payload)} ic=${ic?.let { "%02X".format(it) } ?: "?"}")
        if (ic != Pn532Frame.IC_PN532) {
            config.logger("[PN532] handshake rejected: IC != 0x32")
            return false
        }

        // Normal mode, no SAM timeout. Best-effort: some bridge firmwares reply
        // with D5 15, some swallow it — either way the poll works, so a missing
        // response here is not fatal.
        drain(30)
        link.write(Pn532Frame.SAM_CONFIGURATION)
        config.logger("[PN532] TX SAMConfiguration ${hex(Pn532Frame.SAM_CONFIGURATION)}")
        logRx("SAMConfiguration", readFrame(config.statusTimeoutMs))

        // RFConfiguration MaxRetries (libnfc parity). Bounds passive activation so
        // InListPassiveTarget returns promptly instead of blocking; without it the
        // reader stayed silent to every poll. Best-effort — a missing reply is not
        // fatal, but its RX tells us whether the chip accepts config at all.
        drain(30)
        link.write(Pn532Frame.RF_CONFIG_MAX_RETRIES)
        config.logger("[PN532] TX RFConfiguration(MaxRetries) ${hex(Pn532Frame.RF_CONFIG_MAX_RETRIES)}")
        logRx("RFConfiguration", readFrame(config.statusTimeoutMs))
        return true
    }

    /** Log the outcome of a config-command read so the trace shows whether the
     *  chip acknowledges SAM/RF config (silence here => bridge swallows replies). */
    private fun logRx(cmd: String, rx: Rx) = when (rx) {
        is Rx.Frame -> config.logger("[PN532] RX $cmd ${hex(rx.payload)}")
        Rx.Timeout -> config.logger("[PN532] RX $cmd: none (timeout)")
        Rx.LinkDead -> config.logger("[PN532] RX $cmd: link dead")
    }

    /**
     * Edge-triggered poll: one UID emitted per absent->present transition; a
     * dwelling card does not re-fire (the host-side lock/cooldown upstream is a
     * second net). Observes cancellation within one bounded poll cycle.
     *
     * KEY DIFFERENCE from the CCID engine: InListPassiveTarget BLOCKS the chip
     * until a card enters the field, so a read timeout with no card is the NORMAL
     * standby state — NOT a failure. We abort the blocked command and keep polling
     * indefinitely. Only a genuine link loss (read() < 0 = detach/brownout),
     * sustained for [maxConsecutiveTransferFailures] cycles, tears the session
     * down. (Treating idle timeouts as failures previously killed the session ~3s
     * after arming, before a driver could tap.)
     */
    suspend fun runLoop(emit: suspend (ByteArray) -> Unit) {
        var cardPresent = false
        var consecutiveLinkDead = 0
        var noTargetRun = 0        // consecutive prompt "no target" replies (RF alive)
        var timeoutRun = 0         // consecutive silent polls (no RX at all)
        config.logger("[PN532] polling started (InListPassiveTarget ${hex(Pn532Frame.IN_LIST_PASSIVE_TARGET_106A)}) — idle timeouts are normal until a card taps")

        while (currentCoroutineContext().isActive) {
            drain(0)
            link.write(Pn532Frame.IN_LIST_PASSIVE_TARGET_106A)

            when (val r = readFrame(config.transferTimeoutMs)) {
                is Rx.Frame -> {
                    consecutiveLinkDead = 0
                    timeoutRun = 0
                    when (val u = Pn532Frame.extractUid(r.payload)) {
                        is Pn532Frame.UidResult.Ok -> {
                            noTargetRun = 0
                            if (!cardPresent) {
                                config.logger("[PN532] RX target frame (${r.payload.size}B) -> UID ${sens(u.uid)}")
                                emit(u.uid)
                                cardPresent = true
                            }
                        }
                        Pn532Frame.UidResult.NoTarget -> {
                            // RF field IS energizing and the command path works —
                            // the chip is actively reporting an empty field. Log the
                            // first, then throttle so a held-empty reader can't spam.
                            if (noTargetRun == 0 || noTargetRun % 25 == 0) {
                                config.logger("[PN532] poll: no target (RF alive, run=$noTargetRun)")
                            }
                            noTargetRun++
                            if (cardPresent) config.logger("[PN532] target removed — re-armed")
                            cardPresent = false          // re-arm for next tap
                        }
                        is Pn532Frame.UidResult.Reject -> {
                            // One attempt per presence episode: arm so a bad card
                            // can't spin a retry storm; never emit an unvalidated value.
                            cardPresent = true
                            noTargetRun = 0
                            config.logger("[PN532] read rejected: ${u.reason} raw=${sens(r.payload)}")
                        }
                    }
                }
                Rx.Timeout -> {
                    // No card in the field this cycle. Abort the still-blocked
                    // InListPassiveTarget so the chip is ready for the next poll,
                    // then keep polling. This is standby, not a failure.
                    // A LONG run of pure timeouts (never a `4B 00`) means the chip
                    // is silent to InListPassiveTarget — RF/command path dead, not
                    // merely "no card" — so surface that distinctly.
                    consecutiveLinkDead = 0
                    if (timeoutRun == 0 || timeoutRun % 25 == 0) {
                        config.logger("[PN532] poll: silent (no RX, run=$timeoutRun) — chip not answering InListPassiveTarget")
                    }
                    timeoutRun++
                    abort()
                    cardPresent = false
                }
                Rx.LinkDead -> {
                    consecutiveLinkDead++
                    config.logger("[PN532] link read failed ($consecutiveLinkDead/${config.maxConsecutiveTransferFailures})")
                    if (consecutiveLinkDead >= config.maxConsecutiveTransferFailures) {
                        throw TransferFailureException("pn532_link_lost_requires_replug")
                    }
                }
            }
            delay(if (cardPresent) config.presentPollDelayMs else config.absentPollDelayMs)
        }
    }

    /** Send an ACK frame — aborts any in-flight PN532 command. Idempotent-safe. */
    fun abort() {
        runCatching {
            link.write(Pn532Frame.ACK)
            // Consume the ACK-of-ACK / trailing bytes so they can't prefix the
            // next response.
            link.read(rx, 40)
        }
    }

    /** Outcome of one bounded read: a parsed frame, an idle timeout (no card /
     *  chip blocked — recoverable), or a dead link (detach — escalate). */
    private sealed interface Rx {
        data class Frame(val payload: ByteArray) : Rx
        object Timeout : Rx
        object LinkDead : Rx
    }

    /**
     * Accumulate bytes until a complete PN532 response frame is parsed or
     * [deadlineMs] elapses. A leading ACK is skipped by the parser. An invalid
     * frame is reported as Timeout (drop this cycle, keep the session) rather
     * than LinkDead — only a physical read failure is LinkDead.
     */
    private fun readFrame(deadlineMs: Int): Rx {
        val acc = ArrayList<Byte>(64)
        val end = clock() + deadlineMs
        while (true) {
            val remaining = (end - clock()).toInt()
            if (remaining <= 0) return Rx.Timeout
            val n = link.read(rx, remaining.coerceAtMost(config.transferTimeoutMs))
            if (n < 0) return Rx.LinkDead                 // physical link failure (detach)
            if (n == 0) continue                          // timeout tick, keep waiting
            for (k in 0 until n) acc.add(rx[k])
            val snapshot = acc.toByteArray()
            when (val p = Pn532Frame.parseResponse(snapshot, snapshot.size)) {
                is Pn532Frame.Parsed.Response -> return Rx.Frame(p.payload)
                is Pn532Frame.Parsed.Invalid -> {
                    config.logger("[PN532] invalid frame: ${p.reason} raw=${sens(snapshot)}")
                    return Rx.Timeout
                }
                Pn532Frame.Parsed.Incomplete -> { /* read more */ }
            }
        }
    }

    /** Drain any pending input for up to [ms] milliseconds (buffer hygiene). */
    private fun drain(ms: Int) {
        if (ms <= 0) { link.read(rx, 1); return }
        val end = clock() + ms
        while (clock() < end) {
            if (link.read(rx, 5) <= 0) break
        }
    }

    private fun hex(b: ByteArray) = b.joinToString(" ") { "%02X".format(it.toInt() and 0xFF) }

    /** Card-identifier bytes (UID / raw target frames) are masked in normal logs;
     *  full hex only when config.logSensitiveValues is enabled (DEBUG/LOCAL ONLY). */
    private fun sens(b: ByteArray) = if (config.logSensitiveValues) hex(b) else "<${b.size}B masked>"
}
