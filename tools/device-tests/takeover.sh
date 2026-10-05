#!/usr/bin/env bash
source "$(dirname "$0")/common.sh"
step() { echo; echo ">> $1"; read -r -p "  press Enter when done... "; }
logcat_clear
step "Start a workout on the PHONE, then start a different exercise on the WATCH (takeover). A confirmation prompt must appear on phone, watch and glasses."
step "Answer Yes on any ONE device. The first answer wins; the others dismiss."
step "Trigger a second takeover and stay silent for 15 s. It must end as Cancelled."
for s in "$PHONE" "$WATCH" "$GLASSES"; do
  [ -n "$s" ] && adb -s "$s" logcat -d | grep -E "LiveFitWatchLink|LiveFitGlassesLink|LiveFitWatch|LiveFitGlasses" | tail -20
done
