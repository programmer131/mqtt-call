package com.far.mqttcall

import com.far.mqttcall.settings.CallSettingsStore

internal enum class MicrophoneDecision {
    PressTalk,
    RequestPermission,
    OpenSettings,
}

/** A stored prompt decision never stands in for Android's current permission result. */
internal class MicrophonePermissionCoordinator(private val settings: CallSettingsStore) {
    fun onPttAttempt(granted: Boolean): MicrophoneDecision {
        if (granted) return MicrophoneDecision.PressTalk
        if (settings.load().microphonePermissionPrompted) return MicrophoneDecision.OpenSettings
        settings.markMicrophonePermissionPrompted()
        return MicrophoneDecision.RequestPermission
    }
}
