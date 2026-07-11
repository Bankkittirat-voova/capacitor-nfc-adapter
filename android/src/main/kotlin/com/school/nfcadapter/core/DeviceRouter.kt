package com.school.nfcadapter.core

import com.school.nfcadapter.NfcModuleConfig

/**
 * Pure, platform-free routing decision. The Android layer maps a
 * android.hardware.usb.UsbDevice into [UsbDeviceInfo] before calling [route],
 * which keeps this logic fully unit-testable on the JVM.
 *
 * Priority: Brand A (most specific, exact VID/PID) > CCID class > serial bridge
 * VID/PID table > CDC-ACM class > unsupported.
 */
data class UsbDeviceInfo(
    val vendorId: Int,
    val productId: Int,
    val deviceClass: Int,
    val interfaceClasses: List<Int>,
    val deviceName: String = ""
)

sealed interface Route {
    object BrandA : Route
    object Ccid : Route
    data class Serial(val chipFamily: String) : Route
    object Unsupported : Route
}

class DeviceRouter(private val config: NfcModuleConfig = NfcModuleConfig()) {

    companion object {
        const val USB_CLASS_CSCID = 0x0B   // CCID smart-card reader
        const val USB_CLASS_CDC = 0x02     // CDC-ACM serial

        /** (VID to PID) -> chip family. Vendor-specific class devices are only
         *  identifiable this way. Mirrors res/xml/nfc_device_filter.xml. */
        val SERIAL_BRIDGES: Map<Pair<Int, Int>, String> = mapOf(
            (0x1A86 to 0x7523) to "CH340",
            (0x1A86 to 0x5523) to "CH341",
            (0x10C4 to 0xEA60) to "CP210x",
            (0x10C4 to 0xEA70) to "CP210x",
            (0x0403 to 0x6001) to "FTDI",
            (0x0403 to 0x6010) to "FTDI",
            (0x0403 to 0x6011) to "FTDI",
            (0x0403 to 0x6014) to "FTDI",
            (0x0403 to 0x6015) to "FTDI",
            (0x067B to 0x2303) to "PL2303"
        )
    }

    fun route(d: UsbDeviceInfo): Route {
        if (config.brandAVendorId > 0 &&
            d.vendorId == config.brandAVendorId &&
            (config.brandAProductIds.isEmpty() || d.productId in config.brandAProductIds)
        ) return Route.BrandA

        if (d.deviceClass == USB_CLASS_CSCID || USB_CLASS_CSCID in d.interfaceClasses)
            return Route.Ccid

        SERIAL_BRIDGES[d.vendorId to d.productId]?.let { return Route.Serial(it) }

        if (d.deviceClass == USB_CLASS_CDC || USB_CLASS_CDC in d.interfaceClasses)
            return Route.Serial("CDC-ACM")

        return Route.Unsupported
    }
}
