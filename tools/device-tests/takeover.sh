#!/usr/bin/env bash
source "$(dirname "$0")/common.sh"
step() { echo; echo ">> $1"; read -r -p "  press Enter when done... "; }
require PHONE WATCH GLASSES
logcat_clear
step "On the WATCH start a Samsung Health workout FIRST, then start a workout in LiveFit (phone, watch or glasses). A \"Take over workout?\" prompt must appear on phone, watch and glasses."
step "Answer Yes on any ONE device. The first answer wins; the others dismiss."
step "Trigger a second takeover and stay silent for 15 s. It must end as Cancelled."
for s in "$PHONE" "$WATCH" "$GLASSES"; do
  adb -s "$s" logcat -d | grep -E "LiveFitWatchLink|LiveFitGlassesLink|LiveFitWatch|LiveFitGlasses" | tail -20 || true
done
