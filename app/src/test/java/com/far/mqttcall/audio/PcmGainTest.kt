package com.far.mqttcall.audio

import org.junit.Assert.assertArrayEquals
import org.junit.Test

class PcmGainTest {
    @Test
    fun `quiet microphone samples are boosted before encoding`() {
        assertArrayEquals(
            shortArrayOf(-8_000, -4_000, 0, 4_000, 8_000),
            boostPcm(shortArrayOf(-1_000, -500, 0, 500, 1_000)),
        )
    }

    @Test
    fun `microphone gain clips instead of wrapping samples`() {
        assertArrayEquals(
            shortArrayOf(Short.MAX_VALUE, Short.MIN_VALUE),
            boostPcm(shortArrayOf(10_000, -10_000)),
        )
    }
}
