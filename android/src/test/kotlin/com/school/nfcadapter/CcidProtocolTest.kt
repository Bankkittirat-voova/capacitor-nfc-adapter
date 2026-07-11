package com.school.nfcadapter

import com.school.nfcadapter.handler.ccid.CcidProtocol
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class CcidProtocolTest {

    // ------------------------------------------------------------- builders

    @Test
    fun xfrBlockGoldenBytes() {
        val frame = CcidProtocol.buildXfrBlock(seq = 7, apdu = CcidProtocol.GET_UID_APDU)
        assertArrayEquals(
            byteArrayOf(
                0x6F, 0x05, 0x00, 0x00, 0x00, 0x00, 0x07, 0x00, 0x00, 0x00,
                0xFF.toByte(), 0xCA.toByte(), 0x00, 0x00, 0x00
            ),
            frame
        )
    }

    @Test
    fun getSlotStatusGoldenBytes() {
        assertArrayEquals(
            byteArrayOf(0x65, 0, 0, 0, 0, 0, 0x02, 0, 0, 0),
            CcidProtocol.buildGetSlotStatus(seq = 2)
        )
    }

    // --------------------------------------------------------------- parser

    private fun dataBlock(data: ByteArray, iccStatus: Int = 0, cmdStatus: Int = 0, bError: Int = 0): ByteArray {
        val b = ByteArray(CcidProtocol.HEADER_LEN + data.size)
        b[0] = 0x80.toByte()
        b[1] = (data.size and 0xFF).toByte()
        b[2] = ((data.size shr 8) and 0xFF).toByte()
        b[7] = ((cmdStatus shl 6) or iccStatus).toByte()
        b[8] = bError.toByte()
        data.copyInto(b, CcidProtocol.HEADER_LEN)
        return b
    }

    private val uid4 = byteArrayOf(0xFB.toByte(), 0x5D, 0x82.toByte(), 0x29)
    private val sw9000 = byteArrayOf(0x90.toByte(), 0x00)

    @Test
    fun validUidResponsesFor4And7And10Bytes() {
        for (size in intArrayOf(4, 7, 10)) {
            val uid = ByteArray(size) { (it + 1).toByte() }
            val frame = dataBlock(uid + sw9000)
            val r = CcidProtocol.parseUidResponse(frame, frame.size)
            assertTrue("expected Ok for $size-byte UID", r is CcidProtocol.UidResult.Ok)
            assertArrayEquals(uid, (r as CcidProtocol.UidResult.Ok).uid)
        }
    }

    @Test
    fun truncationAtEveryCutPointNeverYieldsUid() {
        val frame = dataBlock(uid4 + sw9000)
        for (cut in 0 until frame.size) {
            val r = CcidProtocol.parseUidResponse(frame, cut)
            assertTrue("cut at $cut must not be Ok", r !is CcidProtocol.UidResult.Ok)
        }
    }

    @Test
    fun cardAbsentStatusIsCardGone() {
        val frame = dataBlock(uid4 + sw9000, iccStatus = 2)
        assertEquals(CcidProtocol.UidResult.CardGone, CcidProtocol.parseUidResponse(frame, frame.size))
    }

    @Test
    fun rfLostStatusWord6300IsCardGone() {
        val frame = dataBlock(byteArrayOf(0x63, 0x00))
        assertEquals(CcidProtocol.UidResult.CardGone, CcidProtocol.parseUidResponse(frame, frame.size))
    }

    @Test
    fun apduFailure6A81IsReject() {
        val frame = dataBlock(byteArrayOf(0x6A, 0x81.toByte()))
        assertTrue(CcidProtocol.parseUidResponse(frame, frame.size) is CcidProtocol.UidResult.Reject)
    }

    @Test
    fun commandFailedBitIsReject() {
        val frame = dataBlock(uid4 + sw9000, cmdStatus = 1, bError = 0xFE)
        assertTrue(CcidProtocol.parseUidResponse(frame, frame.size) is CcidProtocol.UidResult.Reject)
    }

    @Test
    fun wrongMessageTypeIsReject() {
        val frame = dataBlock(uid4 + sw9000).also { it[0] = 0x81.toByte() }
        assertTrue(CcidProtocol.parseUidResponse(frame, frame.size) is CcidProtocol.UidResult.Reject)
    }

    @Test
    fun invalidUidLengthIsReject() {
        val frame = dataBlock(ByteArray(5) { 1 } + sw9000)   // 5-byte "UID"
        assertTrue(CcidProtocol.parseUidResponse(frame, frame.size) is CcidProtocol.UidResult.Reject)
    }

    @Test
    fun slotStatusParsing() {
        val present = ByteArray(10).also { it[0] = 0x81.toByte(); it[7] = 0 }
        val inactive = ByteArray(10).also { it[0] = 0x81.toByte(); it[7] = 1 }
        val absent = ByteArray(10).also { it[0] = 0x81.toByte(); it[7] = 2 }
        assertEquals(true, CcidProtocol.parseSlotStatusPresent(present, 10))
        assertEquals(true, CcidProtocol.parseSlotStatusPresent(inactive, 10))
        assertEquals(false, CcidProtocol.parseSlotStatusPresent(absent, 10))
        assertEquals(null, CcidProtocol.parseSlotStatusPresent(present, 5))   // truncated
    }
}
