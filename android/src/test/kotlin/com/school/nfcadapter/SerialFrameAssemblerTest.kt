package com.school.nfcadapter

import com.school.nfcadapter.handler.serial.AsciiHexLineProfile
import com.school.nfcadapter.handler.serial.SerialFrameAssembler
import com.school.nfcadapter.handler.serial.StxEtxBinaryProfile
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Test

class SerialFrameAssemblerTest {

    private val uidA = byteArrayOf(0xFB.toByte(), 0x5D, 0x82.toByte(), 0x29)

    // ------------------------------------------------------- ASCII hex lines

    @Test
    fun fragmentedAsciiFrameReassembles() {
        val a = SerialFrameAssembler(AsciiHexLineProfile(), interByteGapMs = 150)
        assertEquals(0, a.feed("FB5D".toByteArray(), nowMs = 0).size)
        val uids = a.feed("8229\r\n".toByteArray(), nowMs = 50)
        assertEquals(1, uids.size)
        assertArrayEquals(uidA, uids[0])
    }

    @Test
    fun staleBytesDiscardedAfterGap_cardFlick() {
        val a = SerialFrameAssembler(AsciiHexLineProfile(), interByteGapMs = 150)
        a.feed("FB5D".toByteArray(), nowMs = 0)                    // flick: stream stops
        val uids = a.feed("8229\r".toByteArray(), nowMs = 1_000)   // > gap: must NOT prefix
        assertEquals("orphan '8229' is a 2-byte uid -> invalid -> dropped", 0, uids.size)
        val next = a.feed("FB5D8229\n".toByteArray(), nowMs = 1_050)
        assertEquals(1, next.size)
        assertArrayEquals(uidA, next[0])
    }

    @Test
    fun multipleFramesInOneChunk() {
        val a = SerialFrameAssembler(AsciiHexLineProfile(), interByteGapMs = 150)
        val uids = a.feed("FB5D8229\r\n04A1B2C3\r\n".toByteArray(), nowMs = 0)
        assertEquals(2, uids.size)
    }

    @Test
    fun nonHexGarbageDropped() {
        val a = SerialFrameAssembler(AsciiHexLineProfile(), interByteGapMs = 150)
        assertEquals(0, a.feed("HELLO!!\r\n".toByteArray(), nowMs = 0).size)
    }

    // ------------------------------------------------------- STX/ETX binary

    private fun stxFrame(payload: ByteArray, corruptChk: Boolean = false): ByteArray {
        var chk = 0
        payload.forEach { chk = chk xor it.toInt() }
        if (corruptChk) chk = chk xor 0xFF
        return byteArrayOf(0x02, payload.size.toByte()) + payload + byteArrayOf(chk.toByte(), 0x03)
    }

    @Test
    fun binaryFrameWithValidChecksum() {
        val a = SerialFrameAssembler(StxEtxBinaryProfile(), interByteGapMs = 150)
        val uids = a.feed(stxFrame(uidA), nowMs = 0)
        assertEquals(1, uids.size)
        assertArrayEquals(uidA, uids[0])
    }

    @Test
    fun corruptedChecksumDropped_partialRead() {
        val a = SerialFrameAssembler(StxEtxBinaryProfile(), interByteGapMs = 150)
        assertEquals(0, a.feed(stxFrame(uidA, corruptChk = true), nowMs = 0).size)
        // and a following good frame still parses (resync works)
        assertEquals(1, a.feed(stxFrame(uidA), nowMs = 10).size)
    }

    @Test
    fun leadingGarbageBeforeStxIsSkipped() {
        val a = SerialFrameAssembler(StxEtxBinaryProfile(), interByteGapMs = 150)
        val uids = a.feed(byteArrayOf(0x55, 0x66, 0x77) + stxFrame(uidA), nowMs = 0)
        assertEquals(1, uids.size)
        assertArrayEquals(uidA, uids[0])
    }
}
