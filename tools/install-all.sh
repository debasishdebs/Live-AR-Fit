#!/usr/bin/env bash
# Builds and installs phone, watch and glasses APKs from the same commit (protocol versions must match).
set -euo pipefail
# Wireless-adb serials can contain spaces ("adb-XYZ (2)._adb-tls-connect._tcp"): take everything before " device ".
serial_for() { adb devices -l | sed -nE "/model:$1/{s/[[:space:]]+device[[:space:]].*//p;q;}"; }
# Any-brand watch/phone: classify by ro.build.characteristics (contains "watch" => watch); glasses = model RG_glasses.
all_serials() { adb devices -l | sed -nE '/[[:space:]]device[[:space:]]/{s/[[:space:]]+device[[:space:]].*//p;}'; }
is_watch() { adb -s "$1" shell getprop ro.build.characteristics </dev/null 2>/dev/null | tr -d '\r' | grep -qi watch; }
first_watch() { local s; while IFS= read -r s; do [ -n "$s" ] && is_watch "$s" && { echo "$s"; return; }; done < <(all_serials); return 0; }
first_phone() { local s g; g=$(serial_for RG_glasses); while IFS= read -r s; do [ -n "$s" ] && [ "$s" != "$g" ] && ! is_watch "$s" && { echo "$s"; return; }; done < <(all_serials); return 0; }
cd "$(dirname "$0")/.."
export JAVA_HOME=$(/usr/libexec/java_home -v 17)
./gradlew :phone:assembleDebug :watch:assembleDebug :glasses:assembleDebug -q

PHONE=${PHONE:-$(first_phone)}
WATCH=${WATCH:-$(first_watch)}
GLASSES=${GLASSES:-$(serial_for RG_glasses)}

# Phone last: its hub reconnects to the glasses on update, so the glasses app must already be replaced.
for pair in "glasses:$GLASSES" "watch:$WATCH" "phone:$PHONE"; do
  name=${pair%%:*}; serial=${pair#*:}
  if [ -z "$serial" ]; then echo "SKIP $name: not connected"; continue; fi
  extra=""; [ "$name" = "phone" ] && extra="--user 0"
  adb -s "$serial" install -r $extra "$name/build/outputs/apk/debug/$name-debug.apk" >/dev/null && echo "OK   $name ($serial)"
  if [ "$name" = "glasses" ]; then adb -s "$serial" shell pm grant com.debasish.livefit.glasses android.permission.RECORD_AUDIO
    adb -s "$serial" shell pm grant com.debasish.livefit.glasses android.permission.BLUETOOTH_ADVERTISE || true; fi
done
