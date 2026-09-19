# MQTT Push-to-Talk Android App Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Build a foreground Android push-to-talk app that connects two devices through a selectable MQTT broker, sends encrypted Opus audio in 200 ms batches, buffers three batches before playback, and disables local PTT while another talker owns the channel.

**Architecture:** A Kotlin Android app separates pure protocol, crypto, floor-control, and jitter-buffer logic from Android audio, MQTT, and Compose UI adapters. The channel protocol uses one topic, `call/channel/<number>`, with authenticated binary packets for claims, releases, and audio. The audio adapter captures 20 ms PCM frames, encodes them with a portable Opus binding, groups ten frames per MQTT message, and feeds a three-batch playback buffer.

**Tech Stack:** Kotlin 2.0.21, Android Gradle Plugin 8.7.3, Gradle 8.9, Android API 35 compile/target with API 26 minimum, Jetpack Compose BOM 2024.10.01, AndroidX Activity 1.9.3, Lifecycle 2.8.6, Eclipse Paho MQTT Java 1.2.5, Kopus 1.6.1.3, JUnit 4, Kotlin coroutines, Android Keystore, `AudioRecord`, and `AudioTrack`.

**Spec:** `docs/superpowers/specs/2026-09-19-mqtt-ptt-design.md`

## Global Constraints

- Static demo defaults are broker `broker.emqx.io:1883`, channel `3344`, and key `PTT-DEMO-3344`.
- The MQTT topic is exactly `call/channel/<channel-number>`; channel input is ASCII digits, 1–12 digits after trimming.
- MQTT is version 3.1.1, clean session, 30-second keepalive, QoS 0 for audio, QoS 1 for claims/releases, and no retained packets.
- Audio is 16 kHz mono Opus with 20 ms frames; ten frames form each 200 ms transport batch.
- Playback starts after three complete batches, pauses at an empty queue, and resumes after three new batches; the queue holds at most ten batches.
- Claim leases last 1,000 ms and are renewed by each audio batch; the deterministic session-ID tie-breaker resolves simultaneous claims.
- Packet protocol version is `1`; the fixed authenticated header is 34 bytes: version, kind, 16-byte session ID, 32-bit sequence, and 12-byte nonce.
- AES-GCM uses a fresh 12-byte nonce per packet and rejects authentication failures before decoding audio.
- The crypto layer prefers StrongBox, then Trusted Environment, then platform software; the UI reports the actual security level.
- The MVP is foreground-only and releases the talk claim when the activity backgrounds or audio focus is lost.
- Public brokers are test infrastructure; the app warns users and never logs keys or decrypted audio.
- No production product code is written before its behavior has a failing test, except build configuration and Android resource scaffolding.

## Review Focus

- A malformed, truncated, duplicated, or tampered packet must be discarded before audio decoding; packet codec tests pin this in Task 3.
- A key or channel change must produce a different derived key while the same inputs remain interoperable; crypto tests pin this in Task 2.
- A second talker must not take the local PTT during a valid remote claim/audio lease; floor-control tests pin this in Task 4.
- A jitter buffer must wait for exactly three batches after startup and after an underrun without growing beyond ten batches; buffer tests pin this in Task 4.
- Device/provider crypto differences must be visible and must not prevent software fallback; Android crypto tests and device checks pin this in Task 5.

---

### Task 1: Create the Android project and reproducible build

**Files:**
- Create: `settings.gradle.kts`
- Create: `build.gradle.kts`
- Create: `gradle.properties`
- Create: `gradle/libs.versions.toml`
- Create: `gradlew`
- Create: `gradlew.bat`
- Create: `gradle/wrapper/gradle-wrapper.jar`
- Create: `gradle/wrapper/gradle-wrapper.properties`
- Create: `app/build.gradle.kts`
- Create: `app/src/main/AndroidManifest.xml`
- Create: `app/src/main/res/values/strings.xml`
- Create: `app/src/main/res/values/themes.xml`
- Create: `.gitignore`
- Create: `README.md`

**Interfaces:**
- Produces an `:app` Android application with namespace `com.far.mqttcall`, application ID `com.far.mqttcall`, min SDK 26, target/compile SDK 35, and a debug APK installable on both connected devices.

