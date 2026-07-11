package com.school.nfcadapter.core

import android.content.Context
import android.hardware.usb.UsbDevice
import android.hardware.usb.UsbDeviceConnection
import android.hardware.usb.UsbInterface
import android.hardware.usb.UsbManager
import android.os.SystemClock
import com.hoho.android.usbserial.driver.UsbSerialProber
import com.school.nfcadapter.NfcModuleConfig
import com.school.nfcadapter.api.ReaderError
import com.school.nfcadapter.api.ReaderState
import com.school.nfcadapter.handler.NfcReaderHandler
import com.school.nfcadapter.handler.branda.BrandAHandlerHook
import com.school.nfcadapter.handler.ccid.CcidReaderHandler
import com.school.nfcadapter.handler.serial.AsciiHexLineProfile
import com.school.nfcadapter.handler.serial.SerialReaderHandler
import com.school.nfcadapter.transport.AndroidUsbTransport
import com.school.nfcadapter.transport.TransferFailureException
import com.school.nfcadapter.usb.UsbEndpoints
import com.school.nfcadapter.usb.UsbPermissionCoordinator
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import java.util.concurrent.atomic.AtomicLong

/**
 * The race-free heart of the module. ALL lifecycle mutations run on one
 * single-threaded dispatcher; every hardware session carries a generation
 * token, so late events from a dead session (stale polling loop, stale
 * broadcast, vibration bounce) compare tokens and self-discard.
 */
