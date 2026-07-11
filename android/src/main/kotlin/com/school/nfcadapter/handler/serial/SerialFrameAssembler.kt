package com.school.nfcadapter.handler.serial

/**
 * Reassembles arbitrary USB read chunks into validated UID byte arrays.
 * Handles the three field hazards of serial streams:
 *  - fragmentation (a frame split across many reads),
 *  - partial reads / card flicks (silence mid-frame -> stale bytes discarded
 *    after [interByteGapMs] so they can never prefix the next tap),
 *  - garbage floods (buffer hard-capped at 2x max frame size).
 *
 * Pure Kotlin; the caller supplies timestamps, so tests control the clock.
 */
class SerialFrameAssembler(
    private val profile: SerialProfile,
    private val interByteGapMs: Long = 150,
    private val logger: (String) -> Unit = {}
) {
    private var buf = ByteArray(0)
    private var lastByteAtMs = 0L

    /** Feed one raw chunk; returns zero or more validated raw UID byte arrays. */
    fun feed(chunk: ByteArray, nowMs: Long): List<ByteArray> {
        if (buf.isNotEmpty() && nowMs - lastByteAtMs > interByteGapMs) {
            logger("serial: discarding ${buf.size} stale bytes (partial read / card flick)")
            buf = ByteArray(0)
        }
        lastByteAtMs = nowMs
        buf += chunk

        val uids = mutableListOf<ByteArray>()
        while (true) {
            val ex = profile.extractFrame(buf) ?: break
            buf = buf.copyOfRange(ex.consumed, buf.size)
            if (ex.frame.isEmpty()) continue                       // discarded garbage/terminator
            val uid = profile.validate(ex.frame)
            if (uid != null) uids += uid
            else logger("serial: invalid frame dropped (${ex.frame.size} bytes)")
        }

        if (buf.size > profile.maxFrameSize * 2) {
            logger("serial: flood guard reset (${buf.size} unframed bytes)")
            buf = ByteArray(0)
        }
        return uids
    }
}
