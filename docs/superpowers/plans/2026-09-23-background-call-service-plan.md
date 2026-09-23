# Background Call Foreground Service Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Move the active MQTT call into a foreground service so received audio continues in the background, while making microphone permission one-time, adding 2× input gain, and applying 30 dB playback boost.

**Architecture:** `CallService` owns `CallViewModel`, MQTT transport, crypto, audio, and settings. `MainActivity` starts/binds to the service and renders its bound `StateFlow`; unbinding does not disconnect. The service remains disconnected until Connect and stops after explicit Disconnect/notification Stop cleanup.

**Tech Stack:** Kotlin, Android foreground service, AndroidX lifecycle/Compose, Paho MQTT, `AudioRecord`, `AudioTrack`, `LoudnessEnhancer`, existing JUnit4/coroutines tests.

**Spec:** `docs/superpowers/specs/2026-09-23-foreground-service-background-audio-design.md`

## Global Constraints

- Support API 24 and target SDK 35.
- Never open the microphone on app launch, service start, or permission grant alone.
- Capture starts only during an active PTT hold and stops on release, disconnect, or service destruction.
- Request `RECORD_AUDIO` only on the first PTT attempt without permission; persist the prompted decision and direct later denials to App Settings.
- Preserve `audioPacketIntervalUnits`: public brokers `4` (400ms), `lan2` `1` (100ms).
- Apply microphone software gain `2.0x` with signed 16-bit clipping.
- Apply playback `LoudnessEnhancer` target gain `3000 mB` (+30 dB).
- Do not attempt to restart after Android force-stop.

## Review Focus

- Activity unbind/background: service and MQTT playback continue without a second ViewModel.
- Explicit Disconnect/notification Stop: transport, playback, capture, and service all stop.
- Permission already granted: no system prompt or microphone open on launch.
- Permission denied once: no repeated system prompt; PTT reports an App Settings action.
- API 24 audio: software gain, `AudioTrack`, notification, and foreground service APIs remain compatible.

### Task 1: Persist service and microphone-permission state

**Files:**
- Modify: `app/src/main/java/com/far/mqttcall/settings/CallSettingsStore.kt`
- Test: `app/src/test/java/com/far/mqttcall/settings/CallSettingsStoreTest.kt` (create)

**Interfaces:**
- Add `keepConnected: Boolean` and `microphonePermissionPrompted: Boolean` to the settings contract.
- Add `CallSettingsStore.markMicrophonePermissionPrompted()` and `setKeepConnected(Boolean)` or equivalent atomic settings methods.
- Preserve existing broker/channel/key and `audioPacketIntervalUnits` persistence.

- [ ] **Step 1: Write failing persistence tests**

```kotlin
@Test
fun `microphone prompt decision and keep connected survive a settings round trip`() {
    val store = InMemoryCallSettingsStore()
    store.setMicrophonePermissionPrompted(true)
    store.setKeepConnected(true)

    assertTrue(store.load().microphonePermissionPrompted)
    assertTrue(store.load().keepConnected)
}
```

- [ ] **Step 2: Run the focused test and verify it fails**

Run: `JAVA_HOME=/usr/lib/jvm/java-21-openjdk-amd64 ANDROID_HOME=/home/farrukhhussain/Android/Sdk ./gradlew :app:testDebugUnitTest --tests '*CallSettingsStoreTest*'`

Expected: compile/test failure because the new settings fields and methods are absent.

- [ ] **Step 3: Implement the in-memory and Android SharedPreferences state**

Use dedicated keys `keep_connected` and `microphone_permission_prompted`; default both to `false`. Keep existing saved broker interval data intact.

- [ ] **Step 4: Run focused and full JVM tests**

Run the focused command, then `./gradlew :app:testDebugUnitTest` with JDK 21 and the local Android SDK. Expected: all tests pass.

### Task 2: Add input gain and playback boost

**Files:**
- Modify: `app/src/main/java/com/far/mqttcall/audio/AudioEngine.kt`
- Test: `app/src/test/java/com/far/mqttcall/audio/AudioGainTest.kt` (create)

