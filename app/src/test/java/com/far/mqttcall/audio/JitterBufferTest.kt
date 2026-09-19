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

    @Test
    fun `finish plays out a short tail and then accepts a new speaker`() {
        val buffer = JitterBuffer()
        val first = byteArrayOf(1)
        buffer.offer(AudioBatch(first, 10, listOf(byteArrayOf(10))))
        assertEquals(BufferState.BUFFERING, buffer.state())

        buffer.finish(first)
        assertEquals(BufferState.PLAYING, buffer.state())
        assertNotNull(buffer.pollFrame())
        assertNull(buffer.pollFrame())

        val second = byteArrayOf(2)
        repeat(3) { buffer.offer(AudioBatch(second, it.toLong(), listOf(byteArrayOf(it.toByte())))) }
        assertEquals(BufferState.PLAYING, buffer.state())
    }

    @Test
    fun `a new speaker is accepted once the previous stream has drained`() {
        val buffer = JitterBuffer()
        repeat(3) { buffer.offer(batch(it + 100L)) }
        drainAllFrames(buffer)

        val next = byteArrayOf(2)
        repeat(3) { buffer.offer(AudioBatch(next, it.toLong(), listOf(byteArrayOf(1)))) }

        assertEquals(BufferState.PLAYING, buffer.state())
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
