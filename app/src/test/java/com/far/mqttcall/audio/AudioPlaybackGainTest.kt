package com.far.mqttcall.audio

import org.junit.Assert.assertEquals
import org.junit.Test

class AudioPlaybackGainTest {
    @Test
    fun `playback loudness target is 3000 millibels`() {
        assertEquals(3_000, PLAYBACK_GAIN_MB)
    }
}
