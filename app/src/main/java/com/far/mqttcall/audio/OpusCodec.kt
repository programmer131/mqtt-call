package com.far.mqttcall.audio

interface OpusCodec {
    fun encode(frame: ShortArray): ByteArray

    fun decode(packet: ByteArray): ShortArray

    fun close()
}
