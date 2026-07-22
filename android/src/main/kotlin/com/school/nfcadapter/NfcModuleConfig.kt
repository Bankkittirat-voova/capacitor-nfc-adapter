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

    /** PN532-over-UART command-driven readers: near-universal HSU baud. */
    val pn532BaudRate: Int = 115200,
    /** Settle time after asserting DTR: an ATmega-CDC bridge auto-resets and must
     *  finish its bootloader before the handshake, or the first command races it. */
    val pn532BootDelayMs: Long = 1800,

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

    /**
     * DEBUG / LOCAL ONLY. When true, full card identifiers (UID/ATR and the raw
     * frames that carry them) are written to the log. Ships false: normal logs
     * show only byte lengths, so a card's identity never lands in logcat.
     */
    val logSensitiveValues: Boolean = false,

    val logger: (String) -> Unit = {}
)
