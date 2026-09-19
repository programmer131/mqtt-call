package com.far.mqttcall.audio

import java.util.ArrayDeque

data class AudioBatch(
    val sessionId: ByteArray,
    val sequence: Long,
    val frames: List<ByteArray>,
)

enum class BufferState {
    BUFFERING,
    PLAYING,
}

/**
 * Accessed from the MQTT event collector and the playback loop concurrently, so every public
 * method is synchronized.
 */
class JitterBuffer(
    private val startupBatches: Int = 3,
    private val maxBatches: Int = 10,
) {
    private val batches = ArrayDeque<AudioBatch>()
    private val currentFrames = ArrayDeque<ByteArray>()
    private var sessionId: ByteArray? = null
    private var lastSequence = -1L
    private var bufferState = BufferState.BUFFERING
    private var draining = false

    @Synchronized
    fun offer(batch: AudioBatch) {
        if (batch.frames.isEmpty()) return
        val activeSession = sessionId
        if (activeSession != null && !activeSession.contentEquals(batch.sessionId)) {
            // A new speaker may take over once the previous one has fully played out.
            if (!isEmpty()) return
            resetSession()
        }
        if (batch.sequence <= lastSequence) return

        sessionId = batch.sessionId.copyOf()
        lastSequence = batch.sequence
        draining = false
        if (batches.size >= maxBatches) batches.removeFirst()
        batches.addLast(batch)
        if (batches.size >= startupBatches) bufferState = BufferState.PLAYING
    }

    /** The speaker released the floor: play out whatever is left, then accept any session. */
    @Synchronized
    fun finish(sessionId: ByteArray) {
        if (this.sessionId?.contentEquals(sessionId) != true) return
        if (isEmpty()) {
            resetSession()
        } else {
            draining = true
            bufferState = BufferState.PLAYING
        }
    }

    @Synchronized
    fun pollFrame(): ByteArray? {
        if (bufferState != BufferState.PLAYING) return null

        while (currentFrames.isEmpty() && batches.isNotEmpty()) {
            currentFrames.addAll(batches.removeFirst().frames)
        }

        val frame: ByteArray? = currentFrames.pollFirst()
        if (isEmpty()) {
            bufferState = BufferState.BUFFERING
            if (draining) resetSession()
        }
        return frame
    }

    @Synchronized
    fun state(): BufferState {
        if (isEmpty() && !draining) bufferState = BufferState.BUFFERING
        return bufferState
    }

    private fun isEmpty(): Boolean = currentFrames.isEmpty() && batches.isEmpty()

    private fun resetSession() {
        sessionId = null
        lastSequence = -1L
        draining = false
        bufferState = BufferState.BUFFERING
    }
}
