package com.far.mqttcall.crypto

import com.far.mqttcall.protocol.PacketHeader
import com.far.mqttcall.protocol.PacketKind
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertNotEquals
import org.junit.Test

class CryptoEngineTest {
    private val jvmCrypto = JvmCryptoEngine()

    @Test
    fun `same channel and key derive interoperable sessions`() {
        val first = jvmCrypto.prepare("3344", "shared".toCharArray())
        val second = jvmCrypto.prepare("3344", "shared".toCharArray())
        val packet = first.encrypt(testHeader(PacketKind.AUDIO, 1), byteArrayOf(1, 2, 3))

        assertArrayEquals(byteArrayOf(1, 2, 3), second.decrypt(packet)!!.plaintext)
    }

    @Test
    fun `tampering and wrong key fail authentication`() {
        val packet = jvmCrypto.prepare("3344", "one".toCharArray())
            .encrypt(testHeader(PacketKind.AUDIO, 1), byteArrayOf(1))
        packet[packet.lastIndex] = (packet[packet.lastIndex].toInt() xor 0x01).toByte()

        assertNull(jvmCrypto.prepare("3344", "one".toCharArray()).decrypt(packet))
        assertNull(jvmCrypto.prepare("3344", "two".toCharArray()).decrypt(packet))
    }

    @Test
    fun `each encrypted packet receives a fresh nonce`() {
        val session = jvmCrypto.prepare("3344", "shared".toCharArray())
        val first = session.encrypt(testHeader(PacketKind.AUDIO, 1), byteArrayOf(1))
        val second = session.encrypt(testHeader(PacketKind.AUDIO, 2), byteArrayOf(1))

        assertNotEquals(
            first.copyOfRange(22, 34).toList(),
            second.copyOfRange(22, 34).toList(),
        )
    }

    @Test
    fun `duplicate sequence is rejected after the first successful decrypt`() {
        val sender = jvmCrypto.prepare("3344", "shared".toCharArray())
        val receiver = jvmCrypto.prepare("3344", "shared".toCharArray())
        val packet = sender.encrypt(testHeader(PacketKind.AUDIO, 7), byteArrayOf(4, 5))

        assertArrayEquals(byteArrayOf(4, 5), receiver.decrypt(packet)!!.plaintext)
        assertNull(receiver.decrypt(packet))
    }

    private fun testHeader(kind: PacketKind, sequence: Long): PacketHeader = PacketHeader(
        kind = kind,
        sessionId = ByteArray(16) { index -> (index + 1).toByte() },
        sequence = sequence,
        nonce = ByteArray(12),
    )
}
