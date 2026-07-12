package com.school.nfcadapter.usb

import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.hardware.usb.UsbDevice
import android.hardware.usb.UsbManager
import android.os.Build
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.withTimeoutOrNull

/**
 * Runtime USB permission flow, compliant with:
 *  - API 31+ (Android 12): mutability flag is MANDATORY on every PendingIntent.
 *    This one must be FLAG_MUTABLE — the USB service mutates the intent to
 *    attach EXTRA_DEVICE / EXTRA_PERMISSION_GRANTED. FLAG_IMMUTABLE here means
 *    silently losing those extras.
 *  - targetSdk 34+ (Android 14): a mutable PendingIntent must wrap an EXPLICIT
 *    intent — satisfied via setPackage().
 *  - API 33+: runtime receivers must declare exported-ness (NOT_EXPORTED here).
 *
 * Zero-dialog happy path: when the reader matches res/xml/nfc_device_filter.xml
 * and the driver once tapped "Always open Driver App", hasPermission() is
 * already true and this class never shows anything.
 */
internal class UsbPermissionCoordinator(private val context: Context) {

    companion object {
        private const val ACTION_USB_PERMISSION = "com.school.nfcadapter.USB_PERMISSION"
        private const val DIALOG_TIMEOUT_MS = 60_000L
    }

    // Written from the module dispatcher (ensurePermission), completed from the
    // main thread (broadcast onReceive) — must be volatile for visibility.
    @Volatile
    private var pending: CompletableDeferred<Boolean>? = null

    private val receiver = object : BroadcastReceiver() {
        override fun onReceive(ctx: Context, intent: Intent) {
            if (intent.action != ACTION_USB_PERMISSION) return
            val granted = intent.getBooleanExtra(UsbManager.EXTRA_PERMISSION_GRANTED, false)
            pending?.complete(granted)
            pending = null
        }
    }

    fun register() {
        val filter = IntentFilter(ACTION_USB_PERMISSION)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            context.registerReceiver(receiver, filter, Context.RECEIVER_NOT_EXPORTED)
        } else {
            @Suppress("UnspecifiedRegisterReceiverFlag")
            context.registerReceiver(receiver, filter)
        }
    }

    fun unregister() {
        runCatching { context.unregisterReceiver(receiver) }
    }

    /** Suspends until granted/denied, or false if the driver ignored the dialog. */
    suspend fun ensurePermission(usbManager: UsbManager, device: UsbDevice): Boolean {
        if (usbManager.hasPermission(device)) return true

        val deferred = CompletableDeferred<Boolean>()
        pending = deferred

        val intent = Intent(ACTION_USB_PERMISSION).setPackage(context.packageName)
        val pi = PendingIntent.getBroadcast(
            context,
            /* requestCode = */ device.deviceId,
            intent,
            PendingIntent.FLAG_MUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )
        usbManager.requestPermission(device, pi)

        return withTimeoutOrNull(DIALOG_TIMEOUT_MS) { deferred.await() } ?: false
    }
}
