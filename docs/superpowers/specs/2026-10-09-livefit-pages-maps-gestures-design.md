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

Out of scope: turn-by-turn navigation, route planning, offline map packs, map on the phone's live Workout screen (history detail may show the route, §2.4).

## 2. GPS and maps

### 2.1 Position source (`LocationSource`, phone hub)
- **Watch first.** During GPS workouts (Run, Cycle, Auto, and Walk when "GPS outdoors" is on) the watch enables Health Services `LOCATION`. Each fix `{lat, lon, accuracyM, bearingDeg?, tMs}` is carried in the watch's session deltas (same ordered, acknowledged, offline-buffered path as samples), so the track survives disconnects and replays.
- **Phone fallback.** If no watch fix arrives for **15 s** while a GPS workout is active, the phone uses fused location (`PRIORITY_HIGH_ACCURACY`, 1 s) until watch fixes resume for 5 s. Each point records `source = Watch | Phone`.
- **Constraint:** Android gives location to a foreground service only if it was started while the app was visible; the hub may have been started in the background (presence/boot). The phone fallback therefore works only when the hub currently holds a location-capable FGS start (LiveFit opened on the phone during the workout, or the workout started from the phone UI). Otherwise the fallback reports "Phone GPS unavailable — open LiveFit" in Linked services and the map shows the last watch point.
- **Permissions:** phone `ACCESS_FINE_LOCATION` (setup step "Map fallback", optional) and FGS type `location` added to the hub; watch already has location.

### 2.2 Route
- `RouteTrack` (pure, `:core:model`): appends a fix only if `accuracyM ≤ 30` and it is ≥ 3 m from the last kept point; keeps start point, current point and bearing (from fix or last two points).
- Stored with the session (Room table `route_point(sessionId, idx, lat, lon, accuracyM, source, tMs)`); history detail shows a static route thumbnail (§2.4).

### 2.3 Shared map math (`:core:map`, pure Kotlin)
- Web-Mercator slippy-tile math (lat/lon ↔ tile x/y/z ↔ pixel), viewport centred on the current position, north-up, default zoom 16 (~300 m across 480 px).
- Route projection to viewport pixels; decimation for drawing.
- Used by the phone glasses renderer and the watch map so both look identical.

### 2.4 Tiles
- Source: OpenStreetMap standard raster tiles (`tile.openstreetmap.org/{z}/{x}/{y}.png`), HTTPS, app-specific User-Agent, **attribution "© OpenStreetMap contributors" always visible**, disk cache (LRU 50 MB phone, 20 MB watch, 7-day max-age honoured), no prefetch beyond the visible 3×3 tiles. Provider URL is a constant behind a `TileSource` interface so it can be swapped.
- Phone: tiles fetched over mobile data. Watch: fetched over the watch's own connection (phone proxy, Wi-Fi or LTE).

### 2.5 Glasses map (phone renders → glasses display)
- Glasses have no internet. The phone composes a **480×480** image: tiles converted to the HUD palette (black background, streets as dim green luminance, water/park dropped), plus route line (bright), position arrow, start marker, scale bar and attribution; encoded PNG (target ≤ 40 KB).
- Sent on new channel `lf_map` (binary stream payload) **only while the glasses report the Map page visible** (`lf_page_state` glasses→phone on every page change). Cadence: every 3 s or after ≥ 25 m movement, whichever first; never more than 1/s.
- No tiles (offline/failure): route-only rendering on black with "No map — route only".
- When not on the Map page nothing image-related is sent.

### 2.6 Watch map
- Native Compose map on the watch: tiles via the watch's network + cache, its own route from its own fixes (no round-trip to the phone). Rotary bezel = zoom (14–18), always re-centres on the current position. Offline: route-only on black.

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
- Some gesture in page mode must map to **Close app** and some to **Next page** (otherwise the screen refuses the change with a message).
- In scroll mode, if no gesture maps to Exit scroll, the ✕ Back item and idle timeout still exit.
- Confirmation overlays are not configurable.

The full mapping is sent in the glasses settings frame (compact table); the glasses `HudNav` resolves gesture → action from it (pure, JVM-tested).

### 4.5 Watch
Touchscreen + bezel, native behaviour: horizontal swipe between enabled pages, tap buttons, bezel scrolls lists / zooms the map / changes volume on Music controls. Gesture settings in §4.4 apply to the glasses only.

## 5. Voice
- New: "map view", "music view"/"player view"/"music controls" → Music controls; "stats view" → Stats. "playlist view" stays Playlist (the earlier "music view → Playlist" alias moves to Music controls).
- Disabled page → toast "<Page> page is turned off in Settings". Voice page commands move the glasses only.

## 6. Protocol
- `protocolVersion` → **4** (coordinated upgrade, §4.7): new `lf_map` (phone→glasses image), `lf_page_state` (glasses→phone visible page), settings frame gains `pages` + `gestures`; watch settings message gains `pages`; watch deltas gain location fixes. All three APKs updated together (`tools/install-all.sh`).

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
- Device: outdoor walk — map on glasses (cadence, size, attribution) and watch (tiles, zoom); watch GPS off → phone fallback with LiveFit open; page toggles reflected on both; every default gesture on glasses; a custom mapping change applied live.
