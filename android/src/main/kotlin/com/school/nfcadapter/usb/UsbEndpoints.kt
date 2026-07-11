package com.school.nfcadapter.usb

import android.hardware.usb.UsbConstants
import android.hardware.usb.UsbDevice
import android.hardware.usb.UsbEndpoint
import android.hardware.usb.UsbInterface

/**
 * Locates the CCID interface (class 0x0B) and its bulk-in/bulk-out endpoint
 * pair on an attached device.
 */
internal object UsbEndpoints {

    data class CcidEndpoints(
        val iface: UsbInterface,
        val bulkIn: UsbEndpoint,
        val bulkOut: UsbEndpoint
    )

    fun findCcid(device: UsbDevice): CcidEndpoints? {
        for (i in 0 until device.interfaceCount) {
            val iface = device.getInterface(i)
            if (iface.interfaceClass != UsbConstants.USB_CLASS_CSCID) continue
            var epIn: UsbEndpoint? = null
            var epOut: UsbEndpoint? = null
            for (e in 0 until iface.endpointCount) {
                val ep = iface.getEndpoint(e)
                if (ep.type != UsbConstants.USB_ENDPOINT_XFER_BULK) continue
                if (ep.direction == UsbConstants.USB_DIR_IN) epIn = ep else epOut = ep
            }
            if (epIn != null && epOut != null) return CcidEndpoints(iface, epIn, epOut)
        }
        return null
    }
}
