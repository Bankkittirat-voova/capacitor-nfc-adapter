package com.school.nfcadapter

import com.school.nfcadapter.core.UidNormalizer
import com.school.nfcadapter.handler.ccid.CcidProtocol
import com.school.nfcadapter.handler.serial.AsciiHexLineProfile
import com.school.nfcadapter.handler.serial.SerialFrameAssembler
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * Cross-engine contract test: for every golden card payload, the CCID pipeline
 * (CCID frame -> validation -> normalize) and the serial pipeline (ASCII frame
 * -> assembly -> normalize) must both produce the exact uid_dec_reversed
 * string. The iOS lane runs the same vectors in ios/Tests/UidNormalizerTests.
 */
class EnginesEquivalenceTest {

    private val vectors = JSONObject(File("../shared-test-vectors/uid_vectors.json").readText())

    private fun hexToBytes(hex: String): ByteArray =
        ByteArray(hex.length / 2) { hex.substring(it * 2, it * 2 + 2).toInt(16).toByte() }

    @Test
    fun ccidAndSerialPipelinesProduceIdenticalContractStrings() {
        val valid = vectors.getJSONArray("valid")
        for (i in 0 until valid.length()) {
            val v = valid.getJSONObject(i)
            val rawHex = v.getString("raw_hex")
            val expected = v.getString("uid_dec_reversed")
            val raw = hexToBytes(rawHex)

            // --- CCID engine path: reader DataBlock -> validation chain -> normalize
            val apduResponse = raw + byteArrayOf(0x90.toByte(), 0x00)
            val frame = ByteArray(CcidProtocol.HEADER_LEN + apduResponse.size).also {
                it[0] = 0x80.toByte()
                it[1] = (apduResponse.size and 0xFF).toByte()
                apduResponse.copyInto(it, CcidProtocol.HEADER_LEN)
            }
            val parsed = CcidProtocol.parseUidResponse(frame, frame.size)
            assertTrue("CCID validation failed for $rawHex", parsed is CcidProtocol.UidResult.Ok)
            val ccidOut = UidNormalizer.decReversed((parsed as CcidProtocol.UidResult.Ok).uid)

            // --- Serial engine path: ASCII line -> frame assembly -> normalize
            val assembler = SerialFrameAssembler(AsciiHexLineProfile(), interByteGapMs = 150)
            val uids = assembler.feed("$rawHex\r\n".toByteArray(), nowMs = 0)
            assertEquals("serial assembly failed for $rawHex", 1, uids.size)
            val serialOut = UidNormalizer.decReversed(uids[0])

            assertEquals("CCID engine contract mismatch for $rawHex", expected, ccidOut)
            assertEquals("serial engine contract mismatch for $rawHex", expected, serialOut)
        }
    }
}
