package com.school.nfcadapter

/**
 * Tuning knobs for the module. Pure Kotlin — no Android imports — so unit tests
 * can shrink every timing to near-zero.
 */
data class NfcModuleConfig(
    /** Vibration-bounce grace: a device must stay attached this long before init. */
    val attachDebounceMs: Long = 300,

    /** CCID presence-poll cadence while waiting for a tap. */
    val absentPollDelayMs: Long = 50,
    /** CCID presence-poll cadence while a card dwells on the reader. */
    val presentPollDelayMs: Long = 100,

    /** Bounded blocking-transfer timeouts (requirement: <= 1000 ms). */
    val statusTimeoutMs: Int = 500,
    val transferTimeoutMs: Int = 1000,

    /** Read the UID twice and require both reads to match (RF-corruption guard). */
    val strictReRead: Boolean = true,

    /** Consecutive failed transfers before the session is declared dead. */
    val maxConsecutiveTransferFailures: Int = 3,

    val readBufferSize: Int = 512,

    /** Serial: identical UID frames inside this window are reader double-fires. */
    val uidCooldownMs: Long = 1500,
    /** Serial: silence longer than this mid-frame means a partial read (card flick). */
    val serialInterByteGapMs: Long = 150,

    /** Attach-storm detection (vehicle vibration / loose port). */
    val stormWindowMs: Long = 30_000,
    val stormThreshold: Int = 5,
    val stormCooldownMs: Long = 20_000,

    /** Brownout heuristic: unexpected detaches per window before escalating. */
    val brownoutWindowMs: Long = 60_000,
    val brownoutThreshold: Int = 3,

    /** Brand A routing — leave at -1 until the SDK's VID/PID is confirmed. */
    val brandAVendorId: Int = -1,
    val brandAProductIds: Set<Int> = emptySet(),

    val logger: (String) -> Unit = {}
)
