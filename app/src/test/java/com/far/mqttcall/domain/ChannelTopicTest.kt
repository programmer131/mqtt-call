package com.far.mqttcall.domain

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ChannelTopicTest {
    @Test
    fun `builds exact channel topic`() {
        assertEquals("call/channel/3344", channelTopic(" 3344 ").getOrThrow())
    }

    @Test
    fun `rejects non numeric or out of range channels`() {
        assertTrue(channelTopic("abc").isFailure)
        assertTrue(channelTopic("1234567890123").isFailure)
    }
}
