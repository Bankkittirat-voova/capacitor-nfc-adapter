package com.school.nfcadapter.handler.serial

/**
 * Pure builders/parsers for the NXP PN532 host-controller UART/HSU protocol
 * (NXP UM0701-02, §6.2 "Normal information frame"). No Android imports — fully
 * unit-testable with golden byte vectors, exactly like [com.school.nfcadapter.handler.ccid.CcidProtocol].
 *
 * This is the wire format for the command-driven serial NFC reader class:
 * devices that DO NOT stream a UID on tap, but answer host commands. The first
 * observed member is the "PCR532 / PM5" duplicator, an ATmega-CDC bridge in
 * front of a PN532 at 115200 baud — but nothing here is device-specific; any
 * PN532-over-UART reader speaks it.
 *
 * Normal frame:  00 00 FF  LEN  LCS  TFI  PD0..PDn  DCS  00
 *   LEN = 1 (TFI) + payload length
 *   LCS = (0x100 - LEN) & 0xFF                     (LEN + LCS == 0x00 mod 256)
 *   TFI = 0xD4 host->PN532,  0xD5 PN532->host
 *   DCS = (0x100 - sum(TFI, PD0..PDn)) & 0xFF
 *
 * Only normal frames are emitted/parsed (LEN in 1..254); the UID-read exchange
 * never needs the extended (0xFF 0xFF) frame form.
 */
object Pn532Frame {

    const val TFI_HOST: Int = 0xD4      // Data Frame In  (host -> PN532)
    const val TFI_PN532: Int = 0xD5     // Data Frame Out (PN532 -> host)

    // Command codes (payload byte 0). Read-only subset — NO write/clone/auth.
    const val CMD_GET_FIRMWARE_VERSION: Int = 0x02
    const val CMD_SAM_CONFIGURATION: Int = 0x14
    const val CMD_RF_CONFIGURATION: Int = 0x32
    const val CMD_IN_LIST_PASSIVE_TARGET: Int = 0x4A

    /** IC byte returned by GetFirmwareVersion for a genuine PN532. */
    const val IC_PN532: Int = 0x32

    /**
     * HSU wake-up preamble. A PN532 in low-power/standby needs bus activity
     * before its UART clock is stable; libnfc prepends the same run of 0x55.
     */
    val WAKEUP: ByteArray = byteArrayOf(
        0x55, 0x55, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00,
        0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00
    )

    /** ACK frame. Host->PN532 it ABORTS the command in progress (critical for
     *  the hang-safe poll loop); PN532->host it acknowledges receipt. */
    val ACK: ByteArray = byteArrayOf(0x00, 0x00, 0xFF.toByte(), 0x00, 0xFF.toByte(), 0x00)

    val GET_FIRMWARE_VERSION: ByteArray = buildCommand(byteArrayOf(CMD_GET_FIRMWARE_VERSION.toByte()))

    /** SAMConfiguration: normal mode, no virtual-card timeout, IRQ off. */
    val SAM_CONFIGURATION: ByteArray =
        buildCommand(byteArrayOf(CMD_SAM_CONFIGURATION.toByte(), 0x01, 0x00, 0x00))

    /**
     * RFConfiguration, CfgItem 0x05 (MaxRetries) — the init step libnfc's pn53x
     * driver performs before polling and which this profile previously omitted.
     * Without it, MxRtyPassiveActivation defaults to 0xFF (infinite) and
     * InListPassiveTarget BLOCKS the chip scanning forever, so our per-cycle
     * ACK-abort races detection and the reader stays silent.
     *   [0x32, 0x05, MxRtyATR, MxRtyPSL, MxRtyPassiveActivation]
     * PassiveActivation = 0x01: one attempt per poll → the chip returns a prompt
     * `4B 00` (no target) when the field is empty and a target frame when a card
     * is present. Read-only configuration; energizes nothing destructive.
     */
    val RF_CONFIG_MAX_RETRIES: ByteArray =
        buildCommand(byteArrayOf(CMD_RF_CONFIGURATION.toByte(), 0x05, 0xFF.toByte(), 0x01, 0x01))

    /** InListPassiveTarget: MaxTg=1, BrTy=0x00 (106 kbps ISO14443 Type A). */
    val IN_LIST_PASSIVE_TARGET_106A: ByteArray =
        buildCommand(byteArrayOf(CMD_IN_LIST_PASSIVE_TARGET.toByte(), 0x01, 0x00))

    /**
     * Wrap a payload (TFI is added here) into a normal information frame.
     * @param payload command code + parameters, e.g. [0x4A, 0x01, 0x00].
     */
    fun buildCommand(payload: ByteArray): ByteArray {
        val len = payload.size + 1                       // +1 for TFI
        val lcs = (0x100 - len) and 0xFF
        var sum = TFI_HOST
        for (b in payload) sum += b.toInt() and 0xFF
        val dcs = (0x100 - (sum and 0xFF)) and 0xFF
        val out = ByteArray(6 + payload.size + 2)
        out[0] = 0x00; out[1] = 0x00; out[2] = 0xFF.toByte()
        out[3] = len.toByte(); out[4] = lcs.toByte(); out[5] = TFI_HOST.toByte()
        payload.copyInto(out, 6)
        out[6 + payload.size] = dcs.toByte()
        out[7 + payload.size] = 0x00
        return out
    }

    /** True if [buf] (first [len] bytes) contains a PN532 ACK frame anywhere. */
    fun containsAck(buf: ByteArray, len: Int): Boolean = indexOfAck(buf, len) >= 0

