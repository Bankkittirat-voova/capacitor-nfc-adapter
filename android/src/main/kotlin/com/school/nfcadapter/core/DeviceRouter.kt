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
    /** Command-driven PN532-over-UART reader (must be commanded; does not stream). */
    object Pn532Serial : Route
    data class Serial(val chipFamily: String) : Route
    object Unsupported : Route
}

class DeviceRouter(private val config: NfcModuleConfig = NfcModuleConfig()) {

    companion object {
        const val USB_CLASS_CSCID = 0x0B   // CCID smart-card reader
        const val USB_CLASS_CDC = 0x02     // CDC-ACM serial

        /** (VID to PID) -> chip family. ONLY for vendor-specific-class serial
         *  bridges the OS cannot identify by USB class; CCID readers never
         *  belong here (class 0x0B matching catches them all). Every entry must
         *  be supported by usb-serial-for-android's default prober and mirrored
         *  in res/xml/nfc_device_filter.xml. */
        /** Command-driven PN532-over-UART readers, matched by (VID to PID). The
         *  first member is the PCR532/PM5 class (ATmega-CDC bridge, Arduino Uno
         *  VID/PID). Membership only ROUTES the device to the PN532 handler; that
         *  handler's GetFirmwareVersion handshake must still succeed before it
         *  claims the device, so a non-PN532 device on the same VID/PID becomes a
         *  failed session, never mis-driven. Not PM5-only — any PN532-UART reader
         *  belongs here. */
        val PN532_SERIAL_READERS: Set<Pair<Int, Int>> = setOf(
            0x2341 to 0x0043    // Arduino Uno CDC (PCR532 / PM5 duplicator class)
        )

        val SERIAL_BRIDGES: Map<Pair<Int, Int>, String> = mapOf(
            // WCH
            (0x1A86 to 0x7523) to "CH340",
            (0x1A86 to 0x5523) to "CH341",
            (0x1A86 to 0x55D4) to "CH9102",
            // Silicon Labs
            (0x10C4 to 0xEA60) to "CP210x",   // CP2102/2103/2104/2109
            (0x10C4 to 0xEA70) to "CP210x",   // CP2105 dual
            (0x10C4 to 0xEA71) to "CP210x",   // CP2108 quad
            // FTDI
            (0x0403 to 0x6001) to "FTDI",     // FT232R
            (0x0403 to 0x6010) to "FTDI",     // FT2232
            (0x0403 to 0x6011) to "FTDI",     // FT4232
            (0x0403 to 0x6014) to "FTDI",     // FT232H
            (0x0403 to 0x6015) to "FTDI",     // FT230X/FT231X/FT234XD
            // Prolific
            (0x067B to 0x2303) to "PL2303",   // HX/HXD/TA...
            (0x067B to 0x23A3) to "PL2303",   // GC
            (0x067B to 0x23B3) to "PL2303",   // GB
            (0x067B to 0x23C3) to "PL2303",   // GT
            (0x067B to 0x23D3) to "PL2303",   // GL
            (0x067B to 0x23E3) to "PL2303",   // GE
            (0x067B to 0x23F3) to "PL2303"    // GS
        )
    }

    fun route(d: UsbDeviceInfo): Route {
        if (config.brandAVendorId > 0 &&
            d.vendorId == config.brandAVendorId &&
            (config.brandAProductIds.isEmpty() || d.productId in config.brandAProductIds)
        ) return Route.BrandA

        if (d.deviceClass == USB_CLASS_CSCID || USB_CLASS_CSCID in d.interfaceClasses)
            return Route.Ccid

        // Command-driven PN532-UART readers first: they enumerate as generic CDC
        // (class 0x02) so without this they would fall into Route.Serial and be
        // treated as ASCII-hex streamers — which they are not, hence zero scans.
        if ((d.vendorId to d.productId) in PN532_SERIAL_READERS) return Route.Pn532Serial

        SERIAL_BRIDGES[d.vendorId to d.productId]?.let { return Route.Serial(it) }

        if (d.deviceClass == USB_CLASS_CDC || USB_CLASS_CDC in d.interfaceClasses)
            return Route.Serial("CDC-ACM")

        return Route.Unsupported
    }
}
