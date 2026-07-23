package com.school.nfcadapter

import com.school.nfcadapter.api.NfcScanListener
import com.school.nfcadapter.api.ReaderError
import com.school.nfcadapter.api.ReaderState
import com.school.nfcadapter.core.reportReaderOpenFailure
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ReaderOpenFailureReporterTest {

    @Test
    fun reopenFailureUsesExistingErrorAndDisconnectedStateContract() {
        val listener = RecordingListener()
        val logs = mutableListOf<String>()

        reportReaderOpenFailure(
            listener = listener,
            logger = logs::add,
            diagnostic = "openDevice returned null (device gone or permission revoked)",
            userMessage = "Could not open the scanner."
        )

        assertEquals(listOf(ReaderState.DISCONNECTED), listener.states)
        assertEquals(1, listener.errors.size)
        assertEquals(ReaderError.ErrorCode.TRANSFER_FAILURE, listener.errors.single().code)
        assertEquals("Could not open the scanner.", listener.errors.single().message)
        assertTrue(listener.errors.single().recoverable)
        assertEquals(
            listOf("[ERROR] openDevice returned null (device gone or permission revoked)"),
            logs
        )
    }

    private class RecordingListener : NfcScanListener {
        val states = mutableListOf<ReaderState>()
        val errors = mutableListOf<ReaderError>()

        override fun onCardScanned(uidDecReversed: String) = Unit
        override fun onReaderStateChanged(state: ReaderState) {
            states += state
        }
        override fun onReaderError(error: ReaderError) {
            errors += error
        }
    }
}
