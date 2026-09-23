package com.far.mqttcall.audio

import android.media.AudioFormat
import android.media.AudioRecord
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.far.mqttcall.crypto.AndroidCryptoEngine
import com.far.mqttcall.domain.AppDefaults
import com.far.mqttcall.protocol.PacketHeader
import com.far.mqttcall.protocol.PacketKind
import com.far.mqttcall.settings.AndroidCallSettingsStore
import com.far.mqttcall.settings.SavedCallSettings
import org.junit.Assert.assertEquals
import org.junit.Assert.assertArrayEquals
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

    @Test
    fun android_keystore_crypto_round_trip_works_on_device() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val engine = AndroidCryptoEngine(context)
        val session = engine.prepare("3344", AppDefaults.defaultKey.toCharArray())
        val packet = session.encrypt(
            PacketHeader(
                kind = PacketKind.CLAIM,
                sessionId = ByteArray(16) { 7 },
                sequence = 1,
                nonce = ByteArray(12),
            ),
            byteArrayOf(1, 2, 3),
        )

        assertArrayEquals(byteArrayOf(1, 2, 3), session.decrypt(packet)?.plaintext)
    }

    @Test
    fun protected_settings_round_trip_works_on_device() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val store = AndroidCallSettingsStore(context)
        val original = store.load()
        val custom = SavedCallSettings(
            original.broker,
            "9988",
            listOf("device-test-key", "spare-key-one", "spare-key-two"),
            activeKeyIndex = 1,
        )
        try {
            store.save(custom)
            val loaded = store.load()
            assertEquals(custom.channel, loaded.channel)
            assertEquals(custom.keySlots, loaded.keySlots)
            assertEquals(custom.activeKeyIndex, loaded.activeKeyIndex)
            assertEquals("spare-key-one", loaded.activeKey)
        } finally {
            store.save(original)
        }
    }

    private companion object {
        const val SAMPLE_RATE = 16_000
        const val FRAME_SAMPLES = 320
    }
}
