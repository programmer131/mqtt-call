package com.far.mqttcall.audio

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test

class JitterBufferTest {
    @Test
    fun `buffer waits for three batches and re-buffers after underrun`() {
        val buffer = JitterBuffer()
        buffer.offer(batch(1))
        buffer.offer(batch(2))
        assertEquals(BufferState.BUFFERING, buffer.state())

        buffer.offer(batch(3))
        assertEquals(BufferState.PLAYING, buffer.state())
        drainAllFrames(buffer)

        assertEquals(BufferState.BUFFERING, buffer.state())
        buffer.offer(batch(4))
        buffer.offer(batch(5))
        assertEquals(BufferState.BUFFERING, buffer.state())
        buffer.offer(batch(6))
        assertEquals(BufferState.PLAYING, buffer.state())
    }

    @Test
    fun `buffer drops oldest batches after reaching ten`() {
        val buffer = JitterBuffer()
        repeat(11) { buffer.offer(batch((it + 1).toLong())) }

        assertNotNull(buffer.state())
        repeat(10 * 2) { assertNotNull(buffer.pollFrame()) }
        assertNull(buffer.pollFrame())
    }

    @Test
    fun `buffer rejects a different session and old sequence`() {
        val buffer = JitterBuffer()
        val session = byteArrayOf(1)
        buffer.offer(AudioBatch(session, 2, listOf(byteArrayOf(2))))
        buffer.offer(AudioBatch(session, 1, listOf(byteArrayOf(1))))
        buffer.offer(AudioBatch(byteArrayOf(2), 3, listOf(byteArrayOf(3))))

        assertEquals(BufferState.BUFFERING, buffer.state())
    }

    private fun drainAllFrames(buffer: JitterBuffer) {
        while (buffer.pollFrame() != null) {
            // Drain until the buffer reports an underrun.
        }
    }

    private fun batch(sequence: Long): AudioBatch = AudioBatch(
        sessionId = byteArrayOf(1),
        sequence = sequence,
        frames = listOf(byteArrayOf(sequence.toByte()), byteArrayOf((sequence + 1).toByte())),
    )
}
