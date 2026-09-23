#!/usr/bin/env bash
set -euo pipefail
HERE="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
CONFIG="${1:-$HERE/listener.conf}"
if [[ ! -x "$HERE/c/mqtt-call-listener" ]]; then make -C "$HERE/c"; fi
exec "$HERE/c/mqtt-call-listener" "$CONFIG"
