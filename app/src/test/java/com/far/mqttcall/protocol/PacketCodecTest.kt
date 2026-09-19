package com.far.mqttcall.protocol

import java.nio.ByteBuffer
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class PacketCodecTest {
    @Test
    fun `encodes and decodes the fixed header and ciphertext`() {
        val header = PacketHeader(
            kind = PacketKind.AUDIO,
            sessionId = ByteArray(16) { it.toByte() },
            sequence = 42,
            nonce = ByteArray(12) { (it + 1).toByte() },
        )
        val ciphertext = ByteArray(16) { (it + 1).toByte() }

        val parsed = PacketCodec.decode(PacketCodec.encode(header, ciphertext))

        requireNotNull(parsed)
        assertEquals(header.kind, parsed.header.kind)
        assertArrayEquals(header.sessionId, parsed.header.sessionId)
        assertEquals(header.sequence, parsed.header.sequence)
        assertArrayEquals(header.nonce, parsed.header.nonce)
        assertArrayEquals(ciphertext, parsed.ciphertext)
    }

    @Test
    fun `rejects unsupported version and truncated packets`() {
        val header = PacketHeader(PacketKind.CLAIM, ByteArray(16), 0, ByteArray(12))
        val packet = PacketCodec.encode(header, ByteArray(16))
        packet[0] = 99

        assertNull(PacketCodec.decode(packet))
        assertNull(PacketCodec.decode(ByteArray(PacketCodec.HEADER_LENGTH - 1)))
    }

    @Test
    fun `encodes integers in big endian order`() {
        val header = PacketHeader(PacketKind.RELEASE, ByteArray(16), 0x01020304, ByteArray(12))

        val encoded = PacketCodec.encode(header, ByteArray(16))

        assertEquals(0x01.toByte(), encoded[18])
        assertEquals(0x02.toByte(), encoded[19])
        assertEquals(0x03.toByte(), encoded[20])
        assertEquals(0x04.toByte(), encoded[21])
        assertEquals(0, ByteBuffer.wrap(encoded, 18, 4).int.compareTo(0x01020304))
    }
}
