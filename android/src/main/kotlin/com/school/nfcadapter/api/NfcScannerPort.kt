package com.school.nfcadapter.api

/**
 * The unified cross-platform contract. This is the ONLY surface the host app
 * depends on. A Swift twin of this interface lives in ios/Sources/NfcScannerPort.swift;
 * both implementations deliver the identical `uid_dec_reversed` string format.
 *
 * Platform behavior:
 *  - Android: external USB Type-C readers via USB Host (CCID / serial / Brand A SDK).
 *  - iOS:     built-in NFC antenna via CoreNFC (NFCTagReaderSession) — students tap
 *             the card against the back of the iPhone.
 *  - Unsupported hardware (no OTG): permanent no-op reporting isSupported = false.
 */
interface NfcScannerPort {
    val isSupported: Boolean
    fun setListener(listener: NfcScanListener?)
    fun start()
    fun stop()
}

interface NfcScanListener {
    /**
     * Always invoked on the MAIN thread.
     * @param uidDecReversed the card UID in the strict `uid_dec_reversed` contract
     *        format: decimal representation of the reversed raw byte array.
     *        Example: raw hex FB5D8229 -> reversed 29825DFB -> "696409595".
     */
    fun onCardScanned(uidDecReversed: String)

    /** Reader lifecycle — drive the "Standby" UI badge from these. Main thread. */
    fun onReaderStateChanged(state: ReaderState)

    /** Non-fatal, human-readable diagnostics. Main thread. */
    fun onReaderError(error: ReaderError)

    /**
     * A USB reader was enumerated and routed (fired before the permission
     * prompt). Diagnostic only — lets a debug/pilot UI show which physical
     * device attached and which protocol path claimed it. Default no-op so
     * existing/other-platform implementors need not override. Main thread.
     */
    fun onReaderAttached(info: ReaderAttachInfo) {}
}

/** Diagnostic snapshot of a freshly attached USB reader. */
data class ReaderAttachInfo(
    val vendorId: Int,
    val productId: Int,
    /** Protocol path chosen by the router, e.g. "CCID", "PN532_SERIAL", "SERIAL:CH340". */
    val route: String,
    val productName: String?,
    val manufacturerName: String?
)

enum class ReaderState { DISCONNECTED, PERMISSION_PENDING, INITIALIZING, STANDBY, READING, ERROR }

data class ReaderError(
    val code: ErrorCode,
    val message: String,
    val recoverable: Boolean
) {
    enum class ErrorCode {
        PERMISSION_DENIED,
        UNSUPPORTED_DEVICE,
        TRANSFER_FAILURE,
        PARTIAL_READ,
        POWER_SUSPECTED,
        CONNECTION_UNSTABLE,
        SESSION_UNAVAILABLE,   // iOS: NFC session could not start / was interrupted
        INTERNAL
    }
}

/** Permanent no-op used on hardware without USB Host support (and as the iOS-side
 *  default in shared code when no platform implementation is bound). */
class NoopScannerPort : NfcScannerPort {
    override val isSupported = false
    override fun setListener(listener: NfcScanListener?) {}
    override fun start() {}
    override fun stop() {}
}