**Interfaces:**
- Add an internal pure helper `applyInputGain(samples: ShortArray, gain: Float): ShortArray`.
- Keep `AudioBatcher` and per-profile packet interval behavior unchanged.
- Change `PLAYBACK_GAIN_MB` from `1500` to `3000`.

- [ ] **Step 1: Write failing gain tests**

```kotlin
@Test
fun `input gain doubles samples and clips at signed 16 bit bounds`() {
    assertArrayEquals(
        shortArrayOf(2000, -2000, Short.MAX_VALUE, Short.MIN_VALUE),
        applyInputGain(shortArrayOf(1000, -1000, 20000, -20000), 2f),
    )
}
```

- [ ] **Step 2: Run the focused test and verify it fails**

Run: `./gradlew :app:testDebugUnitTest --tests '*AudioGainTest*'` with JDK 21 and the local SDK. Expected: unresolved helper/failing assertion.

- [ ] **Step 3: Implement minimal gain and apply it before Opus encoding**

In the capture loop, copy the `AudioRecord` PCM frame, apply `2.0f` gain with `coerceIn(Short.MIN_VALUE.toInt(), Short.MAX_VALUE.toInt())`, then pass the gained frame to `AudioBatcher`. Keep AGC enabled where available. Set `LoudnessEnhancer.setTargetGain(3000)` for playback.

- [ ] **Step 4: Run audio and full JVM tests**

Run `./gradlew :app:testDebugUnitTest`; expected: all tests pass and packet timing tests still pass.

### Task 3: Create the foreground service and notification

**Files:**
- Create: `app/src/main/java/com/far/mqttcall/CallService.kt`
- Modify: `app/src/main/java/com/far/mqttcall/MainActivity.kt` (factory ownership moves)
- Modify: `app/src/main/AndroidManifest.xml`
- Modify: `app/src/main/res/values/strings.xml`
- Test: `app/src/test/java/com/far/mqttcall/CallServiceTest.kt` or a service-owned lifecycle test (create)

**Interfaces:**
- `CallService.LocalBinder` exposes `val viewModel: CallViewModel` and `val uiState: StateFlow<CallUiState>`.
- Service creates `CallViewModel` with `PahoMqttTransport`, `AndroidCryptoEngine`, `AndroidAudioEngine`, and `AndroidCallSettingsStore`.
- Service receives `ACTION_STOP` and dispatches Disconnect before `stopSelf()`.

- [ ] **Step 1: Write failing lifecycle tests**

Cover these behaviors with fakes: service-owned ViewModel is created once, cleanup calls immediate capture stop and transport disconnect, and Stop clears `keepConnected`.

- [ ] **Step 2: Run focused tests and verify failure**

Run: `./gradlew :app:testDebugUnitTest --tests '*CallServiceTest*'`. Expected: service class/lifecycle behavior is absent.

- [ ] **Step 3: Implement service startup and notification**

Declare `android.permission.FOREGROUND_SERVICE`, `android.permission.FOREGROUND_SERVICE_MEDIA_PLAYBACK`, and `android.permission.FOREGROUND_SERVICE_MICROPHONE`; declare `CallService` with `foregroundServiceType="mediaPlayback|microphone"`. Create a low-importance notification channel and call `startForeground` before doing work. Use media-playback mode while disconnected/receiving and include microphone mode only while capture is active. Use `START_STICKY` only when persisted `keepConnected` is true; explicit Disconnect clears it and stops the service.

- [ ] **Step 4: Run tests and compile the Android app**

Run focused service tests, `./gradlew :app:testDebugUnitTest :app:compileDebugAndroidTestKotlin :app:assembleDebug`. Expected: pass/build success.

### Task 4: Bind the Activity and make permission prompting one-time

**Files:**
- Modify: `app/src/main/java/com/far/mqttcall/MainActivity.kt`
- Modify: `app/src/main/java/com/far/mqttcall/CallViewModel.kt`
- Modify: `app/src/main/java/com/far/mqttcall/ui/CallScreen.kt`
- Modify: `app/src/main/java/com/far/mqttcall/settings/CallSettingsStore.kt`
- Test: `app/src/androidTest/java/com/far/mqttcall/ui/CallScreenTest.kt` and permission-focused unit tests