- [ ] **Step 1: Install the Android build prerequisites**

Use `/home/far/Android/Sdk` as the SDK root. If the command-line tools are absent, install the official Android command-line tools there, accept licenses, and install `platforms;android-35`, `build-tools;35.0.0`, and `platform-tools`. If the system `gradle` command is absent, download the Gradle 8.9 binary distribution into a task-scoped temporary directory and use its `bin/gradle wrapper --gradle-version 8.9` command to generate the checked-in wrapper files. Use a task-specific shell environment:

```bash
ANDROID_SDK_ROOT=/home/far/Android/Sdk
export ANDROID_SDK_ROOT
export PATH="$ANDROID_SDK_ROOT/platform-tools:$PATH"
sdkmanager "platform-tools" "platforms;android-35" "build-tools;35.0.0"
```

- [ ] **Step 2: Add the Gradle project files**

Pin the versions from the plan header, enable Kotlin/Compose plugins, use Google and Maven Central repositories, and add `org.eclipse.paho:org.eclipse.paho.client.mqttv3:1.2.5` and `eu.buney.kopus:kopus:1.6.1.3` in the app module. Add AndroidX Compose, lifecycle, coroutines, JUnit, and Android test dependencies. Add `android.permission.INTERNET` and `android.permission.RECORD_AUDIO` to the manifest.

- [ ] **Step 3: Add the launcher resource and README**

Set the app label to `MQTT Call`, define the light/dark Material theme, and document the build commands, two device serials, static demo defaults, public-broker warning, and the debug install command.

- [ ] **Step 4: Build the empty app**

Run:

```bash
./gradlew :app:assembleDebug
```

Expected: `BUILD SUCCESSFUL` and `app/build/outputs/apk/debug/app-debug.apk` exists.

- [ ] **Step 5: Commit the project skeleton**

```bash
git add settings.gradle.kts build.gradle.kts gradle.properties gradle/libs.versions.toml app .gitignore README.md
git commit -m "build: scaffold Android MQTT call app"
```

### Task 2: Add domain models, defaults, and channel validation

**Files:**
- Create: `app/src/main/java/com/far/mqttcall/domain/AppDefaults.kt`
- Create: `app/src/main/java/com/far/mqttcall/domain/BrokerProfile.kt`
- Create: `app/src/main/java/com/far/mqttcall/domain/ChannelTopic.kt`
- Create: `app/src/test/java/com/far/mqttcall/domain/ChannelTopicTest.kt`
- Create: `app/src/test/java/com/far/mqttcall/domain/BrokerProfileTest.kt`

**Interfaces:**
- `object AppDefaults { val defaultBroker: BrokerProfile; const val defaultChannel = "3344"; const val defaultKey = "PTT-DEMO-3344" }`
- `data class BrokerProfile(val name: String, val host: String, val port: Int, val tls: Boolean, val username: String?, val password: String?)`
- `fun channelTopic(channel: String): Result<String>`
- `fun defaultBrokerProfiles(): List<BrokerProfile>`

- [ ] **Step 1: Write failing topic and preset tests**

```kotlin
@Test fun `builds exact channel topic`() {
    assertEquals("call/channel/3344", channelTopic(" 3344 ").getOrThrow())
}

@Test fun `rejects non numeric or out of range channels`() {
    assertTrue(channelTopic("abc").isFailure)
    assertTrue(channelTopic("1234567890123").isFailure)
}

@Test fun `defaults include shared demo broker and values`() {
    assertEquals("broker.emqx.io", AppDefaults.defaultBroker.host)
    assertEquals("3344", AppDefaults.defaultChannel)
    assertEquals("PTT-DEMO-3344", AppDefaults.defaultKey)
}
```

- [ ] **Step 2: Run the focused tests and verify RED**

Run `./gradlew :app:testDebugUnitTest --tests 'com.far.mqttcall.domain.*'`. Expected: compilation/test failure because the domain APIs do not exist.

- [ ] **Step 3: Implement validation and profiles**

Trim the channel, require ASCII digits, require 1–12 digits, and return the exact topic. Define EMQX TCP/TLS and Mosquitto TCP/TLS presets in the same order as the spec, with EMQX TCP as default.

- [ ] **Step 4: Run the focused tests and verify GREEN**

