package com.school.nfcadapter.handler.serial

/**
 * Per-reader-family framing rules. Pure Kotlin.
 *
 * extractFrame scans [buf] for one complete frame; validate turns a frame into
 * raw UID bytes (or rejects it). Both are side-effect-free so tests can
 * table-drive them with golden byte vectors.
 */
interface SerialProfile {
    val name: String
    val maxFrameSize: Int

    /** @return one complete frame + bytes consumed from the front of [buf],
     *  or null if no complete frame is available yet. An empty frame with
     *  consumed > 0 means "discard these bytes" (garbage / bare terminator). */
    fun extractFrame(buf: ByteArray): Extracted?

    /** @return raw UID bytes (MSB-first) or null if the frame is invalid. */
    fun validate(frame: ByteArray): ByteArray?

    data class Extracted(val frame: ByteArray, val consumed: Int)
}

/**
 * The most common serial NFC reader format: the UID as ASCII hex characters
 * terminated by CR and/or LF (e.g. "FB5D8229\r\n").
 */
class AsciiHexLineProfile : SerialProfile {
    override val name = "ascii-hex-line"
    override val maxFrameSize = 64

    override fun extractFrame(buf: ByteArray): SerialProfile.Extracted? {
        val idx = buf.indexOfFirst { it == '\r'.code.toByte() || it == '\n'.code.toByte() }
        if (idx < 0) return null
        return SerialProfile.Extracted(buf.copyOfRange(0, idx), idx + 1)
    }

    override fun validate(frame: ByteArray): ByteArray? {
        if (frame.isEmpty() || frame.size % 2 != 0) return null
        val text = String(frame, Charsets.US_ASCII).uppercase()
        if (!text.all { it in '0'..'9' || it in 'A'..'F' }) return null
        val bytes = ByteArray(text.length / 2) {
            ((Character.digit(text[it * 2], 16) shl 4) or Character.digit(text[it * 2 + 1], 16)).toByte()
        }
        return if (bytes.size == 4 || bytes.size == 7 || bytes.size == 10) bytes else null
    }
}

/**
 * Binary STX/ETX framing: 02 | LEN | payload(LEN) | CHK | 03, CHK = XOR of payload.
 * Representative of many OEM binary readers — adjust per datasheet by adding a
 * new SerialProfile, nothing else changes.
 */
class StxEtxBinaryProfile : SerialProfile {
    override val name = "stx-etx-binary"
    override val maxFrameSize = 32

    companion object {
        const val STX: Byte = 0x02
        const val ETX: Byte = 0x03
    }

    override fun extractFrame(buf: ByteArray): SerialProfile.Extracted? {
        val stx = buf.indexOfFirst { it == STX }
        if (stx < 0) {
            // Pure garbage (no STX anywhere) — discard it all.
            return if (buf.isNotEmpty()) SerialProfile.Extracted(ByteArray(0), buf.size) else null
        }
        if (stx > 0) return SerialProfile.Extracted(ByteArray(0), stx)   // drop leading garbage
        if (buf.size < 2) return null
        val len = buf[1].toInt() and 0xFF
        val total = 1 + 1 + len + 1 + 1                                   // STX LEN payload CHK ETX
        if (len > maxFrameSize) return SerialProfile.Extracted(ByteArray(0), 1)  // insane length: drop STX, resync
        if (buf.size < total) return null                                 // incomplete — wait for more bytes
        if (buf[total - 1] != ETX) return SerialProfile.Extracted(ByteArray(0), 1) // framing broken: resync
        return SerialProfile.Extracted(buf.copyOfRange(0, total), total)
    }

    override fun validate(frame: ByteArray): ByteArray? {
        if (frame.size < 5 || frame[0] != STX || frame[frame.size - 1] != ETX) return null
        val len = frame[1].toInt() and 0xFF
        if (frame.size != len + 4) return null
        val payload = frame.copyOfRange(2, 2 + len)
        var chk: Byte = 0
        for (b in payload) chk = (chk.toInt() xor b.toInt()).toByte()
        if (chk != frame[2 + len]) return null                            // checksum fail = partial/corrupt
        return if (payload.size == 4 || payload.size == 7 || payload.size == 10) payload else null
    }
}
