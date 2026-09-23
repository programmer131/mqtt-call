package com.far.mqttcall

import com.far.mqttcall.settings.InMemoryCallSettingsStore
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class MicrophonePermissionCoordinatorTest {
    @Test
    fun `creating coordinator does not prompt or change stored permission decision`() {
        val settings = InMemoryCallSettingsStore()

        MicrophonePermissionCoordinator(settings)

        assertFalse(settings.load().microphonePermissionPrompted)
    }

    @Test
    fun `first ungranted PTT attempt records prompt before requesting and later attempts open settings`() {
        val settings = InMemoryCallSettingsStore()
        val coordinator = MicrophonePermissionCoordinator(settings)

        assertEquals(MicrophoneDecision.RequestPermission, coordinator.onPttAttempt(granted = false))
        assertTrue(settings.load().microphonePermissionPrompted)
        assertEquals(MicrophoneDecision.OpenSettings, coordinator.onPttAttempt(granted = false))
        assertEquals(MicrophoneDecision.OpenSettings, MicrophonePermissionCoordinator(settings).onPttAttempt(granted = false))
    }

    @Test
    fun `granted permission presses PTT without recording a prompt`() {
        val settings = InMemoryCallSettingsStore()
        val coordinator = MicrophonePermissionCoordinator(settings)

        assertEquals(MicrophoneDecision.PressTalk, coordinator.onPttAttempt(granted = true))
        assertFalse(settings.load().microphonePermissionPrompted)
    }

    @Test
    fun `grant from App Settings bypasses a previous denial`() {
        val settings = InMemoryCallSettingsStore()
        settings.markMicrophonePermissionPrompted()

        assertEquals(
            MicrophoneDecision.PressTalk,
            MicrophonePermissionCoordinator(settings).onPttAttempt(granted = true),
        )
    }
}
