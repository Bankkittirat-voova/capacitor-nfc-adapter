package com.school.nfcadapter

import com.school.nfcadapter.core.UidNormalizer
import com.school.nfcadapter.handler.serial.Pn532Frame
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class Pn532FrameTest {

    private fun bytes(vararg v: Int) = ByteArray(v.size) { v[it].toByte() }

    // ---------------------------------------------------------- frame builders
    // Golden frames are the canonical NXP UM0701 encodings; the InListPassiveTarget
    // / GetFirmwareVersion forms were also confirmed on the physical PCR532/PM5.

    @Test
    fun buildsGetFirmwareVersionFrame() {
        assertArrayEquals(
            bytes(0x00, 0x00, 0xFF, 0x02, 0xFE, 0xD4, 0x02, 0x2A, 0x00),
            Pn532Frame.GET_FIRMWARE_VERSION
        )
    }

    @Test
    fun buildsSamConfigurationFrame() {
        assertArrayEquals(
            bytes(0x00, 0x00, 0xFF, 0x05, 0xFB, 0xD4, 0x14, 0x01, 0x00, 0x00, 0x17, 0x00),
            Pn532Frame.SAM_CONFIGURATION
        )
    }

    @Test
    fun buildsInListPassiveTargetFrame() {
        assertArrayEquals(
            bytes(0x00, 0x00, 0xFF, 0x04, 0xFC, 0xD4, 0x4A, 0x01, 0x00, 0xE1, 0x00),
            Pn532Frame.IN_LIST_PASSIVE_TARGET_106A
        )
    }

    @Test
    fun buildsRfConfigurationMaxRetriesFrame() {
        // CfgItem 0x05 (MaxRetries): MxRtyATR=FF, MxRtyPSL=01, MxRtyPassiveActivation=01.
        assertArrayEquals(
            bytes(0x00, 0x00, 0xFF, 0x06, 0xFA, 0xD4, 0x32, 0x05, 0xFF, 0x01, 0x01, 0xF4, 0x00),
            Pn532Frame.RF_CONFIG_MAX_RETRIES
        )
    }

    // ---------------------------------------------------------------- ACK frame

    @Test
    fun detectsAckAnywhere() {
        assertTrue(Pn532Frame.containsAck(bytes(0x00, 0x00, 0xFF, 0x00, 0xFF, 0x00), 6))
        assertTrue(Pn532Frame.containsAck(bytes(0xAA, 0x00, 0x00, 0xFF, 0x00, 0xFF, 0x00), 7))
        assertTrue(!Pn532Frame.containsAck(bytes(0x00, 0x00, 0xFF, 0x02, 0xFE), 5))
    }

    // ------------------------------------------------ parse: REAL device bytes
    // Captured verbatim from the PM5 (COM17 @115200): ACK + GetFirmwareVersion
    // response, IC byte 0x32 (=PN532), firmware v1.6.

    @Test
    fun parsesRealFirmwareResponseSkippingLeadingAck() {
        val rx = bytes(
            0x00, 0x00, 0xFF, 0x00, 0xFF, 0x00,                        // ACK
            0x00, 0x00, 0xFF, 0x06, 0xFA, 0xD5, 0x03, 0x32, 0x01, 0x06, 0x07, 0xE8, 0x00
        )
        val p = Pn532Frame.parseResponse(rx, rx.size)
        assertTrue(p is Pn532Frame.Parsed.Response)
        val payload = (p as Pn532Frame.Parsed.Response).payload
        assertArrayEquals(bytes(0x03, 0x32, 0x01, 0x06, 0x07), payload)
        assertEquals(Pn532Frame.IC_PN532, Pn532Frame.firmwareIc(payload))
    }

    @Test
    fun rejectsFirmwareIcForNonPn532() {
        // Same shape but IC = 0x07 (PN533) must not read as PN532.
        val payload = bytes(0x03, 0x07, 0x01, 0x06, 0x07)
        assertEquals(0x07, Pn532Frame.firmwareIc(payload))
        assertTrue(Pn532Frame.firmwareIc(payload) != Pn532Frame.IC_PN532)
    }

    // ---------------------------------------------- parse: structural failures

    @Test
    fun incompleteFrameWaitsForMore() {
        val partial = bytes(0x00, 0x00, 0xFF, 0x06, 0xFA, 0xD5, 0x03)   // truncated mid-payload
        assertTrue(Pn532Frame.parseResponse(partial, partial.size) is Pn532Frame.Parsed.Incomplete)
    }

    @Test
    fun badLcsIsInvalid() {
        val bad = bytes(0x00, 0x00, 0xFF, 0x06, 0x00, 0xD5, 0x03, 0x32, 0x01, 0x06, 0x07, 0xE8, 0x00)
        assertTrue(Pn532Frame.parseResponse(bad, bad.size) is Pn532Frame.Parsed.Invalid)
    }

    @Test
    fun badDcsIsInvalid() {
        val bad = bytes(0x00, 0x00, 0xFF, 0x06, 0xFA, 0xD5, 0x03, 0x32, 0x01, 0x06, 0x07, 0x00, 0x00)
        assertTrue(Pn532Frame.parseResponse(bad, bad.size) is Pn532Frame.Parsed.Invalid)
    }

    // -------------------------------------------------- extractUid + contract

    /** Build a PN532->host InListPassiveTarget response for one 106A target. */
    private fun inListResponse(uid: ByteArray): ByteArray {
        val payload = bytes(0x4B, 0x01, 0x01, 0x00, 0x04, 0x08, uid.size) + uid
        val len = payload.size + 1
        val lcs = (0x100 - len) and 0xFF
        var sum = 0xD5
        for (b in payload) sum += b.toInt() and 0xFF
        val dcs = (0x100 - (sum and 0xFF)) and 0xFF
        return bytes(0x00, 0x00, 0xFF, len, lcs, 0xD5) + payload + bytes(dcs, 0x00)
    }

    @Test
    fun extractsUidAndMatchesDecReversedContract() {
        val uid = bytes(0xFB, 0x5D, 0x82, 0x29)          // the shared-test-vectors card
        val rx = inListResponse(uid)
        val p = Pn532Frame.parseResponse(rx, rx.size)
        assertTrue(p is Pn532Frame.Parsed.Response)
        val u = Pn532Frame.extractUid((p as Pn532Frame.Parsed.Response).payload)
        assertTrue(u is Pn532Frame.UidResult.Ok)
        assertArrayEquals(uid, (u as Pn532Frame.UidResult.Ok).uid)
        // The PN532 path feeds the SAME central normalizer as CCID/serial:
        assertEquals("696409595", UidNormalizer.decReversed(u.uid))
    }

    @Test
    fun noTargetWhenNbTgZero() {
        val payload = bytes(0x4B, 0x00)
        assertTrue(Pn532Frame.extractUid(payload) is Pn532Frame.UidResult.NoTarget)
    }

    @Test
    fun rejectsWrongResponseCode() {
        val payload = bytes(0x03, 0x32, 0x01)            // firmware payload, not InList
        assertTrue(Pn532Frame.extractUid(payload) is Pn532Frame.UidResult.Reject)
    }

    @Test
    fun rejectsInvalidUidLength() {
        val payload = bytes(0x4B, 0x01, 0x01, 0x00, 0x04, 0x08, 0x03, 0xAA, 0xBB, 0xCC) // 3-byte UID
        assertTrue(Pn532Frame.extractUid(payload) is Pn532Frame.UidResult.Reject)
    }

    @Test
    fun firmwareIcNullForNonFirmwarePayload() {
        assertNull(Pn532Frame.firmwareIc(bytes(0x4B, 0x01)))
    }
}
