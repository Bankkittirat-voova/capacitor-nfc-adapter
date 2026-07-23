package com.school.nfcadapter

import com.school.nfcadapter.core.formatUidLog
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ReaderLogFormatterTest {

    @Test
    fun normalUidLogMasksRawAndNormalizedValues() {
        val output = formatUidLog(
            rawUid = byteArrayOf(0xFB.toByte(), 0x5D, 0x82.toByte(), 0x29),
            uidDecReversed = "696409595",
            logSensitiveValues = false
        )

        assertTrue(output.contains("raw=<4B masked>"))
        assertTrue(output.contains("uid_dec_reversed=<masked 9 digits>"))
        assertFalse(output.contains("FB 5D 82 29"))
        assertFalse(output.contains("696409595"))
    }
}
