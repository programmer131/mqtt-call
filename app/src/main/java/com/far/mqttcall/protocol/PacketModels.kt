package com.far.mqttcall.protocol

const val PROTOCOL_VERSION: Byte = 1
const val SESSION_ID_LENGTH = 16
const val NONCE_LENGTH = 12
const val HEADER_LENGTH = 34
const val GCM_TAG_LENGTH = 16

enum class PacketKind(val wireValue: Byte) {
    CLAIM(1),
    RELEASE(2),
    AUDIO(3),
    ;

    companion object {
        fun fromWireValue(value: Byte): PacketKind? = entries.firstOrNull { it.wireValue == value }
    }
}

data class PacketHeader(
    val kind: PacketKind,
    val sessionId: ByteArray,
    val sequence: Long,
    val nonce: ByteArray,
)

data class ParsedPacket(
    val header: PacketHeader,
    val ciphertext: ByteArray,
)

data class DecodedPacket(
    val header: PacketHeader,
    val plaintext: ByteArray,
)
