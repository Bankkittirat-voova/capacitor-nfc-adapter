package com.school.nfcadapter.diag

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.pm.ApplicationInfo
import com.school.nfcadapter.ModuleRegistry
import com.school.nfcadapter.NfcAdapterModule
import com.school.nfcadapter.api.NfcScanListener
import com.school.nfcadapter.api.NfcScannerPort
import com.school.nfcadapter.api.ReaderError
import com.school.nfcadapter.api.ReaderState

/**
 * adb-triggerable diagnostic session — zero app-side coding required.
 *
 *   adb shell am broadcast -n <applicationId>/com.school.nfcadapter.diag.NfcDiagReceiver \
 *       -a com.school.nfcadapter.DIAG_START
 *   adb logcat -s NfcDiag
 *   adb shell am broadcast -n <applicationId>/com.school.nfcadapter.diag.NfcDiagReceiver \
 *       -a com.school.nfcadapter.DIAG_STOP
 *
 * Guards:
 *  - Debuggable builds only (silently inert in release).
 *  - Manifest export is protected by android.permission.DUMP (held by adb shell,
 *    not grantable to ordinary third-party apps).
 *  - If the host app already runs a scanning session, no second module is
 *    started (two sessions would fight over claimInterface) — the existing
 *    session's logs already flow to the NfcDiag tag.
 */
class NfcDiagReceiver : BroadcastReceiver() {

    companion object {
        const val ACTION_START = "com.school.nfcadapter.DIAG_START"
        const val ACTION_STOP = "com.school.nfcadapter.DIAG_STOP"

        @Volatile
        private var standalone: NfcScannerPort? = null
    }

    override fun onReceive(context: Context, intent: Intent) {
        val debuggable = context.applicationInfo.flags and ApplicationInfo.FLAG_DEBUGGABLE != 0
        if (!debuggable) return

        when (intent.action) {
            ACTION_START -> start(context)
            ACTION_STOP -> stop()
        }
    }

    private fun start(context: Context) {
        if (ModuleRegistry.active != null) {
            NfcDiag.log("[DIAG] app scanning session already active — its checkpoints already stream to this tag; no standalone session started")
            return
        }
        if (standalone != null) {
            NfcDiag.log("[DIAG] standalone diagnostic session already running")
            return
        }
        NfcDiag.log("[DIAG] starting standalone diagnostic session — plug reader in, tap card")
        standalone = NfcAdapterModule.create(context.applicationContext).also { port ->
            if (!port.isSupported) {
                NfcDiag.log("[ERROR] USB Host not supported on this device — diagnostics cannot run")
            }
            port.setListener(object : NfcScanListener {
                override fun onCardScanned(uidDecReversed: String) {
                    NfcDiag.log("[UID] onCardScanned uid_dec_reversed=$uidDecReversed")
                }

                override fun onReaderStateChanged(state: ReaderState) {
                    NfcDiag.log("[USB] reader state -> $state")
                }

                override fun onReaderError(error: ReaderError) {
                    NfcDiag.log("[ERROR] ${error.code}: ${error.message} (recoverable=${error.recoverable})")
                }
            })
            port.start()
        }
    }

    private fun stop() {
        standalone?.stop()
        standalone = null
        NfcDiag.log("[DIAG] standalone diagnostic session stopped")
    }
}
