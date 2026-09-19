package com.far.mqttcall.protocol

import java.nio.ByteBuffer

object PacketCodec {
    const val HEADER_LENGTH: Int = com.far.mqttcall.protocol.HEADER_LENGTH

    fun encode(header: PacketHeader, ciphertext: ByteArray): ByteArray {
        validateHeader(header)
        require(header.sequence in 0..0xFFFF_FFFFL) { "Sequence must fit in 32 bits" }

        return ByteBuffer.allocate(HEADER_LENGTH + ciphertext.size)
            .put(PROTOCOL_VERSION)
            .put(header.kind.wireValue)
            .put(header.sessionId)
            .putInt(header.sequence.toInt())
            .put(header.nonce)
            .put(ciphertext)
            .array()
    }

    fun decode(packet: ByteArray): ParsedPacket? {
        if (packet.size < HEADER_LENGTH + GCM_TAG_LENGTH) return null

        return runCatching {
            val buffer = ByteBuffer.wrap(packet)
            if (buffer.get() != PROTOCOL_VERSION) return null
            val kind = PacketKind.fromWireValue(buffer.get()) ?: return null
            val sessionId = ByteArray(SESSION_ID_LENGTH).also(buffer::get)
            val sequence = buffer.int.toLong() and 0xFFFF_FFFFL
            val nonce = ByteArray(NONCE_LENGTH).also(buffer::get)
            val ciphertext = ByteArray(buffer.remaining()).also(buffer::get)
            ParsedPacket(PacketHeader(kind, sessionId, sequence, nonce), ciphertext)
        }.getOrNull()
    }

    fun headerBytes(header: PacketHeader): ByteArray = encode(header, ByteArray(0))
        .copyOf(HEADER_LENGTH)

    private fun validateHeader(header: PacketHeader) {
        require(header.sessionId.size == SESSION_ID_LENGTH) { "Session ID must be 16 bytes" }
        require(header.nonce.size == NONCE_LENGTH) { "Nonce must be 12 bytes" }
        require(header.sequence >= 0) { "Sequence cannot be negative" }
    }
}
