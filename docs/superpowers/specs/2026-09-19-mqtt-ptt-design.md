# MQTT Push-to-Talk Android App Design

**Date:** 2026-09-19  
**Status:** Approved for implementation planning

## Goal

Build a small Android push-to-talk app that lets people on the same MQTT broker, channel, and key speak to one another. The first acceptance target is two connected Android devices: a Hytera PNC460 running Android API 32 and a Xiaomi 2201117TG running Android API 33.

The app is a demonstrable prototype for public MQTT brokers. It must make the default path immediate: install, open, connect with the prefilled values, press and hold the talk button, and hear the other device.

## Scope

The MVP includes:

- A single Compose screen for broker, channel, key, connection, buffer, and PTT state.
- A prefilled list of public test broker profiles, with a custom broker option.
- Static demo defaults shared by every installation: EMQX public broker, channel `3344`, and key `PTT-DEMO-3344`.
- Editable channel and key fields, including generated shareable keys and clipboard copy.
- MQTT 3.1.1 connection, subscription, reconnect, and disconnect handling.
- Mono microphone capture, 20 ms Opus frames, and 200 ms MQTT audio batches.
- Three-batch receive buffering before playback and the same threshold after an underrun.
- Per-channel talker activity state that disables PTT while another talker is active.
- AES-GCM packet encryption using Android platform cryptography, with Android Keystore protection and hardware-security-level reporting.
- Unit tests for the wire format, encryption, topic building, talker lock, and jitter buffer.
- Two-device installation and manual acceptance testing through ADB.

The public broker presets are testing infrastructure, not a privacy or availability guarantee. EMQX documents `broker.emqx.io` on ports 1883 and 8883; Eclipse Mosquitto documents `test.mosquitto.org` with MQTT and TLS listeners. Public messages and topics can be observed by other users, so the app must keep audio encrypted and display a test-broker warning. Sources: <https://www.emqx.com/en/mqtt/public-mqtt5-broker> and <https://test.mosquitto.org/>.

## Non-goals for the MVP

- User accounts, contact lists, or a directory of channels.
- Call history, recordings, media notifications, or background listening.
- Server-side authentication or broker provisioning.
- A guarantee of collision-free floor control under arbitrary network partitions.
- Production-grade availability from public brokers.
- A promise that every device has a hardware-backed crypto implementation.

## User experience

On launch, the app shows the default broker, channel, and key. The primary action is `Connect`. Channel and key fields are editable while disconnected. `Generate key` creates a short, copyable share key and `Reset demo defaults` restores the static shared values.

After connecting, the screen shows the selected broker, the topic, connection state, encryption security level, talker state, and receive buffer state. The talk button is a large press-and-hold control. Pressing it claims the channel and starts transmission; releasing it publishes release state and stops capture. The button is disabled while another talker claim or audio stream is active.

The UI reports these states directly: disconnected, connecting, connected/listening, transmitting, channel busy, buffering, playing, reconnecting, microphone permission required, and broker error. Invalid packets from a wrong key are ignored and surfaced as an unobtrusive `No matching encrypted audio` state rather than producing audio.

The app only promises foreground use in the MVP. If the activity leaves the foreground or loses the audio focus, it releases the talk claim and stops capture.

## Broker presets

The first preset list is:

| Name | Host | Port | Transport | TLS | Default |
| --- | --- | ---: | --- | --- | --- |
| EMQX public | `broker.emqx.io` | 1883 | MQTT | No | Yes |
| EMQX public TLS | `broker.emqx.io` | 8883 | MQTT | Yes | No |
| Mosquitto public | `test.mosquitto.org` | 1883 | MQTT | No | No |
| Mosquitto public TLS | `test.mosquitto.org` | 8883 | MQTT | Yes | No |

The custom option accepts host, port, TLS, username, and password. Presets have no credentials. The app must warn before using unauthenticated public TCP or TLS test brokers and must never log the key or decrypted audio.

## Topic and MQTT behavior

The channel topic is exactly:

```text
call/channel/<channel-number>
```

The channel number is trimmed, restricted to ASCII digits, and limited to 1–12 digits. No wildcard subscription is used. Every client generates a unique MQTT client ID per connection.

The client uses MQTT 3.1.1 with a clean session and a 30-second keepalive. Audio packets use QoS 0 because late audio is unusable. Claim and release control packets use QoS 1 without retained messages. The app subscribes before enabling the talk button. Reconnect uses bounded exponential backoff and resubscribes after every successful connection.

All packet types share the channel topic. The packet kind is in the authenticated envelope so the receiver can route control and audio messages without extra topics.

## Audio pipeline

The capture path is:

```text
AudioRecord → 16 kHz mono PCM → 20 ms Opus frames → 10-frame/200 ms batch → AES-GCM → MQTT QoS 0 publish
```

The playback path is:

```text
MQTT receive → authenticate/decrypt → batch sequence check → Opus frames → jitter buffer → AudioTrack
```

The codec contract is Opus at 16 kHz mono. Each 20 ms frame contains 320 samples. Ten encoded frames form one MQTT audio batch sent every 200 ms. A 200 ms batch is therefore a transport grouping, not an invalid 200 ms Opus frame.

The receiver does not start `AudioTrack` playback until three complete audio batches from the current talk session are queued, giving a nominal 600 ms startup buffer. If the queue reaches zero, playback pauses and waits for three new complete batches before resuming. The queue holds at most ten batches, or two seconds of audio; when that maximum is reached, the oldest queued batch is dropped so latency cannot grow without bound.

The implementation will use a portable libopus binding for consistent behavior across the two test devices. A platform codec may be used only when its Opus support is verified, but the wire format remains the same.

