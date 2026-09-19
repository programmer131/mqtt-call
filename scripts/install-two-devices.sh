#!/usr/bin/env bash
set -euo pipefail

ANDROID_SDK_ROOT="${ANDROID_SDK_ROOT:-/home/far/Android/Sdk}"
export ANDROID_SDK_ROOT

./gradlew :app:assembleDebug

apk="app/build/outputs/apk/debug/app-debug.apk"
for serial in 0612RD2394 7f8a1522; do
    adb -s "$serial" install -r "$apk"
    adb -s "$serial" shell am force-stop com.far.mqttcall
    adb -s "$serial" shell am start -n com.far.mqttcall/.MainActivity >/dev/null
done

echo "Installed and launched com.far.mqttcall on both requested serials."
