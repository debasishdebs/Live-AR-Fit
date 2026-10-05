#!/usr/bin/env bash
# Usage: start a workout, then run for ~60 s. Prints p50/p95/max end-to-end latency (target <= 1000 ms).
source "$(dirname "$0")/common.sh"
require WATCH GLASSES
WOFF=$(offset_ms "$WATCH"); GOFF=$(offset_ms "$GLASSES")
adb -s "$GLASSES" logcat -c; sleep "${1:-60}"
adb -s "$GLASSES" logcat -d -s LiveFitLatency | python3 -c "
import re,sys
woff,goff=int(sys.argv[1]),int(sys.argv[2])
first={}
for m in re.finditer(r'sample=(\d+) render=(\d+)', sys.stdin.read()): first.setdefault(int(m.group(1)), int(m.group(2)))
lat=sorted(r-goff-(s-woff) for s,r in first.items())
if not lat: print('no samples'); sys.exit(1)
p=lambda q: lat[min(len(lat)-1,int(q*len(lat)))]
print(f'n={len(lat)} p50={p(.5)}ms p95={p(.95)}ms max={lat[-1]}ms', ('PASS' if p(.95)<=1000 else 'FAIL (target p95 <= 1000 ms)') if len(lat)>=30 else 'INCONCLUSIVE (need n >= 30 distinct samples)')
" "$WOFF" "$GOFF"
