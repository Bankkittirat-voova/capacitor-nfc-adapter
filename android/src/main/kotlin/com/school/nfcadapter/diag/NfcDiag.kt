package com.school.nfcadapter.diag

import android.util.Log

/**
 * One-tap diagnostic channel. NfcAdapterModule.create() tees EVERY module log
 * line into this tag, so a field engineer traces the whole chain with:
 *
 *     adb logcat -s NfcDiag
 *
 * Checkpoint prefixes: [USB] attach/permission/state, [CCID] handshake/ATR,
 * [SERIAL] frame assembly, [UID] raw bytes -> uid_dec_reversed, [ERROR] failures.
 */
object NfcDiag {
    const val TAG = "NfcDiag"

    fun log(msg: String) {
        Log.i(TAG, msg)
    }

    /** Wraps a config logger so every line also reaches logcat under [TAG]. */
    fun tee(downstream: (String) -> Unit): (String) -> Unit = { msg ->
        log(msg)
        downstream(msg)
    }

    fun hex(bytes: ByteArray, len: Int = bytes.size): String =
        (0 until minOf(len, bytes.size)).joinToString(" ") { "%02X".format(bytes[it]) }
}