    private fun indexOfAck(buf: ByteArray, len: Int): Int {
        var i = 0
        while (i + 6 <= len) {
            if (buf[i] == 0x00.toByte() && buf[i + 1] == 0x00.toByte() &&
                buf[i + 2] == 0xFF.toByte() && buf[i + 3] == 0x00.toByte() &&
                buf[i + 4] == 0xFF.toByte() && buf[i + 5] == 0x00.toByte()
            ) return i
            i++
        }
        return -1
    }

    sealed interface Parsed {
        /** A valid PN532->host response; [payload] starts at the response code
         *  (command code + 1), i.e. TFI is already stripped. */
        data class Response(val payload: ByteArray) : Parsed
        /** A well-formed response frame is not present yet — read more bytes. */
        object Incomplete : Parsed
        /** A frame boundary was found but it was structurally invalid. */
        data class Invalid(val reason: String) : Parsed
    }

    /**
     * Locate and validate the first PN532->host normal response frame in [buf]
     * (first [len] bytes). Skips a leading ACK frame if present. Returns the
     * payload with TFI removed, or Incomplete/Invalid.
     */
    fun parseResponse(buf: ByteArray, len: Int): Parsed {
        var start = 0
        val ackAt = indexOfAck(buf, len)
        if (ackAt >= 0) start = ackAt + 6            // response follows the ACK

        // Find the 00 00 FF start-of-frame at or after `start`.
        var i = start
        var sof = -1
        while (i + 3 <= len) {
            if (buf[i] == 0x00.toByte() && buf[i + 1] == 0x00.toByte() && buf[i + 2] == 0xFF.toByte()) {
                // Distinguish from an ACK frame (LEN==0x00 LCS==0xFF).
                if (i + 5 < len && buf[i + 3] == 0x00.toByte() && buf[i + 4] == 0xFF.toByte()) {
                    i += 6; continue                 // this is another ACK, skip it
                }
                sof = i; break
            }
            i++
        }
        if (sof < 0) return Parsed.Incomplete
        if (sof + 5 > len) return Parsed.Incomplete   // need LEN, LCS, TFI

        val lenField = buf[sof + 3].toInt() and 0xFF
        val lcs = buf[sof + 4].toInt() and 0xFF
        if (lenField == 0xFF) return Parsed.Invalid("extended frame not supported")
        if (((lenField + lcs) and 0xFF) != 0) return Parsed.Invalid("bad LCS")
        // Frame total: 3 (SOF) + 1 (LEN) + 1 (LCS) + lenField (TFI+payload) + 1 (DCS) + 1 (postamble)
        val frameEnd = sof + 5 + lenField + 1         // index just past DCS
        if (frameEnd > len) return Parsed.Incomplete  // wait for the rest

        val tfi = buf[sof + 5].toInt() and 0xFF
        if (tfi != TFI_PN532) return Parsed.Invalid("unexpected TFI %02X".format(tfi))

        var sum = tfi
        val payloadLen = lenField - 1
        val payload = ByteArray(payloadLen)
        for (k in 0 until payloadLen) {
            val b = buf[sof + 6 + k].toInt() and 0xFF
            payload[k] = b.toByte()
            sum += b
        }
        val dcs = buf[sof + 6 + payloadLen].toInt() and 0xFF
        if (((sum + dcs) and 0xFF) != 0) return Parsed.Invalid("bad DCS")
        return Parsed.Response(payload)
    }

    /** @return the PN532 IC byte from a GetFirmwareVersion payload, or null. */
    fun firmwareIc(payload: ByteArray): Int? {
        // payload: [0x03 (=CMD+1), IC, Ver, Rev, Support]
        if (payload.size < 2 || (payload[0].toInt() and 0xFF) != CMD_GET_FIRMWARE_VERSION + 1) return null
        return payload[1].toInt() and 0xFF
    }

    sealed interface UidResult {
        data class Ok(val uid: ByteArray) : UidResult
        /** No target in the RF field (NbTg == 0) — the "no card" case. */
        object NoTarget : UidResult
        data class Reject(val reason: String) : UidResult
    }

    /**
     * Extract the raw UID (NFCID1, MSB-first) from an InListPassiveTarget
     * response payload. Layout for one 106 kbps Type-A target:
     *   [0x4B, NbTg, Tg, SENS_RES(2), SEL_RES, NFCIDLength, NFCID1(NFCIDLength)...]
     * The raw UID is returned unmodified; normalization to uid_dec_reversed
     * happens centrally in ConnectionManager (never in a handler).
     */
    fun extractUid(payload: ByteArray): UidResult {
        if (payload.isEmpty() || (payload[0].toInt() and 0xFF) != CMD_IN_LIST_PASSIVE_TARGET + 1) {
            return UidResult.Reject("not an InListPassiveTarget response")
        }
        if (payload.size < 2) return UidResult.Reject("truncated response")
        val nbTg = payload[1].toInt() and 0xFF
        if (nbTg == 0) return UidResult.NoTarget
        // Tg(1) SENS_RES(2) SEL_RES(1) NFCIDLength(1) at offsets 2..5, UID at 6.
        val idLenIdx = 2 + 1 + 2 + 1
        if (payload.size <= idLenIdx) return UidResult.Reject("no NFCIDLength")
        val idLen = payload[idLenIdx].toInt() and 0xFF
        val uidStart = idLenIdx + 1
        if (uidStart + idLen > payload.size) return UidResult.Reject("UID runs past frame")
        if (idLen != 4 && idLen != 7 && idLen != 10) return UidResult.Reject("invalid UID length $idLen")
        return UidResult.Ok(payload.copyOfRange(uidStart, uidStart + idLen))
    }
}