internal class ConnectionManager(
    private val context: Context,
    private val usbManager: UsbManager,
    private val permissions: UsbPermissionCoordinator,
    private val listener: ListenerProxy,
    private val config: NfcModuleConfig
) {
    // limitedParallelism is stable in behavior but still gated behind the
    // experimental-API opt-in in coroutines 1.8.x.
    @OptIn(ExperimentalCoroutinesApi::class)
    private val moduleScope =
        CoroutineScope(SupervisorJob() + Dispatchers.Default.limitedParallelism(1))

    private val currentGeneration = AtomicLong(0)
    private var pendingAttach: Job? = null
    private var activeSession: Session? = null
    private val attachStorm = SlidingWindowCounter(config.stormWindowMs, config.stormThreshold)
    private val brownout = SlidingWindowCounter(config.brownoutWindowMs, config.brownoutThreshold)
    private var stormCooldownUntil = 0L
    private val router = DeviceRouter(config)

    private class Session(
        val device: UsbDevice,
        val connection: UsbDeviceConnection,
        val iface: UsbInterface?,
        val handler: NfcReaderHandler,
        val generation: Long,
        var pollingJob: Job? = null
    )

    // ---------------------------------------------------------------- attach

    fun onDeviceAttached(device: UsbDevice) {
        moduleScope.launch {
            val now = SystemClock.elapsedRealtime()
            if (now < stormCooldownUntil) return@launch
            if (attachStorm.record(now)) {
                stormCooldownUntil = now + config.stormCooldownMs
                listener.onReaderError(
                    ReaderError(
                        ReaderError.ErrorCode.CONNECTION_UNSTABLE,
                        "Scanner connection unstable — check the cable is pushed in firmly.",
                        recoverable = true
                    )
                )
                listener.onReaderStateChanged(ReaderState.ERROR)
                return@launch
            }
            // Vibration-bounce debounce: invest in init only for a stable attach.
            pendingAttach?.cancel()
            pendingAttach = launch {
                delay(config.attachDebounceMs)
                openSession(device)
            }
        }
    }

    fun onDeviceDetached(device: UsbDevice) {
        moduleScope.launch {
            pendingAttach?.cancel()
            closeSession(device, unexpected = true)
        }
    }

    fun shutdown() {
        moduleScope.launch { closeSession(null, unexpected = false) }
        moduleScope.cancel()
    }

    // --------------------------------------------------------------- session

    /** Runs on the module dispatcher only. */
    private suspend fun openSession(device: UsbDevice) {
        if (activeSession != null) {
            config.logger("Second scanner ignored (one active session policy): ${device.deviceName}")
            return
        }
        val info = device.toInfo()
        val route = router.route(info)
        if (route is Route.Unsupported) {
            listener.onReaderError(
                ReaderError(
                    ReaderError.ErrorCode.UNSUPPORTED_DEVICE,
                    "Scanner model not supported (VID=%04X PID=%04X)".format(info.vendorId, info.productId),
                    recoverable = false
                )
            )
            return
        }

        listener.onReaderStateChanged(ReaderState.PERMISSION_PENDING)
        if (!permissions.ensurePermission(usbManager, device)) {
            listener.onReaderError(
                ReaderError(
                    ReaderError.ErrorCode.PERMISSION_DENIED,
                    "USB permission was not granted for the scanner.",
                    recoverable = true
                )
            )
            listener.onReaderStateChanged(ReaderState.DISCONNECTED)
            return
        }

        listener.onReaderStateChanged(ReaderState.INITIALIZING)
        val connection = usbManager.openDevice(device) ?: run {
            listener.onReaderError(
                ReaderError(ReaderError.ErrorCode.TRANSFER_FAILURE, "Could not open the scanner.", true)
            )
            listener.onReaderStateChanged(ReaderState.DISCONNECTED)
            return
        }

        val generation = currentGeneration.incrementAndGet()
        val session: Session? = when (route) {
            is Route.Ccid -> {
                val ep = UsbEndpoints.findCcid(device)
                if (ep == null || !connection.claimInterface(ep.iface, true)) {
                    connection.close(); null
                } else {
                    Session(
                        device, connection, ep.iface,
                        CcidReaderHandler(AndroidUsbTransport(connection, ep.bulkIn, ep.bulkOut), config),
                        generation
                    )
                }
            }
            is Route.Serial -> {
                val driver = UsbSerialProber.getDefaultProber().probeDevice(device)
                if (driver == null || driver.ports.isEmpty()) {
                    connection.close(); null
                } else {
                    Session(
                        device, connection, null,
                        SerialReaderHandler(driver.ports[0], connection, AsciiHexLineProfile(), config),
                        generation
                    )
                }
            }
            is Route.BrandA ->
                Session(device, connection, null, BrandAHandlerHook(device, connection, config), generation)
            Route.Unsupported -> null // unreachable — handled above
        }

        if (session == null) {
            listener.onReaderError(
                ReaderError(ReaderError.ErrorCode.TRANSFER_FAILURE, "Scanner initialization failed.", true)
            )
            listener.onReaderStateChanged(ReaderState.DISCONNECTED)
            return
        }
        activeSession = session

        session.pollingJob = moduleScope.launch(Dispatchers.IO) {
            try {
                if (!session.handler.initialize()) {
                    onSessionDead(session, "handler initialize() failed")
                    return@launch
                }
                notifyIfCurrent(session) { listener.onReaderStateChanged(ReaderState.STANDBY) }
                session.handler.runPollingLoop { rawUid ->
                    // Generation guard: a loop orphaned by a newer session must not emit.
                    if (session.generation != currentGeneration.get()) return@runPollingLoop
                    val uid = UidNormalizer.decReversed(rawUid)
                    if (uid == null) {
                        listener.onReaderError(
                            ReaderError(ReaderError.ErrorCode.PARTIAL_READ, "Discarded malformed card read.", true)
                        )
                        return@runPollingLoop
                    }
                    listener.onCardScanned(uid)
                }
            } catch (e: TransferFailureException) {
                onSessionDead(session, e.message ?: "transfer failure")
            }
        }
    }

    /** A polling loop declared its link dead — reroute to the ordinary teardown. */
    private fun onSessionDead(session: Session, reason: String) {
        config.logger("session dead: $reason")
        moduleScope.launch { closeSession(session.device, unexpected = true) }
    }

    /** Idempotent, single-owner teardown — the ONLY place resources are released. */
    private fun closeSession(device: UsbDevice?, unexpected: Boolean) {
        val session = activeSession ?: return
        if (device != null && session.device.deviceId != device.deviceId) return
        activeSession = null
        currentGeneration.incrementAndGet()          // orphan all of this session's callbacks
        session.pollingJob?.cancel()                 // cooperative: loop exits at next bounded transfer
        runCatching { session.handler.close() }
        runCatching { session.iface?.let { session.connection.releaseInterface(it) } }
        runCatching { session.connection.close() }   // unblocks any in-flight bulkTransfer
        listener.onReaderStateChanged(ReaderState.DISCONNECTED)

        if (unexpected && brownout.record(SystemClock.elapsedRealtime())) {
            listener.onReaderError(
                ReaderError(
                    ReaderError.ErrorCode.POWER_SUSPECTED,
                    "Scanner keeps losing power — plug the phone into the bus charger or use a powered USB hub.",
                    recoverable = true
                )
            )
        }
    }

    private inline fun notifyIfCurrent(session: Session, block: () -> Unit) {
        if (session.generation == currentGeneration.get()) block()
    }

    private fun UsbDevice.toInfo() = UsbDeviceInfo(
        vendorId = vendorId,
        productId = productId,
        deviceClass = deviceClass,
        interfaceClasses = (0 until interfaceCount).map { getInterface(it).interfaceClass },
        deviceName = deviceName
    )
}
