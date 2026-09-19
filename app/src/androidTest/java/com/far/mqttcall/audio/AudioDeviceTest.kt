package com.far.mqttcall.audio

import android.media.AudioFormat
import android.media.AudioRecord
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class AudioDeviceTest {
    @Test
    fun opus_round_trip_works_on_device() {
        val codec = KopusCodec()
        try {
            val pcm = ShortArray(FRAME_SAMPLES) { index ->
                ((index * 97) % Short.MAX_VALUE).toShort()
            }
            val encoded = codec.encode(pcm)
            val decoded = codec.decode(encoded)

            assertTrue(encoded.isNotEmpty())
            assertEquals(FRAME_SAMPLES, decoded.size)
        } finally {
            codec.close()
        }
    }

    @Test
    fun microphone_reports_a_supported_buffer_size() {
        val bufferSize = AudioRecord.getMinBufferSize(
            SAMPLE_RATE,
            AudioFormat.CHANNEL_IN_MONO,
            AudioFormat.ENCODING_PCM_16BIT,
        )

        assertTrue("AudioRecord buffer size: $bufferSize", bufferSize > 0)
    }

    private companion object {
        const val SAMPLE_RATE = 16_000
        const val FRAME_SAMPLES = 320
    }
}