## Talker lock and channel busy behavior

When PTT is pressed, the client publishes an encrypted `CLAIM` packet containing a random talk session ID and expiry. It publishes an encrypted `RELEASE` packet on PTT release. Every audio batch renews the talk session lease. Claims are not retained.

The receiver marks a channel busy when it sees a valid claim or audio batch from another session. A claim lease lasts 1,000 ms and every audio batch renews it. The receiver keeps the channel busy until a release arrives or 1,000 ms passes without a valid claim or audio batch. Audio activity itself is enough to mark the channel busy, so a missing claim cannot accidentally enable simultaneous talking.

If two clients claim at nearly the same time, each uses a deterministic session-ID tie-breaker. The winning session remains active; the losing client stops capture and shows `Channel busy`. This is a best-effort distributed floor-control rule and does not require a broker plugin.

The local talk button is disabled during another active session. A device may transmit again once the remote release is received or the remote lease has expired.

## Packet protocol

The protocol version is `1`. Each MQTT payload is a compact binary envelope:

```text
version(1) | kind(1) | session-id(16) | sequence(4) | nonce(12) | ciphertext | auth-tag(16)
```

The fixed 34-byte header is authenticated as AES-GCM additional authenticated data. `kind` identifies `CLAIM`, `RELEASE`, or `AUDIO`. The session ID distinguishes talkers; sequence numbers are monotonic within one talk session. An audio ciphertext contains a frame count byte, ten unsigned 16-bit frame lengths, and the encoded frame bytes. Control payloads contain the lease expiry and the sender's protocol capabilities. The 16-byte GCM authentication tag is appended after the ciphertext.

The receiver rejects unsupported versions, malformed lengths, duplicate sequence numbers, expired claims, and failed authentication. It does not attempt to decode unauthenticated bytes.

## Encryption and hardware security

The entered key is normalized to UTF-8 bytes and expanded into a 256-bit channel key with a password-based KDF performed once per connection and a domain-separated salt containing the protocol version and channel number. The channel key is never sent over MQTT. Each packet gets a fresh random GCM nonce. The topic and transport metadata remain visible to the broker, while the packet contents are authenticated and encrypted.

The crypto layer has two responsibilities:

1. Protect the user-entered key at rest with an Android Keystore wrapping key.
2. Use the Android platform AES-GCM provider for live packet encryption and decryption, preferring a Keystore-backed AES key when the device accepts the imported channel key.

The app requests StrongBox-backed storage when available, falls back to a Trusted Environment-backed key, and finally falls back to the platform software provider when hardware support is unavailable or incompatible. It reports the actual security level using `KeyInfo.getSecurityLevel()` on API 29+ and the legacy hardware check on older supported versions. The app must not fail solely because StrongBox is unavailable. Android documents AES support, StrongBox availability, and security-level inspection in <https://developer.android.com/privacy-and-security/keystore> and <https://developer.android.com/reference/android/security/keystore/KeyInfo>.

The implementation must benchmark the hardware-backed path on both connected devices. StrongBox is preferred for protecting stored key material, but its documented resource and latency tradeoffs mean the live 200 ms stream must remain responsive. The UI reports the selected path; it must never claim hardware encryption when the provider reports software security.

## Application structure

The implementation is organized around these boundaries:

- `MainActivity` and Compose UI: renders state and emits user actions.
- `CallViewModel`: owns screen state and coordinates connect, disconnect, PTT, and permission actions.
- `MqttTransport`: broker connection, subscription, publish, reconnect, and incoming byte stream.
- `PacketCodec`: binary envelope validation, encoding, decoding, and sequence metadata.
- `CryptoEngine`: KDF, Keystore key handling, AES-GCM, and security-level reporting.
- `TalkFloor`: claim lease, collision resolution, busy state, and release behavior.
- `AudioEngine`: microphone capture, Opus encode/decode, batching, jitter buffering, and AudioTrack playback.

Each boundary has a small Kotlin interface so protocol and timing behavior can be tested without an Android microphone or live broker.

## Errors and recovery

- Missing microphone permission: keep the app connected for listening but disable PTT and show a permission action.
- Broker connection failure: show the error, keep fields editable, and offer retry with bounded backoff.
- MQTT reconnect: stop local transmission, release the claim, clear the jitter buffer, and resubscribe.
- Wrong key or malformed packet: discard the packet and increment a local diagnostic counter; never play it.
- Audio capture or playback failure: stop the current session, release the claim, and return to connected/listening.
- App background or audio focus loss: release the claim and stop capture immediately.

## Testing and acceptance

Unit tests must cover:

- Channel normalization and exact topic generation.
- KDF determinism for the same channel/key and separation for different keys/channels.
- AES-GCM round trip, nonce uniqueness, tamper rejection, and wrong-key rejection.
- Packet round trip, malformed length rejection, unsupported-version rejection, and duplicate sequence handling.
- Three-batch jitter-buffer start, pause-on-empty, and three-batch rebuffer.
- Talk lease expiry, release, and simultaneous-claim tie-break behavior.

Instrumented/device tests must verify:

- Runtime microphone permission on API 32 and API 33.
- Hardware security-level reporting on both devices.
- Opus capture and playback on both devices.
- Connection and reconnect using EMQX public and Mosquitto public presets.
- Default-value interoperability: both devices launch with the same broker, channel, and key and can communicate without editing fields.
- Wrong-key silence and visible busy-state behavior.

The MVP is accepted when both devices can connect with defaults, one can press and hold PTT, the other begins playback only after three batches, release stops transmission, the receiving device blocks its own PTT while the sender is active, and a mismatched key produces no audio.