Run the same Gradle command. Expected: all domain tests pass.

- [ ] **Step 5: Commit the domain slice**

```bash
git add app/src/main/java/com/far/mqttcall/domain app/src/test/java/com/far/mqttcall/domain
git commit -m "feat: add broker presets and channel validation"
```

### Task 3: Implement crypto and the authenticated packet codec

**Files:**
- Create: `app/src/main/java/com/far/mqttcall/protocol/PacketModels.kt`
- Create: `app/src/main/java/com/far/mqttcall/protocol/PacketCodec.kt`
- Create: `app/src/main/java/com/far/mqttcall/crypto/CryptoEngine.kt`
- Create: `app/src/main/java/com/far/mqttcall/crypto/JvmCryptoEngine.kt`
- Create: `app/src/main/java/com/far/mqttcall/crypto/AndroidCryptoEngine.kt`
- Create: `app/src/test/java/com/far/mqttcall/protocol/PacketCodecTest.kt`
- Create: `app/src/test/java/com/far/mqttcall/crypto/CryptoEngineTest.kt`

**Interfaces:**
- `enum class PacketKind { CLAIM, RELEASE, AUDIO }`
- `data class PacketHeader(val kind: PacketKind, val sessionId: ByteArray, val sequence: Long, val nonce: ByteArray)`
- `data class DecodedPacket(val header: PacketHeader, val plaintext: ByteArray)`
- `interface CryptoEngine { val securityLevel: SecurityLevel; fun prepare(channel: String, passphrase: CharArray): CryptoSession }`
- `interface CryptoSession { fun encrypt(header: PacketHeader, plaintext: ByteArray): ByteArray; fun decrypt(packet: ByteArray): DecodedPacket? }`
- `enum class SecurityLevel { STRONGBOX, TRUSTED_ENVIRONMENT, SOFTWARE }`
- `PacketCodec.encode(header, ciphertext): ByteArray` and `PacketCodec.decode(bytes): ParsedPacket`

- [ ] **Step 1: Write failing crypto tests**

```kotlin
@Test fun `same channel and key derive interoperable sessions`() {
    val first = jvmCrypto.prepare("3344", "shared".toCharArray())
    val second = jvmCrypto.prepare("3344", "shared".toCharArray())
    val header = testHeader(PacketKind.AUDIO, 1)
    val packet = first.encrypt(header, byteArrayOf(1, 2, 3))
    assertArrayEquals(byteArrayOf(1, 2, 3), second.decrypt(packet)!!.plaintext)
}

@Test fun `tampering and wrong key fail authentication`() {
    val packet = jvmCrypto.prepare("3344", "one".toCharArray())
        .encrypt(testHeader(PacketKind.AUDIO, 1), byteArrayOf(1))
    packet[packet.lastIndex] = (packet[packet.lastIndex].toInt() xor 0x01).toByte()
    assertNull(jvmCrypto.prepare("3344", "one".toCharArray()).decrypt(packet))
    assertNull(jvmCrypto.prepare("3344", "two".toCharArray()).decrypt(packet))
}
```

- [ ] **Step 2: Run crypto tests and verify RED**

Run `./gradlew :app:testDebugUnitTest --tests 'com.far.mqttcall.crypto.*' --tests 'com.far.mqttcall.protocol.*'`. Expected: failure because the crypto and packet APIs do not exist.

- [ ] **Step 3: Implement the pure JVM crypto and packet format**

Use PBKDF2-HMAC-SHA256 with 100,000 iterations and a 32-byte output, with salt `mqtt-ptt-v1/<channel>`. Use AES/GCM/NoPadding, a fresh 12-byte nonce from `SecureRandom`, and the 34-byte header as GCM AAD. Encode integers big-endian. Reject bad version, packet kind, session length, nonce length, sequence, ciphertext length, duplicate sequence values, and failed GCM authentication.

- [ ] **Step 4: Add Android Keystore selection**

Implement `AndroidCryptoEngine` to store the derived channel key under a channel alias using an Android Keystore AES/GCM key. Attempt StrongBox first, retry with a Trusted Environment key when `StrongBoxUnavailableException` occurs, and use the JVM/platform provider when import or provider capability fails. Read `KeyInfo.getSecurityLevel()` on API 29+ and the legacy hardware flag on lower supported versions. Do not log passphrases or plaintext.

