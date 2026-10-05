#!/usr/bin/env bash
# Usage: during an active workout. Drops phone Bluetooth for $1 s (default 60) and checks recovery.
source "$(dirname "$0")/common.sh"
logcat_clear
echo "Disabling phone Bluetooth for ${1:-60}s - keep moving; pause+resume on the watch once."
adb -s "$PHONE" shell cmd bluetooth_manager disable; sleep "${1:-60}"; adb -s "$PHONE" shell cmd bluetooth_manager enable
sleep 30
echo "Watch sessions still buffered (expect 0 after sync):"; adb -s "$WATCH" shell "run-as com.debasish.livefit ls files/lf-buffer 2>/dev/null | wc -l"
echo "Check the phone now shows continuous time/HR and no 'Syncing' banner."
