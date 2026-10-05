#!/usr/bin/env bash
source "$(dirname "$0")/common.sh"
step() { echo; echo ">> $1"; read -r -p "  press Enter when done... "; }
logcat_clear
step "Start a Walk from the WATCH. Phone + glasses must show it within 2 s."
step "Pause from the GLASSES by voice ('pause workout'). Watch + phone must show paused."
step "Resume from the PHONE. Watch + glasses must resume."
step "Next song from the WATCH music page. Phone + glasses show the new title."
step "Stop from the PHONE. All show 'Saving...' then Summary."
adb -s "$PHONE" logcat -d | grep -cE "LiveFitWatchLink|LiveFitGlassesLink" | xargs echo "link log lines:"