- [ ] **Step 5: Run all unit tests and verify GREEN**

Run `./gradlew :app:testDebugUnitTest`. Expected: all crypto and protocol tests pass, including nonce uniqueness, wrong-key rejection, malformed packet rejection, and duplicate sequence rejection.

- [ ] **Step 6: Commit the protocol slice**

```bash
git add app/src/main/java/com/far/mqttcall/protocol app/src/main/java/com/far/mqttcall/crypto app/src/test/java/com/far/mqttcall/protocol app/src/test/java/com/far/mqttcall/crypto
git commit -m "feat: add encrypted MQTT packet protocol"
```

### Task 4: Implement talk-floor arbitration and the jitter buffer

**Files:**
- Create: `app/src/main/java/com/far/mqttcall/floor/TalkFloor.kt`
- Create: `app/src/main/java/com/far/mqttcall/audio/JitterBuffer.kt`
- Create: `app/src/test/java/com/far/mqttcall/floor/TalkFloorTest.kt`
- Create: `app/src/test/java/com/far/mqttcall/audio/JitterBufferTest.kt`

**Interfaces:**
- `data class TalkClaim(val sessionId: ByteArray, val expiresAtMs: Long)`
- `class TalkFloor(private val clockMs: () -> Long) { fun onRemoteClaim(...); fun onRemoteAudio(...); fun onRemoteRelease(...); fun canTransmit(): Boolean; fun activeRemoteSession(): ByteArray?; fun localClaim(...); fun releaseLocal() }`
- `data class AudioBatch(val sessionId: ByteArray, val sequence: Long, val frames: List<ByteArray>)`
- `class JitterBuffer(private val startupBatches: Int = 3, private val maxBatches: Int = 10) { fun offer(batch: AudioBatch); fun pollFrame(): ByteArray?; fun state(): BufferState }`

- [ ] **Step 1: Write failing floor and buffer tests**

```kotlin
@Test fun `remote claim disables local transmit until release or expiry`() {
    val clock = FakeClock(0)
    val floor = TalkFloor(clock::now)
    floor.onRemoteClaim(remoteSession, expiresAtMs = 1_000)
    assertFalse(floor.canTransmit())
    clock.advance(1_001)
    assertTrue(floor.canTransmit())
}

@Test fun `buffer waits for three batches and re-buffers after underrun`() {
    val buffer = JitterBuffer()
    buffer.offer(batch(1)); buffer.offer(batch(2))
    assertEquals(BufferState.BUFFERING, buffer.state())
    buffer.offer(batch(3))
    assertEquals(BufferState.PLAYING, buffer.state())
    drainAllFrames(buffer)
    assertEquals(BufferState.BUFFERING, buffer.state())
    buffer.offer(batch(4)); buffer.offer(batch(5))
    assertEquals(BufferState.BUFFERING, buffer.state())
    buffer.offer(batch(6))
    assertEquals(BufferState.PLAYING, buffer.state())
}
```

- [ ] **Step 2: Run focused tests and verify RED**

Run `./gradlew :app:testDebugUnitTest --tests 'com.far.mqttcall.floor.*' --tests 'com.far.mqttcall.audio.JitterBufferTest'`. Expected: failure because the APIs do not exist.

- [ ] **Step 3: Implement the floor state machine**

Use a 1,000 ms lease. On simultaneous local/remote claims, keep the lexicographically greater 16-byte session ID and reject the losing local claim. Renew remote state on valid audio. Ignore stale sessions after release or expiry. Expose `IDLE`, `LOCAL_TALKING`, `REMOTE_TALKING`, and `BUSY` states.

- [ ] **Step 4: Implement the bounded batch/frame buffer**

Keep at most ten batches, drop the oldest batch when full, require three queued batches before `PLAYING`, and return to `BUFFERING` when all frames are drained. Reject batches from the wrong session and sequence numbers at or below the last accepted sequence.

- [ ] **Step 5: Run all unit tests and verify GREEN**

Run `./gradlew :app:testDebugUnitTest`. Expected: all domain, crypto, protocol, floor, and buffer tests pass.

- [ ] **Step 6: Commit the state-machine slice**

