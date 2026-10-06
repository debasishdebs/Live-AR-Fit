#!/usr/bin/env bash
# Usage: during an active workout. Drops phone Bluetooth for $1 s (default 60) and checks recovery.
source "$(dirname "$0")/common.sh"
require PHONE WATCH
logcat_clear
trap 'adb -s "$PHONE" shell cmd bluetooth_manager enable >/dev/null 2>&1 || true' EXIT
echo "Disabling phone Bluetooth for ${1:-60}s - keep moving; pause+resume on the watch once."
adb -s "$PHONE" shell cmd bluetooth_manager disable; sleep "${1:-60}"; adb -s "$PHONE" shell cmd bluetooth_manager enable
sleep 30
# The active session's directory (header + checkpoint) is intentionally retained; only unacked delta files (d-<seq>.json) must be gone.
echo -n "Watch unacknowledged delta files (expect 0 after sync): "
if n=$(adb -s "$WATCH" shell "run-as com.debasish.livefit sh -c 'ls files/lf-buffer/*/ 2>/dev/null'; echo rc=\$?" | tr -d '\r'); then
  echo "$n" | grep -Ec '^d-[0-9]+\.json$' || true
else echo unknown; fi
echo "Check the phone now shows continuous time/HR and no 'Syncing' banner."
