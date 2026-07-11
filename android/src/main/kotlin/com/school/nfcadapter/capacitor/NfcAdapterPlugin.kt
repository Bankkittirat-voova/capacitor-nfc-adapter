package com.school.nfcadapter.capacitor

import com.getcapacitor.JSObject
import com.getcapacitor.Plugin
import com.getcapacitor.PluginCall
import com.getcapacitor.PluginMethod
import com.getcapacitor.annotation.CapacitorPlugin
import com.school.nfcadapter.NfcAdapterModule as CoreNfcAdapterModule
import com.school.nfcadapter.api.NfcScanListener
import com.school.nfcadapter.api.NfcScannerPort
import com.school.nfcadapter.api.ReaderError
import com.school.nfcadapter.api.ReaderState

/**
 * Capacitor 3 bridge over the core NfcAdapterModule facade.
 *
 * JS surface (see /index.js):
 *   NfcAdapter.startScanning() / stopScanning() / isSupported()
 * Events via notifyListeners:
 *   "onCardScanned"        -> { uid }  — the strict uid_dec_reversed contract value
 *   "onReaderStateChanged" -> { state }
 *   "onReaderError"        -> { code, message, recoverable }
 */
@CapacitorPlugin(name = "NfcAdapter")
class NfcAdapterPlugin : Plugin(), NfcScanListener {

    private var port: NfcScannerPort? = null

    /** Core callbacks already arrive on the Android main thread (ListenerProxy). */
    private fun ensurePort(): NfcScannerPort =
        port ?: CoreNfcAdapterModule.create(context.applicationContext).also {
            it.setListener(this)
            port = it
        }

    @PluginMethod
    fun startScanning(call: PluginCall) {
        ensurePort().start()
        call.resolve()
    }

    @PluginMethod
    fun stopScanning(call: PluginCall) {
        port?.stop()
        call.resolve()
    }

    @PluginMethod
    fun isSupported(call: PluginCall) {
        call.resolve(JSObject().put("supported", ensurePort().isSupported))
    }

    override fun handleOnDestroy() {
        port?.stop()
        port = null
        super.handleOnDestroy()
    }

    // ------------------------------------------------ NfcScanListener -> JS

    override fun onCardScanned(uidDecReversed: String) {
        notifyListeners("onCardScanned", JSObject().put("uid", uidDecReversed))
    }

    override fun onReaderStateChanged(state: ReaderState) {
        notifyListeners("onReaderStateChanged", JSObject().put("state", state.name))
    }

    override fun onReaderError(error: ReaderError) {
        notifyListeners(
            "onReaderError",
            JSObject()
                .put("code", error.code.name)
                .put("message", error.message)
                .put("recoverable", error.recoverable)
        )
    }
}
