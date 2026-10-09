#!/usr/bin/env bash
# Usage: during a GPS workout with the glasses on the Map page, run for N s (default 60).
# Checks spec §2.5 on the phone's LiveFitMap log: PNG <= 40 KB, never more than 1/s, at least every ~3 s.
# Exit 0 PASS, 1 FAIL, 2 INCONCLUSIVE.
source "$(dirname "$0")/common.sh"
require PHONE
adb -s "$PHONE" logcat -c; sleep "${1:-60}"
adb -s "$PHONE" logcat -d -v epoch -s LiveFitMap | python3 -c "
import re,sys
pts=[(float(m.group(1)),int(m.group(2)),int(m.group(3))) for m in re.finditer(r'^\s*(\d+\.\d+).*sent seq=(\d+) bytes=(\d+)', sys.stdin.read(), re.M)]
if len(pts)<5: print('INCONCLUSIVE (fewer than 5 images: is the Map page visible?)'); sys.exit(2)
gaps=[b[0]-a[0] for a,b in zip(pts,pts[1:])]
big=max(p[2] for p in pts)
ok=min(gaps)>=0.95 and max(gaps)<=3.6 and big<=40960
print(f'n={len(pts)} gap min={min(gaps):.2f}s max={max(gaps):.2f}s png max={big} B', 'PASS' if ok else 'FAIL (need gaps 1..3.6 s and PNG <= 40 KB)')
sys.exit(0 if ok else 1)
"
