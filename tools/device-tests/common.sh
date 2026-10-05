#!/usr/bin/env bash
# Shared helpers: device serials and clock offsets (ms) relative to this Mac.
set -euo pipefail
PHONE=${PHONE:-$(adb devices -l | awk '/model:SM_S93/{print $1; exit}')}
WATCH=${WATCH:-$(adb devices -l | awk '/model:SM_R9/{print $1; exit}')}
GLASSES=${GLASSES:-$(adb devices -l | awk '/model:RG_glasses/{print $1; exit}')}

now_ms() { python3 -c 'import time; print(int(time.time()*1000))'; }
# Device epoch ms minus host epoch ms (round-trip corrected).
offset_ms() {
  local s=$1 t0 dev t1
  t0=$(now_ms); dev=$(adb -s "$s" shell 'date +%s%3N' | tr -d '\r'); t1=$(now_ms)
  echo $(( dev - (t0 + t1) / 2 ))
}
logcat_clear() { for s in "$PHONE" "$WATCH" "$GLASSES"; do adb -s "$s" logcat -c; done; }