**Interfaces:**
- Activity starts the service, binds/unbinds using `ServiceConnection`, collects the service ViewModel state, and dispatches UI actions through the binder.
- Remove the startup `LaunchedEffect` that dispatches microphone permission state as a permission request.
- PTT permission callback dispatches a granted action only after Android reports granted; the service/ViewModel independently checks platform permission before capture.

- [ ] **Step 1: Write failing permission tests**

Assert that launch does not request permission, the first ungranted PTT attempt marks the prompt as shown and requests once, a second denied attempt does not launch another request, and a granted permission dispatches PTT without requesting.

- [ ] **Step 2: Run permission tests and verify failure**

Run the focused unit/instrumentation test command. Expected: current startup/request behavior fails the new one-time-permission contract.

- [ ] **Step 3: Implement binding and permission coordinator**

Keep the Android permission callback in the Activity, persist `microphonePermissionPrompted` before launching the first request, and display an App Settings action after a later denial. Do not call `AudioRecord` or `startCapture` from launch/bind/grant callbacks. Add a small top-right Exit action that dispatches service Disconnect/Stop, unbinds, and calls `finishAndRemoveTask()`.

- [ ] **Step 4: Run UI/unit tests and build**

Run `./gradlew :app:testDebugUnitTest :app:compileDebugAndroidTestKotlin :app:assembleDebug`. Expected: pass/build success.

### Task 5: Connect background behavior and cleanup paths

**Files:**
- Modify: `app/src/main/java/com/far/mqttcall/CallViewModel.kt`
- Modify: `app/src/main/java/com/far/mqttcall/CallService.kt`
- Modify: `app/src/main/java/com/far/mqttcall/transport/PahoMqttTransport.kt` if service lifecycle requires an explicit close
- Test: `app/src/test/java/com/far/mqttcall/CallViewModelTest.kt` and service lifecycle tests

**Interfaces:**
- Connect sets persisted `keepConnected=true` and updates the notification.
- Disconnect and notification Stop call `releaseTalkNow`, stop playback, disconnect MQTT, clear `keepConnected`, and stop the service.
- Service `onDestroy` calls immediate capture cancellation plus suspend cleanup where lifecycle allows.

- [ ] **Step 1: Add failing background/cleanup tests**

Cover Activity unbind without disconnect, notification Stop cleanup, service destruction while PTT is active, and reconnect after a sticky service restart when `keepConnected=true`.

- [ ] **Step 2: Run the focused tests and verify failure**

Run the relevant JVM service/ViewModel test filters. Expected: current Activity-owned lifecycle cannot satisfy them.

- [ ] **Step 3: Implement lifecycle wiring and notification updates**

Ensure unbinding only removes the UI connection. Ensure service destruction cannot leave a separately-scoped `AndroidAudioEngine` capture job or MQTT client alive.

- [ ] **Step 4: Run the full JVM suite**

Run `./gradlew :app:testDebugUnitTest`; expected: all tests pass.

### Task 6: Device verification and delivery APK

**Files:**
- Modify only if verification exposes a concrete API 24 issue.

- [ ] **Step 1: Build the final APK and Android tests**

Run:

```bash
JAVA_HOME=/usr/lib/jvm/java-21-openjdk-amd64 \
ANDROID_HOME=/home/farrukhhussain/Android/Sdk \
ANDROID_SDK_ROOT=/home/farrukhhussain/Android/Sdk \
./gradlew :app:testDebugUnitTest :app:compileDebugAndroidTestKotlin :app:assembleDebug
```

- [ ] **Step 2: Install on both connected devices without uninstalling**

Run `adb -s SERIAL install -r app/build/outputs/apk/debug/app-debug.apk`; do not uninstall automatically because that can erase saved settings and keys.

- [ ] **Step 3: Verify API 24 and current-device behavior**

On the Redmi/API 24 device: connect, background the Activity, receive a test packet, verify playback continues, press/release PTT while foreground, and verify mic capture starts/stops only during the hold. On the newer device: repeat and verify 400ms public / 100ms LAN batching.

- [ ] **Step 4: Verify the final diff**

Run `git diff --check` and report any device install/test blocker explicitly.
