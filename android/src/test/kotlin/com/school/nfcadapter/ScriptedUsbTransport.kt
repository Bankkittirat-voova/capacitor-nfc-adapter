package com.school.nfcadapter

import com.school.nfcadapter.transport.UsbTransport

/**
 * The mocking blueprint's scripted transport: plays a byte-stream script back
 * to the REAL CcidReaderHandler, simulating taps, card flicks (truncated
 * frames), timeouts and brownout disconnects — no hardware required.
 */
class ScriptedUsbTransport(script: List<Step>) : UsbTransport {

    sealed interface Step {
        /** Next bulkIn returns these bytes; bSeq (byte 6) echoes the last command
         *  frame's seq, exactly like a spec-compliant reader. */
        data class Reply(val bytes: ByteArray) : Step
        /** Reply delivered verbatim — bSeq NOT echoed. Simulates a stale frame
         *  left over from a previously timed-out command (desync scenario). */
        data class RawReply(val bytes: ByteArray) : Step
        /** Card flick: only the first [cutAt] bytes arrive (seq echoed if included). */
        data class ReplyTruncated(val bytes: ByteArray, val cutAt: Int) : Step
        /** Next bulkIn times out (-1) once. */
        object Timeout : Step
        /** Cable out / brownout: every call fails from now on. */
        object Disconnect : Step
    }

    val sentFrames = mutableListOf<ByteArray>()
    private val queue = ArrayDeque(script)
    private var disconnected = false
    private var lastSentSeq: Byte = 0

    override fun claim() = true
    override fun release() {}

    override fun bulkOut(data: ByteArray, timeoutMs: Int): Int {
        if (disconnected) return -1
        sentFrames += data.copyOf()
        if (data.size > 6) lastSentSeq = data[6]
        return data.size
    }

    override fun bulkIn(buffer: ByteArray, timeoutMs: Int): Int {
        if (disconnected) return -1
        return when (val s = queue.removeFirstOrNull() ?: return -1) {
            is Step.Reply -> {
                s.bytes.copyInto(buffer)
                if (s.bytes.size > 6) buffer[6] = lastSentSeq
                s.bytes.size
            }
            is Step.RawReply -> {
                s.bytes.copyInto(buffer)
                s.bytes.size
            }
            is Step.ReplyTruncated -> {
                s.bytes.copyInto(buffer, 0, 0, s.cutAt)
                if (s.cutAt > 6) buffer[6] = lastSentSeq
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
