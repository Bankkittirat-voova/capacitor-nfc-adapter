package com.school.nfcadapter

import com.school.nfcadapter.ScriptedUsbTransport.Step.Disconnect
import com.school.nfcadapter.ScriptedUsbTransport.Step.Reply
import com.school.nfcadapter.handler.ccid.CcidProtocol
import com.school.nfcadapter.handler.ccid.CcidReaderHandler
import com.school.nfcadapter.transport.TransferFailureException
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class CcidLoggingPrivacyTest {

    @Test
    fun normalCcidLoggingMasksAtrValue() = runTest {
        val logs = mutableListOf<String>()
        val config = NfcModuleConfig(
            absentPollDelayMs = 1,
            presentPollDelayMs = 1,
            maxConsecutiveTransferFailures = 1,
            logSensitiveValues = false,
            logger = logs::add
        )
        val uid = byteArrayOf(0xFB.toByte(), 0x5D, 0x82.toByte(), 0x29)
        val atr = byteArrayOf(0x3B, 0x81.toByte(), 0x80.toByte(), 0x01)
        val transport = ScriptedUsbTransport(
            listOf(
                Reply(slotStatus(present = true)),
                Reply(dataBlock(atr)),
                Reply(dataBlock(uid + byteArrayOf(0x90.toByte(), 0x00))),
                Reply(dataBlock(uid + byteArrayOf(0x90.toByte(), 0x00))),
                Disconnect
            )
        )

        try {
            CcidReaderHandler(transport, config).runPollingLoop {}
        } catch (_: TransferFailureException) {
            // Expected scripted disconnect after the one complete scan.
        }

        val output = logs.joinToString("\n")
        assertTrue(output.contains("<4B masked>"))
        assertFalse(output.contains("3B 81 80 01"))
    }

    private fun slotStatus(present: Boolean) = ByteArray(CcidProtocol.HEADER_LEN).also {
        it[0] = 0x81.toByte()
        it[7] = if (present) 0 else 2
    }

    private fun dataBlock(data: ByteArray) =
        ByteArray(CcidProtocol.HEADER_LEN + data.size).also {
            it[0] = 0x80.toByte()
            it[1] = (data.size and 0xFF).toByte()
            data.copyInto(it, CcidProtocol.HEADER_LEN)
        }
}
