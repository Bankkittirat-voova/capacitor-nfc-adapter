package com.school.nfcadapter.core

import java.math.BigInteger
import java.util.Locale

/**
 * THE OUTPUT CONTRACT — `uid_dec_reversed`:
 * the decimal representation of the reversed raw UID byte array.
 *
 *   raw bytes (as read, MSB-first):  FB 5D 82 29
 *   reversed:                        29 82 5D FB
 *   delivered to onCardScanned():    "696409595"        (0x29825DFB = 696409595)
 *
 * No zero padding. Valid ISO 14443 UID lengths only (4 / 7 / 10 bytes).
 * The iOS twin (ios/Sources/UidNormalizer.swift) implements the identical
 * transform; both are pinned by shared-test-vectors/uid_vectors.json.
 */
object UidNormalizer {

    private val VALID_LENGTHS = intArrayOf(4, 7, 10)

    /** @return the contract string, or null if [raw] is not a plausible UID. */
    fun decReversed(raw: ByteArray): String? {
        if (raw.size !in VALID_LENGTHS.toList()) return null
        val reversed = ByteArray(raw.size) { raw[raw.size - 1 - it] }
        return BigInteger(1, reversed).toString(10)
    }

    /** Convenience for handlers that produce hex text (e.g. ASCII serial readers). */
    fun decReversedFromHex(hex: String): String? {
        val clean = hex.replace(" ", "").replace(":", "").uppercase(Locale.ROOT)
        if (clean.isEmpty() || clean.length % 2 != 0) return null
        if (!clean.all { it in '0'..'9' || it in 'A'..'F' }) return null
        val bytes = ByteArray(clean.length / 2) {
            ((Character.digit(clean[it * 2], 16) shl 4) or Character.digit(clean[it * 2 + 1], 16)).toByte()
        }
        return decReversed(bytes)
    }
}
