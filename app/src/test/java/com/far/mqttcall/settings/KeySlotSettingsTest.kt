package com.far.mqttcall.settings

import com.far.mqttcall.domain.AppDefaults
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
}
