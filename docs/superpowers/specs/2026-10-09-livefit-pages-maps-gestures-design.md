# Rokid LiveFit — Pages, live map & configurable gestures (design)

- **Date:** 2026-10-09
- **Status:** Draft for owner review
- **Extends:** [V1 design](2026-10-05-livefit-v1-design.md) (glasses HUD §6.3, voice §5.4, settings §6.1, sync §4)
- **Branch:** `design/livefit-v1-v2`

## 1. Intent

During a workout the owner wants the same set of pages on the **Rokid glasses and the Galaxy Watch**, including a **live map** of the route and a **music player** page, with every page and every touchpad gesture controllable from the phone app.

Success criteria:
- On an outdoor Walk/Run/Cycle the Map page shows the current position and the route over a street map on the glasses and the watch, updating within ~3 s.
- Enabling/disabling a page in the phone app changes the page set on both glasses and watch within ~1 s.
- On the glasses every touchpad gesture's action is configurable per page/mode from the phone; defaults match §4.
- Workout recording is unaffected when the map has no network (route-only fallback).

Out of scope: turn-by-turn navigation, survey-grade position accuracy (the glasses map is **for reference**; improving position accuracy — e.g. smoothing/map-matching — is a future item), route planning, offline map packs, map on the phone's live Workout screen (history detail may show the route, §2.4).

## 2. GPS and maps

