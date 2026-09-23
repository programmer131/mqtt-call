package com.far.mqttcall.audio

import org.junit.Assert.assertArrayEquals
import org.junit.Test

class AudioGainTest {
    @Test
    fun `input gain doubles samples and clips at signed 16 bit bounds`() {
        assertArrayEquals(
            shortArrayOf(2000, -2000, Short.MAX_VALUE, Short.MIN_VALUE),
            applyInputGain(shortArrayOf(1000, -1000, 20000, -20000), 2f),
        )
    }

    @Test
    fun `unity gain preserves values without changing the input`() {
        val input = shortArrayOf(0, 1234, -1234, Short.MAX_VALUE, Short.MIN_VALUE)

        assertArrayEquals(input, applyInputGain(input, 1f))
        assertArrayEquals(shortArrayOf(0, 1234, -1234, Short.MAX_VALUE, Short.MIN_VALUE), input)
    }

    @Test
    fun `input gain does not mutate the source frame`() {
        val input = shortArrayOf(1000, -20000)

        applyInputGain(input, 2f)

        assertArrayEquals(shortArrayOf(1000, -20000), input)
    }
}
