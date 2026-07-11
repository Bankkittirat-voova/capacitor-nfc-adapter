package com.school.nfcadapter.handler.serial

import com.school.nfcadapter.NfcModuleConfig
import com.school.nfcadapter.transport.TransferFailureException
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive

/**
 * Seam over a serial port so the read loop is JVM-testable.
 * Real implementation (AndroidSerialLink) wraps usb-serial-for-android.
 * read(): bytes read, 0 on timeout with no data, < 0 on link failure.
 */
interface SerialLink {
    fun read(dest: ByteArray, timeoutMs: Int): Int
    fun close()
}

/**
 * The serial engine core: read chunks -> frame assembly -> validated raw UID
 * bytes -> emit. A per-UID cooldown absorbs reader double-fires on marginal
 * RF coupling (the gateway's cross-channel debounce is a second net upstream).
 */
class SerialReadLoop(
    private val link: SerialLink,
    private val assembler: SerialFrameAssembler,
    private val config: NfcModuleConfig = NfcModuleConfig(),
    private val clock: () -> Long = { System.nanoTime() / 1_000_000 }
) {
    suspend fun run(emit: suspend (ByteArray) -> Unit) {
        val buf = ByteArray(config.readBufferSize)
        var consecutiveFailures = 0
        var lastUid: ByteArray? = null
        var lastUidAtMs = 0L

        while (currentCoroutineContext().isActive) {
            val n = link.read(buf, config.transferTimeoutMs)
            when {
                n < 0 -> {
                    consecutiveFailures++
                    if (consecutiveFailures >= config.maxConsecutiveTransferFailures) {
                        throw TransferFailureException("serial: $consecutiveFailures consecutive read failures")
                    }
                    delay(config.absentPollDelayMs)
                }
                n == 0 -> consecutiveFailures = 0        // timeout, nothing tapped — normal standby
                else -> {
                    consecutiveFailures = 0
                    for (uid in assembler.feed(buf.copyOfRange(0, n), clock())) {
                        val now = clock()
                        val doubleFire = lastUid?.contentEquals(uid) == true &&
                            now - lastUidAtMs < config.uidCooldownMs
                        if (doubleFire) {
                            config.logger("serial: double-fire suppressed")
                            continue
                        }
                        lastUid = uid
                        lastUidAtMs = now
                        emit(uid)
                    }
                }
            }
        }
    }
}
