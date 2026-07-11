package com.school.nfcadapter.core

import android.os.Handler
import android.os.Looper
import com.school.nfcadapter.api.NfcScanListener
import com.school.nfcadapter.api.ReaderError
import com.school.nfcadapter.api.ReaderState

/**
 * Marshals every callback onto the main thread and swallows deliveries when no
 * listener is bound. The host app never sees the module's internal threads.
 */
internal class ListenerProxy : NfcScanListener {

    @Volatile
    var delegate: NfcScanListener? = null

    private val main = Handler(Looper.getMainLooper())

    override fun onCardScanned(uidDecReversed: String) {
        main.post { delegate?.onCardScanned(uidDecReversed) }
    }

    override fun onReaderStateChanged(state: ReaderState) {
        main.post { delegate?.onReaderStateChanged(state) }
    }

    override fun onReaderError(error: ReaderError) {
        main.post { delegate?.onReaderError(error) }
    }
}
