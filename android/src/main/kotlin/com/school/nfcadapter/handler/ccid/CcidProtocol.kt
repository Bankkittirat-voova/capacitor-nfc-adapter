package com.school.nfcadapter.handler.ccid

/**
 * Pure builders/parsers for the USB CCID (smart-card class 0x0B) wire protocol.
 * No Android imports — fully unit-testable. Frame layouts follow the USB CCID
 * Revision 1.1 specification; see Appendix A of the module spec document.
 */
object CcidProtocol {

    const val HEADER_LEN = 10

    // PC_to_RDR message types
    const val PC_ICC_POWER_ON: Byte = 0x62
    const val PC_ICC_POWER_OFF: Byte = 0x63
    const val PC_GET_SLOT_STATUS: Byte = 0x65
    const val PC_XFR_BLOCK: Byte = 0x6F

    // RDR_to_PC message types
    const val RDR_DATA_BLOCK: Byte = 0x80.toByte()
    const val RDR_SLOT_STATUS: Byte = 0x81.toByte()

    /** GET DATA (UID) — PC/SC contactless pseudo-APDU: FF CA 00 00 00. */
    val GET_UID_APDU = byteArrayOf(0xFF.toByte(), 0xCA.toByte(), 0x00, 0x00, 0x00)

    private fun header(type: Byte, dataLen: Int, slot: Byte, seq: Byte): ByteArray {
        val b = ByteArray(HEADER_LEN + dataLen)
        b[0] = type
        b[1] = (dataLen and 0xFF).toByte()          // dwLength, little-endian
        b[2] = ((dataLen shr 8) and 0xFF).toByte()
        b[3] = ((dataLen shr 16) and 0xFF).toByte()
        b[4] = ((dataLen shr 24) and 0xFF).toByte()
        b[5] = slot
        b[6] = seq
        // bytes 7..9: message-specific (bBWI / abRFU / wLevelParameter) — zero here
        return b
    }

    fun buildIccPowerOn(seq: Byte, slot: Byte = 0): ByteArray = header(PC_ICC_POWER_ON, 0, slot, seq)
    fun buildIccPowerOff(seq: Byte, slot: Byte = 0): ByteArray = header(PC_ICC_POWER_OFF, 0, slot, seq)
    fun buildGetSlotStatus(seq: Byte, slot: Byte = 0): ByteArray = header(PC_GET_SLOT_STATUS, 0, slot, seq)

    fun buildXfrBlock(seq: Byte, apdu: ByteArray, slot: Byte = 0): ByteArray {
        val b = header(PC_XFR_BLOCK, apdu.size, slot, seq)
        apdu.copyInto(b, HEADER_LEN)
        return b
    }

    sealed interface UidResult {
        data class Ok(val uid: ByteArray) : UidResult
        /** ICC absent / RF lost mid-transaction — the card-flick signature. */
        object CardGone : UidResult
        data class Reject(val reason: String) : UidResult
    }

    /**
     * Full validation chain for a RDR_to_PC_DataBlock answering FF CA 00 00 00.
     * Any deviation returns CardGone/Reject — unvalidated bytes can never
     * become a UID (partial-read guarantee, spec section 4.2).
     */
    fun parseUidResponse(buf: ByteArray, len: Int): UidResult {
        if (len < HEADER_LEN) return UidResult.Reject("short frame ($len bytes)")
        if (buf[0] != RDR_DATA_BLOCK) return UidResult.Reject("unexpected message type")
        val declared = readLeInt(buf, 1)
        if (declared < 0 || len != HEADER_LEN + declared) {
            return UidResult.Reject("length mismatch: declared $declared, actual ${len - HEADER_LEN}")
        }
        val iccStatus = buf[7].toInt() and 0x03          // 0 active, 1 inactive, 2 absent
        val cmdStatus = (buf[7].toInt() shr 6) and 0x03  // 0 success
        if (iccStatus == 2) return UidResult.CardGone
        if (cmdStatus != 0) return UidResult.Reject("command failed, bError=${buf[8].toInt() and 0xFF}")
        if (declared < 2) return UidResult.Reject("no room for SW1SW2")
        val sw1 = buf[HEADER_LEN + declared - 2].toInt() and 0xFF
        val sw2 = buf[HEADER_LEN + declared - 1].toInt() and 0xFF
        if (sw1 == 0x63 && sw2 == 0x00) return UidResult.CardGone   // "no card / RF lost"
        if (sw1 != 0x90 || sw2 != 0x00) return UidResult.Reject("APDU status ${hex(sw1)} ${hex(sw2)}")
        val uid = buf.copyOfRange(HEADER_LEN, HEADER_LEN + declared - 2)
        if (uid.size != 4 && uid.size != 7 && uid.size != 10) {
            return UidResult.Reject("invalid UID length ${uid.size}")
        }
        return UidResult.Ok(uid)
    }

    /** @return true = card present, false = absent, null = malformed frame. */
    fun parseSlotStatusPresent(buf: ByteArray, len: Int): Boolean? {
        if (len < HEADER_LEN) return null
        if (buf[0] != RDR_SLOT_STATUS && buf[0] != RDR_DATA_BLOCK) return null
        return when (buf[7].toInt() and 0x03) {
            0, 1 -> true
            2 -> false
            else -> null
        }
    }

    private fun readLeInt(b: ByteArray, off: Int): Int =
        (b[off].toInt() and 0xFF) or
            ((b[off + 1].toInt() and 0xFF) shl 8) or
            ((b[off + 2].toInt() and 0xFF) shl 16) or
            ((b[off + 3].toInt() and 0xFF) shl 24)

    private fun hex(v: Int) = "%02X".format(v)
}
