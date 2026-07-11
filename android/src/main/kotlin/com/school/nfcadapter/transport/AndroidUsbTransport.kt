package com.school.nfcadapter.transport

import android.hardware.usb.UsbDeviceConnection
import android.hardware.usb.UsbEndpoint

/**
 * Real UsbTransport backed by UsbDeviceConnection.bulkTransfer. The interface
 * is claimed/released by the ConnectionManager (which owns the UsbInterface),
 * so claim()/release() are pass-throughs here.
 */
internal class AndroidUsbTransport(
    private val connection: UsbDeviceConnection,
    private val bulkIn: UsbEndpoint,
    private val bulkOut: UsbEndpoint
) : UsbTransport {

    override fun claim(): Boolean = true
    override fun release() {}

    override fun bulkOut(data: ByteArray, timeoutMs: Int): Int =
        try {
            connection.bulkTransfer(bulkOut, data, data.size, timeoutMs)
        } catch (_: Exception) {
            -1   // closed connection can throw on some OEM stacks — normalize to failure
        }

    override fun bulkIn(buffer: ByteArray, timeoutMs: Int): Int =
        try {
            connection.bulkTransfer(bulkIn, buffer, buffer.size, timeoutMs)
        } catch (_: Exception) {
            -1
        }
}