```bash
git add app/src/main/java/com/far/mqttcall/floor app/src/main/java/com/far/mqttcall/audio/JitterBuffer.kt app/src/test/java/com/far/mqttcall/floor app/src/test/java/com/far/mqttcall/audio
git commit -m "feat: add talk floor and jitter buffering"
```

### Task 5: Add MQTT transport and settings persistence

**Files:**
- Create: `app/src/main/java/com/far/mqttcall/transport/MqttTransport.kt`
- Create: `app/src/main/java/com/far/mqttcall/transport/PahoMqttTransport.kt`
- Create: `app/src/main/java/com/far/mqttcall/settings/CallSettingsStore.kt`
- Create: `app/src/test/java/com/far/mqttcall/transport/FakeMqttTransportTest.kt`

**Interfaces:**
- `interface MqttTransport { val events: Flow<MqttEvent>; suspend fun connect(profile: BrokerProfile, topic: String); suspend fun publish(payload: ByteArray, qos: Int); suspend fun disconnect() }`
- `sealed interface MqttEvent { data object Connected; data object Disconnected; data class Message(val payload: ByteArray); data class Error(val message: String) }`
- `interface CallSettingsStore { fun load(): SavedCallSettings; fun save(settings: SavedCallSettings) }`

- [ ] **Step 1: Write failing transport/settings tests**

Test that a fake transport emits `Connected`, records the exact topic and QoS for publish operations, emits incoming messages, and emits `Disconnected` after disconnect. Test that settings load defaults on first run and preserves a custom broker/channel while storing the key through the crypto-backed store.

- [ ] **Step 2: Run focused tests and verify RED**

Run `./gradlew :app:testDebugUnitTest --tests 'com.far.mqttcall.transport.*'`. Expected: failure because transport and settings APIs do not exist.

- [ ] **Step 3: Implement Paho transport**

Use `MqttAsyncClient` with a unique client ID, `MqttConnectOptions.mqttVersion = MQTT_VERSION_3_1_1`, clean session, 30-second keepalive, automatic reconnect disabled in favor of a coroutine backoff loop, and TLS socket factories for TLS profiles. Subscribe before emitting `Connected`. Publish audio at QoS 0 and control packets at QoS 1. Never use retained messages.

- [ ] **Step 4: Implement settings storage**

Persist the selected broker, channel, and key through `SharedPreferences`; protect the entered key using the Android Keystore wrapping path from Task 3. If the stored value cannot be decrypted, reset only the key to `PTT-DEMO-3344` and keep the broker/channel settings.

- [ ] **Step 5: Run all unit tests and verify GREEN**

Run `./gradlew :app:testDebugUnitTest`. Expected: all transport, settings, and earlier unit tests pass.

- [ ] **Step 6: Commit the transport slice**

```bash
git add app/src/main/java/com/far/mqttcall/transport app/src/main/java/com/far/mqttcall/settings app/src/test/java/com/far/mqttcall/transport
git commit -m "feat: add MQTT transport and protected settings"
```

### Task 6: Add Opus and Android microphone/playback adapters

**Files:**
- Create: `app/src/main/java/com/far/mqttcall/audio/OpusCodec.kt`
- Create: `app/src/main/java/com/far/mqttcall/audio/KopusCodec.kt`
- Create: `app/src/main/java/com/far/mqttcall/audio/AudioEngine.kt`
- Create: `app/src/test/java/com/far/mqttcall/audio/AudioBatcherTest.kt`
- Create: `app/src/androidTest/java/com/far/mqttcall/audio/AudioDeviceTest.kt`

**Interfaces:**
- `interface OpusCodec { fun encode(frame: ShortArray): ByteArray; fun decode(packet: ByteArray): ShortArray; fun close() }`
- `interface AudioEngine { suspend fun startCapture(onBatch: suspend (List<ByteArray>) -> Unit); suspend fun stopCapture(); suspend fun play(batch: AudioBatch); suspend fun stopPlayback() }`
- `class AudioBatcher(private val codec: OpusCodec) { fun addFrame(frame: ShortArray): List<ByteArray>? }`

- [ ] **Step 1: Write failing batcher tests**

