#!/usr/bin/env bash
# Shared helpers: device serials and clock offsets (ms) relative to this Mac.
set -euo pipefail
# Wireless-adb serials can contain spaces ("adb-XYZ (2)._adb-tls-connect._tcp"): take everything before " device ".
serial_for() { adb devices -l | sed -nE "/model:$1/{s/[[:space:]]+device[[:space:]].*//p;q;}"; }
# Any-brand watch/phone: classify by ro.build.characteristics (contains "watch" => watch); glasses = model RG_glasses.
all_serials() { adb devices -l | sed -nE '/[[:space:]]device[[:space:]]/{s/[[:space:]]+device[[:space:]].*//p;}'; }
is_watch() { adb -s "$1" shell getprop ro.build.characteristics </dev/null 2>/dev/null | tr -d '\r' | grep -qi watch; }
first_watch() { local s; while IFS= read -r s; do [ -n "$s" ] && is_watch "$s" && { echo "$s"; return; }; done < <(all_serials); return 0; }
first_phone() { local s g; g=$(serial_for RG_glasses); while IFS= read -r s; do [ -n "$s" ] && [ "$s" != "$g" ] && ! is_watch "$s" && { echo "$s"; return; }; done < <(all_serials); return 0; }
PHONE=${PHONE:-$(first_phone)}
WATCH=${WATCH:-$(first_watch)}
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
