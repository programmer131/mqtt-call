package com.far.mqttcall.protocol

import java.nio.ByteBuffer
import java.nio.charset.StandardCharsets

const val MAX_TALKER_NAME_BYTES = 64

data class TalkClaim(
    val expiryMs: Long,
    val userName: String?,
)

fun normalizeTalkerName(value: String): String {
    var result = value.trim()
    while (result.toByteArray(StandardCharsets.UTF_8).size > MAX_TALKER_NAME_BYTES) {
        result = result.dropLast(1)
    }
    return result
}

fun encodeTalkClaim(expiryMs: Long, userName: String): ByteArray {
    val normalized = normalizeTalkerName(userName)
    val name = normalized.toByteArray(StandardCharsets.UTF_8)
    return ByteBuffer.allocate(Long.SIZE_BYTES + name.size)
        .putLong(expiryMs)
        .put(name)
        .array()
}

fun decodeTalkClaim(payload: ByteArray): TalkClaim? = runCatching {
    require(payload.size >= Long.SIZE_BYTES)
    val name = payload.copyOfRange(Long.SIZE_BYTES, payload.size)
        .toString(StandardCharsets.UTF_8)
        .trim()
        .takeIf { it.isNotEmpty() }
    require(normalizeTalkerName(name.orEmpty()) == name.orEmpty())
    TalkClaim(ByteBuffer.wrap(payload).long, name)
}.getOrNull()
