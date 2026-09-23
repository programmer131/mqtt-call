package com.far.mqttcall.domain

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class BrokerProfileTest {
    @Test
    fun `defaults include shared demo broker and values`() {
        assertEquals("broker.emqx.io", AppDefaults.defaultBroker.host)
        assertEquals("3344", AppDefaults.defaultChannel)
        assertEquals("PTT-DEMO-3344", AppDefaults.defaultKey)
    }

    @Test
    fun `default profiles include EMQX and Mosquitto TCP and TLS`() {
        val profiles = defaultBrokerProfiles()

        assertEquals(5, profiles.size)
        assertEquals("broker.emqx.io", profiles[0].host)
        assertEquals(1883, profiles[0].port)
        assertFalse(profiles[0].tls)
        assertEquals(4, profiles[0].audioPacketIntervalUnits)
        assertEquals("broker.emqx.io", profiles[1].host)
        assertTrue(profiles[1].tls)
        assertEquals("test.mosquitto.org", profiles[2].host)
        assertEquals("test.mosquitto.org", profiles[3].host)
        assertTrue(profiles[3].tls)
        assertEquals("DietPi LAN", profiles[4].name)
        assertEquals("192.168.1.107", profiles[4].host)
        assertEquals(1886, profiles[4].port)
        assertEquals(1, profiles[4].audioPacketIntervalUnits)
    }

    @Test
    fun `LAN hosts use low latency interval while public hosts use larger packets`() {
        assertEquals(1, defaultAudioPacketIntervalUnits("192.168.1.107"))
        assertEquals(1, defaultAudioPacketIntervalUnits("localhost"))
        assertEquals(4, defaultAudioPacketIntervalUnits("broker.emqx.io"))
    }
}
