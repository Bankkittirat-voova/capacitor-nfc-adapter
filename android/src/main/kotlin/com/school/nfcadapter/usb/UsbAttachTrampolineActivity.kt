package com.school.nfcadapter.usb

import android.app.Activity
import android.hardware.usb.UsbDevice
import android.hardware.usb.UsbManager
import android.os.Build
import android.os.Bundle
import com.school.nfcadapter.ModuleRegistry

/**
 * Invisible, instant-finish entry point for ACTION_USB_DEVICE_ATTACHED
 * (the system delivers that action to activities only — never to manifest
 * receivers). If the app is already running, the device is forwarded to the
 * live module; on a cold start triggered by plugging the reader in, the
 * launcher activity is brought up and the module's start() picks the
 * already-attached device up from UsbManager.deviceList.
 */
class UsbAttachTrampolineActivity : Activity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        if (intent?.action == UsbManager.ACTION_USB_DEVICE_ATTACHED) {
            val device: UsbDevice? = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                intent.getParcelableExtra(UsbManager.EXTRA_DEVICE, UsbDevice::class.java)
            } else {
                @Suppress("DEPRECATION")
                intent.getParcelableExtra(UsbManager.EXTRA_DEVICE)
            }
            val module = ModuleRegistry.active
            if (module != null && device != null) {
                module.onUsbAttach(device)
            } else {
                // Cold start: bring the app up; module.start() rescans deviceList.
                packageManager.getLaunchIntentForPackage(packageName)?.let(::startActivity)
            }
        }
        finish()
    }
}
