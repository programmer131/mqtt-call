# Background Audio Foreground Service Design

## Goal

Keep an active MQTT call connected and play received audio while the app is in the background or the screen is off, while preserving PTT-only microphone capture and requesting microphone permission at most once.

## Requirements

- Opening the call screen starts and binds a persistent foreground service that owns the call state; the service remains disconnected until the user presses Connect.
- Received MQTT audio continues to play while `MainActivity` is stopped or unbound.
- Disconnecting, the notification Stop action, or an explicit user stop tears down MQTT, playback, and capture.
- A small Exit button in the top-right app bar disconnects, stops the foreground service, unbinds the Activity, and exits the app task.
- Android force-stop remains a terminal action; the service must not restart after force-stop.
- The microphone is never opened merely because the service starts or the app launches.
- The microphone permission prompt is launched only from the first PTT attempt that lacks permission. A denial is recorded so the app does not repeatedly prompt on later launches; later attempts show an actionable permission error instead.
- If permission was already granted, the app must not show the prompt again.
- PTT capture remains active only while the user holds PTT and is stopped when released, disconnected, or the service is destroyed.
- Captured microphone PCM receives an adjustable software gain before encoding; the initial gain is `2.0x`, with signed 16-bit clipping protection. Existing hardware Automatic Gain Control remains enabled where available.
- Playback volume receives an adjustable internal `LoudnessEnhancer` target gain; raise the current default from `1500 mB` (+15 dB) to `3000 mB` (+30 dB), while retaining media-stream hardware volume control.
- The service must support the project minimum API level 24 and current target SDK 35.

## Architecture

`CallService` becomes the owner of `CallViewModel`, `PahoMqttTransport`, `AndroidCryptoEngine`, `AndroidAudioEngine`, and `AndroidCallSettingsStore`. `MainActivity` starts and binds to it on entry; the service runs in the foreground with a persistent notification even while disconnected, and exposes a local binder containing the ViewModel's `StateFlow` and `dispatch` entry point. Connect establishes the MQTT session; explicit Disconnect cleans up and stops the service.

`MainActivity` binds to the service while visible and renders the bound state. It may unbind when stopped without disconnecting; the service continues independently. The service starts in media-playback foreground mode after Connect. When PTT capture begins, it upgrades the active foreground-service type to include microphone; it returns to media-playback mode after release.

The service uses a persisted `keepConnected` flag. Connect sets it, Disconnect and Stop clear it. A system process reclaim may restart the service and reconnect when this flag is set. Android force-stop remains outside the app's restart control.

The existing `CallViewModel` remains the state machine and gains service lifecycle hooks rather than duplicating MQTT/audio logic in the service. The service invokes cleanup from `onDestroy`, including immediate capture cancellation, transport disconnect, and playback shutdown.

## Permission flow

`MainActivity` stops requesting microphone permission from `LaunchedEffect`. It reads the current platform permission and passes PTT permission requests through a small permission coordinator. The coordinator stores `microphonePermissionPrompted` in the settings store:

1. PTT pressed with permission granted: dispatch `PressTalk` immediately.
2. PTT pressed without permission and not previously prompted: mark prompted, launch the Android permission request, and wait for the next PTT press after grant.
3. PTT pressed without permission after a prior denial: do not launch another system prompt; show an error directing the user to App Settings.

The service checks the actual platform permission before starting capture; the persisted flag never grants permission by itself.

## Notification behavior

- Channel: `MQTT Call`, low importance, ongoing while connected.
- Content: broker name/host, channel number, and Connected/Connecting/Error state.
- Action: Disconnect/Stop, routed to the service.
- The Activity app bar Exit action uses the same cleanup path, then calls `finishAndRemoveTask()`.
- On Android 13+, notification permission is requested separately when needed for visible notification presentation; it is not conflated with microphone permission.

## Testing

- Service tests verify Connect starts the service/session, Activity unbinding does not stop it, Disconnect/Stop cleans up, and service destruction stops capture/playback.
- Permission tests verify no request on launch, one request on the first denied PTT attempt, no repeated request after denial, and no request when already granted.
- Existing ViewModel tests continue to verify PTT-only capture and release cleanup.
- Audio tests verify software gain, positive/negative clipping, and unchanged samples when gain is `1.0x`.
- Audio playback verification checks that the configured LoudnessEnhancer target gain is applied when the output track is created.
- Android instrumentation verifies the foreground notification and background audio path on API 24+ where device policy permits test APK installation.

## Non-goals

- Restarting after an explicit Android force-stop.
- Capturing microphone audio without an active PTT hold.
- Adding a new in-app screen for service configuration.
