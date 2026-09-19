# MQTT Call

Foreground Android push-to-talk voice over MQTT.

[![GitHub stars](https://img.shields.io/github/stars/programmer131/mqtt-call?style=flat-square)](https://github.com/programmer131/mqtt-call/stargazers) [Stars page](https://programmer131.github.io/mqtt-call/stars.html)

The first demo uses these shared defaults on every install:

- Broker: `broker.emqx.io:1883`
- Channel: `3344`
- Key: `PTT-DEMO-3344`

Public brokers are for testing only. Topics and traffic metadata are public, and availability is not guaranteed; audio packets are encrypted by the app.

## Build

```bash
export ANDROID_SDK_ROOT=/home/far/Android/Sdk
./gradlew :app:assembleDebug
```

## Install on the connected test devices

```bash
./scripts/install-two-devices.sh
```

The acceptance devices are a Hytera PNC460 running Android API 32 and a Xiaomi 2201117TG running Android API 33.

## Diagnostics

```bash
./scripts/capture-two-device-logs.sh
```

The log script keeps only MQTT, audio, crypto, and talk-floor diagnostics and redacts common key and packet fields. The public broker presets are useful for interoperability checks; they do not provide privacy or uptime guarantees. Use a broker you control and a unique key for real use.

The current device run has verified Opus encode/decode and microphone capability on the PNC460. The Xiaomi must be online in ADB before the two-device script can install and exercise both sides. The PNC460 management overlay can prevent visual Compose assertions while the app window is being tested; the device state contract and audio instrumentation remain runnable.
