package com.school.nfcadapter.handler

/**
 * STRATEGY: one implementation per hardware protocol family
 * (CCID, serial bridge, Brand A SDK hook).
 *
 * Handlers emit the RAW UID byte array exactly as read from the card
 * (MSB-first, per ISO 14443 anticollision). Normalization to the
 * `uid_dec_reversed` contract string happens once, centrally, in the
 * ConnectionManager — handlers never format output.
 */
interface NfcReaderHandler {

    /** Blocking init on the I/O dispatcher: claim interface, power on, configure. */
    suspend fun initialize(): Boolean

    /**
     * Long-running suspend function; emits validated raw UID bytes.
     * Must observe cancellation between transfers (bounded timeouts <= 1000 ms
     * guarantee the loop re-checks isActive at least once per second).
     * Throws TransferFailureException when the link is dead.
     */
    suspend fun runPollingLoop(emit: suspend (rawUid: ByteArray) -> Unit)

    /** Idempotent; releases claimed interfaces/ports. Safe to call repeatedly. */
    fun close()
}