```kotlin
@Test fun `ten 20 millisecond frames produce one transport batch`() {
    val batcher = AudioBatcher(FakeOpusCodec())
    repeat(9) { assertNull(batcher.addFrame(ShortArray(320))) }
    assertEquals(10, batcher.addFrame(ShortArray(320))!!.size)
}
```

- [ ] **Step 2: Run focused tests and verify RED**

Run `./gradlew :app:testDebugUnitTest --tests 'com.far.mqttcall.audio.AudioBatcherTest'`. Expected: failure because the codec and batcher APIs do not exist.

- [ ] **Step 3: Implement the portable Opus adapter**

Construct `OpusEncoder(16_000, 1, OpusApplication.Voip)` and `OpusDecoder(16_000, 1)` from Kopus, use a 320-sample frame size, set a 24,000 bps target bitrate, and close native objects deterministically. The batcher returns ten encoded frames every 200 ms.

- [ ] **Step 4: Implement `AudioRecord` capture and `AudioTrack` playback**

Capture `AudioFormat.CHANNEL_IN_MONO` and `ENCODING_PCM_16BIT` at 16 kHz using `AudioRecord`, read exactly 320 samples per frame on a dedicated coroutine dispatcher, and publish batches through the callback. Decode frames into 320-sample PCM blocks and write them to a streaming `AudioTrack`. Stop and release both objects on cancellation, background, audio-focus loss, and errors.

- [ ] **Step 5: Run JVM and device audio tests**

Run `./gradlew :app:testDebugUnitTest :app:connectedDebugAndroidTest`. Expected: batcher tests pass and the two devices report successful microphone permission, Opus encode/decode, and short playback without crashes.

- [ ] **Step 6: Commit the audio slice**

```bash
git add app/src/main/java/com/far/mqttcall/audio app/src/test/java/com/far/mqttcall/audio app/src/androidTest/java/com/far/mqttcall/audio
git commit -m "feat: add Opus audio capture and playback"
```

### Task 7: Connect the application state to a Compose PTT screen

**Files:**
- Create: `app/src/main/java/com/far/mqttcall/CallViewModel.kt`
- Create: `app/src/main/java/com/far/mqttcall/MainActivity.kt`
- Create: `app/src/main/java/com/far/mqttcall/ui/CallScreen.kt`
- Create: `app/src/main/java/com/far/mqttcall/ui/AppTheme.kt`
- Create: `app/src/test/java/com/far/mqttcall/CallViewModelTest.kt`
- Create: `app/src/androidTest/java/com/far/mqttcall/ui/CallScreenTest.kt`

**Interfaces:**
- `data class CallUiState(...)` exposes broker, channel, key, topic, connection, talk state, buffer state, encryption security, error, and diagnostic counters.
- `sealed interface CallAction { data object Connect; data object Disconnect; data object GenerateKey; data object ResetDefaults; data object PressTalk; data object ReleaseTalk; data object RequestMicrophone }`
- `class CallViewModel(...) { val uiState: StateFlow<CallUiState>; fun dispatch(action: CallAction) }`

- [ ] **Step 1: Write failing ViewModel tests**

Test that the initial state contains the static defaults, `GenerateKey` changes the key without changing the channel, `Connect` does not enable PTT until transport subscription succeeds, `PressTalk` publishes a claim before audio, and `ReleaseTalk` publishes release and stops capture.

- [ ] **Step 2: Run focused tests and verify RED**

Run `./gradlew :app:testDebugUnitTest --tests 'com.far.mqttcall.CallViewModelTest'`. Expected: failure because the ViewModel and state APIs do not exist.

- [ ] **Step 3: Implement the ViewModel orchestration**

Inject transport, crypto, floor, audio, clock, and settings dependencies. On connect, validate fields, prepare crypto, connect/subscribe, and expose the security level. On incoming packets, decrypt, route control/audio, update floor and jitter state, and start playback only when the buffer changes to `PLAYING`. On press/release, enforce floor state and microphone permission.

- [ ] **Step 4: Implement the Compose UI**

Build one screen with broker dropdown, custom broker fields, channel field, password-style key field, generate/copy/reset actions, connect/disconnect action, connection/security/buffer status cards, and a large press-and-hold PTT button. Use `Modifier.pointerInput` or `combinedClickable` so press starts transmission and release/cancel always stops it. Disable the button for disconnected, connecting, permission, busy, or error states. Show the public-broker warning in the connection area.

