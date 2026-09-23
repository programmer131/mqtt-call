# MQTT Call

Foreground Android push-to-talk voice over MQTT.

[![GitHub stars](https://img.shields.io/github/stars/programmer131/mqtt-call?style=flat-square)](https://github.com/programmer131/mqtt-call/stargazers) [Stars page](https://programmer131.github.io/mqtt-call/stars.html)

## Support me

If MQTT Call is useful to you, you can support development by scanning the payment QR code below.

<p align="center">
  <img src="docs/paymentQR.jpeg" alt="Payment QR code" width="280">
</p>

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

## Linux listener

The Linux companion can listen to the same encrypted channel as the Android app. The Python version keeps the browser-based listener available for interactive use:

```bash
sudo apt install python3-venv libopus0 alsa-utils
./linux/run.sh
```

Choose a broker or enter a custom one, then press **Connect & listen**. It needs an active ALSA playback device. The Python virtual environment and dependencies are installed on first launch.

### Headless C listener

For a background Linux machine with no UI, use the C receiver. It reads the broker, channel, key, and ALSA device from `linux/listener.conf` and only plays received voice; it has no PTT and never publishes.

```bash
sudo ./linux/install-c-deps.sh
cp linux/listener.conf.example linux/listener.conf
chmod 600 linux/listener.conf
make -C linux/c
./linux/c/mqtt-call-listener linux/listener.conf
```

The C listener waits for three 200 ms batches before playback. It closes and releases the ALSA speaker as soon as the queued audio drains or an underrun occurs. `linux/mqtt-call-listener.service.example` shows how to run it under systemd.

The current device run has verified Opus encode/decode and microphone capability on the PNC460. The Xiaomi must be online in ADB before the two-device script can install and exercise both sides. The PNC460 management overlay can prevent visual Compose assertions while the app window is being tested; the device state contract and audio instrumentation remain runnable.
