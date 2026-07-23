package com.school.nfcadapter.core

import com.school.nfcadapter.api.NfcScanListener
import com.school.nfcadapter.api.ReaderError
import com.school.nfcadapter.api.ReaderState

/** Existing recoverable reader-open failure contract, extracted for JVM coverage. */
internal fun reportReaderOpenFailure(
    listener: NfcScanListener,
    logger: (String) -> Unit,
    diagnostic: String?,
    userMessage: String
) {
    if (diagnostic != null) logger("[ERROR] $diagnostic")
    listener.onReaderError(
        ReaderError(ReaderError.ErrorCode.TRANSFER_FAILURE, userMessage, recoverable = true)
    )
    listener.onReaderStateChanged(ReaderState.DISCONNECTED)
}
