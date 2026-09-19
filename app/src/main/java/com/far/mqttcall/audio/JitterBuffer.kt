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

class JitterBuffer(
    private val startupBatches: Int = 3,
    private val maxBatches: Int = 10,
) {
    private val batches = ArrayDeque<AudioBatch>()
    private val currentFrames = ArrayDeque<ByteArray>()
    private var sessionId: ByteArray? = null
    private var lastSequence = -1L
    private var bufferState = BufferState.BUFFERING

    fun offer(batch: AudioBatch) {
        if (batch.frames.isEmpty()) return
        val activeSession = sessionId
        if (activeSession != null && !activeSession.contentEquals(batch.sessionId)) return
        if (batch.sequence <= lastSequence) return

        sessionId = batch.sessionId.copyOf()
        lastSequence = batch.sequence
        if (batches.size >= maxBatches) batches.removeFirst()
        batches.addLast(batch)
        if (batches.size >= startupBatches) bufferState = BufferState.PLAYING
    }

    fun pollFrame(): ByteArray? {
        if (bufferState != BufferState.PLAYING) return null

        while (currentFrames.isEmpty() && batches.isNotEmpty()) {
            currentFrames.addAll(batches.removeFirst().frames)
        }

        val frame: ByteArray? = if (currentFrames.isEmpty()) {
            null
        } else {
            currentFrames.removeFirst()
        }
        if (frame == null) {
            bufferState = BufferState.BUFFERING
            return null
        }

        if (currentFrames.isEmpty() && batches.isEmpty()) {
            bufferState = BufferState.BUFFERING
        }
        return frame
    }

    fun state(): BufferState {
        if (currentFrames.isEmpty() && batches.isEmpty()) bufferState = BufferState.BUFFERING
        return bufferState
    }
}
