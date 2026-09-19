package com.far.mqttcall.audio

import eu.buney.kopus.OPUS_SET_BITRATE_REQUEST
import eu.buney.kopus.OpusApplication
import eu.buney.kopus.OpusDecoder
import eu.buney.kopus.OpusEncoder

class KopusCodec : OpusCodec {
    private val encoder = OpusEncoder(SAMPLE_RATE, CHANNELS, OpusApplication.Voip)
    private val decoder = OpusDecoder(SAMPLE_RATE, CHANNELS)

    init {
        check(encoder.ctl(OPUS_SET_BITRATE_REQUEST, BITRATE) >= 0) {
            "Unable to configure Opus bitrate"
        }
    }

    override fun encode(frame: ShortArray): ByteArray {
        require(frame.size == FRAME_SAMPLES) { "Opus frame must contain 320 samples" }
        val output = ByteArray(MAX_PACKET_BYTES)
        val length = encoder.encode(
            frame,
            0,
            FRAME_SAMPLES,
            output,
            0,
            output.size,
        )
        check(length > 0) { "Opus encoder returned $length" }
        return output.copyOf(length)
    }

    override fun decode(packet: ByteArray): ShortArray {
        require(packet.isNotEmpty()) { "Opus packet must not be empty" }
        val output = ShortArray(FRAME_SAMPLES)
        val samples = decoder.decode(
            packet,
            0,
            packet.size,
            output,
            0,
            FRAME_SAMPLES,
            false,
        )
        check(samples > 0) { "Opus decoder returned $samples" }
        return output.copyOf(samples)
    }

    override fun close() {
        encoder.close()
        decoder.close()
    }

    private companion object {
        const val SAMPLE_RATE = 16_000
        const val CHANNELS = 1
        const val FRAME_SAMPLES = 320
        const val BITRATE = 24_000
        const val MAX_PACKET_BYTES = 1_275
    }
}
