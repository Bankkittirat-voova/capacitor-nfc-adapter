package com.school.nfcadapter.usb

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.hardware.usb.UsbDevice
import android.hardware.usb.UsbManager
import android.os.Build

/**
 * Runtime-registered receiver for ACTION_USB_DEVICE_DETACHED (a genuine
 * broadcast, unlike ATTACHED). Registered once in NfcAdapterModule.start(),
 * unregistered once in stop() — never per-device.
 */
internal class UsbDetachReceiver(
    private val onDetached: (UsbDevice) -> Unit
) : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != UsbManager.ACTION_USB_DEVICE_DETACHED) return
        val device: UsbDevice? = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            intent.getParcelableExtra(UsbManager.EXTRA_DEVICE, UsbDevice::class.java)
        } else {
            @Suppress("DEPRECATION")
            intent.getParcelableExtra(UsbManager.EXTRA_DEVICE)
        }
        device?.let(onDetached)
    }
}
