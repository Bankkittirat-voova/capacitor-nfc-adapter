package com.school.nfcadapter

import com.school.nfcadapter.core.UidNormalizer
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/** Pins the Kotlin normalizer to the shared cross-platform golden vectors. */
class UidNormalizerTest {

    private val vectors =
        JSONObject(File("../shared-test-vectors/uid_vectors.json").readText())

    private fun hexToBytes(hex: String): ByteArray =
        ByteArray(hex.length / 2) { hex.substring(it * 2, it * 2 + 2).toInt(16).toByte() }

    @Test
    fun contractAnchorVector() {
        // Fixed in the product contract: raw FB5D8229 -> "696409595".
        assertEquals(
            "696409595",
            UidNormalizer.decReversed(byteArrayOf(0xFB.toByte(), 0x5D, 0x82.toByte(), 0x29))
        )
    }

    @Test
    fun goldenVectors() {
        val valid = vectors.getJSONArray("valid")
        assertTrue(valid.length() > 0)
        for (i in 0 until valid.length()) {
            val v = valid.getJSONObject(i)
            val raw = hexToBytes(v.getString("raw_hex"))
            assertEquals(
                "mismatch for raw ${v.getString("raw_hex")}",
                v.getString("uid_dec_reversed"),
                UidNormalizer.decReversed(raw)
            )
            // The hex-text entry point must agree with the byte entry point.
            assertEquals(
                v.getString("uid_dec_reversed"),
                UidNormalizer.decReversedFromHex(v.getString("raw_hex"))
            )
        }
    }

    @Test
    fun invalidInputsRejected() {
        val invalid = vectors.getJSONArray("invalid_hex")
        for (i in 0 until invalid.length()) {
            assertNull(
                "should reject '${invalid.getString(i)}'",
                UidNormalizer.decReversedFromHex(invalid.getString(i))
            )
        }
        assertNull(UidNormalizer.decReversed(ByteArray(0)))
        assertNull(UidNormalizer.decReversed(ByteArray(3)))
        assertNull(UidNormalizer.decReversed(ByteArray(5)))
        assertNull(UidNormalizer.decReversed(ByteArray(11)))
    }
}
