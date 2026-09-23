package com.far.mqttcall.settings

import com.far.mqttcall.domain.AppDefaults
import com.far.mqttcall.domain.BrokerProfile
import org.junit.Assert.assertEquals
import org.junit.Test

class KeySlotSettingsTest {
    @Test
    fun `three key slots and active selection survive persistence`() {
        val store = InMemoryCallSettingsStore()
        val settings = SavedCallSettings(
            broker = AppDefaults.defaultBroker,
            channel = AppDefaults.defaultChannel,
            keySlots = listOf("doorbell", "spare-one", "spare-two"),
            activeKeyIndex = 1,
        )

        store.save(settings)

        assertEquals(settings, store.load())
        assertEquals("spare-one", store.load().activeKey)
    }

    @Test
    fun `fresh settings keep the shared default active`() {
        val settings = InMemoryCallSettingsStore().load()

        assertEquals(0, settings.activeKeyIndex)
        assertEquals(AppDefaults.defaultKey, settings.activeKey)
        assertEquals(3, settings.keySlots.size)
    }

    @Test
    fun `saved broker profiles survive persistence`() {
        val store = InMemoryCallSettingsStore()
        val custom = BrokerProfile("Doorbell", "192.168.1.107", 1883, false)
        val settings = SavedCallSettings(
            broker = custom,
            channel = AppDefaults.defaultChannel,
            keySlots = AppDefaults.defaultKeySlots,
            savedBrokers = listOf(custom),
        )

        store.save(settings)

        assertEquals(listOf(custom), store.load().savedBrokers)
        assertEquals(custom, store.load().broker)
    }
}
