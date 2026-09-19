#!/usr/bin/env bash
set -euo pipefail

output_dir="${1:-build/two-device-logs}"
mkdir -p "$output_dir"

for serial in 0612RD2394 7f8a1522; do
    output_file="$output_dir/$serial.log"
    timeout 15s adb -s "$serial" logcat -d -v threadtime \
        | grep -E 'MqttCall|Audio|Crypto|TalkFloor' \
        | sed -E 's/((key|passphrase|payload|plaintext|ciphertext|token)[=:])[^ ]+/\1<redacted>/Ig' \
        > "$output_file" || true
    echo "Captured filtered diagnostics for $serial: $output_file"
done