- [ ] **Step 5: Run unit and UI tests**

Run `./gradlew :app:testDebugUnitTest :app:connectedDebugAndroidTest`. Expected: state transitions and Compose semantics tests pass on the connected devices.

- [ ] **Step 6: Commit the application slice**

```bash
git add app/src/main/java/com/far/mqttcall app/src/test/java/com/far/mqttcall/CallViewModelTest.kt app/src/androidTest/java/com/far/mqttcall/ui
git commit -m "feat: add Compose push-to-talk screen"
```

### Task 8: Verify hardware security, broker interoperability, and two-device calling

**Files:**
- Create: `scripts/install-two-devices.sh`
- Create: `scripts/capture-two-device-logs.sh`
- Modify: `README.md`
- Modify: `app/src/androidTest/java/com/far/mqttcall/audio/AudioDeviceTest.kt`

**Interfaces:**
- `scripts/install-two-devices.sh` builds the debug APK and installs it on serials `0612RD2394` and `7f8a1522`.
- `scripts/capture-two-device-logs.sh` collects filtered app logs from both serials without printing keys or packet payloads.

- [ ] **Step 1: Add failing device acceptance checks**

Add instrumentation assertions for the visible security level, default broker/channel fields, connect state, disabled PTT while remote activity is active, and reset-to-buffering after a simulated underrun.

- [ ] **Step 2: Run device checks and verify RED for missing integration**

Run `./gradlew :app:connectedDebugAndroidTest`. Expected: integration failures until the scripts, UI, and full transport/audio wiring are present; record the first real failure rather than weakening assertions.

- [ ] **Step 3: Implement installation and diagnostic scripts**

Use `adb -s 0612RD2394 install -r` and `adb -s 7f8a1522 install -r`, launch the same package on both devices, and collect only `MqttCall`, `Audio`, `Crypto`, and `TalkFloor` tags. The scripts must stop on failed commands and must not echo settings or payloads.

- [ ] **Step 4: Exercise both public broker presets**

With both devices on the EMQX default, connect without editing fields, press PTT on one device, verify the other enters buffering then playback after three batches, release, and verify the receiver returns to listening. Repeat with the Mosquitto preset and then with different keys to confirm silence.

- [ ] **Step 5: Measure the crypto path on both devices**

Record the reported StrongBox/Trusted Environment/software level and measure 100 encrypt/decrypt operations for a representative audio batch. Confirm five 200 ms batches per second keep up without buffer growth or missed UI events. If StrongBox is unavailable, the app must display the actual fallback and continue working.

- [ ] **Step 6: Run the complete verification suite**

Run:

```bash
./gradlew test connectedDebugAndroidTest
./scripts/install-two-devices.sh
./scripts/capture-two-device-logs.sh
```

Expected: JVM tests, instrumentation tests, installation, default-value interoperability, wrong-key silence, floor locking, and audio playback all pass.

- [ ] **Step 7: Update the README and commit the acceptance work**

Document the exact build/install commands, observed device crypto levels, broker caveat, and the successful two-device test sequence.

```bash
git add scripts README.md app/src/androidTest
git commit -m "test: verify two-device MQTT calling"
```

## Plan Self-Review

- Spec coverage: defaults and broker presets are Task 2; packet/encryption behavior is Task 3; floor and jitter timing is Task 4; MQTT/reconnect is Task 5; Opus and device audio is Task 6; UI and permissions are Task 7; two-device acceptance is Task 8.
- Placeholder scan: the plan contains no unfinished markers or unspecified implementation steps.
- Type consistency: `PacketHeader`, `CryptoSession`, `MqttTransport`, `AudioBatch`, `TalkFloor`, `JitterBuffer`, `AudioEngine`, `CallUiState`, and `CallAction` are defined before later tasks consume them.
- Review focus coverage: malformed packets, key separation, remote busy state, three-batch rebuffering, and hardware/software crypto reporting each have an owning test task.
- Toolchain constraint: the current workspace has Java 17 and ADB but no detected Android SDK command-line tools, so Task 1 explicitly provisions the SDK before the first Gradle build.
