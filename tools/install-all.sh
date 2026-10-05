#!/usr/bin/env bash
# Builds and installs phone, watch and glasses APKs from the same commit (protocol versions must match).
set -euo pipefail
cd "$(dirname "$0")/.."
export JAVA_HOME=$(/usr/libexec/java_home -v 17)
./gradlew :phone:assembleDebug :watch:assembleDebug :glasses:assembleDebug -q

PHONE=${PHONE:-$(adb devices -l | awk '/model:SM_S93/{print $1; exit}')}
WATCH=${WATCH:-$(adb devices -l | awk '/model:SM_R9/{print $1; exit}')}
GLASSES=${GLASSES:-$(adb devices -l | awk '/model:RG_glasses/{print $1; exit}')}

for pair in "phone:$PHONE" "watch:$WATCH" "glasses:$GLASSES"; do
  name=${pair%%:*}; serial=${pair#*:}
  if [ -z "$serial" ]; then echo "SKIP $name: not connected"; continue; fi
  extra=""; [ "$name" = "phone" ] && extra="--user 0"
  adb -s "$serial" install -r $extra "$name/build/outputs/apk/debug/$name-debug.apk" >/dev/null && echo "OK   $name ($serial)"
  if [ "$name" = "glasses" ]; then adb -s "$serial" shell pm grant com.debasish.livefit.glasses android.permission.RECORD_AUDIO; fi
done
