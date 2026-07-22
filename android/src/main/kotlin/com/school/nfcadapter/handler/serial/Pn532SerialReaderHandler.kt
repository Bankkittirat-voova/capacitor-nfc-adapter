package com.school.nfcadapter.handler.serial

import android.hardware.usb.UsbDeviceConnection
import com.hoho.android.usbserial.driver.UsbSerialPort
import com.school.nfcadapter.NfcModuleConfig
import com.school.nfcadapter.handler.NfcReaderHandler
import kotlinx.coroutines.delay
import java.io.IOException

/**
 * Android handler for the command-driven PN532-over-UART reader class
 * (e.g. the PCR532 / PM5 ATmega-CDC bridge, VID 2341 / PID 0043). Opens/configures
 * the serial port, then delegates all protocol logic to the pure, JVM-tested
 * [Pn532ReadEngine]. This class holds the only Android/usb-serial imports.
 *
 * Distinct from [SerialReaderHandler] (which listens for a streamed ASCII-hex
 * UID): this reader emits nothing until commanded, so we must drive it.
 */
internal class Pn532SerialReaderHandler(
    private val port: UsbSerialPort,
    private val connection: UsbDeviceConnection,
    private val config: NfcModuleConfig
) : NfcReaderHandler {

    private val engine = Pn532ReadEngine(UsbSerialPn532Link(port), config)

    override suspend fun initialize(): Boolean {
        try {
            port.open(connection)
            port.setParameters(
                config.pn532BaudRate, 8, UsbSerialPort.STOPBITS_1, UsbSerialPort.PARITY_NONE
            )
            // DTR/RTS asserted: the ATmega-CDC bridge front-ends often gate TX on
            // DTR, and asserting it triggers the Arduino auto-reset — so wait out
            // the bootloader before the first byte or the handshake races it.
            runCatching { port.dtr = true; port.rts = true }
        } catch (e: IOException) {
            config.logger("[PN532] port open failed: ${e.message}")
            return false
        }
        delay(config.pn532BootDelayMs)
        return engine.handshakeAndConfigure()
    }

    override suspend fun runPollingLoop(emit: suspend (ByteArray) -> Unit) = engine.runLoop(emit)

    override fun close() {
        runCatching { engine.abort() }
        runCatching { port.close() }
    }

    /** usb-serial-for-android UsbSerialPort adapted to the pure engine's seam. */
    private class UsbSerialPn532Link(private val port: UsbSerialPort) : Pn532Link {
        override fun write(data: ByteArray): Boolean = try {
            port.write(data, WRITE_TIMEOUT_MS)
            true
        } catch (_: IOException) {
            false
        }

        override fun read(dst: ByteArray, timeoutMs: Int): Int = try {
            port.read(dst, timeoutMs)   // 0 on timeout with no data
        } catch (_: IOException) {
            -1                          // link failure (detach / brownout)
        }

        override fun close() {
            runCatching { port.close() }
        }

        private companion object {
            const val WRITE_TIMEOUT_MS = 500
        }
    }
}
