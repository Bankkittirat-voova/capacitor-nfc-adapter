package com.school.nfcadapter

import com.school.nfcadapter.transport.UsbTransport

/**
 * The mocking blueprint's scripted transport: plays a byte-stream script back
 * to the REAL CcidReaderHandler, simulating taps, card flicks (truncated
 * frames), timeouts and brownout disconnects — no hardware required.
 */
class ScriptedUsbTransport(script: List<Step>) : UsbTransport {

    sealed interface Step {
        /** Next bulkIn returns these bytes. */
        data class Reply(val bytes: ByteArray) : Step
        /** Card flick: only the first [cutAt] bytes arrive. */
        data class ReplyTruncated(val bytes: ByteArray, val cutAt: Int) : Step
        /** Next bulkIn times out (-1) once. */
        object Timeout : Step
        /** Cable out / brownout: every call fails from now on. */
        object Disconnect : Step
    }

    val sentFrames = mutableListOf<ByteArray>()
    private val queue = ArrayDeque(script)
    private var disconnected = false

    override fun claim() = true
    override fun release() {}

    override fun bulkOut(data: ByteArray, timeoutMs: Int): Int {
        if (disconnected) return -1
        sentFrames += data.copyOf()
        return data.size
    }

    override fun bulkIn(buffer: ByteArray, timeoutMs: Int): Int {
        if (disconnected) return -1
        return when (val s = queue.removeFirstOrNull() ?: return -1) {
            is Step.Reply -> {
                s.bytes.copyInto(buffer)
                s.bytes.size
            }
            is Step.ReplyTruncated -> {
                s.bytes.copyInto(buffer, 0, 0, s.cutAt)
                s.cutAt
            }
            Step.Timeout -> -1
            Step.Disconnect -> {
                disconnected = true
                -1
            }
        }
    }
}
