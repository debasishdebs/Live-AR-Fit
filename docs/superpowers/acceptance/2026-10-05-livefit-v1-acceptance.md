# LiveFit V1 acceptance (spec §1.3)

Run on: Galaxy S25 + Galaxy Watch6 Classic + Rokid Glasses, all installed via `tools/install-all.sh` from one commit.

Run log (2026-10-06): `adb devices` showed only the phone (wireless). The watch and glasses were not connected, and every check below needs at least one of them, so no check was executed. Nothing here is marked as passed. Helper scripts in `tools/device-tests/` are untested against hardware.

| # | Check | How | Pass | Result |
|---|---|---|---|---|
| 1 | Live watch data on glasses <= 1 s (p95) | 10-min walk, `tools/device-tests/latency.sh 600` | p95 <= 1000 ms | Not run (watch and glasses not connected) |
| 2 | Start/pause/resume/stop from each device | `tools/device-tests/sync.sh` | all three reflect each action | Not run (watch and glasses not connected) |
| 3 | Music play/pause/next/previous/like/volume from each device | phone Music screen, watch music page (bezel), glasses voice | YouTube Music reacts; all show state | Not run (watch and glasses not connected) |
| 4 | Session in Activity history | after #1 | listed, no Demo/Incomplete badge, HR chart shown | Not run (depends on #1) |
| 5 | Phone drop loses no data | `tools/device-tests/offline.sh 120` mid-workout | total active time = wall time - pauses; HR continuous; watch buffer empty | Not run (watch not connected) |
| 6 | Takeover asks first; any device answers | `tools/device-tests/takeover.sh` | prompt on all three; first answer wins; 15 s silence = Cancelled | Not run (watch and glasses not connected) |
| 7 | Voice offline only | airplane mode on phone, glasses tap -> "next song" | works; with pack removed: "Voice needs ... pack" | Not run (glasses not connected) |
| 8 | Coordinated upgrade guard | install an older glasses APK | phone shows "Update LiveFit on your glasses"; glasses commands ignored | Not run (glasses not connected) |
