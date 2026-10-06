#!/usr/bin/env bash
# Usage: start a workout, then run for ~60 s. Prints p50/p95/max end-to-end latency.
# PASS needs >= 30 distinct samples, p95 <= 1000 ms and max <= 2000 ms. Exit 0 PASS, 1 FAIL, 2 INCONCLUSIVE.
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
if not lat: print('INCONCLUSIVE (no samples)'); sys.exit(2)
p=lambda q: lat[min(len(lat)-1,int(q*len(lat)))]
verdict = 'INCONCLUSIVE (need n >= 30 distinct samples)' if len(lat)<30 else ('PASS' if p(.95)<=1000 and lat[-1]<=2000 else 'FAIL (target p95 <= 1000 ms and max <= 2000 ms)')
print(f'n={len(lat)} p50={p(.5)}ms p95={p(.95)}ms max={lat[-1]}ms', verdict)
sys.exit(2 if verdict.startswith('INCONCLUSIVE') else 0 if verdict=='PASS' else 1)
" "$WOFF" "$GOFF"
