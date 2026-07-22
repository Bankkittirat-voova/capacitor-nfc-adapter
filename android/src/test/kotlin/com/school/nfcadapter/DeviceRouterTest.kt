package com.school.nfcadapter

import com.school.nfcadapter.core.DeviceRouter
import com.school.nfcadapter.core.Route
import com.school.nfcadapter.core.UsbDeviceInfo
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class DeviceRouterTest {

    private val router = DeviceRouter()

    private fun dev(vid: Int, pid: Int, devClass: Int = 0, ifaceClasses: List<Int> = emptyList()) =
        UsbDeviceInfo(vid, pid, devClass, ifaceClasses)

    @Test
    fun ccidByDeviceClass() {
        assertEquals(Route.Ccid, router.route(dev(0x1234, 0x0001, devClass = 0x0B)))
    }

    @Test
    fun ccidByInterfaceClassOnly() {
        // Common layout: deviceClass 0 ("per interface"), CCID at interface level.
        assertEquals(Route.Ccid, router.route(dev(0x1234, 0x0001, devClass = 0, ifaceClasses = listOf(0x0B))))
    }

    @Test
    fun serialBridgesByVidPid() {
        assertEquals(Route.Serial("CH340"), router.route(dev(0x1A86, 0x7523)))
        assertEquals(Route.Serial("CP210x"), router.route(dev(0x10C4, 0xEA60)))
        assertEquals(Route.Serial("FTDI"), router.route(dev(0x0403, 0x6001)))
        assertEquals(Route.Serial("PL2303"), router.route(dev(0x067B, 0x2303)))
    }

    @Test
    fun ccidReaderMatchesByClassWithoutAnyVidPidEntry() {
        // ACR1255U-J1-style layout (ACS VID 0x072F): deviceClass 0, CCID at the
        // interface level. Must route via class 0x0B alone — the VID/PID is
        // deliberately NOT in any table.
        assertTrue((0x072F to 0x223B) !in DeviceRouter.SERIAL_BRIDGES)
        assertEquals(Route.Ccid, router.route(dev(0x072F, 0x223B, devClass = 0, ifaceClasses = listOf(0x0B))))
    }

    @Test
    fun extendedSerialBridgeMatrix() {
        assertEquals(Route.Serial("CH9102"), router.route(dev(0x1A86, 0x55D4)))
        assertEquals(Route.Serial("CP210x"), router.route(dev(0x10C4, 0xEA71)))
        assertEquals(Route.Serial("PL2303"), router.route(dev(0x067B, 0x23A3)))
        assertEquals(Route.Serial("PL2303"), router.route(dev(0x067B, 0x23F3)))
    }

    @Test
    fun cdcAcmByClass() {
        assertEquals(Route.Serial("CDC-ACM"), router.route(dev(0x9999, 0x0001, ifaceClasses = listOf(0x02, 0x0A))))
    }

    @Test
    fun pn532SerialWinsOverGenericCdcForKnownReader() {
        // PCR532 / PM5 duplicator: ATmega-CDC bridge (Arduino Uno VID/PID), enumerates
        // as generic CDC (class 0x02). Must take the command-driven PN532 route, NOT be
        // mistaken for an ASCII-hex serial streamer (which yields zero scans).
        assertTrue((0x2341 to 0x0043) in DeviceRouter.PN532_SERIAL_READERS)
        assertEquals(Route.Pn532Serial, router.route(dev(0x2341, 0x0043, ifaceClasses = listOf(0x02, 0x0A))))
    }

    @Test
    fun unknownCdcDeviceStillRoutesGenericSerial_notPn532() {
        // A CDC device NOT in the PN532 table must keep the existing streaming route.
        assertEquals(Route.Serial("CDC-ACM"), router.route(dev(0x1111, 0x2222, ifaceClasses = listOf(0x02))))
    }

    @Test
    fun ccidReaderNeverStolenByPn532Route() {
        // Scanner A regression guard: an ACS-style CCID reader must still route CCID
        // even though this suite also exercises the new PN532 path.
        assertEquals(Route.Ccid, router.route(dev(0x072F, 0x223F, devClass = 0, ifaceClasses = listOf(0x0B))))
    }

    @Test
    fun unknownDeviceUnsupported() {
        assertEquals(Route.Unsupported, router.route(dev(0x9999, 0x9999)))
    }

    @Test
    fun brandAWinsOverCcidWhenConfigured() {
        val r = DeviceRouter(NfcModuleConfig(brandAVendorId = 0x2222, brandAProductIds = setOf(0x0001)))
        // Brand A reader that ALSO exposes a CCID interface: most specific match wins.
        assertEquals(Route.BrandA, r.route(dev(0x2222, 0x0001, ifaceClasses = listOf(0x0B))))
        // Different PID from same vendor with explicit PID set: falls through to CCID.
        assertEquals(Route.Ccid, r.route(dev(0x2222, 0x0002, ifaceClasses = listOf(0x0B))))
    }

    @Test
    fun brandANeverMatchesWhileUnconfigured() {
        assertTrue(router.route(dev(0x2222, 0x0001)) is Route.Unsupported)
    }
}
