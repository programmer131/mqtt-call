package com.far.mqttcall.audio

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class AudioBatcherTest {
    @Test
    fun `ten 20 millisecond frames produce one transport batch`() {
        val batcher = AudioBatcher(FakeOpusCodec())

        repeat(9) {
            assertNull(batcher.addFrame(ShortArray(320)))
        }

        assertEquals(10, batcher.addFrame(ShortArray(320))!!.size)
    }

    @Test
    fun `one 100 millisecond interval batches five frames`() {
        val batcher = AudioBatcher(FakeOpusCodec(), framesPerBatch = 5)

        repeat(4) {
            assertNull(batcher.addFrame(ShortArray(320)))
        }

        assertEquals(5, batcher.addFrame(ShortArray(320))!!.size)
    }

    private class FakeOpusCodec : OpusCodec {
        override fun encode(frame: ShortArray): ByteArray = byteArrayOf(frame.size.toByte())
        override fun decode(packet: ByteArray): ShortArray = ShortArray(packet.first().toInt())
        override fun close() = Unit
    }
}
