package com.school.nfcadapter.handler.branda

import android.hardware.usb.UsbDevice
import android.hardware.usb.UsbDeviceConnection
import com.school.nfcadapter.NfcModuleConfig
import com.school.nfcadapter.handler.NfcReaderHandler
import kotlinx.coroutines.suspendCancellableCoroutine

/**
 * PLACEHOLDER for the Brand A vendor SDK (.aar), which is held by another
 * developer. Integration contract — changes stay INSIDE this file:
 *
 *  1. Add the .aar dependency to :nfc-adapter/build.gradle.kts only.
 *  2. initialize(): instantiate the SDK with [device]/[connection]; return true.
 *  3. runPollingLoop(): bridge the SDK's scan callback to emit(rawUidBytes)
 *     (callbackFlow or suspendCancellableCoroutine); emit RAW bytes — the
 *     ConnectionManager applies the uid_dec_reversed normalization centrally.
 *  4. close(): release the SDK.
 *  5. Set brandAVendorId/brandAProductIds in NfcModuleConfig from the datasheet.
 *
 * Until then: initialize() returns false, so a Brand A reader surfaces a clean
 * "initialization failed" instead of a dead standby.
 */
internal class BrandAHandlerHook(
    @Suppress("unused") private val device: UsbDevice,
    @Suppress("unused") private val connection: UsbDeviceConnection,
    private val config: NfcModuleConfig
) : NfcReaderHandler {

    override suspend fun initialize(): Boolean {
        config.logger("BrandA: SDK not linked yet — see BrandAHandlerHook integration contract")
        return false
    }

    override suspend fun runPollingLoop(emit: suspend (ByteArray) -> Unit) {
        // Unreachable while initialize() returns false. With the SDK linked:
        // suspendCancellableCoroutine { cont ->
        //     sdk.setOnCardListener { bytes -> launch { emit(bytes) } }
        //     cont.invokeOnCancellation { sdk.stop() }
        // }
        suspendCancellableCoroutine<Unit> { /* parked until cancellation */ }
    }

    override fun close() {
        // sdk.release() once linked
    }
}