### 2.1 Position source (`LocationSource`, phone hub)
- **Watch first.** During GPS workouts (Run, Cycle, Auto, and Walk when "GPS outdoors" is on) the watch enables Health Services `LOCATION`. Each fix `{lat, lon, accuracyM, bearingDeg?, fixTimeMs}` (fix time from the data point, converted with the update's boot-time base) is carried in the watch's session deltas (ordered, acknowledged, offline-buffered), so the track survives disconnects and replays.
- **Clock calibration (independent of delivery delay).** On every watch connect/reconnect and every 5 min, the phone runs a time-sync ping over the Data Layer: phone sends `t0`, watch replies with its clock `tw`, phone receives at `t1`; offset = `tw − (t0 + t1)/2`, accepted only if round-trip `t1 − t0 ≤ 1 s` (else retried, up to 5 tries). The offset from delta arrival times is **not** used for freshness. Until a sync has succeeded in the current phone process, watch fixes are recorded for history but are **not live**: they cannot move the marker or stop the phone fallback.
- **Usable live fix.** A fix (watch or phone) is usable-live if, with calibrated time, `−2 s ≤ age ≤ 10 s` (2 s explicit clock tolerance; anything further in the future is not live) **and** `accuracyM ≤ 30`. Only usable-live fixes move the current-position marker and drive source switching. (The 3 m thinning in §2.2 applies to the drawn route only; an accurate stationary fix still counts as usable-live.) Health Services may batch location while the watch screen is off (the V1 5 s override covers HR only); the watch requests any location batching override in `supportedBatchingModeOverrides` and logs the list.
- **Degraded display.** No usable-live fix for 10–30 s → position arrow drawn hollow + "GPS delayed"; > 30 s → "GPS lost", last point kept. The route still grows when (batched/replayed) fixes arrive, in time order (§2.2).
- **Phone fallback.** Starts when no **usable-live** watch fix for **15 s** during a GPS workout; stops when watch fixes have been usable-live continuously for **10 s** (replayed, batched, inaccurate or uncalibrated fixes never count, so replay after a reconnect or a burst of poor fixes can't flip the source). While both are usable-live, watch fixes win.
- **Fallback benefits glasses and history only.** The watch map always uses the watch's own fixes (§2.6).
- **Foreground-service requirements.**
  - Watch: the exercise service runs with FGS type **`health|location`** during GPS workouts (declare both; start with `location` only when `ACCESS_FINE_LOCATION` is granted, else `health` and GPS off — never crash on denial).
  - Phone: hub manifest adds type `location`. Because a hub started from the background (presence/boot/update) cannot hold location, the hub **re-promotes itself** (`startForeground(..., CONNECTED_DEVICE | LOCATION)`) whenever LiveFit's Activity becomes visible and fine location is granted; until then the fallback is unavailable and Linked services shows "Phone GPS available after opening LiveFit".
  - Phone `ACCESS_FINE_LOCATION` is optional (setup step "Map fallback"); denial keeps the hub starting normally with type `connectedDevice` only.
### 2.2 Route (merge and storage)
- `RouteTrack` (pure, `:core:model`) keeps points **sorted by `fixTimeMs`**, not by arrival. Inserting an older replayed watch fix places it chronologically.
- **Filtering:** drop `accuracyM > 30`; drop a point < 3 m from its chronological neighbour of the same source.
- **Duplicates:** same source + same `fixTimeMs` (±50 ms) + same position (±1 m) → ignored (replay/resend safe).
- **Overlap/precedence:** within any time window where both sources have points, **watch points win**: phone points within ±5 s of a watch point are hidden from the drawn route (kept in storage with `source = Phone` for diagnostics).
- **Clock tolerance:** watch fix times are mapped to phone time with the hub's watch-clock offset; points more than 2 min in the future are rejected; ordering ties break watch-first.
- **Storage:** Room `route_point(sessionId, fixTimeMs, source, lat, lon, accuracyM)` with a unique key `(sessionId, source, fixTimeMs)`; history detail shows a static route thumbnail.
- **Bearing/current point:** the latest **live** point (§2.1); bearing from the fix or from the last two kept points.

### 2.3 Shared map math (`:core:map`, pure Kotlin)
- Web-Mercator slippy-tile math (lat/lon ↔ tile x/y/z ↔ pixel), viewport centred on the current position, north-up, default **zoom 18 (~270 m across 480 px at latitude 20°; ~1.08 km at zoom 16)**, zoom 17 for Cycle.
- Route projection to viewport pixels; decimation for drawing.
- Used by the phone glasses renderer and the watch map so both look identical.

### 2.4 Tiles
- Source: OpenStreetMap standard raster tiles (`tile.openstreetmap.org/{z}/{x}/{y}.png`), HTTPS, app-specific User-Agent, **attribution "© OpenStreetMap contributors" always visible**, disk cache (LRU 50 MB phone, 20 MB watch, 7-day max-age honoured), no prefetch beyond the visible 3×3 tiles. Provider URL is a constant behind a `TileSource` interface so it can be swapped.
- Phone: tiles fetched over mobile data. Watch: fetched over the watch's own connection (phone proxy, Wi-Fi or LTE).

### 2.5 Glasses map (phone renders → glasses display)
- Glasses have no internet. The phone composes a **480×480** image: tiles converted to the HUD palette (black background, streets as dim green luminance, water/park dropped), plus route line (bright), position arrow, start marker, scale bar and attribution; encoded PNG (target ≤ 40 KB).
- Sent on new channel `lf_map` (binary stream payload) **only while the glasses report the Map page visible**. The glasses send `lf_page_state{page, seq}` on every page change **and on every connect/reconnect** (so a restarted phone learns the current page without a page change); the phone **clears** visibility on disconnect. Each image carries `{sessionId, renderEpoch, renderSeq}`. The phone picks a new random `renderEpoch` at process start and on every glasses (re)connect, and announces it first on `lf_map` (epoch header with no image) before sending images. The glasses accept the newest announced epoch, reset their last-seen `renderSeq` for it, and drop images from any older epoch, another session, or an older `renderSeq` within the epoch. Cadence: every 3 s or after ≥ 25 m movement, whichever first; never more than 1/s.
- No tiles (offline/failure): route-only rendering on black with "No map — route only".
- When not on the Map page nothing image-related is sent.

### 2.6 Watch map
- Native Compose map on the watch: tiles via the watch's network + cache, its own route from its own fixes (no round-trip to the phone).
- **Route persistence on the watch:** the full-session route is kept in its own file (`route.bin`, append-only, per session) independent of delta resend retention — acknowledged deltas may be deleted but the route stays, and is reloaded after watch process death (route and start marker survive). Deleted when the session is finalized on the phone and the watch summary is dismissed. Rotary bezel = zoom (14–18), always re-centres on the current position. Offline: route-only on black.

## 3. Pages

### 3.1 One page set for both surfaces
Order (cycling): **Glance → Workout → Stats → Playlist → Map → Music controls**. Same set on glasses and watch.

| Page | Content | Can be disabled |
|---|---|---|
| Glance | timer + HR, large, low in view | yes |
| Workout | current full workout HUD / watch Heart page | **no** |
| Stats | steps, distance, speed, calories, avg/max HR | yes |
| Playlist | YTM queue window (existing) | yes |
| Map | §2.5 / §2.6 | yes |
| Music controls | title/artist, progress, ⏮ ⏯ ⏭, volume bar | yes |

- The Map page is **skipped** when no GPS workout is active.
- Watch gains Glance and Playlist; glasses gain Stats, Map, Music controls.
- Workout start lands on Workout (existing rule); voice "<page> view" jumps there (§5).

### 3.2 Settings → Pages
Generic list screen with one switch per page and a subtitle "Glasses + Watch". Stored on the phone; sent in the settings frame to glasses (existing `lf_settings`) and to the watch (settings message on the Data Layer) on change and on every (re)connect.

### 3.3 Page transitions
- If the visible page becomes unavailable (disabled in Settings, or Map ineligible because the GPS workout ended) the device **switches to Workout immediately**.
- Any actual page change (swipe, voice, fallback) **clears Scroll mode and any highlight/selector**.
- Scroll mode **stays** after Play highlighted / Press selected (so you can keep choosing); the idle timer **restarts** on every gesture handled in Scroll mode (tap, short or long swipe); confirmations pause it.

## 4. Glasses touchpad

### 4.1 Available gestures (measured on device, see V1 §6.3)
Tap, double tap, short swipe forward/back, long swipe forward/back. (Long press is reserved by the system for "Hi Rokid"; vertical swipes are not sensed.)

### 4.2 Modes
- **Page mode** (default on every page).
- **Scroll mode** (Playlist, Music controls only): entered by the page's "enter scroll" action; left by its "exit scroll" action, by selecting the in-page **✕ Back** item, or after the **idle timeout** (default 5 s, 3–15 s).
- Cross-device confirmation overlays and the close-app confirm take priority over both modes (existing rules: swipe moves Yes/No, tap confirms, double tap = No).

### 4.3 Defaults
| Context | Tap | Double tap | Short swipe fwd/back | Long swipe fwd/back |
|---|---|---|---|---|
| Page mode — Glance, Workout, Stats, Map | Talk | Close app (asks during workout) | Next / previous page | ±2 pages |
| Page mode — Playlist, Music controls | Enter scroll mode | Close app | Next / previous page | ±2 pages |
| Scroll — Playlist | Play highlighted (play/pause if current) | Close app | Highlight ±1 row | Highlight ±2 rows |
| Scroll — Music controls | Press selected control | Close app | Selector next / previous control (⏮ ⏯ ⏭ ✕) | Volume up / down |

### 4.4 Configurable mapping (all gestures, all contexts)
Settings → **Glasses gestures** (hierarchical):
- **Page mode** → per page (Glance, Workout, Stats, Playlist, Map, Music controls) → per gesture (Tap, Double tap, Short swipe forward, Short swipe back, Long swipe forward, Long swipe back) → action picker.
- **Scroll mode** → Playlist / Music controls → per gesture → action picker.
- **General**: idle timeout (3–15 s), "Ask before closing during a workout" (on/off), reset to defaults.

Action catalogue (only actions valid for the context are offered):
`None, Talk (voice), Next page, Previous page, +2 pages, −2 pages, Close app, Enter scroll mode, Exit scroll mode, Highlight next/prev row, Highlight ±2 rows, Play highlighted, Selector next/prev, Press selected, Play/pause, Next song, Previous song, Volume up, Volume down, Like song`.

Safety rules enforced by the phone UI and the glasses:
- **For every page separately** (including currently disabled pages), the page-mode mapping must contain at least one gesture → **Close app** and one → **Next page** (or Previous page); the phone refuses a change that breaks this for any page, and the glasses validate the received table per page and fall back to defaults for any page that fails.
- In scroll mode, if no gesture maps to Exit scroll, the ✕ Back item and idle timeout still exit.
- Confirmation overlays are not configurable.

The full mapping is sent in the glasses settings frame (compact table); the glasses `HudNav` resolves gesture → action from it (pure, JVM-tested).

### 4.5 Watch
Touchscreen + bezel, native behaviour: horizontal swipe between enabled pages, tap buttons, bezel scrolls lists / zooms the map / changes volume on Music controls. Gesture settings in §4.4 apply to the glasses only.

## 5. Voice
- New: "map view", "music view"/"player view"/"music controls" → Music controls; "stats view" → Stats. "playlist view" stays Playlist (the earlier "music view → Playlist" alias moves to Music controls).
- Disabled page → toast "<Page> page is turned off in Settings". Voice page commands move the glasses only.

## 6. Protocol
- `protocolVersion` → **4** (coordinated upgrade, §4.7): new `lf_map` (phone→glasses image), `lf_page_state` (glasses→phone visible page), settings frame gains `pages` + `gestures`; watch settings message gains `pages`; watch deltas gain location fixes; **watch Playlist:** the phone sends the queue window (`QueueFrame`: items with `queueId`, title, artist, current `queueId`) to the watch on change and on every reconnect; watch selection sends the existing `PlayQueueItem(queueId)` command (play/pause when it's the current item); an unavailable/empty queue shows an explicit empty state ("Nothing queued — start music on the phone"). All three APKs updated together (`tools/install-all.sh`).

## 7. Error handling
| Situation | Behaviour |
|---|---|
| No GPS fix yet | Map page shows "Waiting for GPS…" with the last known point if any |
| Watch GPS stops, phone fallback unavailable | Map shows last point + "GPS lost"; workout continues |
| Tiles fail / offline | Route-only rendering, "No map — route only" |
| Glasses image send fails | Next cadence retries; never blocks state frames |
| Location permission denied on phone | Fallback disabled; Linked services shows "Phone GPS off" |
| Invalid gesture mapping | Phone refuses with reason; glasses keep the last valid mapping |

## 8. Testing
- JVM: `RouteTrack` filtering/bearing, tile math + viewport/projection, palette conversion (luminance mapping) on a sample tile, page set + skip rules, gesture resolution for every default + custom mapping + safety rules, fallback switching timer, voice phrases, protocol v4 serialization.
- JVM additions: time-sync offset (RTT bound, retries, uncalibrated → not live), freshness window incl. future-dated fix (+2 s tolerance), usable-live accuracy gate (accurate phone fallback vs 10 s of inaccurate watch fixes keeps phone), stationary accurate fixes stay live, render epoch acceptance/rejection, chronological merge with replayed watch fixes behind phone points, duplicate suppression, source switching ignores non-live fixes, per-page gesture safety validation, page fallback on disable/ineligible, scroll-mode timer reset rules, stale image rejection.
- Device: delayed first watch replay after a phone restart (must not look live); phone process restart while Map stays visible (new epoch images shown); outdoor walk **with the watch screen off and phone locked** (fix age, "GPS delayed" behaviour); reconnect while Map is visible without changing pages; acknowledgement then watch process death (route + start marker survive); map on glasses (cadence, size, attribution) and watch (tiles, zoom); watch GPS off → phone fallback with LiveFit open; page toggles reflected on both; every default gesture on glasses; a custom mapping change applied live.
