package com.school.nfcadapter

import com.school.nfcadapter.ScriptedUsbTransport.Step.Disconnect
import com.school.nfcadapter.ScriptedUsbTransport.Step.Reply
import com.school.nfcadapter.ScriptedUsbTransport.Step.ReplyTruncated
import com.school.nfcadapter.handler.ccid.CcidProtocol
import com.school.nfcadapter.handler.ccid.CcidReaderHandler
import com.school.nfcadapter.transport.TransferFailureException
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Drives the REAL CCID engine end-to-end with scripted byte streams:
 * taps, dwells, card flicks, RF corruption and brownout disconnects.
 */
class CcidHandlerScenarioTest {

    private val config = NfcModuleConfig(
        absentPollDelayMs = 1,
        presentPollDelayMs = 1,
        maxConsecutiveTransferFailures = 3
    )

    private val uidA = byteArrayOf(0xFB.toByte(), 0x5D, 0x82.toByte(), 0x29)
    private val uidB = byteArrayOf(0x04, 0xA1.toByte(), 0xB2.toByte(), 0xC3.toByte())
    private val sw9000 = byteArrayOf(0x90.toByte(), 0x00)
    private val atr = byteArrayOf(0x3B, 0x81.toByte(), 0x80.toByte(), 0x01)

    private fun slotStatus(present: Boolean) = ByteArray(10).also {
        it[0] = 0x81.toByte()
        it[7] = if (present) 0 else 2
    }

    private fun dataBlock(data: ByteArray, iccStatus: Int = 0) =
        ByteArray(CcidProtocol.HEADER_LEN + data.size).also {
            it[0] = 0x80.toByte()
            it[1] = (data.size and 0xFF).toByte()
            it[7] = iccStatus.toByte()
            data.copyInto(it, CcidProtocol.HEADER_LEN)
        }

    private fun powerOnAck(iccStatus: Int = 0) = dataBlock(atr, iccStatus)
    private fun uidBlock(uid: ByteArray) = dataBlock(uid + sw9000)

    private fun runScenario(steps: List<ScriptedUsbTransport.Step>): Pair<List<ByteArray>, Boolean> {
        val transport = ScriptedUsbTransport(steps)
        val handler = CcidReaderHandler(transport, config)
        val emissions = mutableListOf<ByteArray>()
        var linkDied = false
        return runCatching {
            kotlinx.coroutines.test.runTest {
                try {
                    handler.runPollingLoop { emissions += it.copyOf() }
                } catch (_: TransferFailureException) {
                    linkDied = true
                }
            }
        }.let { emissions to linkDied }
    }

    @Test
    fun happyTapEmitsExactlyOnce() {
        val (emissions, died) = runScenario(
            listOf(
                Reply(slotStatus(false)), Reply(slotStatus(false)),
                Reply(slotStatus(true)),                         // the tap
                Reply(powerOnAck()), Reply(uidBlock(uidA)), Reply(uidBlock(uidA)),  // strict re-read
                Reply(slotStatus(true)),                         // dwell — must not re-fire
                Reply(slotStatus(false)),                        // card removed
                Disconnect
            )
        )
        assertEquals(1, emissions.size)
        assertArrayEquals(uidA, emissions[0])
        assertTrue("script ends in disconnect", died)
    }

    @Test
    fun dwellingCardNeverRefires() {
        val steps = mutableListOf<ScriptedUsbTransport.Step>(
            Reply(slotStatus(false)), Reply(slotStatus(true)),
            Reply(powerOnAck()), Reply(uidBlock(uidA)), Reply(uidBlock(uidA))
        )
        repeat(50) { steps += Reply(slotStatus(true)) }          // long dwell
        steps += Reply(slotStatus(false))
        steps += Disconnect
        val (emissions, _) = runScenario(steps)
        assertEquals("edge-trigger proof: 50 present-polls, one emission", 1, emissions.size)
    }

    @Test
    fun cardFlickDuringPowerOnEmitsNothingThenRecovers() {
        val (emissions, _) = runScenario(
            listOf(
                Reply(slotStatus(true)),
                Reply(powerOnAck(iccStatus = 2)),                // card already gone: flick
                Reply(slotStatus(false)),
                Reply(slotStatus(true)),                         // second, stable tap
                Reply(powerOnAck()), Reply(uidBlock(uidB)), Reply(uidBlock(uidB)),
                Reply(slotStatus(false)),
                Disconnect
            )
        )
        assertEquals(1, emissions.size)
        assertArrayEquals("only the stable tap's UID", uidB, emissions[0])
    }

    @Test
    fun truncatedUidFrameIsDiscarded() {
        val full = uidBlock(uidA)
        val (emissions, _) = runScenario(
            listOf(
                Reply(slotStatus(true)),
                Reply(powerOnAck()),
                ReplyTruncated(full, cutAt = 6),                 // flick mid-transfer
                Reply(slotStatus(false)),
                Reply(slotStatus(true)),                         // clean retap
                Reply(powerOnAck()), Reply(uidBlock(uidA)), Reply(uidBlock(uidA)),
                Disconnect
            )
        )
        assertEquals(1, emissions.size)
        assertArrayEquals(uidA, emissions[0])
    }

    @Test
    fun strictReReadMismatchEmitsNothing() {
        val (emissions, _) = runScenario(
            listOf(
                Reply(slotStatus(true)),
                Reply(powerOnAck()),
                Reply(uidBlock(uidA)), Reply(uidBlock(uidB)),    // RF corruption: reads differ
                Disconnect
            )
        )
        assertEquals("corrupted read must never emit", 0, emissions.size)
    }

    @Test
    fun disconnectRaisesTransferFailureCleanly() {
        val (emissions, died) = runScenario(
            listOf(Reply(slotStatus(false)), Disconnect)
        )
        assertEquals(0, emissions.size)
        assertTrue(died)
    }
}
