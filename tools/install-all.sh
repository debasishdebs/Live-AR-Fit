#!/usr/bin/env bash
# Builds and installs phone, watch and glasses APKs from the same commit (protocol versions must match).
set -euo pipefail
# Wireless-adb serials can contain spaces ("adb-XYZ (2)._adb-tls-connect._tcp"): take everything before " device ".
serial_for() { adb devices -l | sed -nE "/model:$1/{s/[[:space:]]+device[[:space:]].*//p;q;}"; }
cd "$(dirname "$0")/.."
export JAVA_HOME=$(/usr/libexec/java_home -v 17)
./gradlew :phone:assembleDebug :watch:assembleDebug :glasses:assembleDebug -q

PHONE=${PHONE:-$(serial_for SM_S93)}
WATCH=${WATCH:-$(serial_for SM_R9)}
GLASSES=${GLASSES:-$(serial_for RG_glasses)}

for pair in "phone:$PHONE" "watch:$WATCH" "glasses:$GLASSES"; do
  name=${pair%%:*}; serial=${pair#*:}
  if [ -z "$serial" ]; then echo "SKIP $name: not connected"; continue; fi
  extra=""; [ "$name" = "phone" ] && extra="--user 0"
  adb -s "$serial" install -r $extra "$name/build/outputs/apk/debug/$name-debug.apk" >/dev/null && echo "OK   $name ($serial)"
  if [ "$name" = "glasses" ]; then adb -s "$serial" shell pm grant com.debasish.livefit.glasses android.permission.RECORD_AUDIO
    adb -s "$serial" shell pm grant com.debasish.livefit.glasses android.permission.BLUETOOTH_ADVERTISE || true; fi
done
