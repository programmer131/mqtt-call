package com.far.mqttcall.settings

import com.far.mqttcall.domain.AppDefaults
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class CallSettingsStoreTest {
    @Test
    fun `microphone prompt decision and keep connected survive a settings round trip`() {
        val store = InMemoryCallSettingsStore()
        store.markMicrophonePermissionPrompted()
        store.setKeepConnected(true)

        assertTrue(store.load().microphonePermissionPrompted)
        assertTrue(store.load().keepConnected)
    }

    @Test
    fun `service and microphone prompt flags default to false`() {
        val settings = InMemoryCallSettingsStore().load()

        assertFalse(settings.keepConnected)
        assertFalse(settings.microphonePermissionPrompted)
    }

    @Test
    fun `saving call configuration preserves flags and broker data`() {
        val store = InMemoryCallSettingsStore()
        store.markMicrophonePermissionPrompted()
        store.setKeepConnected(true)
        val broker = AppDefaults.defaultBroker.copy(host = "custom.example")

        store.save(SavedCallSettings(broker, "7788", "another-key"))

        assertEquals(broker, store.load().broker)
        assertEquals("7788", store.load().channel)
        assertEquals("another-key", store.load().key)
        assertTrue(store.load().keepConnected)
        assertTrue(store.load().microphonePermissionPrompted)
    }

    @Test
    fun `disconnect clears keep connected without clearing microphone prompt decision`() {
        val store = InMemoryCallSettingsStore()
        store.markMicrophonePermissionPrompted()
        store.setKeepConnected(true)

        store.setKeepConnected(false)

        assertFalse(store.load().keepConnected)
        assertTrue(store.load().microphonePermissionPrompted)
    }
}
