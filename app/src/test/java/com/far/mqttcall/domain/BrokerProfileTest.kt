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

        assertEquals(4, profiles.size)
        assertEquals("broker.emqx.io", profiles[0].host)
        assertEquals(1883, profiles[0].port)
        assertFalse(profiles[0].tls)
        assertEquals("broker.emqx.io", profiles[1].host)
        assertTrue(profiles[1].tls)
        assertEquals("test.mosquitto.org", profiles[2].host)
        assertEquals("test.mosquitto.org", profiles[3].host)
        assertTrue(profiles[3].tls)
    }
}
