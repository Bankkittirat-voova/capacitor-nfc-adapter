package com.school.nfcadapter.core

import android.content.Context
import android.hardware.usb.UsbDevice
import android.hardware.usb.UsbDeviceConnection
import android.hardware.usb.UsbInterface
import android.hardware.usb.UsbManager
import android.os.SystemClock
import com.hoho.android.usbserial.driver.UsbSerialProber
import com.school.nfcadapter.NfcModuleConfig
import com.school.nfcadapter.api.ReaderAttachInfo
import com.school.nfcadapter.api.ReaderError
import com.school.nfcadapter.api.ReaderState
import com.school.nfcadapter.handler.NfcReaderHandler
import com.school.nfcadapter.handler.branda.BrandAHandlerHook
import com.school.nfcadapter.handler.ccid.CcidReaderHandler
import com.school.nfcadapter.handler.serial.AsciiHexLineProfile
import com.school.nfcadapter.handler.serial.Pn532SerialReaderHandler
import com.school.nfcadapter.handler.serial.SerialReaderHandler
import com.school.nfcadapter.transport.AndroidUsbTransport
import com.school.nfcadapter.transport.TransferFailureException
import com.school.nfcadapter.usb.UsbEndpoints
import com.school.nfcadapter.usb.UsbPermissionCoordinator
import kotlinx.coroutines.CancellationException
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
    private val lifecycle = RestartableLifecycle<Session>()
    private val currentGeneration = AtomicLong(0)
    private val attachStorm = SlidingWindowCounter(config.stormWindowMs, config.stormThreshold)
    private val brownout = SlidingWindowCounter(config.brownoutWindowMs, config.brownoutThreshold)
    private var stormCooldownUntil = 0L
    private val router = DeviceRouter(config)

    private class Session(
        val device: UsbDevice,
        val connection: UsbDeviceConnection,
        val iface: UsbInterface?,
        val handler: NfcReaderHandler,
        val generation: Long
    )

    // ---------------------------------------------------------------- attach

    fun onDeviceAttached(device: UsbDevice) {
        lifecycle.launchControl control@{
            config.logger(
                "[USB] device attached: ${device.deviceName} VID=%04X PID=%04X class=0x%02X"
                    .format(device.vendorId, device.productId, device.deviceClass)
            )
            val now = SystemClock.elapsedRealtime()
            if (now < stormCooldownUntil) return@control
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
                return@control
            }
            // Vibration-bounce debounce: invest in init only for a stable attach.
            lifecycle.replacePendingAttach(config.attachDebounceMs) {
                openSession(device)
            }
        }
    }

    fun onDeviceDetached(device: UsbDevice) {
        lifecycle.launchControl {
            config.logger("[USB] device detached: ${device.deviceName}")
            lifecycle.cancelPendingAttach()
            closeSession(device, unexpected = true)
        }
    }

    fun shutdown() {
        lifecycle.temporaryStop { releaseSession(null, unexpected = false) }
    }

    fun destroy() {
        lifecycle.destroy { releaseSession(null, unexpected = false) }
    }

    // --------------------------------------------------------------- session

    /** Runs on the module dispatcher only. */
    private suspend fun openSession(device: UsbDevice) {
        if (lifecycle.activeSession() != null) {
            config.logger("Second scanner ignored (one active session policy): ${device.deviceName}")
            return
        }
        val info = device.toInfo()
        val route = router.route(info)
        config.logger("[USB] route decision: $route (interfaces=${info.interfaceClasses.joinToString { "0x%02X".format(it) }})")
        listener.onReaderAttached(
            ReaderAttachInfo(
                vendorId = info.vendorId,
                productId = info.productId,
                route = routeName(route),
                productName = runCatching { device.productName }.getOrNull(),
                manufacturerName = runCatching { device.manufacturerName }.getOrNull()
            )
        )
        if (route is Route.Unsupported) {
            config.logger("[ERROR] unsupported device — no CCID interface, no known serial-bridge VID/PID")
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
            config.logger("[USB] permission DENIED (or dialog timed out)")
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

        config.logger("[USB] permission granted")
        listener.onReaderStateChanged(ReaderState.INITIALIZING)
        val connection = usbManager.openDevice(device) ?: run {
            reportReaderOpenFailure(
                listener,
                config.logger,
                "openDevice returned null (device gone or permission revoked)",
                "Could not open the scanner."
            )
            return
        }

        val generation = currentGeneration.incrementAndGet()
        // try/catch: any unexpected throw from endpoint discovery / the serial
        // prober / a handler constructor must still close the open connection.
        val session: Session? = try {
            when (route) {
                is Route.Ccid -> {
                    val ep = UsbEndpoints.findCcid(device)
                    if (ep == null || !connection.claimInterface(ep.iface, true)) {
                        config.logger(
                            if (ep == null) "[ERROR] no CCID bulk-in/bulk-out endpoint pair found"
                            else "[ERROR] claimInterface failed on the CCID interface"
                        )
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
                        config.logger("[ERROR] serial prober found no driver/port for this device")
                        connection.close(); null
                    } else {
                        Session(
                            device, connection, null,
                            SerialReaderHandler(driver.ports[0], connection, AsciiHexLineProfile(), config),
                            generation
                        )
                    }
                }
                is Route.Pn532Serial -> {
                    val driver = UsbSerialProber.getDefaultProber().probeDevice(device)
                    if (driver == null || driver.ports.isEmpty()) {
                        config.logger("[ERROR] serial prober found no driver/port for the PN532 reader")
                        connection.close(); null
                    } else {
                        Session(
                            device, connection, null,
                            Pn532SerialReaderHandler(driver.ports[0], connection, config),
                            generation
                        )
                    }
                }
                is Route.BrandA ->
                    Session(device, connection, null, BrandAHandlerHook(device, connection, config), generation)
                Route.Unsupported -> null // unreachable — handled above
            }
        } catch (e: Exception) {
            config.logger("[ERROR] session setup threw ${e.javaClass.simpleName}: ${e.message}")
            runCatching { connection.close() }
            null
        }

        if (session == null) {
            reportReaderOpenFailure(
                listener,
                config.logger,
                diagnostic = null,
                userMessage = "Scanner initialization failed."
            )
            return
        }
        if (!lifecycle.setActiveSession(session)) {
            runCatching { session.handler.close() }
            runCatching { session.iface?.let { session.connection.releaseInterface(it) } }
            runCatching { session.connection.close() }
            return
        }

        lifecycle.launchPolling polling@{
            try {
                if (!session.handler.initialize()) {
                    onSessionDead(session, "handler initialize() failed")
                    return@polling
                }
                config.logger("[USB] session ready (generation=${session.generation}) — standby, waiting for tap")
                notifyIfCurrent(session) { listener.onReaderStateChanged(ReaderState.STANDBY) }
                session.handler.runPollingLoop { rawUid ->
                    // Generation guard: a loop orphaned by a newer session must not emit.
                    if (session.generation != currentGeneration.get()) return@runPollingLoop
                    val uid = UidNormalizer.decReversed(rawUid)
                    if (uid == null) {
                        config.logger("[ERROR] malformed UID discarded (raw=${sens(rawUid)})")
                        listener.onReaderError(
                            ReaderError(ReaderError.ErrorCode.PARTIAL_READ, "Discarded malformed card read.", true)
                        )
                        return@runPollingLoop
                    }
                    config.logger(formatUidLog(rawUid, uid, config.logSensitiveValues))
                    listener.onCardScanned(uid)
                }
            } catch (e: TransferFailureException) {
                onSessionDead(session, e.message ?: "transfer failure")
            } catch (e: CancellationException) {
                throw e   // normal teardown — never swallow cancellation
            } catch (e: Exception) {
                // A SupervisorJob child with no handler would crash the app on
                // any unexpected throw (OEM USB stack quirks). Contain it.
                onSessionDead(session, "unexpected ${e.javaClass.simpleName}: ${e.message}")
            }
        }
    }

    private fun hex(bytes: ByteArray) = bytes.joinToString(" ") { "%02X".format(it) }

    /** Card identifiers are masked in normal logs; full value only when
     *  config.logSensitiveValues is enabled (DEBUG/LOCAL ONLY). */
    private fun sens(bytes: ByteArray) = if (config.logSensitiveValues) hex(bytes) else "<${bytes.size}B masked>"

    /** A polling loop declared its link dead — reroute to the ordinary teardown. */
    private fun onSessionDead(session: Session, reason: String) {
        config.logger("[ERROR] session dead: $reason")
        lifecycle.launchControl { closeSession(session.device, unexpected = true) }
    }

    /** Idempotent, single-owner teardown — the ONLY place resources are released. */
    private suspend fun closeSession(device: UsbDevice?, unexpected: Boolean) {
        val active = lifecycle.activeSession() ?: return
        if (device != null && active.device.deviceId != device.deviceId) return
        lifecycle.stopPolling { releaseSession(device, unexpected) }
    }

    /** Runs after polling has been cancelled, while lifecycle teardown owns the session. */
    private fun releaseSession(device: UsbDevice?, unexpected: Boolean) {
        val session = lifecycle.takeActiveSession {
            device == null || it.device.deviceId == device.deviceId
        } ?: return
        currentGeneration.incrementAndGet()          // orphan all of this session's callbacks
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

    private fun routeName(route: Route): String = when (route) {
        Route.BrandA -> "BRAND_A"
        Route.Ccid -> "CCID"
        Route.Pn532Serial -> "PN532_SERIAL"
        is Route.Serial -> "SERIAL:${route.chipFamily}"
        Route.Unsupported -> "UNSUPPORTED"
    }

    private fun UsbDevice.toInfo() = UsbDeviceInfo(
        vendorId = vendorId,
        productId = productId,
        deviceClass = deviceClass,
        interfaceClasses = (0 until interfaceCount).map { getInterface(it).interfaceClass },
        deviceName = deviceName
    )
}
