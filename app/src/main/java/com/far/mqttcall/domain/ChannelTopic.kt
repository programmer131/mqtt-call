package com.far.mqttcall.domain

private val CHANNEL_PATTERN = Regex("[0-9]{1,16}")

fun channelTopic(channel: String): Result<String> {
    val normalized = channel.trim()
    return if (CHANNEL_PATTERN.matches(normalized)) {
        Result.success("call/channel/$normalized")
    } else {
        Result.failure(
            IllegalArgumentException("Channel must contain 1 to 16 ASCII digits"),
        )
    }
}
