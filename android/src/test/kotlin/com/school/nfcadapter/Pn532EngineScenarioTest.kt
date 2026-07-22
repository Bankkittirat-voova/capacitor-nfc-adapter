package com.school.nfcadapter

import com.school.nfcadapter.handler.serial.Pn532Frame
import com.school.nfcadapter.handler.serial.Pn532Link
import com.school.nfcadapter.handler.serial.Pn532ReadEngine
import com.school.nfcadapter.transport.TransferFailureException
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Drives the REAL PN532 engine over a scripted link with an injected clock:
 * handshake gating, one-emit-per-tap edge triggering, and the hang-safe timeout
 * path (ACK-abort then TransferFailureException).
 */
class Pn532EngineScenarioTest {

    private val config = NfcModuleConfig(
        absentPollDelayMs = 1,
        presentPollDelayMs = 1,
        statusTimeoutMs = 200,
        transferTimeoutMs = 200,
        maxConsecutiveTransferFailures = 3
    )

    /** Advancing virtual clock: each read() moves it forward so engine deadlines
     *  expire deterministically without real time. */
    private class Clock { var now = 0L }

    /** Each read() pops the next scripted chunk (null/empty => a timeout tick)
     *  and advances [clock] so deadlines in the engine can expire. */
    private class ScriptedPn532Link(
        chunks: List<ByteArray?>,
        private val clock: Clock
    ) : Pn532Link {
        private val queue = ArrayDeque(chunks)
        val writes = mutableListOf<ByteArray>()
        override fun write(data: ByteArray): Boolean { writes += data.copyOf(); return true }
        override fun read(dst: ByteArray, timeoutMs: Int): Int {
            clock.now += 50
            if (queue.isEmpty()) return -1          // exhausted script => link dead (detach)
            val c = queue.removeFirst()
            if (c == null || c.isEmpty()) return 0  // null element => idle timeout tick
            c.copyInto(dst)
            return c.size
        }
        override fun close() {}
    }

    private fun bytes(vararg v: Int) = ByteArray(v.size) { v[it].toByte() }

    private fun firmwareResponse(ic: Int) = bytes(0x00, 0x00, 0xFF, 0x00, 0xFF, 0x00) + run {
        val payload = bytes(0x03, ic, 0x01, 0x06, 0x07)
        val len = payload.size + 1
        val lcs = (0x100 - len) and 0xFF
        var sum = 0xD5; for (b in payload) sum += b.toInt() and 0xFF
        val dcs = (0x100 - (sum and 0xFF)) and 0xFF
        bytes(0x00, 0x00, 0xFF, len, lcs, 0xD5) + payload + bytes(dcs, 0x00)
    }

    private fun inListResponse(uid: ByteArray): ByteArray {
        val payload = bytes(0x4B, 0x01, 0x01, 0x00, 0x04, 0x08, uid.size) + uid
        val len = payload.size + 1
        val lcs = (0x100 - len) and 0xFF
        var sum = 0xD5; for (b in payload) sum += b.toInt() and 0xFF
        val dcs = (0x100 - (sum and 0xFF)) and 0xFF
        return bytes(0x00, 0x00, 0xFF, len, lcs, 0xD5) + payload + bytes(dcs, 0x00)
    }

    private val noTargetResponse: ByteArray = run {
        val payload = bytes(0x4B, 0x00)
        val len = payload.size + 1
        val lcs = (0x100 - len) and 0xFF
        var sum = 0xD5; for (b in payload) sum += b.toInt() and 0xFF
        val dcs = (0x100 - (sum and 0xFF)) and 0xFF
        bytes(0x00, 0x00, 0xFF, len, lcs, 0xD5) + payload + bytes(dcs, 0x00)
    }

    private val uidA = bytes(0xFB, 0x5D, 0x82, 0x29)

    @Test
    fun handshakeAcceptsGenuinePn532() = runTest {
        val clock = Clock()
        val link = ScriptedPn532Link(listOf(null, firmwareResponse(Pn532Frame.IC_PN532)), clock)
        val engine = Pn532ReadEngine(link, config) { clock.now }
        assertTrue(engine.handshakeAndConfigure())
        // Proves we actively sent the GetFirmwareVersion command (not just listened).
        assertTrue(link.writes.any { it.contentEquals(Pn532Frame.GET_FIRMWARE_VERSION) })
    }

    @Test
    fun handshakeRejectsNonPn532() = runTest {
        val clock = Clock()
        val link = ScriptedPn532Link(listOf(null, firmwareResponse(0x07)), clock)  // PN533, not PN532
        val engine = Pn532ReadEngine(link, config) { clock.now }
        assertFalse(engine.handshakeAndConfigure())
    }

    @Test
    fun handshakeRejectsSilentDevice() = runTest {
        val clock = Clock()
        val link = ScriptedPn532Link(emptyList(), clock)   // never answers
        val engine = Pn532ReadEngine(link, config) { clock.now }
        assertFalse(engine.handshakeAndConfigure())
    }

    @Test
    fun tapEmitsExactlyOnceThenLinkDies() = runTest {
        val clock = Clock()
        // drain(0) tick, InList->UID, drain tick, InList->UID(dwell), drain tick,
        // InList->NoTarget(removed); then queue empties -> timeouts -> abort x3 -> throw.
        val link = ScriptedPn532Link(
            listOf(
                null, inListResponse(uidA),
                null, inListResponse(uidA),
                null, noTargetResponse
            ),
            clock
        )
        val engine = Pn532ReadEngine(link, config) { clock.now }
        val emissions = mutableListOf<ByteArray>()
        var died = false
        try {
            engine.runLoop { emissions += it.copyOf() }
        } catch (_: TransferFailureException) {
            died = true
        }
        assertEquals("edge-trigger: dwell must not re-fire", 1, emissions.size)
        assertArrayEquals(uidA, emissions[0])
        assertTrue("link loss raises TransferFailureException", died)
    }

    @Test
    fun idleTimeoutsKeepPollingAndAbort_notFatal() = runTest {
        // No card for many cycles: the engine must NOT tear down (regression guard
        // for the ~3s premature-teardown bug). It aborts the blocked command and
        // keeps polling; only the eventual scripted link-death ends the loop.
        val clock = Clock()
        val link = ScriptedPn532Link(List(10) { null }, clock)  // 10 idle timeout ticks, then link dead
        val engine = Pn532ReadEngine(link, config) { clock.now }
        val emissions = mutableListOf<ByteArray>()
        var died = false
        try {
            engine.runLoop { emissions += it.copyOf() }
        } catch (_: TransferFailureException) {
            died = true
        }
        assertEquals("no card => no emission", 0, emissions.size)
        assertTrue("abort ACK sent on idle timeout (hang-safe)", link.writes.any { it.contentEquals(Pn532Frame.ACK) })
        assertTrue("only genuine link loss ends the loop", died)
    }
}
