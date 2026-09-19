# MQTT Call

Foreground Android push-to-talk voice over MQTT.

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
adb -s 0612RD2394 install -r app/build/outputs/apk/debug/app-debug.apk
adb -s 7f8a1522 install -r app/build/outputs/apk/debug/app-debug.apk
```

The acceptance devices are a Hytera PNC460 running Android API 32 and a Xiaomi 2201117TG running Android API 33.
