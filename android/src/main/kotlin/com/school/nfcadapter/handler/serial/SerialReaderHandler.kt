package com.school.nfcadapter.handler.serial

import android.hardware.usb.UsbDeviceConnection
import com.hoho.android.usbserial.driver.UsbSerialPort
import com.school.nfcadapter.NfcModuleConfig
import com.school.nfcadapter.handler.NfcReaderHandler
import java.io.IOException

/**
 * Android wrapper for serial-bridge readers (CH340/CP210x/FTDI/PL2303/CDC-ACM)
 * via usb-serial-for-android. All stream logic lives in the pure SerialReadLoop;
 * this class only opens/configures/closes the physical port.
 */
internal class SerialReaderHandler(
    private val port: UsbSerialPort,
    private val connection: UsbDeviceConnection,
    profile: SerialProfile,
    private val config: NfcModuleConfig
) : NfcReaderHandler {

    private val loop = SerialReadLoop(
        link = PortLink(port),
        assembler = SerialFrameAssembler(profile, config.serialInterByteGapMs, config.logger),
        config = config
    )

    override suspend fun initialize(): Boolean = try {
        port.open(connection)
        // 9600-8-N-1 is the near-universal default for serial NFC readers;
        // per-model overrides belong in the SerialProfile if a vendor differs.
        port.setParameters(9600, 8, UsbSerialPort.STOPBITS_1, UsbSerialPort.PARITY_NONE)
        true
    } catch (_: IOException) {
        false
    }

    override suspend fun runPollingLoop(emit: suspend (ByteArray) -> Unit) = loop.run(emit)

    override fun close() {
        runCatching { port.close() }
    }

    private class PortLink(private val port: UsbSerialPort) : SerialLink {
        override fun read(dest: ByteArray, timeoutMs: Int): Int = try {
            port.read(dest, timeoutMs)   // 0 on timeout with no data
        } catch (_: IOException) {
            -1                           // link failure (detach / brownout)
        }

        override fun close() {
            runCatching { port.close() }
        }
    }
}
