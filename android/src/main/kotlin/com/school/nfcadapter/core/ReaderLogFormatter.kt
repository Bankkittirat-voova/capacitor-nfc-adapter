package com.school.nfcadapter.core

/** Existing UID diagnostic format, extracted so normal masking is JVM-tested. */
internal fun formatUidLog(
    rawUid: ByteArray,
    uidDecReversed: String,
    logSensitiveValues: Boolean
): String {
    val raw = if (logSensitiveValues) {
        rawUid.joinToString(" ") { "%02X".format(it) }
    } else {
        "<${rawUid.size}B masked>"
    }
    val normalized = if (logSensitiveValues) {
        uidDecReversed
    } else {
        "<masked ${uidDecReversed.length} digits>"
    }
    return "[UID] raw=$raw -> uid_dec_reversed=$normalized"
}
