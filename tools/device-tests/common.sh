#!/usr/bin/env bash
# Shared helpers: device serials and clock offsets (ms) relative to this Mac.
set -euo pipefail
# Wireless-adb serials can contain spaces ("adb-XYZ (2)._adb-tls-connect._tcp"): take everything before " device ".
serial_for() { adb devices -l | sed -nE "/model:$1/{s/[[:space:]]+device[[:space:]].*//p;q;}"; }
PHONE=${PHONE:-$(serial_for SM_S93)}
WATCH=${WATCH:-$(serial_for SM_R9)}
GLASSES=${GLASSES:-$(serial_for RG_glasses)}

now_ms() { python3 -c 'import time; print(int(time.time()*1000))'; }
# Fails (exit 2) unless every named variable (PHONE, WATCH, GLASSES) holds a connected device serial.
require() {
  local v
  for v in "$@"; do
    if [ -z "${!v:-}" ]; then echo "error: $v device not connected (see 'adb devices -l')" >&2; exit 2; fi
  done
}
# Device epoch ms minus host epoch ms (round-trip corrected).
offset_ms() {
  local s=$1 t0 dev t1
  t0=$(now_ms); dev=$(adb -s "$s" shell 'date +%s%3N' | tr -d '\r')
  case "$dev" in ''|*[!0-9]*|*N) dev=$(adb -s "$s" shell 'date +%s%N' | tr -d '\r' | cut -c1-13) ;; esac
  [[ "$dev" =~ ^[0-9]{13}$ ]] || { echo "error: bad device clock '$dev' from $s" >&2; exit 2; }
  t1=$(now_ms)
  echo $(( dev - (t0 + t1) / 2 ))
}
logcat_clear() { for s in "$PHONE" "$WATCH" "$GLASSES"; do [ -n "$s" ] && adb -s "$s" logcat -c; done; return 0; }
