package com.school.nfcadapter

import android.content.Context
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.hardware.usb.UsbManager
import com.school.nfcadapter.api.NfcScanListener
import com.school.nfcadapter.api.NfcScannerPort
import com.school.nfcadapter.api.NoopScannerPort
import com.school.nfcadapter.core.ConnectionManager
import com.school.nfcadapter.core.ListenerProxy
import com.school.nfcadapter.diag.NfcDiag
import com.school.nfcadapter.usb.UsbDetachReceiver
import com.school.nfcadapter.usb.UsbPermissionCoordinator

/**
 * PUBLIC FACADE — the Android implementation of [NfcScannerPort].
 *
 * create() probes hardware capability first: on devices without USB Host
 * support it returns a permanent [NoopScannerPort], so the host app never
 * branches on platform quirks and never crashes on non-OTG hardware.
 */
class NfcAdapterModule private constructor(
    private val appContext: Context,
    config: NfcModuleConfig
) : NfcScannerPort {

    companion object {
        fun create(context: Context, config: NfcModuleConfig = NfcModuleConfig()): NfcScannerPort {
            val pm = context.packageManager
            if (!pm.hasSystemFeature(PackageManager.FEATURE_USB_HOST)) return NoopScannerPort()
            if (context.getSystemService(Context.USB_SERVICE) !is UsbManager) return NoopScannerPort()
            // Tee every module log line into logcat tag "NfcDiag" so field
            // diagnostics need no app-side wiring (adb logcat -s NfcDiag).
            val diagConfig = config.copy(logger = NfcDiag.tee(config.logger))
            return NfcAdapterModule(context.applicationContext, diagConfig)
        }
    }

    override val isSupported = true

    private val usbManager = appContext.getSystemService(Context.USB_SERVICE) as UsbManager
    private val listenerProxy = ListenerProxy()
    private val permissions = UsbPermissionCoordinator(appContext)
    private val connectionManager =
        ConnectionManager(appContext, usbManager, permissions, listenerProxy, config)
    private val detachReceiver = UsbDetachReceiver { device ->
        connectionManager.onDeviceDetached(device)
    }
    private var started = false

    override fun setListener(listener: NfcScanListener?) {
        listenerProxy.delegate = listener
    }

    override fun start() {
        if (started) return
        started = true
        ModuleRegistry.active = this
        permissions.register()
        appContext.registerReceiver(detachReceiver, IntentFilter(UsbManager.ACTION_USB_DEVICE_DETACHED))
        // Zero-config: a reader plugged in BEFORE the app started must also work.
        usbManager.deviceList.values.forEach { connectionManager.onDeviceAttached(it) }
    }

    override fun stop() {
        if (!started) return
        started = false
        if (ModuleRegistry.active === this) ModuleRegistry.active = null
        runCatching { appContext.unregisterReceiver(detachReceiver) }
        permissions.unregister()
        connectionManager.shutdown()
    }

    /** Entry point used by UsbAttachTrampolineActivity. */
    internal fun onUsbAttach(device: android.hardware.usb.UsbDevice) {
        if (started) connectionManager.onDeviceAttached(device)
    }
}

/** Lets the manifest-launched trampoline find the live module instance. */
internal object ModuleRegistry {
    @Volatile
    var active: NfcAdapterModule? = null
}
