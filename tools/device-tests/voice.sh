#!/usr/bin/env bash
source "$(dirname "$0")/common.sh"
step() { echo; echo ">> $1"; read -r -p "  press Enter when done... "; }
logcat_clear
step "Put the PHONE in airplane mode (Bluetooth stays on)."
step "On the GLASSES tap to talk and say 'next song'. It must work offline."
step "Remove the en-IN on-device voice pack, tap to talk again. The glasses must say 'Voice needs ... pack'."
adb -s "$PHONE" logcat -d | grep -E "LiveFitWatchLink|LiveFitGlassesLink|LiveFitVoice" | tail -20
