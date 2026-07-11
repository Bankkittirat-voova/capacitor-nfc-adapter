package com.school.nfcadapter.transport

/**
 * THE MOCK SEAM. Every byte that crosses the USB bus goes through this
 * interface, so the entire CCID engine is testable on the JVM with a scripted
 * implementation — no device, no emulator.
 *
 * Semantics mirror UsbDeviceConnection.bulkTransfer: return value is bytes
 * moved, or < 0 on failure/timeout/disconnect.
 */
interface UsbTransport {
    fun claim(): Boolean
    fun release()
    fun bulkOut(data: ByteArray, timeoutMs: Int): Int
    fun bulkIn(buffer: ByteArray, timeoutMs: Int): Int
}

/** Thrown by a handler when the link is considered dead (N consecutive transfer
 *  failures). The ConnectionManager catches it and tears the session down. */
class TransferFailureException(message: String) : RuntimeException(message)
