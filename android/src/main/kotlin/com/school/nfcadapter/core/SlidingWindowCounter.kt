package com.school.nfcadapter.core

/**
 * Shared implementation behind the brownout heuristic (unexpected detaches per
 * window) and the attach-storm detector (attaches per window). Pure Kotlin.
 * Not thread-safe by design: callers touch it only on the single-threaded
 * module dispatcher.
 */
class SlidingWindowCounter(
    private val windowMs: Long,
    private val threshold: Int
) {
    private val stamps = ArrayDeque<Long>()

    /** Record an event at [nowMs]; returns true when the threshold is reached. */
    fun record(nowMs: Long): Boolean {
        stamps.addLast(nowMs)
        while (stamps.isNotEmpty() && nowMs - stamps.first() > windowMs) {
            stamps.removeFirst()
        }
        return stamps.size >= threshold
    }

    fun reset() = stamps.clear()
}
