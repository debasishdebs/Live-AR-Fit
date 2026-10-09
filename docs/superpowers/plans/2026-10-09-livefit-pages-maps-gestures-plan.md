# Rokid LiveFit — Pages, Live Map & Configurable Gestures Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** One shared page set (Glance → Workout → Stats → Playlist → Map → Music controls) on the Rokid glasses and the Galaxy Watch, a live street map of the route on both, and every glasses touchpad gesture configurable per page/mode from the phone — without ever disturbing workout recording.

**Architecture:** Pure-Kotlin foundations come first and are JVM-tested: protocol v4 types and page/gesture rules in `:core:model`, slippy-tile math, scene building, HUD palette and the tile cache/fetcher in a new pure `:core:map` module, and time-sync, freshness, source selection, route merging and the glasses map stream in `:services:sync`. The watch records Health Services location fixes into its session deltas and into a per-session `route.bin`, and draws its own map from its own fixes. The phone hub merges watch fixes (mapped to phone time with a calibrated clock offset) and fallback phone fixes into one `RouteTrack`, stores it in Room, renders a 480×480 HUD-palette PNG and streams it on `lf_map` only while the glasses report the Map page. The glasses resolve every gesture through the received table in a pure `HudNav`.

**Tech Stack:** Kotlin 2.0.21, AGP 8.13.2, Gradle 8.13, JDK 17, kotlinx.coroutines 1.9.0, kotlinx.serialization-json 1.7.3, Jetpack Compose (BOM 2024.09.00), Wear Compose 1.4.0, Room 2.6.1 (+KSP), Robolectric 4.13, Health Services 1.1.0-alpha05, Play Services Wearable 19.0.0, Rokid CXR-L `com.rokid.cxr:client-l:1.1.2` (phone), CXR-S `com.rokid.cxr:cxr-service-bridge:1.4` (glasses), `java.net.HttpURLConnection` for tiles (no new HTTP dependency), `android.location.LocationManager` for the phone fallback (no Play Services Location).

**Spec:** `docs/superpowers/specs/2026-10-09-livefit-pages-maps-gestures-design.md` (approved). Base spec: `docs/superpowers/specs/2026-10-05-livefit-v1-design.md` (§4 protocol, §5.4 voice, §6.1 settings, §6.3 glasses touchpad). Read both alongside this plan.

## Revision 2 (review 2026-10-09)

Addresses all 12 findings of `reviews/2026-10-09-pages-maps-plan-review.md` (plan commit 630b34b). Each finding → the task(s) whose code and failing-first tests changed:

| # | Finding | Resolution | Tasks |
|---|---|---|---|
| 1 | Route rows lost after ACK / failed write | `RoomSessionStore.storeDelta` writes the delta's route rows in the delta's transaction (before the ack); RouteHub retries its own writes from an unsaved queue (tick + next fix) and, on every session load, rebuilds rows missing from `route_point` out of the stored deltas; tombstoned sessions never get rows | 13, 14 (+18 wiring) |
| 2 | Pre-calibration fixes stored with raw watch time, never repaired | `route_point` identity = (sessionId, source, device time), `phoneTimeMs` nullable/updatable; uncalibrated rows stored with `null`, not ordered/drawn/future-checked; any calibration change re-maps all rows, rebuilds the track and runs `normalizeWatchTimes`; future rejection only on calibrated times | 3, 13, 14, 20 |
| 3 | A delayed batch manufactures a 10 s recovery run | Recovery run measured in observed (arrival) time; freshness/marker keep measurement time | 4 |
| 4 | Append after a torn tail misaligns route.bin | `append` truncates to the last whole record first | 10 |
| 5 | Renderer downloads 9 tiles sequentially before drawing | New `TileLoader` (bounded concurrency, independent tiles); `GlassesMapPlan` renders at once from loaded tiles or route-only | 8, 18 |
| 6 | Watch retries missing tiles only on placement change | `TileLoader.start()` retries missing visible tiles every 5 s (fetcher backoff honoured), slot released in `finally`; `WatchTiles.show(visible)` | 8, 12 |
| 7 | Watch marker moves to aged points | `WatchMapTracker`: only a fix usable-live on arrival (shared `Freshness`) moves the marker; restored route has no marker | 12 |
| 8 | Missing accuracy became 30 m (usable) | `LocationFix.accuracyM: Float?` (null = unknown); one rule `FixQuality.accurate` used by `RouteTrack`, `Freshness`, RouteHub and the watch; HS and phone providers emit null | 1, 3, 4, 10, 11, 12, 14, 16 |
| 9 | Idle timer exits Scroll after a long confirmation; timeout not keyed | `IdleGate` orders resume-then-deadline; timer effect keyed on the collected idle timeout | 21, 22 |
| 10 | PageReporter drops failed sends | `send` returns Boolean (`sendMessage == 0`); latest unsent page kept and retried every second | 22 |
| 11 | Tile cache caps at 7 days, no validators | `TileMeta` (expiry, ETag, Last-Modified); max-age / Expires honoured uncapped; `If-None-Match`/`If-Modified-Since`; 304 keeps bytes; 7 days only without headers | 8 |
| 12 | Task 12 uses `:core:map` without depending on Task 9 | `api(project(":core:map"))` on `:services:sync` moved to Task 2; index/lanes updated | 2, 9, index |
| — | Early raw-PNG-over-CXR check | Device check D1 (Task 22 Step 9) with the `map_probe` debug command (Task 15); decides `MAP_AS_BASE64` before Task 23 | 15, 22, 25 |

## Global Constraints

- Build with JDK 17: prefix every Gradle command with `export JAVA_HOME=$(/usr/libexec/java_home -v 17) &&`.
- `:core:model`, `:core:map`, `:core:services`, `:services:sync`, `:services:workout` and the parser part of `:services:voice` contain **no `android.*` imports**.
- **`protocolVersion` → 4** (coordinated upgrade, base spec §4.7): new `lf_map` (phone→glasses image), `lf_page_state` (glasses→phone visible page), settings frame gains `pages` + `gestures`; watch settings message gains `pages`; watch deltas gain location fixes; watch gets `QueueFrame`. All three APKs updated together (`tools/install-all.sh`).
- Wire JSON unchanged otherwise: kotlinx.serialization, `classDiscriminator = "cmd"`, `ignoreUnknownKeys = true`, `encodeDefaults = true`.
- **Page order (cycling): Glance → Workout → Stats → Playlist → Map → Music controls.** Same set on glasses and watch. Workout cannot be disabled. Map is skipped when no GPS workout is active. Workout start lands on Workout.
- **Page transitions:** visible page becomes unavailable → switch to Workout immediately; any actual page change clears Scroll mode and any highlight/selector; Scroll mode stays after Play highlighted / Press selected; the idle timer restarts on every gesture handled in Scroll mode; confirmations pause it.
- **Scroll mode** only on Playlist and Music controls; left by "Exit scroll", by the in-page **✕ Back** item, or after the **idle timeout (default 5 s, 3–15 s)**.
- **Usable-live fix:** with calibrated time, **−2 s ≤ age ≤ 10 s** and **accuracyM ≤ 30**. Only usable-live fixes move the marker and drive source switching. A fix without accuracy is **unknown** (`accuracyM = null`) and never usable-live or routed; one shared predicate (`FixQuality` / `Freshness.isUsableLive`) on phone and watch. On the watch a fix must be usable-live when it **arrives** to move the marker.
- **Degraded display:** no usable-live fix for **10–30 s** → hollow arrow + "GPS delayed"; **> 30 s** → "GPS lost", last point kept. No fix yet → "Waiting for GPS…".
- **Phone fallback:** starts when no usable-live watch fix for **15 s** during a GPS workout; stops when watch fixes have been usable-live continuously for **10 s of observed (arrival) time**; while both are usable-live, watch fixes win. Fallback benefits glasses and history only; the watch map always uses its own fixes.
- **Clock calibration:** on every watch connect/reconnect and **every 5 min**: offset = `tw − (t0 + t1)/2`, accepted only if **RTT `t1 − t0` ≤ 1 s**, else retried **up to 5 tries**. Until a sync succeeded in the current phone process, watch fixes are recorded but never live.
- **Route merge:** points sorted by `fixTimeMs`; drop `accuracyM > 30`; drop a point **< 3 m** from its chronological neighbour of the same source; duplicates = same source + same `fixTimeMs` (**±50 ms**) + same position (**±1 m**); phone points within **±5 s** of a watch point hidden from the drawn route (kept in storage); points **> 2 min** in the future rejected; ties break watch-first.
- **Storage:** Room `route_point(sessionId, fixTimeMs, source, lat, lon, accuracyM)` with unique key `(sessionId, source, fixTimeMs)` — `fixTimeMs` is the measuring device's clock (identity, never changed) plus a nullable `phoneTimeMs` (ordering; null until calibrated, rewritten on calibration). A stored delta's route rows are written in the delta's transaction; Discarded/Cleared tombstones block route rows.
- **Map math:** Web-Mercator slippy tiles, north-up, centred on the current position, **zoom 18 default (~270 m across 480 px at latitude 20°; ~1.08 km at zoom 16), zoom 17 for Cycle**; watch bezel zoom **14–18**.
- **Tiles:** `https://tile.openstreetmap.org/{z}/{x}/{y}.png`, app-specific User-Agent, attribution **"© OpenStreetMap contributors" always visible**, disk LRU cache **50 MB phone / 20 MB watch**, the server's cache lifetime honoured (max-age, else Expires; **7 days only when the server sends neither**), expired tiles revalidated with `If-None-Match`/`If-Modified-Since` (304 keeps the bytes), no prefetch beyond the visible **3×3** tiles, provider behind a `TileSource` interface. Rendering never waits for a tile; missing visible tiles load in the background and are retried while visible.
- **Glasses map image:** **480×480** PNG, target **≤ 40 KB**, HUD palette (black background, streets dim green, water/park dropped, bright route, arrow, start marker, scale bar, attribution). Sent on `lf_map` **only while the glasses report the Map page visible**; cadence **every 3 s or after ≥ 25 m movement, never more than 1/s**; each image carries `{sessionId, renderEpoch, renderSeq}`; new random `renderEpoch` at phone process start and on every glasses (re)connect, announced first (epoch header, no image). Offline → "No map — route only".
- **`lf_page_state{page, seq}`** sent by the glasses on every page change **and on every connect/reconnect**; the phone clears visibility on disconnect.
- **FGS:** watch exercise service type **`health|location`** (location only with `ACCESS_FINE_LOCATION`; never crash on denial); phone hub adds type `location`, re-promoted with `CONNECTED_DEVICE | LOCATION` whenever LiveFit's Activity is visible and fine location is granted; otherwise "Phone GPS available after opening LiveFit"; denied → "Phone GPS off", hub still starts with `connectedDevice`.
- **Gesture safety:** for **every page separately** (including disabled pages) the page-mode table must contain ≥ 1 gesture → **Close app** and ≥ 1 → **Next page** or **Previous page**; the phone refuses a breaking change with a reason; the glasses validate per page. Confirmation overlays are not configurable.
- **Gesture defaults (spec §4.3):** Glance/Workout/Stats/Map page mode: Tap = Talk, Double tap = Close app, short swipe = next/previous page, long swipe = ±2 pages. Playlist/Music controls page mode: Tap = Enter scroll mode, rest as above. Scroll Playlist: Tap = Play highlighted, Double tap = Close app, short = highlight ±1, long = highlight ±2. Scroll Music controls: Tap = Press selected, Double tap = Close app, short = selector next/previous (⏮ ⏯ ⏭ ✕), long = volume up/down.
- **Voice:** "map view"; "music view" / "player view" / "music controls" → Music controls; "stats view" → Stats; "playlist view" → Playlist. Disabled page → toast **"<Page> page is turned off in Settings"**. Voice page commands move the glasses only.
- Device serials (owner's setup): glasses `<glasses-serial>` (USB), watch `<watch-adb-serial>`, phone `<phone-adb-serial>` (wireless; re-run `adb devices`). Install phone APKs with `--user 0`.

## Review Focus

Seven real-world conditions the spec implies that are most likely to bite the owner; each line names the test that pins it and the task that owns it.

1. **Phone restarts mid-run; the watch's first replay (minutes-old fixes, watch clock possibly minutes off) arrives before the first time-sync, then again just after it** → the replay is stored by device time, then normalized and drawn in time order once calibrated (nothing rejected as "future"), but never moves the marker, never shows "Live", and never stops the phone fallback. Pinned in Task 14 (`delayedFirstReplayAfterPhoneRestartDoesNotLookLive`, `replayBeforeSyncThenCalibrationMoreThanTwoMinutesAhead`).
2. **Watch screen off + phone locked: Health Services delivers 30–40 s of location in one late batch** → the batch is inserted chronologically, status reads "GPS delayed" (not Live), and the burst — even one spanning the whole 10 s live window — cannot flip the source. Pinned in Task 4 (`lateBatchedBurstNeitherLiveNorFlipsSource`, `oneBatchSpanningTheLiveWindowDoesNotStopFallback`), Task 3 (`lateBatchInsertsChronologically`) and on the watch in Task 12 (`agedBatchGrowsTheRouteButNeverMovesTheMarker`).
3. **Glasses stay on the Map page while the phone process restarts or the CXR link drops and comes back (no page change)** → the phone announces a new epoch, the glasses re-report their page, images resume, and an in-flight image from the old epoch is rejected. Pinned in Task 9 (`reconnectWithoutPageChangeResumesAfterPageReport`, `restartedPhoneNewEpochResetsSequence`).
4. **A gesture table arrives that leaves a page without Close app (older phone build, hand-edited prefs, or a bug)** → the glasses keep that page's last valid table (defaults initially) while applying the valid pages, so the wearer can always leave the app and change pages. Pinned in Task 6 (`brokenTableKeepsLastValidPerPage`).
5. **The wearer is in Scroll mode on Playlist when that page is disabled on the phone (or is on Map when the GPS workout ends)** → the glasses jump to Workout in Page mode with no highlight, and the phone stops sending images. Pinned in Task 21 (`disablingVisibleScrollPageReturnsToWorkoutInPageMode`) and Task 9 (`leavingMapStopsImages`).
6. **The phone process dies right after acking a watch delta (or a route write fails once)** → after restart the full route is back (rows stored with the delta, missing ones rebuilt from stored deltas, failed writes retried), never for a discarded session. Pinned in Task 13 (`storingADeltaStoresItsRouteRowsInTheSameTransaction`) and Task 14 (`routeRowsMissingAfterAnAckAreRebuiltFromStoredDeltas`, `aFailedRouteWriteIsRetriedAndReplayIsIdempotent`).
7. **Tiles are slow or the network is down when the Map page opens, and the wearer does not move** → images keep their 3 s cadence (route, marker, "GPS delayed" / "No map — route only") and tiles fill in by themselves once the network returns, on glasses and watch. Pinned in Task 18 (`blockedTilesNeverHoldBackTheImage`), Task 8 (`missingVisibleTilesAreRetriedWithoutAViewportChange`) and Task 12 (`fixedViewportGetsItsTilesOnceTheNetworkReturns`).

---

## File structure (locked decomposition)

```
settings.gradle.kts                         (modify) include :core:map
core/model/src/main/kotlin/com/debasish/livefit/model/
  Protocol.kt      (modify) PROTOCOL_VERSION = 4; GlassesChannels.MAP / PAGE_STATE; WatchPaths.TIME_REQ / TIME_RES / QUEUE
  Frames.kt        (modify) HudPage(label) × 6; HudSettingsFrame.pages/gestures; PageState; MapFrame(+Kind); WatchSettingsFrame; TimeSyncRequest/Response
  Session.kt       (modify) SessionEvent.Started.gps; SessionDelta.locations
  Workout.kt       (modify) WorkoutSnapshot.gps
  Location.kt      (create) FixSource, LocationFix
  Pages.kt         (create) PageSettings
  Gestures.kt      (create) Gesture, GestureMode, GestureAction, GestureSettings, GestureDefaults
  Route.kt         (create) Geo, RoutePoint, RouteTrack, RouteAdd, GpsStatus, LivePosition, RouteState
  PageSet.kt       (create) page availability, cycling, fallback
  GestureRules.kt  (create) per-context catalogue, safety validation, sanitize, change
core/map/ (new pure module, package com.debasish.livefit.map)
  TileMath.kt, Viewport.kt, MapScene.kt, HudPalette.kt, MapCadence.kt, Tiles.kt (TileSource, OsmTileSource, TileMeta, TileDiskCache, HttpTileFetcher), TileLoader.kt
core/services/.../Services.kt  (modify) RouteStore; GlassesLinkService.pushMap; GlassesEvent.PageVisible; WatchLinkService.pushSettings/pushQueue
services/sync/  WatchClockSync.kt, Freshness.kt, LiveLocationSelector.kt, GlassesMapStreamer.kt, MapImageGate.kt, RouteHub.kt, WatchRouteFile.kt (create);
                WatchSessionRecorder.kt, WatchExerciseController.kt, HubCommandRouter.kt (modify); build.gradle.kts (api :core:map — Task 2)
services/workout/ SessionAssembler.kt, HubWorkoutService.kt (modify: gps)
services/history/ Entities.kt, HistoryDao.kt, HistoryDatabase.kt (v2 + migration), RoomSessionStore.kt (RouteStore)
services/glasses-link/ GlassesInbound.kt (lf_page_state), CxrGlassesLink.kt (pushMap)
services/watch-link/   WatchMessageCodec.kt (time_res), DataLayerWatchLink.kt (time-sync driver, settings, queue)
services/voice/        CommandParser.kt, VoiceCommandGroup.kt
phone/  AndroidManifest.xml, LiveFitHubService.kt, HubLocationPolicy.kt, location/PhoneLocationProvider.kt, map/{GlassesMapText,GlassesMapPlan,GlassesMapRenderer}.kt,
        src/debug/.../DebugReceiver.kt (map_probe, device check D1),
        ServiceGraph.kt, SettingsStore.kt, setup/SetupFlow.kt, setup/SetupScreen.kt, ui/AppActivity.kt, ui/settings/SettingsScreen.kt,
        ui/list/ListSource.kt, ui/list/sources/{PagesSource,GestureSource,GestureMenu,PermissionSource}.kt, ui/history/SessionDetailScreen.kt
watch/  AndroidManifest.xml, build.gradle.kts (test deps), ExerciseService.kt, WatchFgs.kt, HealthServicesExercise.kt, TimeSyncResponder.kt, PhoneCommandListener.kt,
        WatchRuntime.kt, WatchClient.kt, MainActivity.kt, map/{WatchTiles,WatchMapModel (+WatchMapTracker, WatchTilePolicy)}.kt, ui/{WatchApp,WatchPages,WatchMap}.kt
glasses/ build.gradle.kts (test deps), hud/HudNav.kt (rewrite, + IdleGate), hud/DoubleTap.kt (CloseConfirm.onClose), hud/HudController.kt, hud/PageReporter.kt, hud/MapPayload.kt,
         hud/HudScreen.kt, hud/MusicScreen.kt, hud/HudPages.kt (Stats, Map, Music controls), MainActivity.kt
tools/device-tests/map-cadence.sh (create)
```

Decisions this plan makes where the spec is silent or ambiguous (each is also listed in the hand-off report):
- **GPS workout** = "Use GPS outdoors" on, for every type including Walk (spec §2.1 lists Walk explicitly); off = no GPS for any type. The Started event records that GPS was *requested* (`Started.gps`, the phone's choice), so the hub, glasses and watch all derive Map eligibility from `WorkoutSnapshot.gps` + a recording phase — and the Map page and phone fallback stay available when the watch itself cannot get location (permission denied, no fix).
- **`route_point.fixTimeMs` stores the source device's own clock** (watch clock for watch fixes) so the unique key stays replay-safe across re-calibrations; an extra nullable `phoneTimeMs` column holds the mapped time used for ordering (null until the first successful calibration of this phone process; every calibration change re-maps all watch rows of the session). Uncalibrated watch fixes are durable but **not drawn** until calibrated (calibration runs on every connect, so this is normally about a second). History shows never-normalized rows at device time.
- **"Continuously usable-live for 10 s"** = an unbroken run of usable-live watch fixes timed by **arrival** (observed time) with no arrival gap > 3 s, so a late batch is one observation; a fresh-but-unusable watch fix (inaccurate, unknown accuracy, too far ahead) breaks the run; replayed (old) or uncalibrated fixes neither count nor break it.
- **Invalid gesture tables on the glasses** (spec §4.4 "fall back to defaults" vs §7 "keep the last valid mapping"): per page, the last valid table is kept; before any valid table it is the default.
- **Playlist ✕ Back** is a final row after the queue rows; the Music-controls selector wraps (⏮ ⏯ ⏭ ✕), the Playlist highlight stops at the ends (V1 behaviour).
- **lf_map payload:** PNG in the CXR `bytes` argument (spec "binary payload"); the glasses also accept a `pngBase64` field, switched on with `CxrGlassesLink.MAP_AS_BASE64` if early device check D1 (Task 22 Step 9, as soon as sender and receiver exist) shows the bytes argument does not arrive intact (audio already needed Base64 text the other way).
- **Uncalibrated retry:** while no sync has succeeded, the phone retries calibration every 30 s (5 tries each) instead of waiting 5 min.
- **Location batching overrides:** Health Services 1.1.0-alpha05 names no location override, so the watch requests every supported override (today only `HEART_RATE_5_SECONDS`) and logs the list.
- Missing location accuracy (Health Services or the phone) is **unknown** (`null`): recorded in the delta and route.bin, but never routed, stored in `route_point`, or usable-live; logged on the watch.
- **Tile lifetime:** spec §2.4 "7-day max-age honoured" is read as "honour the server's lifetime; 7 days when it gives none" (OSM tile policy §3.2), never capped.
- **Glasses page reports** are retried: the latest unsent `lf_page_state` is re-sent every second until the CXR bridge accepts it (`sendMessage == 0`).

---

## Task index and parallel lanes

| # | Task | Depends on |
|---|---|---|
| 1 | Protocol v4 types and codec | — |
| 2 | `:core:map` module, tile math, viewport | 1 |
| 3 | Geo, RouteTrack, route state types | 1 |
| 4 | Time sync, freshness, live source selection | 3 |
| 5 | Page set and transitions | 1 |
| 6 | Gesture rules and safety validation | 1 |
| 7 | Map scene, HUD palette, cadence | 2, 3 |
| 8 | Tile source, validating disk cache, HTTP fetcher, non-blocking tile loader | 2 |
| 9 | Glasses map stream (phone streamer + glasses image gate) | 3, 7 |
| 10 | Watch recorder: fixes in deltas, `Started.gps`, route file | 1 |
| 11 | Watch platform: HS location, FGS `health\|location`, time-sync responder, settings/queue intake | 10 |
| 12 | Watch pages UI (Glance, Stats, Playlist, Map, Music controls, bezel) | 2, 4, 5, 7, 8, 11 (`:core:map` via `:services:sync` from Task 2) |
| 13 | Route storage in Room (device-time identity, nullable phone time, rows with their delta) | 3 |
| 14 | RouteHub (merge, live source, fallback, calibration normalization, durable retried rows) | 3, 4, 5, 13 |
| 15 | Link plumbing (time-sync driver, watch settings/queue, `lf_map` send, `lf_page_state` inbound, `map_probe`) | 4 |
| 16 | Phone GPS (LocationSource provider, hub re-promotion, setup step, status) | 1 |
| 17 | Settings store pages + gestures, settings frames, voice page gate | 5, 6, 13, 15, 16 |
| 18 | Map pipeline wiring (renderer + ServiceGraph) | 7, 8, 9, 14, 15, 17 |
| 19 | Settings UI: Pages list, hierarchical Glasses gestures | 17 |
| 20 | History detail route thumbnail | 2, 7, 17 |
| 21 | Glasses HudNav v4 (pure) | 5, 6 |
| 22 | Glasses controller + activity (settings, page-state with retry, `lf_map`, dispatch) + **device check D1** | 9, 15, 21 |
| 23 | Glasses pages UI (Stats, Map, Music controls, Playlist scroll) | 22 (incl. D1 outcome) |
| 24 | Voice phrases | 1 |
| 25 | Device acceptance | all |

**Parallel lanes** (Task 1 first, alone — every lane needs protocol v4; within a lane run top to bottom; a lane waits for a cross-lane dependency listed above before starting that task):
- **Lane A — map core:** 2 → 8 → 7 (7 also waits for 3). Task 2 also owns `services/sync/build.gradle.kts` (`api :core:map`).
- **Lane B — route/location core:** 3 → 4 → 9 (9 also waits for 7)
- **Lane C — pages/gestures core + glasses:** 5 → 6 → 21 → 22 (waits for 9 and 15; ends with device check D1) → 23
- **Lane D — watch:** 10 → 11 → 12 (waits for 2, 4, 5, 7, 8)
- **Lane E — phone data/links:** 13 → 14 (waits for 4, 5) → 15
- **Lane F — phone app:** 16 → 17 (waits for 5, 6, 13, 15) → 19 → 20 (waits for 7) → 18 (waits for 8, 9, 14)
- **Lane G — voice:** 24
- **Finish:** 25 after every lane.

Files are owned by exactly one lane (e.g. `ServiceGraph.kt` and `SettingsScreen.kt` only in Lane F, the debug-only `DebugReceiver.kt` only in Lane E, `services/sync/build.gradle.kts` and `core/map/build.gradle.kts` only in Lane A, all glasses files only in Lane C, all watch files only in Lane D), so lanes merge without conflicts.

---

## Phase 0 — Pure foundations

### Task 1: Protocol v4 types and codec

**Files:**
- Modify: `core/model/src/main/kotlin/com/debasish/livefit/model/Protocol.kt`
- Modify: `core/model/src/main/kotlin/com/debasish/livefit/model/Frames.kt`
- Modify: `core/model/src/main/kotlin/com/debasish/livefit/model/Session.kt`
- Modify: `core/model/src/main/kotlin/com/debasish/livefit/model/Workout.kt`
- Create: `core/model/src/main/kotlin/com/debasish/livefit/model/Location.kt`
- Create: `core/model/src/main/kotlin/com/debasish/livefit/model/Pages.kt`
- Create: `core/model/src/main/kotlin/com/debasish/livefit/model/Gestures.kt`
- Test: `core/model/src/test/kotlin/com/debasish/livefit/model/WireTest.kt` (modify), `core/model/src/test/kotlin/com/debasish/livefit/model/WireV4Test.kt` (create)

**Interfaces:**
- Produces (used by every later task):
  - `const val PROTOCOL_VERSION = 4`; `GlassesChannels.MAP = "lf_map"`, `GlassesChannels.PAGE_STATE = "lf_page_state"`; `WatchPaths.TIME_REQ = "/lf/time_req"`, `WatchPaths.TIME_RES = "/lf/time_res"`, `WatchPaths.QUEUE = "/lf/queue"`
  - `enum class HudPage(val label: String) { Glance, Workout, Stats, Playlist, Map, MusicControls }` (declaration order = cycle order)
  - `enum class FixSource { Watch, Phone }`; `data class LocationFix(lat: Double, lon: Double, accuracyM: Float?, bearingDeg: Float? = null, fixTimeMs: Long)` — `accuracyM = null` means **unknown** (review #8; never a borderline number)
  - `SessionEvent.Started(tMs, type, gps: Boolean = false)`; `SessionDelta.locations: List<LocationFix> = emptyList()`; `WorkoutSnapshot.gps: Boolean = false`
  - `data class PageSettings(disabled: Set<HudPage> = emptySet()) { fun isEnabled(page: HudPage): Boolean }`
  - `enum class Gesture(label)`: `Tap, DoubleTap, ShortForward, ShortBack, LongForward, LongBack`; `enum class GestureMode { Page, Scroll }`; `enum class GestureAction(label)` (catalogue below); `data class GestureSettings(page: Map<HudPage, Map<Gesture, GestureAction>>, scroll: Map<HudPage, Map<Gesture, GestureAction>>, idleTimeoutS: Int = 5, askBeforeClose: Boolean = true)`; `object GestureDefaults { SCROLL_PAGES; IDLE_DEFAULT_S/IDLE_MIN_S/IDLE_MAX_S; fun pageTable(page); fun scrollTable(page); val page; val scroll }`
  - `HudSettingsFrame(protocolVersion, settings, pages: PageSettings = PageSettings(), gestures: GestureSettings = GestureSettings())`
  - `data class PageState(protocolVersion, page: HudPage, seq: Long) { companion fun parse(text): PageState? }`
  - `enum class MapFrameKind { Epoch, Image }`; `data class MapFrame(protocolVersion, kind, renderEpoch: Long, sessionId: String? = null, renderSeq: Long = 0, pngBase64: String? = null) { companion fun parse(text): MapFrame? }`
  - `data class WatchSettingsFrame(protocolVersion, pages: PageSettings)`; `data class TimeSyncRequest(protocolVersion, id: Long, t0: Long)`; `data class TimeSyncResponse(protocolVersion, id: Long, t0: Long, tw: Long)`

- [ ] **Step 1: Write the failing test** — create `core/model/src/test/kotlin/com/debasish/livefit/model/WireV4Test.kt`:

```kotlin
package com.debasish.livefit.model

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class WireV4Test {
    private inline fun <reified T> roundTrip(value: T) = assertEquals(value, Wire.decode<T>(Wire.encode(value)))

    /** §6: v4 adds lf_map, lf_page_state, pages + gestures, watch settings, watch queue and location fixes. */
    @Test fun protocolVersionIsFour() = assertEquals(4, PROTOCOL_VERSION)

    @Test fun pageOrderIsTheSharedCycle() = assertEquals(
        listOf(HudPage.Glance, HudPage.Workout, HudPage.Stats, HudPage.Playlist, HudPage.Map, HudPage.MusicControls),
        HudPage.entries,
    )

    @Test fun pageLabelsAreUserFacing() = assertEquals("Music controls", HudPage.MusicControls.label)

    @Test fun newChannelsAndPaths() {
        assertEquals("lf_map", GlassesChannels.MAP)
        assertEquals("lf_page_state", GlassesChannels.PAGE_STATE)
        assertEquals("/lf/time_req", WatchPaths.TIME_REQ)
        assertEquals("/lf/time_res", WatchPaths.TIME_RES)
        assertEquals("/lf/queue", WatchPaths.QUEUE)
    }

    @Test fun deltaCarriesLocationFixesAndGpsStart() = roundTrip(
        SessionDelta(
            sessionId = "s", seq = 0,
            events = listOf(SessionEvent.Started(1_000, WorkoutType.Run, gps = true)),
            locations = listOf(LocationFix(12.9716, 77.5946, 4.5f, 90f, 1_000), LocationFix(12.9717, 77.5946, 6f, null, 2_000)),
            provenance = Provenance.Live("galaxy-watch/health-services"),
        ),
    )

    /** Review #8: a fix without accuracy travels as an explicit null (unknown), never as a borderline number. */
    @Test fun unknownAccuracyIsAnExplicitNull() {
        val f = LocationFix(12.9716, 77.5946, null, null, 3_000)
        roundTrip(f)
        assertTrue(Wire.encode(f).contains("\"accuracyM\":null"))
    }

    @Test fun olderDeltaJsonWithoutLocationsOrGpsStillDecodes() {
        val json = """{"protocolVersion":4,"sessionId":"s","seq":0,"events":[{"cmd":"com.debasish.livefit.model.SessionEvent.Started","tMs":1,"type":"Walk"}],"provenance":{"cmd":"com.debasish.livefit.model.Provenance.Fake"}}"""
        val d = Wire.decode<SessionDelta>(json)
        assertTrue(d.locations.isEmpty())
        assertFalse((d.events.single() as SessionEvent.Started).gps)
    }

    @Test fun snapshotCarriesGps() = roundTrip(WorkoutSnapshot(phase = WorkoutPhase.Active, sessionId = "s", gps = true))

    @Test fun settingsFrameCarriesPagesAndGestures() {
        val f = HudSettingsFrame(settings = HudSettings(), pages = PageSettings(disabled = setOf(HudPage.Map)), gestures = GestureSettings(idleTimeoutS = 8))
        roundTrip(f)
        assertEquals(GestureAction.EnterScroll, f.gestures.page.getValue(HudPage.Playlist).getValue(Gesture.Tap))
        assertTrue(Wire.encode(f).contains("\"Tap\":\"Talk\""), "the gesture table is a compact name → name map")
    }

    @Test fun workoutCannotBeDisabled() {
        val p = PageSettings(disabled = setOf(HudPage.Workout, HudPage.Stats))
        assertTrue(p.isEnabled(HudPage.Workout))
        assertFalse(p.isEnabled(HudPage.Stats))
        assertTrue(p.isEnabled(HudPage.Map))
    }

    @Test fun gestureDefaultsMatchSpecTable() {
        val d = GestureSettings()
        for (p in listOf(HudPage.Glance, HudPage.Workout, HudPage.Stats, HudPage.Map)) {
            assertEquals(
                mapOf(
                    Gesture.Tap to GestureAction.Talk, Gesture.DoubleTap to GestureAction.CloseApp,
                    Gesture.ShortForward to GestureAction.NextPage, Gesture.ShortBack to GestureAction.PreviousPage,
                    Gesture.LongForward to GestureAction.NextPage2, Gesture.LongBack to GestureAction.PreviousPage2,
                ),
                d.page.getValue(p), "page mode $p",
            )
        }
        for (p in listOf(HudPage.Playlist, HudPage.MusicControls)) assertEquals(GestureAction.EnterScroll, d.page.getValue(p).getValue(Gesture.Tap))
        assertEquals(
            mapOf(
                Gesture.Tap to GestureAction.PlayHighlighted, Gesture.DoubleTap to GestureAction.CloseApp,
                Gesture.ShortForward to GestureAction.HighlightNext, Gesture.ShortBack to GestureAction.HighlightPrevious,
                Gesture.LongForward to GestureAction.HighlightNext2, Gesture.LongBack to GestureAction.HighlightPrevious2,
            ),
            d.scroll.getValue(HudPage.Playlist),
        )
        assertEquals(
            mapOf(
                Gesture.Tap to GestureAction.PressSelected, Gesture.DoubleTap to GestureAction.CloseApp,
                Gesture.ShortForward to GestureAction.SelectorNext, Gesture.ShortBack to GestureAction.SelectorPrevious,
                Gesture.LongForward to GestureAction.VolumeUp, Gesture.LongBack to GestureAction.VolumeDown,
            ),
            d.scroll.getValue(HudPage.MusicControls),
        )
        assertEquals(setOf(HudPage.Playlist, HudPage.MusicControls), d.scroll.keys)
        assertEquals(5, d.idleTimeoutS)
        assertTrue(d.askBeforeClose)
    }

    @Test fun watchMessagesRoundTrip() {
        roundTrip(WatchSettingsFrame(pages = PageSettings(disabled = setOf(HudPage.Glance))))
        roundTrip(TimeSyncRequest(id = 7, t0 = 1_000))
        roundTrip(TimeSyncResponse(id = 7, t0 = 1_000, tw = 6_100))
    }

    @Test fun mapFramesParseOnlyCurrentVersion() {
        val epoch = MapFrame(kind = MapFrameKind.Epoch, renderEpoch = -42)
        val image = MapFrame(kind = MapFrameKind.Image, renderEpoch = -42, sessionId = "s", renderSeq = 3)
        assertEquals(epoch, MapFrame.parse(Wire.encode(epoch)))
        assertEquals(image, MapFrame.parse(Wire.encode(image)))
        assertNull(MapFrame.parse("""{"protocolVersion":3,"kind":"Epoch","renderEpoch":1}"""))
        assertNull(MapFrame.parse("garbage"))
    }

    @Test fun pageStateParsesOnlyCurrentVersion() {
        val s = PageState(page = HudPage.Map, seq = 9)
        assertEquals(s, PageState.parse(Wire.encode(s)))
        assertNull(PageState.parse("""{"protocolVersion":3,"page":"Map","seq":1}"""))
        assertNull(PageState.parse("""{"protocolVersion":4,"page":"Nope","seq":1}"""))
    }
}
```

- [ ] **Step 2: Run test to verify it fails**

Run: `export JAVA_HOME=$(/usr/libexec/java_home -v 17) && ./gradlew :core:model:test`
Expected: FAIL — compilation errors (`LocationFix`, `PageSettings`, `GestureSettings`, `MapFrame`, `HudPage.Stats` … unresolved).

- [ ] **Step 3: Update `Protocol.kt`** — replace the version doc + constant and add the new names:

```kotlin
/**
 * 2: glasses music screen — `lf_queue` (QueueFrame) and `Command.PlayQueueItem` (a v1 phone can't decode the new command).
 * 3: glasses pages by voice — `lf_page` (PageRequest) and `Command.ShowGlassesPage` (a v2 glasses app would drop the request).
 * 4: pages, live map and gestures — `lf_map` (MapFrame), `lf_page_state` (PageState), HudSettingsFrame.pages/gestures,
 *    WatchSettingsFrame, watch QueueFrame on /lf/queue, time sync on /lf/time_req|res, SessionDelta.locations.
 */
const val PROTOCOL_VERSION = 4
```

In `object GlassesChannels` add after `PAGE`:

```kotlin
    /** Phone → glasses: map epoch header or a 480×480 PNG map image ([MapFrame]); only while the glasses show the Map page. */
    const val MAP = "lf_map"
    /** Glasses → phone: the visible page ([PageState]) on every page change and every (re)connect. */
    const val PAGE_STATE = "lf_page_state"
```

In `object WatchPaths` add after `DISCOVERABLE`:

```kotlin
    /** Phone → watch: clock-calibration ping ([TimeSyncRequest]); the watch answers at once on [TIME_RES]. */
    const val TIME_REQ = "/lf/time_req"
    const val TIME_RES = "/lf/time_res"
    /** Phone → watch: YouTube Music queue window ([QueueFrame]) for the watch Playlist page. */
    const val QUEUE = "/lf/queue"
```

- [ ] **Step 4: Create `Location.kt`**

```kotlin
package com.debasish.livefit.model

import kotlinx.serialization.Serializable

@Serializable
enum class FixSource { Watch, Phone }

/**
 * One position fix. [fixTimeMs] is the fix's own time on the clock of the device that measured it (watch clock for watch
 * fixes); the phone maps watch times to phone time with its calibrated offset (spec §2.1). [accuracyM] null = the
 * platform reported no accuracy: unknown, which fails every accuracy gate (FixQuality, Task 3; review #8).
 */
@Serializable
data class LocationFix(
    val lat: Double,
    val lon: Double,
    val accuracyM: Float?,
    val bearingDeg: Float? = null,
    val fixTimeMs: Long,
)
```

- [ ] **Step 5: Create `Pages.kt`**

```kotlin
package com.debasish.livefit.model

import kotlinx.serialization.Serializable

/** Settings → Pages (spec §3.2): stored on the phone, sent to glasses and watch. Workout can never be turned off. */
@Serializable
data class PageSettings(val disabled: Set<HudPage> = emptySet()) {
    fun isEnabled(page: HudPage): Boolean = page == HudPage.Workout || page !in disabled
}
```

- [ ] **Step 6: Create `Gestures.kt`**

```kotlin
package com.debasish.livefit.model

import kotlinx.serialization.Serializable

/** Touchpad gestures the Rokid glasses sense (spec §4.1). Long press is the system's "Hi Rokid"; vertical swipes don't exist. */
@Serializable
enum class Gesture(val label: String) {
    Tap("Tap"),
    DoubleTap("Double tap"),
    ShortForward("Short swipe forward"),
    ShortBack("Short swipe back"),
    LongForward("Long swipe forward"),
    LongBack("Long swipe back"),
}

/** Page mode exists on every page; Scroll mode only on Playlist and Music controls (spec §4.2). */
@Serializable
enum class GestureMode { Page, Scroll }

/** The action catalogue (spec §4.4); which ones a context offers is decided by GestureRules.validActions. */
@Serializable
enum class GestureAction(val label: String) {
    None("None"),
    Talk("Talk (voice)"),
    NextPage("Next page"),
    PreviousPage("Previous page"),
    NextPage2("+2 pages"),
    PreviousPage2("−2 pages"),
    CloseApp("Close app"),
    EnterScroll("Enter scroll mode"),
    ExitScroll("Exit scroll mode"),
    HighlightNext("Highlight next row"),
    HighlightPrevious("Highlight previous row"),
    HighlightNext2("Highlight +2 rows"),
    HighlightPrevious2("Highlight −2 rows"),
    PlayHighlighted("Play highlighted"),
    SelectorNext("Selector next"),
    SelectorPrevious("Selector previous"),
    PressSelected("Press selected"),
    PlayPause("Play/pause"),
    NextSong("Next song"),
    PreviousSong("Previous song"),
    VolumeUp("Volume up"),
    VolumeDown("Volume down"),
    LikeSong("Like song"),
}

/**
 * Settings → Glasses gestures (spec §4.4), sent in the glasses settings frame as a compact name → name table.
 * [page] has one table per page (all six, including disabled ones); [scroll] one per scroll page.
 */
@Serializable
data class GestureSettings(
    val page: Map<HudPage, Map<Gesture, GestureAction>> = GestureDefaults.page,
    val scroll: Map<HudPage, Map<Gesture, GestureAction>> = GestureDefaults.scroll,
    val idleTimeoutS: Int = GestureDefaults.IDLE_DEFAULT_S,
    val askBeforeClose: Boolean = true,
)

/** Spec §4.3 defaults. */
object GestureDefaults {
    const val IDLE_DEFAULT_S = 5
    const val IDLE_MIN_S = 3
    const val IDLE_MAX_S = 15
    val SCROLL_PAGES: Set<HudPage> = setOf(HudPage.Playlist, HudPage.MusicControls)

    private val navigation: Map<Gesture, GestureAction> = mapOf(
        Gesture.DoubleTap to GestureAction.CloseApp,
        Gesture.ShortForward to GestureAction.NextPage,
        Gesture.ShortBack to GestureAction.PreviousPage,
        Gesture.LongForward to GestureAction.NextPage2,
        Gesture.LongBack to GestureAction.PreviousPage2,
    )

    fun pageTable(page: HudPage): Map<Gesture, GestureAction> =
        mapOf(Gesture.Tap to if (page in SCROLL_PAGES) GestureAction.EnterScroll else GestureAction.Talk) + navigation

    fun scrollTable(page: HudPage): Map<Gesture, GestureAction> = when (page) {
        HudPage.Playlist -> mapOf(
            Gesture.Tap to GestureAction.PlayHighlighted, Gesture.DoubleTap to GestureAction.CloseApp,
            Gesture.ShortForward to GestureAction.HighlightNext, Gesture.ShortBack to GestureAction.HighlightPrevious,
            Gesture.LongForward to GestureAction.HighlightNext2, Gesture.LongBack to GestureAction.HighlightPrevious2,
        )
        HudPage.MusicControls -> mapOf(
            Gesture.Tap to GestureAction.PressSelected, Gesture.DoubleTap to GestureAction.CloseApp,
            Gesture.ShortForward to GestureAction.SelectorNext, Gesture.ShortBack to GestureAction.SelectorPrevious,
            Gesture.LongForward to GestureAction.VolumeUp, Gesture.LongBack to GestureAction.VolumeDown,
        )
        else -> emptyMap()
    }

    val page: Map<HudPage, Map<Gesture, GestureAction>> = HudPage.entries.associateWith { pageTable(it) }
    val scroll: Map<HudPage, Map<Gesture, GestureAction>> = SCROLL_PAGES.associateWith { scrollTable(it) }
}
```

- [ ] **Step 7: Update `Frames.kt`** — replace the `HudSettingsFrame` declaration with:

```kotlin
/** Phone → glasses on change and on every (re)connect; v4 adds the page set and the gesture table (spec §3.2, §4.4). */
@Serializable
data class HudSettingsFrame(
    val protocolVersion: Int = PROTOCOL_VERSION,
    val settings: HudSettings,
    val pages: PageSettings = PageSettings(),
    val gestures: GestureSettings = GestureSettings(),
)
```

Replace the `HudPage` enum (`enum class HudPage { Glance, Workout, Playlist }` and its doc comment) with:

```kotlin
/** The shared page set of glasses and watch, in cycling order (spec §3.1). */
@Serializable
enum class HudPage(val label: String) {
    Glance("Glance"),
    Workout("Workout"),
    Stats("Stats"),
    Playlist("Playlist"),
    Map("Map"),
    MusicControls("Music controls"),
}
```

Append at the end of `Frames.kt`:

```kotlin
/** Glasses → phone on lf_page_state: the visible page, on every page change and every (re)connect; [seq] grows per glasses process. */
@Serializable
data class PageState(val protocolVersion: Int = PROTOCOL_VERSION, val page: HudPage, val seq: Long) {
    companion object {
        fun parse(text: String): PageState? {
            if (Wire.versionOf(text) != PROTOCOL_VERSION) return null
            return runCatching { Wire.decode<PageState>(text) }.getOrNull()
        }
    }
}

@Serializable
enum class MapFrameKind { Epoch, Image }

/**
 * Phone → glasses on lf_map (spec §2.5). [MapFrameKind.Epoch] announces a new [renderEpoch] (no image);
 * [MapFrameKind.Image] carries a PNG in the CXR bytes argument (or [pngBase64] when CxrGlassesLink.MAP_AS_BASE64 is on).
 */
@Serializable
data class MapFrame(
    val protocolVersion: Int = PROTOCOL_VERSION,
    val kind: MapFrameKind,
    val renderEpoch: Long,
    val sessionId: String? = null,
    val renderSeq: Long = 0,
    val pngBase64: String? = null,
) {
    companion object {
        fun parse(text: String): MapFrame? {
            if (Wire.versionOf(text) != PROTOCOL_VERSION) return null
            return runCatching { Wire.decode<MapFrame>(text) }.getOrNull()
        }
    }
}

/** Phone → watch on /lf/settings: the page set, on change and on every (re)connect (spec §3.2). */
@Serializable
data class WatchSettingsFrame(val protocolVersion: Int = PROTOCOL_VERSION, val pages: PageSettings)

/** Phone → watch: clock calibration ping (spec §2.1). */
@Serializable
data class TimeSyncRequest(val protocolVersion: Int = PROTOCOL_VERSION, val id: Long, val t0: Long)

/** Watch → phone: [tw] = watch wall clock when the ping arrived. */
@Serializable
data class TimeSyncResponse(val protocolVersion: Int = PROTOCOL_VERSION, val id: Long, val t0: Long, val tw: Long)
```

- [ ] **Step 8: Update `Session.kt`** — replace the `Started` line and `SessionDelta`:

```kotlin
    /** [gps]: GPS was requested for this session (phone setting): a GPS workout for Map eligibility and the fallback (spec §3.1). */
    @Serializable data class Started(override val tMs: Long, val type: WorkoutType, val gps: Boolean = false) : SessionEvent
```

```kotlin
/** Watch → phone. seq starts at 0 and increases by 1 per delta within a session. v4: [locations] (watch clock times). */
@Serializable
data class SessionDelta(
    val protocolVersion: Int = PROTOCOL_VERSION,
    val sessionId: String,
    val seq: Long,
    val events: List<SessionEvent> = emptyList(),
    val samples: List<Sample> = emptyList(),
    val provenance: Provenance,
    val final: Boolean = false,
    val locations: List<LocationFix> = emptyList(),
)
```

- [ ] **Step 9: Update `Workout.kt`** — add to `WorkoutSnapshot` after `latestSampleMs`:

```kotlin
    /** A GPS workout (Started.gps, or the hub's GPS choice while Starting): the Map page is eligible while it records. */
    val gps: Boolean = false,
```

- [ ] **Step 10: Update `WireTest.kt`** — delete `protocolVersionIsThree()` and `glassesPagesAreGlanceWorkoutPlaylist()` (now covered by `WireV4Test`). Leave every other test unchanged.

- [ ] **Step 11: Run tests to verify they pass**

Run: `export JAVA_HOME=$(/usr/libexec/java_home -v 17) && ./gradlew :core:model:test`
Expected: PASS.

Then make sure nothing else broke at compile level: `export JAVA_HOME=$(/usr/libexec/java_home -v 17) && ./gradlew :services:sync:test :services:voice:test :services:workout:test :glasses:testDebugUnitTest`
Expected: PASS (HudNav still cycles its own 3-page list until Task 21; parser tests unchanged until Task 24).

- [ ] **Step 12: Commit**

```bash
git add core/model
git commit -m "feat(model): protocol v4 — pages, gestures, map frames, page state, time sync, location fixes"
```

---
### Task 2: `:core:map` module, tile math and viewport

**Files:**
- Modify: `settings.gradle.kts`
- Modify: `services/sync/build.gradle.kts`
- Create: `core/map/build.gradle.kts`
- Create: `core/map/src/main/kotlin/com/debasish/livefit/map/TileMath.kt`
- Create: `core/map/src/main/kotlin/com/debasish/livefit/map/Viewport.kt`
- Test: `core/map/src/test/kotlin/com/debasish/livefit/map/TileMathTest.kt`

**Interfaces:**
- Consumes: `WorkoutType` (Task 1 unchanged type).
- Produces:
  - Gradle module `:core:map` (pure Kotlin JVM, `api(project(":core:model"))`).
  - `:services:sync` exposes `:core:map` (`api(project(":core:map"))`), so phone, watch and glasses see it from here on (review #12: moved from Task 9 so Task 12 depends only on tasks it lists).
  - `const val OSM_ATTRIBUTION = "© OpenStreetMap contributors"`
  - `data class TileId(z: Int, x: Int, y: Int)`, `data class Px(x: Float, y: Float)`
  - `object TileMath { TILE_SIZE = 256; fun worldSize(z): Double; fun lonToWorldX(lon, z): Double; fun latToWorldY(lat, z): Double; fun worldXToLon(x, z): Double; fun worldYToLat(y, z): Double; fun tileFor(lat, lon, z): TileId; fun metersPerPixel(lat, z): Double }`
  - `data class PlacedTile(tile: TileId, left: Float, top: Float)`
  - `data class Viewport(centerLat, centerLon, zoom, widthPx, heightPx) { fun project(lat, lon): Px; fun tiles(): List<PlacedTile>; fun metersPerPixel(): Double; companion { DEFAULT_ZOOM = 18; CYCLE_ZOOM = 17; MIN_ZOOM = 14; MAX_ZOOM = 18; fun zoomFor(type: WorkoutType): Int; fun fit(points: List<Pair<Double, Double>>, widthPx, heightPx, paddingPx = 16, maxZoom = MAX_ZOOM): Viewport? } }`

- [ ] **Step 1: Register the module** — in `settings.gradle.kts` replace `include(":core:model", ":core:services")` with:

```kotlin
include(":core:model", ":core:services", ":core:map")
```

Create `core/map/build.gradle.kts`:

```kotlin
plugins {
    id("org.jetbrains.kotlin.jvm")
}

kotlin { jvmToolchain(17) }

dependencies {
    api(project(":core:model"))
    testImplementation(kotlin("test"))
}

tasks.test { useJUnitPlatform() }
```

In `services/sync/build.gradle.kts` add inside `dependencies { … }`:

```kotlin
    api(project(":core:map"))
```

- [ ] **Step 2: Write the failing test** — `core/map/src/test/kotlin/com/debasish/livefit/map/TileMathTest.kt`:

```kotlin
package com.debasish.livefit.map

import com.debasish.livefit.model.WorkoutType
import kotlin.math.abs
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class TileMathTest {
    /** Spec §2.3: ~270 m across 480 px at latitude 20° on zoom 18; ~1.08 km on zoom 16. */
    @Test fun metersAcross480pxMatchSpec() {
        assertEquals(269.4, TileMath.metersPerPixel(20.0, 18) * 480, 1.0)
        assertEquals(1077.6, TileMath.metersPerPixel(20.0, 16) * 480, 3.0)
    }

    @Test fun knownTiles() {
        assertEquals(TileId(1, 1, 1), TileMath.tileFor(-0.1, 0.1, 1))
        assertEquals(TileId(10, 511, 340), TileMath.tileFor(51.5074, -0.1278, 10)) // London
    }

    @Test fun latLonRoundTrip() {
        val x = TileMath.lonToWorldX(77.5946, 18); val y = TileMath.latToWorldY(12.9716, 18)
        assertEquals(77.5946, TileMath.worldXToLon(x, 18), 1e-9)
        assertEquals(12.9716, TileMath.worldYToLat(y, 18), 1e-9)
    }

    @Test fun centreProjectsToTheMiddleAndOneTileEastIs256px() {
        val vp = Viewport(12.9716, 77.5946, 18, 480, 480)
        assertEquals(Px(240f, 240f), vp.project(12.9716, 77.5946))
        val oneTileEast = TileMath.worldXToLon(TileMath.lonToWorldX(77.5946, 18) + 256, 18)
        assertEquals(496f, vp.project(12.9716, oneTileEast).x, 0.01f)
    }

    /** Spec §2.4: no prefetch beyond the visible 3×3 tiles; the visible ones cover the whole image. */
    @Test fun tilesAreAtMostThreeByThreeAndCoverTheViewport() {
        for (i in 0 until 50) {
            val vp = Viewport(12.9716 + i * 0.00037, 77.5946 + i * 0.00041, 18, 480, 480)
            val tiles = vp.tiles()
            assertTrue(tiles.size in 4..9, "got ${tiles.size}")
            for ((x, y) in listOf(0f to 0f, 479f to 0f, 0f to 479f, 479f to 479f, 240f to 240f)) {
                assertTrue(tiles.any { x >= it.left && x < it.left + 256 && y >= it.top && y < it.top + 256 }, "($x,$y) uncovered at $i")
            }
        }
    }

    @Test fun tilesWrapAroundTheAntimeridian() {
        val tiles = Viewport(0.0, 179.9999, 18, 480, 480).tiles()
        val n = 1 shl 18
        assertTrue(tiles.all { it.tile.x in 0 until n })
        assertTrue(tiles.any { it.tile.x == 0 }, "east of 180° wraps to column 0")
    }

    /** Spec §2.3: zoom 18 default, 17 for Cycle. */
    @Test fun zoomPerWorkoutType() {
        assertEquals(17, Viewport.zoomFor(WorkoutType.Cycle))
        for (t in listOf(WorkoutType.Walk, WorkoutType.Run, WorkoutType.Auto)) assertEquals(18, Viewport.zoomFor(t))
    }

    @Test fun fitKeepsEveryPointInsideThePadding() {
        val pts = listOf(12.9716 to 77.5946, 12.9761 to 77.5990, 12.9700 to 77.6010)
        val vp = assertNotNull(Viewport.fit(pts, 300, 200, paddingPx = 16))
        for ((lat, lon) in pts) {
            val p = vp.project(lat, lon)
            assertTrue(p.x in 15.9f..284.1f && p.y in 15.9f..184.1f, "$p outside")
        }
        assertEquals(18, Viewport.fit(listOf(12.9716 to 77.5946), 300, 200)!!.zoom, "one point = max zoom")
        assertNull(Viewport.fit(emptyList(), 300, 200))
    }

    @Test fun metersPerPixelShrinksTowardsThePoles() =
        assertTrue(abs(TileMath.metersPerPixel(60.0, 18) - TileMath.metersPerPixel(0.0, 18) / 2) < 0.01)
}
```

- [ ] **Step 3: Run test to verify it fails**

Run: `export JAVA_HOME=$(/usr/libexec/java_home -v 17) && ./gradlew :core:map:test`
Expected: FAIL — `TileMath`, `Viewport`, `TileId`, `Px` unresolved.

- [ ] **Step 4: Implement `TileMath.kt`**

```kotlin
package com.debasish.livefit.map

import kotlin.math.PI
import kotlin.math.atan
import kotlin.math.cos
import kotlin.math.floor
import kotlin.math.ln
import kotlin.math.pow
import kotlin.math.sinh
import kotlin.math.tan

/** Spec §2.4: always visible on every map (glasses image, watch map). */
const val OSM_ATTRIBUTION = "© OpenStreetMap contributors"

/** One slippy-map tile (OpenStreetMap numbering). */
data class TileId(val z: Int, val x: Int, val y: Int)

/** A pixel position inside a viewport (origin top-left). */
data class Px(val x: Float, val y: Float)

/** Web-Mercator slippy-tile math in "world pixels" (256 × 2^z per side), spec §2.3. */
object TileMath {
    const val TILE_SIZE = 256
    const val EARTH_CIRCUMFERENCE_M = 40_075_016.686
    const val MAX_LAT = 85.05112878

    fun worldSize(z: Int): Double = TILE_SIZE * 2.0.pow(z)

    fun lonToWorldX(lon: Double, z: Int): Double = (lon + 180.0) / 360.0 * worldSize(z)

    fun latToWorldY(lat: Double, z: Int): Double {
        val r = Math.toRadians(lat.coerceIn(-MAX_LAT, MAX_LAT))
        return (1.0 - ln(tan(r) + 1.0 / cos(r)) / PI) / 2.0 * worldSize(z)
    }

    fun worldXToLon(x: Double, z: Int): Double = x / worldSize(z) * 360.0 - 180.0

    fun worldYToLat(y: Double, z: Int): Double = Math.toDegrees(atan(sinh(PI - 2.0 * PI * y / worldSize(z))))

    fun tileFor(lat: Double, lon: Double, z: Int): TileId {
        val n = 1 shl z
        val x = Math.floorMod(floor(lonToWorldX(lon, z) / TILE_SIZE).toInt(), n)
        val y = floor(latToWorldY(lat, z) / TILE_SIZE).toInt().coerceIn(0, n - 1)
        return TileId(z, x, y)
    }

    fun metersPerPixel(lat: Double, z: Int): Double = EARTH_CIRCUMFERENCE_M * cos(Math.toRadians(lat)) / worldSize(z)
}
```

- [ ] **Step 5: Implement `Viewport.kt`**

```kotlin
package com.debasish.livefit.map

import com.debasish.livefit.model.WorkoutType
import kotlin.math.floor

/** A tile placed at screen offset ([left], [top]) inside a viewport; may be partly outside it. */
data class PlacedTile(val tile: TileId, val left: Float, val top: Float)

/** North-up view centred on a point (spec §2.3); shared by the glasses renderer and the watch map so both look alike. */
data class Viewport(val centerLat: Double, val centerLon: Double, val zoom: Int, val widthPx: Int, val heightPx: Int) {
    private val cx = TileMath.lonToWorldX(centerLon, zoom)
    private val cy = TileMath.latToWorldY(centerLat, zoom)

    fun project(lat: Double, lon: Double): Px = Px(
        (TileMath.lonToWorldX(lon, zoom) - cx + widthPx / 2.0).toFloat(),
        (TileMath.latToWorldY(lat, zoom) - cy + heightPx / 2.0).toFloat(),
    )

    /** Exactly the tiles that intersect the view: at most 3×3 for 480 px (spec §2.4: no prefetch beyond them). */
    fun tiles(): List<PlacedTile> {
        val size = TileMath.TILE_SIZE
        val n = 1 shl zoom
        val left = cx - widthPx / 2.0
        val top = cy - heightPx / 2.0
        val firstCol = floor(left / size).toInt()
        val lastCol = floor((left + widthPx - 1e-6) / size).toInt()
        val firstRow = floor(top / size).toInt()
        val lastRow = floor((top + heightPx - 1e-6) / size).toInt()
        val out = ArrayList<PlacedTile>()
        for (row in firstRow..lastRow) {
            if (row < 0 || row >= n) continue
            for (col in firstCol..lastCol) {
                out += PlacedTile(TileId(zoom, Math.floorMod(col, n), row), (col * size - left).toFloat(), (row * size - top).toFloat())
            }
        }
        return out
    }

    fun metersPerPixel(): Double = TileMath.metersPerPixel(centerLat, zoom)

    companion object {
        const val DEFAULT_ZOOM = 18
        const val CYCLE_ZOOM = 17
        const val MIN_ZOOM = 14
        const val MAX_ZOOM = 18

        fun zoomFor(type: WorkoutType): Int = if (type == WorkoutType.Cycle) CYCLE_ZOOM else DEFAULT_ZOOM

        /** The highest zoom (≤ [maxZoom]) whose view holds every point inside [paddingPx]; null for no points. */
        fun fit(points: List<Pair<Double, Double>>, widthPx: Int, heightPx: Int, paddingPx: Int = 16, maxZoom: Int = MAX_ZOOM): Viewport? {
            if (points.isEmpty()) return null
            val minLat = points.minOf { it.first }; val maxLat = points.maxOf { it.first }
            val minLon = points.minOf { it.second }; val maxLon = points.maxOf { it.second }
            fun at(z: Int): Viewport {
                val x = (TileMath.lonToWorldX(minLon, z) + TileMath.lonToWorldX(maxLon, z)) / 2
                val y = (TileMath.latToWorldY(maxLat, z) + TileMath.latToWorldY(minLat, z)) / 2
                return Viewport(TileMath.worldYToLat(y, z), TileMath.worldXToLon(x, z), z, widthPx, heightPx)
            }
            for (z in maxZoom downTo 1) {
                val w = TileMath.lonToWorldX(maxLon, z) - TileMath.lonToWorldX(minLon, z)
                val h = TileMath.latToWorldY(minLat, z) - TileMath.latToWorldY(maxLat, z)
                if (w <= widthPx - 2 * paddingPx && h <= heightPx - 2 * paddingPx) return at(z)
            }
            return at(1)
        }
    }
}
```

- [ ] **Step 6: Run tests to verify they pass**

Run: `export JAVA_HOME=$(/usr/libexec/java_home -v 17) && ./gradlew :core:map:test`
Expected: PASS (9 tests).

- [ ] **Step 7: Commit**

```bash
git add settings.gradle.kts core/map services/sync/build.gradle.kts
git commit -m "feat(map): :core:map module with slippy-tile math and viewport"
```

---

### Task 3: Geo, RouteTrack and route state types

**Files:**
- Create: `core/model/src/main/kotlin/com/debasish/livefit/model/Route.kt`
- Test: `core/model/src/test/kotlin/com/debasish/livefit/model/RouteTrackTest.kt`

**Interfaces:**
- Consumes: `FixSource`, `LocationFix`, `WorkoutType` (Task 1).
- Produces:
  - `object FixQuality { MAX_ACCURACY_M = 30f; fun accurate(accuracyM: Float?): Boolean }` — **the one accuracy rule** (route filter and usable-live on phone and watch): `null` (unknown) is never accurate (review #8).
  - `@Serializable data class RouteFix(source: FixSource, lat: Double, lon: Double, accuracyM: Float, deviceTimeMs: Long, phoneTimeMs: Long?, bearingDeg: Float? = null) { fun point(): RoutePoint?; fun historyPoint(): RoutePoint }` — one durable `route_point` row: identity `(source, deviceTimeMs)`, `phoneTimeMs` null while uncalibrated (review #1/#2). `point()` is null until it has a phone time; `historyPoint()` falls back to device time (history display only).
  - `fun LocationFix.toRouteFix(source: FixSource, phoneTimeMs: Long?): RouteFix?` — null unless `FixQuality.accurate(accuracyM)`.
  - `object Geo { fun distanceM(lat1, lon1, lat2, lon2): Double; fun bearingDeg(lat1, lon1, lat2, lon2): Float }`
  - `@Serializable data class RoutePoint(source: FixSource, lat: Double, lon: Double, accuracyM: Float, deviceTimeMs: Long, fixTimeMs: Long, bearingDeg: Float? = null)` — `deviceTimeMs` = the measuring device's clock, `fixTimeMs` = phone time used for ordering.
  - `enum class RouteAdd { Added, Inaccurate, TooClose, Duplicate, Future }`
  - `class RouteTrack { val points: List<RoutePoint>; fun add(p: RoutePoint, nowMs: Long): RouteAdd; fun drawn(): List<RoutePoint>; fun lastBearing(): Float?; companion { MAX_ACCURACY_M = FixQuality.MAX_ACCURACY_M; MIN_SPACING_M = 3.0; DUP_TIME_MS = 50L; DUP_DISTANCE_M = 1.0; OVERLAP_MS = 5_000L; MAX_FUTURE_MS = 120_000L; fun of(points: List<RoutePoint>, nowMs: Long = Long.MAX_VALUE): RouteTrack } }` — `of(points, now)` rebuilds from scratch (after a calibration change); only points with a phone time ever reach a track.
  - `enum class GpsStatus { Waiting, Live, Delayed, Lost }`
  - `data class LivePosition(lat: Double, lon: Double, bearingDeg: Float?, source: FixSource, fixTimeMs: Long)`
  - `data class RouteState(sessionId: String? = null, type: WorkoutType = WorkoutType.Walk, route: List<RoutePoint> = emptyList(), start: RoutePoint? = null, live: LivePosition? = null, status: GpsStatus = GpsStatus.Waiting)`

- [ ] **Step 1: Write the failing test** — `core/model/src/test/kotlin/com/debasish/livefit/model/RouteTrackTest.kt`:

```kotlin
package com.debasish.livefit.model

import kotlin.math.cos
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class RouteTrackTest {
    private val lat0 = 12.9716
    private val lon0 = 77.5946
    private fun p(src: FixSource, tMs: Long, northM: Double, acc: Float = 5f, eastM: Double = 0.0) = RoutePoint(
        src, lat0 + northM / 111_195.0, lon0 + eastM / (111_195.0 * cos(Math.toRadians(lat0))), acc, deviceTimeMs = tMs, fixTimeMs = tMs,
    )
    private val now = 1_000_000L

    @Test fun geoDistanceAndBearing() {
        assertEquals(111_195.0, Geo.distanceM(0.0, 0.0, 1.0, 0.0), 1.0)
        assertEquals(0f, Geo.bearingDeg(0.0, 0.0, 1.0, 0.0), 0.01f)
        assertEquals(90f, Geo.bearingDeg(0.0, 0.0, 0.0, 1.0), 0.01f)
    }

    /** Spec §2.2: sorted by fixTimeMs, not arrival; an older replayed watch fix lands chronologically. */
    @Test fun keepsPointsSortedByFixTimeNotArrival() {
        val t = RouteTrack()
        assertEquals(RouteAdd.Added, t.add(p(FixSource.Watch, 3_000, 30.0), now))
        assertEquals(RouteAdd.Added, t.add(p(FixSource.Watch, 1_000, 10.0), now))
        assertEquals(RouteAdd.Added, t.add(p(FixSource.Watch, 2_000, 20.0), now))
        assertEquals(listOf(1_000L, 2_000L, 3_000L), t.points.map { it.fixTimeMs })
    }

    /** Review Focus #2: a 30 s screen-off batch arriving late slots in behind phone points by time. */
    @Test fun lateBatchInsertsChronologically() {
        val t = RouteTrack()
        for (s in 0..10) t.add(p(FixSource.Watch, s * 1_000L, s * 10.0), now)
        for (s in 15..40) t.add(p(FixSource.Phone, s * 1_000L, s * 10.0 + 2.0), now) // fallback while the watch was silent
        for (s in 11..40) t.add(p(FixSource.Watch, s * 1_000L, s * 10.0), now)       // the late batch
        val times = t.points.map { it.fixTimeMs }
        assertEquals(times.sorted(), times, "chronological")
        val drawn = t.drawn()
        assertTrue(drawn.all { it.source == FixSource.Watch }, "phone points within ±5 s of watch points are hidden")
        assertEquals(41, drawn.size)
        assertEquals(26, t.points.count { it.source == FixSource.Phone }, "phone points stay stored for diagnostics")
    }

    @Test fun phonePointsOutsideWatchCoverageAreDrawn() {
        val t = RouteTrack()
        t.add(p(FixSource.Watch, 0, 0.0), now)
        t.add(p(FixSource.Phone, 4_000, 10.0), now)  // within 5 s → hidden
        t.add(p(FixSource.Phone, 6_000, 20.0), now)  // 6 s after the only watch point → drawn
        assertEquals(listOf(FixSource.Watch, FixSource.Phone), t.drawn().map { it.source })
    }

    @Test fun dropsInaccurateFixes() {
        val t = RouteTrack()
        assertEquals(RouteAdd.Inaccurate, t.add(p(FixSource.Watch, 0, 0.0, acc = 30.5f), now))
        assertEquals(RouteAdd.Added, t.add(p(FixSource.Watch, 0, 0.0, acc = 30f), now))
    }

    @Test fun dropsPointsCloserThan3mToTheirSameSourceNeighbour() {
        val t = RouteTrack()
        t.add(p(FixSource.Watch, 0, 0.0), now)
        assertEquals(RouteAdd.TooClose, t.add(p(FixSource.Watch, 1_000, 2.0), now))
        assertEquals(RouteAdd.Added, t.add(p(FixSource.Phone, 1_000, 2.0), now), "other source: no spacing rule")
        assertEquals(RouteAdd.Added, t.add(p(FixSource.Watch, 2_000, 3.5), now))
        assertEquals(RouteAdd.TooClose, t.add(p(FixSource.Watch, 1_500, 1.0), now), "too close to the earlier neighbour")
    }

    /** Spec §2.2: replay/resend safe. */
    @Test fun duplicatesAreIgnored() {
        val t = RouteTrack()
        t.add(p(FixSource.Watch, 10_000, 0.0), now)
        val resend = p(FixSource.Watch, 10_040, 0.5).copy(fixTimeMs = 10_300) // re-mapped with a newer offset
        assertEquals(RouteAdd.Duplicate, t.add(resend, now))
        assertEquals(1, t.points.size)
    }

    @Test fun rejectsFixesMoreThanTwoMinutesInTheFuture() {
        val t = RouteTrack()
        assertEquals(RouteAdd.Future, t.add(p(FixSource.Watch, now + 120_001, 0.0), now))
        assertEquals(RouteAdd.Added, t.add(p(FixSource.Watch, now + 119_000, 0.0), now))
    }

    @Test fun tiesBreakWatchFirst() {
        val t = RouteTrack()
        t.add(p(FixSource.Phone, 5_000, 0.0), now)
        t.add(p(FixSource.Watch, 5_000, 50.0), now)
        assertEquals(listOf(FixSource.Watch, FixSource.Phone), t.points.map { it.source })
    }

    @Test fun bearingFromTheLastTwoDrawnPoints() {
        val t = RouteTrack()
        assertNull(t.lastBearing())
        t.add(p(FixSource.Watch, 0, 0.0), now)
        t.add(p(FixSource.Watch, 1_000, 0.0, eastM = 20.0), now)
        assertEquals(90f, assertNotNull(t.lastBearing()), 0.5f)
    }

    /** Review #8: unknown accuracy is never accurate — no route row, no route point, never live (one shared rule). */
    @Test fun unknownAccuracyFailsTheAccuracyGate() {
        assertFalse(FixQuality.accurate(null))
        assertTrue(FixQuality.accurate(30f))
        assertFalse(FixQuality.accurate(30.01f))
        assertNull(LocationFix(lat0, lon0, null, null, 1_000).toRouteFix(FixSource.Watch, 1_000))
        assertNull(LocationFix(lat0, lon0, 31f, null, 1_000).toRouteFix(FixSource.Watch, 1_000))
    }

    /** Review #2: an uncalibrated row keeps its device-time identity and takes no part in ordering until it has a phone time. */
    @Test fun routeFixWithoutPhoneTimeIsNotARoutePointYet() {
        val f = assertNotNull(LocationFix(lat0, lon0, 5f, 90f, 7_000).toRouteFix(FixSource.Watch, phoneTimeMs = null))
        assertEquals(7_000L, f.deviceTimeMs)
        assertNull(f.point())
        val mapped = assertNotNull(f.copy(phoneTimeMs = 2_000).point())
        assertEquals(2_000L, mapped.fixTimeMs)
        assertEquals(7_000L, mapped.deviceTimeMs, "identity unchanged by calibration")
        assertEquals(90f, mapped.bearingDeg)
        assertEquals(7_000L, f.historyPoint().fixTimeMs, "history fallback: device time")
    }

    /** Review #2: a rebuild after calibration applies the future check with the real clock; history (no clock) never does. */
    @Test fun ofWithNowRejectsOnlyFuturePoints() {
        val pts = listOf(p(FixSource.Watch, now + 200_000, 0.0), p(FixSource.Watch, now - 1_000, 10.0))
        assertEquals(listOf(now - 1_000), RouteTrack.of(pts, now).points.map { it.fixTimeMs })
        assertEquals(2, RouteTrack.of(pts).points.size)
    }

    @Test fun ofRestoresStoredPointsWhateverTheirAge() {
        val stored = listOf(p(FixSource.Watch, 2_000, 10.0), p(FixSource.Watch, 1_000, 0.0))
        assertEquals(listOf(1_000L, 2_000L), RouteTrack.of(stored).points.map { it.fixTimeMs })
    }
}
```

- [ ] **Step 2: Run test to verify it fails**

Run: `export JAVA_HOME=$(/usr/libexec/java_home -v 17) && ./gradlew :core:model:test --tests '*RouteTrackTest*'`
Expected: FAIL — `RouteTrack`, `RoutePoint`, `Geo` unresolved.

- [ ] **Step 3: Implement `Route.kt`**

```kotlin
package com.debasish.livefit.model

import kotlinx.serialization.Serializable
import kotlin.math.asin
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.min
import kotlin.math.pow
import kotlin.math.sin
import kotlin.math.sqrt

object Geo {
    const val EARTH_RADIUS_M = 6_371_008.8

    fun distanceM(lat1: Double, lon1: Double, lat2: Double, lon2: Double): Double {
        val p1 = Math.toRadians(lat1); val p2 = Math.toRadians(lat2)
        val dp = p2 - p1; val dl = Math.toRadians(lon2 - lon1)
        val a = sin(dp / 2).pow(2) + cos(p1) * cos(p2) * sin(dl / 2).pow(2)
        return 2 * EARTH_RADIUS_M * asin(sqrt(min(1.0, a)))
    }

    /** Initial great-circle bearing, 0 = north, clockwise, 0..360. */
    fun bearingDeg(lat1: Double, lon1: Double, lat2: Double, lon2: Double): Float {
        val p1 = Math.toRadians(lat1); val p2 = Math.toRadians(lat2); val dl = Math.toRadians(lon2 - lon1)
        val y = sin(dl) * cos(p2)
        val x = cos(p1) * sin(p2) - sin(p1) * cos(p2) * cos(dl)
        return ((Math.toDegrees(atan2(y, x)) + 360.0) % 360.0).toFloat()
    }
}

/**
 * One kept route point. [deviceTimeMs] is the fix time on the measuring device's clock (stable across re-calibration,
 * used for duplicate detection and as the storage key); [fixTimeMs] is phone time, used for ordering (spec §2.2).
 */
@Serializable
data class RoutePoint(
    val source: FixSource,
    val lat: Double,
    val lon: Double,
    val accuracyM: Float,
    val deviceTimeMs: Long,
    val fixTimeMs: Long,
    val bearingDeg: Float? = null,
)

/** The one accuracy rule (spec §2.1/§2.2): route filter and usable-live, phone and watch. Unknown (null) is never accurate. */
object FixQuality {
    const val MAX_ACCURACY_M = 30f
    fun accurate(accuracyM: Float?): Boolean = accuracyM != null && accuracyM <= MAX_ACCURACY_M
}

/**
 * One durable route row (route_point, spec §2.2). Identity = ([source], [deviceTimeMs]) on the measuring device's clock and
 * never changes; [phoneTimeMs] is the calibrated phone time — null while the watch clock is uncalibrated, rewritten when a
 * (re-)calibration normalizes the session (review #2). Only accurate fixes become rows.
 */
@Serializable
data class RouteFix(
    val source: FixSource,
    val lat: Double,
    val lon: Double,
    val accuracyM: Float,
    val deviceTimeMs: Long,
    val phoneTimeMs: Long?,
    val bearingDeg: Float? = null,
) {
    /** The ordered point once it has a phone time; uncalibrated rows stay out of ordering, overlap and future checks. */
    fun point(): RoutePoint? = phoneTimeMs?.let { RoutePoint(source, lat, lon, accuracyM, deviceTimeMs, it, bearingDeg) }

    /** History display only (a finished session whose rows were never normalized falls back to device time). */
    fun historyPoint(): RoutePoint = RoutePoint(source, lat, lon, accuracyM, deviceTimeMs, phoneTimeMs ?: deviceTimeMs, bearingDeg)
}

/** A route row for this fix, or null when its accuracy is unknown or worse than 30 m. */
fun LocationFix.toRouteFix(source: FixSource, phoneTimeMs: Long?): RouteFix? {
    val acc = accuracyM?.takeIf { FixQuality.accurate(it) } ?: return null
    return RouteFix(source, lat, lon, acc, fixTimeMs, phoneTimeMs, bearingDeg)
}

enum class RouteAdd { Added, Inaccurate, TooClose, Duplicate, Future }

enum class GpsStatus { Waiting, Live, Delayed, Lost }

/** The current-position marker: the latest live point, or the last one while degraded (spec §2.1). */
data class LivePosition(val lat: Double, val lon: Double, val bearingDeg: Float?, val source: FixSource, val fixTimeMs: Long)

/** What the map renderers draw. */
data class RouteState(
    val sessionId: String? = null,
    val type: WorkoutType = WorkoutType.Walk,
    val route: List<RoutePoint> = emptyList(),
    val start: RoutePoint? = null,
    val live: LivePosition? = null,
    val status: GpsStatus = GpsStatus.Waiting,
)

/**
 * Chronological merge of watch and phone fixes (spec §2.2): sorted by phone-time [RoutePoint.fixTimeMs] (ties watch first),
 * inaccurate (> 30 m) and too-close (< 3 m from the same source's chronological neighbour) points dropped, duplicates
 * (same source, ±50 ms device time, ±1 m) ignored, points > 2 min in the future rejected. Phone points within ±5 s of a
 * watch point stay in [points] (storage) but are hidden from [drawn].
 */
class RouteTrack {
    private val sorted = ArrayList<RoutePoint>()
    val points: List<RoutePoint> get() = sorted

    fun add(p: RoutePoint, nowMs: Long): RouteAdd {
        if (!FixQuality.accurate(p.accuracyM)) return RouteAdd.Inaccurate
        if (p.fixTimeMs - nowMs > MAX_FUTURE_MS) return RouteAdd.Future
        val i = insertionIndex(p)
        val neighbours = listOfNotNull(neighbour(i - 1, -1, p.source), neighbour(i, 1, p.source))
        if (neighbours.any { kotlin.math.abs(it.deviceTimeMs - p.deviceTimeMs) <= DUP_TIME_MS && distance(it, p) <= DUP_DISTANCE_M }) return RouteAdd.Duplicate
        if (neighbours.any { distance(it, p) < MIN_SPACING_M }) return RouteAdd.TooClose
        sorted.add(i, p)
        return RouteAdd.Added
    }

    fun drawn(): List<RoutePoint> {
        val watchTimes = sorted.filter { it.source == FixSource.Watch }.map { it.fixTimeMs }
        if (watchTimes.isEmpty()) return sorted.toList()
        return sorted.filter { it.source == FixSource.Watch || !nearWatch(it.fixTimeMs, watchTimes) }
    }

    /** Bearing from the last two drawn points (used when the fix has none). */
    fun lastBearing(): Float? {
        val d = drawn()
        if (d.size < 2) return null
        val a = d[d.size - 2]; val b = d.last()
        return Geo.bearingDeg(a.lat, a.lon, b.lat, b.lon)
    }

    private fun before(a: RoutePoint, b: RoutePoint): Boolean =
        a.fixTimeMs < b.fixTimeMs || (a.fixTimeMs == b.fixTimeMs && a.source == FixSource.Watch && b.source == FixSource.Phone)

    private fun insertionIndex(p: RoutePoint): Int {
        var lo = 0; var hi = sorted.size
        while (lo < hi) { val mid = (lo + hi) ushr 1; if (before(p, sorted[mid])) hi = mid else lo = mid + 1 }
        return lo
    }

    private fun neighbour(from: Int, step: Int, source: FixSource): RoutePoint? {
        var j = from
        while (j in sorted.indices) { if (sorted[j].source == source) return sorted[j]; j += step }
        return null
    }

    private fun nearWatch(t: Long, times: List<Long>): Boolean {
        val found = times.binarySearch(t)
        if (found >= 0) return true
        val i = -found - 1
        return (i < times.size && times[i] - t <= OVERLAP_MS) || (i > 0 && t - times[i - 1] <= OVERLAP_MS)
    }

    private fun distance(a: RoutePoint, b: RoutePoint) = Geo.distanceM(a.lat, a.lon, b.lat, b.lon)

    companion object {
        const val MAX_ACCURACY_M = FixQuality.MAX_ACCURACY_M
        const val MIN_SPACING_M = 3.0
        const val DUP_TIME_MS = 50L
        const val DUP_DISTANCE_M = 1.0
        const val OVERLAP_MS = 5_000L
        const val MAX_FUTURE_MS = 120_000L

        /**
         * Rebuilds a track from scratch: after a calibration change with the real [nowMs] (review #2: a rebuild, not an
         * incremental repair, so the duplicate filter can never block corrected times), or from history without a clock.
         */
        fun of(points: List<RoutePoint>, nowMs: Long = Long.MAX_VALUE): RouteTrack =
            RouteTrack().also { t -> points.sortedBy { it.fixTimeMs }.forEach { t.add(it, nowMs) } }
    }
}
```

- [ ] **Step 4: Run tests to verify they pass**

Run: `export JAVA_HOME=$(/usr/libexec/java_home -v 17) && ./gradlew :core:model:test`
Expected: PASS.

- [ ] **Step 5: Commit**

```bash
git add core/model
git commit -m "feat(model): RouteTrack chronological merge, shared accuracy rule and RouteFix rows with device-time identity"
```

---

### Task 4: Time sync, freshness and live source selection

**Files:**
- Create: `services/sync/src/main/kotlin/com/debasish/livefit/sync/WatchClockSync.kt`
- Create: `services/sync/src/main/kotlin/com/debasish/livefit/sync/Freshness.kt`
- Create: `services/sync/src/main/kotlin/com/debasish/livefit/sync/LiveLocationSelector.kt`
- Test: `services/sync/src/test/kotlin/com/debasish/livefit/sync/LocationTimingTest.kt`

**Interfaces:**
- Consumes: `LocationFix`, `FixSource` (Task 1); `GpsStatus`, `LivePosition`, `FixQuality` (Task 3); `Clock` (existing `:core:services`).
- Produces:
  - `class WatchClockSync(clock: Clock, maxRttMs: Long = 1_000, maxTries: Int = 5) { val offsetMs: StateFlow<Long?>; val calibrated: Boolean; suspend fun calibrate(ping: suspend (t0: Long) -> Long?): Boolean; fun toPhoneTime(watchMs: Long): Long?; companion { PERIOD_MS = 300_000L; RETRY_UNCALIBRATED_MS = 30_000L } }` — offset = watch − phone.
  - `object Freshness { MAX_AGE_MS = 10_000L; FUTURE_TOLERANCE_MS = 2_000L; MAX_ACCURACY_M = FixQuality.MAX_ACCURACY_M; DELAYED_UNTIL_MS = 30_000L; fun isUsableLive(phoneTimeMs: Long?, accuracyM: Float?, nowMs: Long): Boolean; fun status(lastLivePhoneTimeMs: Long?, nowMs: Long): GpsStatus }` — the shared usable-live predicate (phone selector, RouteHub, watch map); unknown accuracy is never usable (review #8).
  - `class LiveLocationSelector(fallbackAfterMs = 15_000, watchStableMs = 10_000, continuityGapMs = 3_000) { val fallback: Boolean; fun begin(gpsWorkout: Boolean, nowMs: Long); fun setGpsWorkout(on: Boolean, nowMs: Long); fun onWatchFix(fix: LocationFix, phoneTimeMs: Long?, nowMs: Long); fun onPhoneFix(fix: LocationFix, nowMs: Long); fun update(nowMs: Long): Boolean; fun current(nowMs: Long): LivePosition?; fun status(nowMs: Long): GpsStatus }` — freshness and the marker use measurement time; the 10 s recovery run uses **observed (arrival) time** (review #3).

- [ ] **Step 1: Write the failing test** — `services/sync/src/test/kotlin/com/debasish/livefit/sync/LocationTimingTest.kt`:

```kotlin
package com.debasish.livefit.sync

import com.debasish.livefit.model.FixSource
import com.debasish.livefit.model.GpsStatus
import com.debasish.livefit.model.LocationFix
import com.debasish.livefit.services.Clock
import kotlinx.coroutines.delay
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class LocationTimingTest {
    private fun fix(t: Long, acc: Float = 5f, northM: Double = 0.0) = LocationFix(12.9716 + northM / 111_195.0, 77.5946, acc, null, t)

    // ---- Clock calibration (spec §2.1) ----

    @Test fun offsetIsMidpointCorrected() = runTest {
        val s = WatchClockSync(Clock { testScheduler.currentTime })
        assertTrue(s.calibrate { t0 -> delay(200); t0 + 100 + 5_000 }) // watch 5 s ahead, 100 ms each way
        assertEquals(5_000L, s.offsetMs.value)
        assertEquals(1_000L, s.toPhoneTime(6_000))
    }

    @Test fun slowRoundTripsAreRetriedUpToFiveTimes() = runTest {
        var calls = 0
        val s = WatchClockSync(Clock { testScheduler.currentTime })
        assertFalse(s.calibrate { t0 -> calls++; delay(1_001); t0 })
        assertEquals(5, calls)
        assertNull(s.offsetMs.value)
        assertNull(s.toPhoneTime(1_000), "uncalibrated: no phone time, so never live")
    }

    @Test fun aLaterFastPingIsAccepted() = runTest {
        var calls = 0
        val s = WatchClockSync(Clock { testScheduler.currentTime })
        assertTrue(s.calibrate { t0 -> calls++; delay(if (calls < 3) 1_500L else 400L); t0 + 2_000 + 200 })
        assertEquals(3, calls)
        assertEquals(2_000L, s.offsetMs.value)
    }

    @Test fun noReplyCountsAsAFailedTry() = runTest {
        var calls = 0
        val s = WatchClockSync(Clock { testScheduler.currentTime })
        assertFalse(s.calibrate { calls++; null })
        assertEquals(5, calls)
    }

    @Test fun aFailedRecalibrationKeepsThePreviousOffset() = runTest {
        val s = WatchClockSync(Clock { testScheduler.currentTime })
        s.calibrate { it + 700 }
        assertFalse(s.calibrate { null })
        assertEquals(700L, s.offsetMs.value)
        assertTrue(s.calibrated)
    }

    // ---- Freshness (spec §2.1) ----

    @Test fun freshnessWindowIncludesTwoSecondsOfFuture() {
        val now = 100_000L
        assertTrue(Freshness.isUsableLive(now + 2_000, 5f, now))
        assertFalse(Freshness.isUsableLive(now + 2_001, 5f, now), "further in the future is not live")
        assertTrue(Freshness.isUsableLive(now - 10_000, 5f, now))
        assertFalse(Freshness.isUsableLive(now - 10_001, 5f, now))
        assertFalse(Freshness.isUsableLive(null, 5f, now), "uncalibrated")
    }

    @Test fun accuracyGate() {
        assertTrue(Freshness.isUsableLive(0, 30f, 0))
        assertFalse(Freshness.isUsableLive(0, 30.1f, 0))
        assertFalse(Freshness.isUsableLive(0, null, 0), "unknown accuracy is never usable-live (review #8)")
    }

    @Test fun statusThresholds() {
        assertEquals(GpsStatus.Waiting, Freshness.status(null, 0))
        assertEquals(GpsStatus.Live, Freshness.status(0, 10_000))
        assertEquals(GpsStatus.Delayed, Freshness.status(0, 10_001))
        assertEquals(GpsStatus.Delayed, Freshness.status(0, 30_000))
        assertEquals(GpsStatus.Lost, Freshness.status(0, 30_001))
    }

    // ---- Source selection (spec §2.1) ----

    @Test fun fallbackStartsAfter15sWithoutALiveWatchFix() {
        val sel = LiveLocationSelector().apply { begin(gpsWorkout = true, nowMs = 0) }
        assertFalse(sel.update(14_999))
        assertTrue(sel.update(15_000))
    }

    @Test fun notAGpsWorkoutNeverFallsBack() {
        val sel = LiveLocationSelector().apply { begin(gpsWorkout = false, nowMs = 0) }
        assertFalse(sel.update(60_000))
    }

    @Test fun uncalibratedWatchFixesNeverCount() {
        val sel = LiveLocationSelector().apply { begin(true, 0) }
        for (s in 0..20L) sel.onWatchFix(fix(s * 1_000), phoneTimeMs = null, nowMs = s * 1_000)
        assertTrue(sel.update(20_000))
        assertEquals(GpsStatus.Waiting, sel.status(20_000))
        assertNull(sel.current(20_000))
    }

    @Test fun tenContinuousSecondsOfLiveWatchFixesStopFallback() {
        val sel = LiveLocationSelector().apply { begin(true, 0) }
        assertTrue(sel.update(15_000))
        for (s in 20..29L) sel.onWatchFix(fix(s * 1_000, northM = s * 5.0), s * 1_000, s * 1_000)
        assertTrue(sel.fallback, "9 s of live watch fixes is not enough")
        sel.onWatchFix(fix(30_000, northM = 150.0), 30_000, 30_000)
        assertFalse(sel.fallback)
    }

    @Test fun aGapRestartsTheTenSecondCount() {
        val sel = LiveLocationSelector().apply { begin(true, 0) }
        sel.update(15_000)
        for (s in (20..25L) + (29..38L)) sel.onWatchFix(fix(s * 1_000), s * 1_000, s * 1_000)
        assertTrue(sel.fallback)
        sel.onWatchFix(fix(39_000), 39_000, 39_000)
        assertFalse(sel.fallback)
    }

    /** Spec §8: accurate phone fallback vs 10 s of inaccurate watch fixes keeps the phone. */
    @Test fun inaccurateWatchFixesKeepThePhone() {
        val sel = LiveLocationSelector().apply { begin(true, 0) }
        sel.update(15_000)
        for (s in 16..32L) {
            sel.onPhoneFix(fix(s * 1_000, northM = s * 4.0), s * 1_000)
            sel.onWatchFix(fix(s * 1_000, acc = 50f), s * 1_000, s * 1_000)
        }
        assertTrue(sel.fallback)
        assertEquals(FixSource.Phone, sel.current(32_000)?.source)
    }

    /** Spec §2.1: replay after a reconnect can't flip the source. */
    @Test fun replayedOldWatchFixesNeverStopFallback() {
        val sel = LiveLocationSelector().apply { begin(true, 0) }
        sel.update(15_000)
        for (s in 20..40L) sel.onWatchFix(fix(s * 1_000 - 60_000), s * 1_000 - 60_000, s * 1_000)
        assertTrue(sel.fallback)
    }

    /** Review Focus #2: a 30 s screen-off batch arrives late; while held back the map says delayed, and it can't flip the source. */
    @Test fun lateBatchedBurstNeitherLiveNorFlipsSource() {
        val sel = LiveLocationSelector().apply { begin(true, 0) }
        for (s in 0..10L) sel.onWatchFix(fix(s * 1_000, northM = s * 5.0), s * 1_000, s * 1_000)
        assertEquals(GpsStatus.Delayed, sel.status(30_000))
        assertTrue(sel.update(30_000), "15 s without a live watch fix → phone fallback")
        for (s in 11..44L) sel.onWatchFix(fix(s * 1_000, northM = s * 5.0), s * 1_000, nowMs = 45_000) // whole batch at once
        assertTrue(sel.fallback, "one batch is one observation: no observed recovery run yet")
        assertEquals(FixSource.Watch, sel.current(45_000)?.source, "a live watch fix still wins the marker")
    }

    /** Review #3: a delayed batch stamped 35..45 s and delivered at 45 s spans the whole live window but is 0 s of recovery. */
    @Test fun oneBatchSpanningTheLiveWindowDoesNotStopFallback() {
        val sel = LiveLocationSelector().apply { begin(true, 0) }
        assertTrue(sel.update(15_000))
        for (s in 35..45L) sel.onWatchFix(fix(s * 1_000, northM = s * 5.0), s * 1_000, nowMs = 45_000)
        assertTrue(sel.fallback, "10 s of measurement time, 0 s of observed time")
        assertEquals(45_000L, sel.current(45_000)?.fixTimeMs, "the marker still takes the newest live fix")
    }

    /** Review #3: after that batch, 10 s of usable fixes arriving on time do stop the fallback. */
    @Test fun tenObservedSecondsAfterTheBatchStopFallback() {
        val sel = LiveLocationSelector().apply { begin(true, 0) }
        sel.update(15_000)
        for (s in 35..45L) sel.onWatchFix(fix(s * 1_000, northM = s * 5.0), s * 1_000, nowMs = 45_000)
        for (s in 46..54L) sel.onWatchFix(fix(s * 1_000, northM = s * 5.0), s * 1_000, s * 1_000)
        assertTrue(sel.fallback, "9 observed seconds")
        sel.onWatchFix(fix(55_000, northM = 275.0), 55_000, 55_000)
        assertFalse(sel.fallback)
    }

    /** Review #8: fresh fixes without accuracy can neither move the marker nor stop the phone fallback. */
    @Test fun fixesWithoutAccuracyAreNeverLive() {
        val sel = LiveLocationSelector().apply { begin(true, 0) }
        assertTrue(sel.update(15_000))
        for (s in 16..40L) sel.onWatchFix(LocationFix(12.9716 + s * 5.0 / 111_195.0, 77.5946, null, null, s * 1_000), s * 1_000, s * 1_000)
        assertTrue(sel.fallback)
        assertNull(sel.current(40_000), "no marker from unknown-accuracy watch fixes")
        assertEquals(GpsStatus.Waiting, sel.status(40_000))
        sel.onPhoneFix(LocationFix(12.9716, 77.5946, null, null, 40_000), 40_000)
        assertNull(sel.current(40_000), "nor from unknown-accuracy phone fixes")
    }

    @Test fun watchWinsWhileBothAreLive() {
        val sel = LiveLocationSelector().apply { begin(true, 0) }
        sel.onPhoneFix(fix(1_000), 1_000)
        sel.onWatchFix(fix(900, northM = 10.0), 900, 1_000)
        assertEquals(FixSource.Watch, sel.current(1_000)?.source)
    }

    /** Spec §2.1: the 3 m thinning is for drawing only; an accurate stationary fix stays live. */
    @Test fun stationaryAccurateFixesStayLive() {
        val sel = LiveLocationSelector().apply { begin(true, 0) }
        for (s in 0..30L) sel.onWatchFix(fix(s * 1_000), s * 1_000, s * 1_000)
        assertEquals(GpsStatus.Live, sel.status(30_000))
        assertFalse(sel.fallback)
    }

    @Test fun degradedStatusKeepsTheLastPoint() {
        val sel = LiveLocationSelector().apply { begin(true, 0) }
        sel.onWatchFix(fix(0, northM = 7.0), 0, 0)
        assertEquals(GpsStatus.Delayed, sel.status(15_000))
        assertNotNull(sel.current(15_000))
        assertEquals(GpsStatus.Lost, sel.status(31_000))
        assertNotNull(sel.current(31_000), "GPS lost keeps the last point")
    }
}
```

- [ ] **Step 2: Run test to verify it fails**

Run: `export JAVA_HOME=$(/usr/libexec/java_home -v 17) && ./gradlew :services:sync:test --tests '*LocationTimingTest*'`
Expected: FAIL — `WatchClockSync`, `Freshness`, `LiveLocationSelector` unresolved.

- [ ] **Step 3: Implement `WatchClockSync.kt`**

```kotlin
package com.debasish.livefit.sync

import com.debasish.livefit.services.Clock
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

/**
 * Phone-side watch clock calibration (spec §2.1): phone sends t0, watch replies with its clock tw, phone receives at t1;
 * offset = tw − (t0 + t1)/2, accepted only if t1 − t0 ≤ [maxRttMs], else retried up to [maxTries]. The offset lives in
 * memory only: until one sync succeeded in this process, watch fixes have no phone time and are never live.
 */
class WatchClockSync(private val clock: Clock, private val maxRttMs: Long = 1_000, private val maxTries: Int = 5) {
    private val _offset = MutableStateFlow<Long?>(null)
    /** Watch clock minus phone clock. */
    val offsetMs: StateFlow<Long?> = _offset
    val calibrated: Boolean get() = _offset.value != null

    /** [ping] sends t0 and returns the watch's tw, or null on failure/timeout. A failed round keeps the previous offset. */
    suspend fun calibrate(ping: suspend (t0: Long) -> Long?): Boolean {
        repeat(maxTries) {
            val t0 = clock.nowMs()
            val tw = ping(t0)
            val t1 = clock.nowMs()
            if (tw != null && t1 - t0 <= maxRttMs) {
                _offset.value = tw - (t0 + t1) / 2
                return true
            }
        }
        return false
    }

    fun toPhoneTime(watchMs: Long): Long? = _offset.value?.let { watchMs - it }

    companion object {
        /** Re-calibrate every 5 min (spec §2.1). */
        const val PERIOD_MS = 300_000L
        /** While no sync has succeeded yet, try again this often (plan decision). */
        const val RETRY_UNCALIBRATED_MS = 30_000L
    }
}
```

- [ ] **Step 4: Implement `Freshness.kt`**

```kotlin
package com.debasish.livefit.sync

import com.debasish.livefit.model.FixQuality
import com.debasish.livefit.model.GpsStatus

/**
 * Spec §2.1 "usable live" and the degraded display thresholds. All times are phone time (the watch map passes its own
 * clock). This is the one predicate used by the phone selector, RouteHub and the watch map (review #7/#8).
 */
object Freshness {
    const val MAX_AGE_MS = 10_000L
    const val FUTURE_TOLERANCE_MS = 2_000L
    const val MAX_ACCURACY_M = FixQuality.MAX_ACCURACY_M
    const val DELAYED_UNTIL_MS = 30_000L

    /** −2 s ≤ age ≤ 10 s and accuracy known and ≤ 30 m; null time = uncalibrated = never live. */
    fun isUsableLive(phoneTimeMs: Long?, accuracyM: Float?, nowMs: Long): Boolean {
        if (phoneTimeMs == null || !FixQuality.accurate(accuracyM)) return false
        val age = nowMs - phoneTimeMs
        return age >= -FUTURE_TOLERANCE_MS && age <= MAX_AGE_MS
    }

    /** From the newest usable-live fix: none → Waiting; ≤ 10 s Live; ≤ 30 s Delayed; older Lost. */
    fun status(lastLivePhoneTimeMs: Long?, nowMs: Long): GpsStatus {
        val t = lastLivePhoneTimeMs ?: return GpsStatus.Waiting
        val age = nowMs - t
        return when {
            age <= MAX_AGE_MS -> GpsStatus.Live
            age <= DELAYED_UNTIL_MS -> GpsStatus.Delayed
            else -> GpsStatus.Lost
        }
    }
}
```

- [ ] **Step 5: Implement `LiveLocationSelector.kt`**

```kotlin
package com.debasish.livefit.sync

import com.debasish.livefit.model.FixSource
import com.debasish.livefit.model.GpsStatus
import com.debasish.livefit.model.LivePosition
import com.debasish.livefit.model.LocationFix

/**
 * Which fix drives the marker and whether the phone fallback runs (spec §2.1). Only usable-live fixes count.
 * - Fallback starts after [fallbackAfterMs] without a usable-live watch fix (measurement time) during a GPS workout.
 * - It stops once watch fixes were usable-live continuously for [watchStableMs] of **observed** time (review #3): the run
 *   is timed by arrival, with no arrival gap > [continuityGapMs], so one late batch — however long a stretch it covers —
 *   is a single observation. A fresh but unusable (inaccurate, unknown accuracy, too far in the future) watch fix breaks
 *   the run; replayed (old) and uncalibrated fixes neither count nor break it.
 * - The marker and freshness use measurement time; while both are usable-live the watch wins the marker.
 */
class LiveLocationSelector(
    private val fallbackAfterMs: Long = 15_000,
    private val watchStableMs: Long = 10_000,
    private val continuityGapMs: Long = 3_000,
) {
    private var gpsWorkout = false
    private var noWatchSinceMs = 0L
    private var watchLive: LivePosition? = null
    /** Arrival (observed) time of the first and of the latest usable-live watch fix of the current recovery run. */
    private var runStartObservedMs: Long? = null
    private var lastLiveObservedMs: Long? = null
    private var phoneLive: LivePosition? = null

    var fallback: Boolean = false
        private set

    /** A new session (or none). */
    fun begin(gpsWorkout: Boolean, nowMs: Long) {
        this.gpsWorkout = gpsWorkout
        noWatchSinceMs = nowMs
        watchLive = null; phoneLive = null
        runStartObservedMs = null; lastLiveObservedMs = null
        fallback = false
    }

    /** The same session became (or stopped being) a recording GPS workout. */
    fun setGpsWorkout(on: Boolean, nowMs: Long) {
        if (on && !gpsWorkout) noWatchSinceMs = nowMs
        gpsWorkout = on
        update(nowMs)
    }

    /** [phoneTimeMs] = the fix time mapped with the calibrated offset; null while uncalibrated. [nowMs] = arrival time. */
    fun onWatchFix(fix: LocationFix, phoneTimeMs: Long?, nowMs: Long) {
        if (phoneTimeMs == null) return
        if (!Freshness.isUsableLive(phoneTimeMs, fix.accuracyM, nowMs)) {
            if (nowMs - phoneTimeMs <= Freshness.MAX_AGE_MS) { runStartObservedMs = null; lastLiveObservedMs = null } // fresh but poor
            update(nowMs)
            return
        }
        if (phoneTimeMs > (watchLive?.fixTimeMs ?: Long.MIN_VALUE)) {
            watchLive = LivePosition(fix.lat, fix.lon, fix.bearingDeg, FixSource.Watch, phoneTimeMs)
        }
        val last = lastLiveObservedMs
        if (runStartObservedMs == null || last == null || nowMs - last > continuityGapMs) runStartObservedMs = nowMs
        lastLiveObservedMs = nowMs
        update(nowMs)
    }

    /** Phone fixes are already in phone time. */
    fun onPhoneFix(fix: LocationFix, nowMs: Long) {
        if (Freshness.isUsableLive(fix.fixTimeMs, fix.accuracyM, nowMs) && fix.fixTimeMs >= (phoneLive?.fixTimeMs ?: Long.MIN_VALUE)) {
            phoneLive = LivePosition(fix.lat, fix.lon, fix.bearingDeg, FixSource.Phone, fix.fixTimeMs)
        }
        update(nowMs)
    }

    /** Re-evaluates the fallback at [nowMs]; returns whether the phone GPS should run. */
    fun update(nowMs: Long): Boolean {
        if (!gpsWorkout) { fallback = false; return false }
        if (!fallback) {
            if (nowMs - maxOf(watchLive?.fixTimeMs ?: Long.MIN_VALUE, noWatchSinceMs) >= fallbackAfterMs) fallback = true
        } else {
            val start = runStartObservedMs
            val last = lastLiveObservedMs
            if (start != null && last != null && last - start >= watchStableMs && nowMs - last <= continuityGapMs) fallback = false
        }
        return fallback
    }

    /** The marker: a live watch fix, else a live phone fix, else the newest one seen (drawn hollow while degraded). */
    fun current(nowMs: Long): LivePosition? {
        watchLive?.takeIf { nowMs - it.fixTimeMs <= Freshness.MAX_AGE_MS }?.let { return it }
        phoneLive?.takeIf { nowMs - it.fixTimeMs <= Freshness.MAX_AGE_MS }?.let { return it }
        return listOfNotNull(watchLive, phoneLive).maxByOrNull { it.fixTimeMs }
    }

    fun status(nowMs: Long): GpsStatus = Freshness.status(listOfNotNull(watchLive?.fixTimeMs, phoneLive?.fixTimeMs).maxOrNull(), nowMs)
}
```

- [ ] **Step 6: Run tests to verify they pass**

Run: `export JAVA_HOME=$(/usr/libexec/java_home -v 17) && ./gradlew :services:sync:test`
Expected: PASS (new 21 tests plus the existing sync tests).

- [ ] **Step 7: Commit**

```bash
git add services/sync
git commit -m "feat(sync): watch clock calibration, shared usable-live predicate and observed-time source selection"
```

---

### Task 5: Page set and transitions

**Files:**
- Create: `core/model/src/main/kotlin/com/debasish/livefit/model/PageSet.kt`
- Test: `core/model/src/test/kotlin/com/debasish/livefit/model/PageSetTest.kt`

**Interfaces:**
- Consumes: `HudPage`, `PageSettings`, `WorkoutSnapshot.gps` (Task 1).
- Produces: `object PageSet { val ORDER: List<HudPage>; val RECORDING: Set<WorkoutPhase>; fun mapEligible(workout: WorkoutSnapshot): Boolean; fun available(settings: PageSettings, mapEligible: Boolean): List<HudPage>; fun step(current: HudPage, steps: Int, available: List<HudPage>): HudPage; fun resolve(current: HudPage, available: List<HudPage>): HudPage }`

- [ ] **Step 1: Write the failing test** — `core/model/src/test/kotlin/com/debasish/livefit/model/PageSetTest.kt`:

```kotlin
package com.debasish.livefit.model

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class PageSetTest {
    private val all = PageSet.available(PageSettings(), mapEligible = true)

    @Test fun orderIsGlanceWorkoutStatsPlaylistMapMusicControls() = assertEquals(
        listOf(HudPage.Glance, HudPage.Workout, HudPage.Stats, HudPage.Playlist, HudPage.Map, HudPage.MusicControls), all,
    )

    @Test fun workoutCannotBeDisabled() =
        assertTrue(HudPage.Workout in PageSet.available(PageSettings(disabled = HudPage.entries.toSet()), mapEligible = true))

    /** Spec §3.1: the Map page is skipped when no GPS workout is active. */
    @Test fun mapSkippedWithoutGpsWorkout() {
        assertFalse(HudPage.Map in PageSet.available(PageSettings(), mapEligible = false))
        assertFalse(HudPage.Map in PageSet.available(PageSettings(disabled = setOf(HudPage.Map)), mapEligible = true))
    }

    @Test fun mapEligibleOnlyWhileAGpsWorkoutRecords() {
        assertTrue(PageSet.mapEligible(WorkoutSnapshot(phase = WorkoutPhase.Active, gps = true)))
        assertTrue(PageSet.mapEligible(WorkoutSnapshot(phase = WorkoutPhase.Paused, gps = true)))
        assertFalse(PageSet.mapEligible(WorkoutSnapshot(phase = WorkoutPhase.Active, gps = false)))
        assertFalse(PageSet.mapEligible(WorkoutSnapshot(phase = WorkoutPhase.Stopping, gps = true)))
        assertFalse(PageSet.mapEligible(WorkoutSnapshot(phase = WorkoutPhase.Summary, gps = true)))
    }

    @Test fun stepCyclesBothWays() {
        assertEquals(HudPage.Glance, PageSet.step(HudPage.MusicControls, 1, all))
        assertEquals(HudPage.MusicControls, PageSet.step(HudPage.Glance, -1, all))
        assertEquals(HudPage.Glance, PageSet.step(HudPage.Map, 2, all))
        assertEquals(HudPage.Map, PageSet.step(HudPage.Glance, -2, all))
    }

    @Test fun stepSkipsDisabledPages() {
        val pages = PageSet.available(PageSettings(disabled = setOf(HudPage.Stats)), mapEligible = false)
        assertEquals(HudPage.Playlist, PageSet.step(HudPage.Workout, 1, pages))
        assertEquals(HudPage.MusicControls, PageSet.step(HudPage.Playlist, 1, pages), "Map skipped")
    }

    /** Spec §3.3: a visible page that becomes unavailable switches to Workout. */
    @Test fun unavailablePageFallsBackToWorkout() {
        val noStats = PageSet.available(PageSettings(disabled = setOf(HudPage.Stats)), mapEligible = true)
        assertEquals(HudPage.Workout, PageSet.resolve(HudPage.Stats, noStats))
        assertEquals(HudPage.Workout, PageSet.resolve(HudPage.Map, PageSet.available(PageSettings(), mapEligible = false)))
        assertEquals(HudPage.Playlist, PageSet.resolve(HudPage.Playlist, noStats))
        assertEquals(HudPage.Workout, PageSet.step(HudPage.Stats, 1, noStats), "stepping from a vanished page lands on Workout")
    }
}
```

- [ ] **Step 2: Run test to verify it fails**

Run: `export JAVA_HOME=$(/usr/libexec/java_home -v 17) && ./gradlew :core:model:test --tests '*PageSetTest*'`
Expected: FAIL — `PageSet` unresolved.

- [ ] **Step 3: Implement `PageSet.kt`**

```kotlin
package com.debasish.livefit.model

/** The shared page set (spec §3): which pages exist right now, cycling, and the fallback to Workout. */
object PageSet {
    val ORDER: List<HudPage> = HudPage.entries
    val RECORDING: Set<WorkoutPhase> = setOf(WorkoutPhase.Starting, WorkoutPhase.Active, WorkoutPhase.Paused, WorkoutPhase.Syncing)

    /** Map is shown only while a GPS workout records. */
    fun mapEligible(workout: WorkoutSnapshot): Boolean = workout.gps && workout.phase in RECORDING

    /** Enabled pages in cycle order (Workout always), Map only when [mapEligible]. */
    fun available(settings: PageSettings, mapEligible: Boolean): List<HudPage> =
        ORDER.filter { settings.isEnabled(it) && (it != HudPage.Map || mapEligible) }

    /** [steps] pages forward (negative = back), cycling; from a page that is not available: Workout. */
    fun step(current: HudPage, steps: Int, available: List<HudPage>): HudPage {
        val i = available.indexOf(current)
        if (i < 0) return HudPage.Workout
        return available[Math.floorMod(i + steps, available.size)]
    }

    /** The page to show: [current] while available, else Workout (spec §3.3). */
    fun resolve(current: HudPage, available: List<HudPage>): HudPage = if (current in available) current else HudPage.Workout
}
```

- [ ] **Step 4: Run tests to verify they pass**

Run: `export JAVA_HOME=$(/usr/libexec/java_home -v 17) && ./gradlew :core:model:test`
Expected: PASS.

- [ ] **Step 5: Commit**

```bash
git add core/model
git commit -m "feat(model): shared page set with Map eligibility, cycling and Workout fallback"
```

---

### Task 6: Gesture rules and safety validation

**Files:**
- Create: `core/model/src/main/kotlin/com/debasish/livefit/model/GestureRules.kt`
- Test: `core/model/src/test/kotlin/com/debasish/livefit/model/GestureRulesTest.kt`

**Interfaces:**
- Consumes: `Gesture`, `GestureMode`, `GestureAction`, `GestureSettings`, `GestureDefaults`, `HudPage` (Task 1).
- Produces:
  - `sealed interface GestureChange { data class Applied(settings: GestureSettings); data class Refused(reason: String) }`
  - `object GestureRules { fun validActions(mode, page): List<GestureAction>; fun defaults(mode, page): Map<Gesture, GestureAction>; fun table(settings, mode, page): Map<Gesture, GestureAction>; fun problem(table, mode, page): String?; fun problems(settings): List<String>; fun sanitized(received: GestureSettings, lastValid: GestureSettings = GestureSettings()): GestureSettings; fun change(settings, mode, page, gesture, action): GestureChange; fun withIdleTimeout(settings, seconds: Int): GestureSettings }`

- [ ] **Step 1: Write the failing test** — `core/model/src/test/kotlin/com/debasish/livefit/model/GestureRulesTest.kt`:

```kotlin
package com.debasish.livefit.model

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertTrue

class GestureRulesTest {
    private val d = GestureSettings()
    private fun applied(c: GestureChange) = assertIs<GestureChange.Applied>(c).settings

    @Test fun everyDefaultTableIsSafe() = assertEquals(emptyList(), GestureRules.problems(d))

    /** Spec §4.4: every page needs Close app … */
    @Test fun refusesRemovingTheOnlyCloseApp() = assertEquals(
        GestureChange.Refused("Glance needs a gesture for Close app"),
        GestureRules.change(d, GestureMode.Page, HudPage.Glance, Gesture.DoubleTap, GestureAction.Talk),
    )

    /** … and Next page or Previous page. */
    @Test fun refusesRemovingBothPageMoves() {
        val a = applied(GestureRules.change(d, GestureMode.Page, HudPage.Workout, Gesture.ShortForward, GestureAction.Talk))
        assertEquals(
            GestureChange.Refused("Workout needs a gesture for Next or Previous page"),
            GestureRules.change(a, GestureMode.Page, HudPage.Workout, Gesture.ShortBack, GestureAction.Talk),
        )
        assertIs<GestureChange.Refused>(
            GestureRules.change(a, GestureMode.Page, HudPage.Workout, Gesture.ShortBack, GestureAction.NextPage2), "±2 pages is not Next/Previous page",
        )
    }

    @Test fun safetyIsCheckedForDisabledPagesToo() =
        assertIs<GestureChange.Refused>(GestureRules.change(d, GestureMode.Page, HudPage.Map, Gesture.DoubleTap, GestureAction.None))

    @Test fun closeAppCanMoveToAnotherGestureFirst() {
        val s1 = applied(GestureRules.change(d, GestureMode.Page, HudPage.Glance, Gesture.Tap, GestureAction.CloseApp))
        val s2 = applied(GestureRules.change(s1, GestureMode.Page, HudPage.Glance, Gesture.DoubleTap, GestureAction.Talk))
        val t = GestureRules.table(s2, GestureMode.Page, HudPage.Glance)
        assertEquals(GestureAction.CloseApp, t[Gesture.Tap])
        assertEquals(GestureAction.Talk, t[Gesture.DoubleTap])
        assertEquals(GestureRules.table(d, GestureMode.Page, HudPage.Workout), GestureRules.table(s2, GestureMode.Page, HudPage.Workout), "other pages untouched")
    }

    @Test fun catalogueDependsOnContext() {
        assertTrue(GestureAction.EnterScroll in GestureRules.validActions(GestureMode.Page, HudPage.Playlist))
        assertFalse(GestureAction.EnterScroll in GestureRules.validActions(GestureMode.Page, HudPage.Glance))
        assertTrue(GestureAction.PlayHighlighted in GestureRules.validActions(GestureMode.Scroll, HudPage.Playlist))
        assertFalse(GestureAction.PlayHighlighted in GestureRules.validActions(GestureMode.Scroll, HudPage.MusicControls))
        assertTrue(GestureAction.PressSelected in GestureRules.validActions(GestureMode.Scroll, HudPage.MusicControls))
        assertTrue(GestureAction.ExitScroll in GestureRules.validActions(GestureMode.Scroll, HudPage.MusicControls))
        assertFalse(GestureAction.ExitScroll in GestureRules.validActions(GestureMode.Page, HudPage.MusicControls))
        for (p in HudPage.entries) assertTrue(GestureAction.Talk in GestureRules.validActions(GestureMode.Page, p))
        assertTrue(GestureRules.validActions(GestureMode.Scroll, HudPage.Stats).isEmpty())
    }

    @Test fun actionsInvalidForTheContextAreRefused() {
        assertEquals(
            GestureChange.Refused("Play highlighted isn't available on Glance"),
            GestureRules.change(d, GestureMode.Page, HudPage.Glance, Gesture.Tap, GestureAction.PlayHighlighted),
        )
        assertEquals(GestureChange.Refused("Stats has no scroll mode"), GestureRules.change(d, GestureMode.Scroll, HudPage.Stats, Gesture.Tap, GestureAction.Talk))
    }

    /** Spec §4.4: in scroll mode the ✕ Back item and the idle timeout still exit, so no Exit scroll gesture is needed. */
    @Test fun scrollWithoutExitScrollIsAllowed() {
        val s = applied(GestureRules.change(d, GestureMode.Scroll, HudPage.Playlist, Gesture.DoubleTap, GestureAction.NextSong))
        assertFalse(GestureAction.ExitScroll in GestureRules.table(s, GestureMode.Scroll, HudPage.Playlist).values)
    }

    /** Review Focus #4: a broken page keeps its last valid table; valid pages of the same frame still apply. */
    @Test fun brokenTableKeepsLastValidPerPage() {
        val lastValid = applied(GestureRules.change(d, GestureMode.Page, HudPage.Glance, Gesture.Tap, GestureAction.NextSong))
        val brokenGlance = Gesture.entries.associateWith { GestureAction.Talk }
        val customWorkout = GestureRules.table(d, GestureMode.Page, HudPage.Workout) + (Gesture.Tap to GestureAction.LikeSong)
        val received = GestureSettings(page = d.page + (HudPage.Glance to brokenGlance) + (HudPage.Workout to customWorkout))
        val s = GestureRules.sanitized(received, lastValid)
        assertEquals(GestureRules.table(lastValid, GestureMode.Page, HudPage.Glance), s.page.getValue(HudPage.Glance))
        assertEquals(customWorkout, s.page.getValue(HudPage.Workout))
        assertEquals(emptyList(), GestureRules.problems(s))
    }

    @Test fun brokenTableWithoutLastValidUsesDefaults() {
        val received = GestureSettings(page = d.page + (HudPage.Stats to Gesture.entries.associateWith { GestureAction.None }))
        assertEquals(GestureDefaults.pageTable(HudPage.Stats), GestureRules.sanitized(received).page.getValue(HudPage.Stats))
    }

    @Test fun invalidScrollActionFallsBack() {
        val received = GestureSettings(scroll = d.scroll + (HudPage.MusicControls to mapOf(Gesture.Tap to GestureAction.PlayHighlighted)))
        assertEquals(GestureDefaults.scrollTable(HudPage.MusicControls), GestureRules.sanitized(received).scroll.getValue(HudPage.MusicControls))
    }

    @Test fun idleTimeoutIsClamped() {
        assertEquals(3, GestureRules.sanitized(GestureSettings(idleTimeoutS = 1)).idleTimeoutS)
        assertEquals(15, GestureRules.sanitized(GestureSettings(idleTimeoutS = 20)).idleTimeoutS)
        assertEquals(9, GestureRules.withIdleTimeout(d, 9).idleTimeoutS)
        assertEquals(15, GestureRules.withIdleTimeout(d, 99).idleTimeoutS)
    }

    @Test fun missingGesturesAreFilledFromDefaults() {
        val s = GestureSettings(page = mapOf(HudPage.Glance to mapOf(Gesture.Tap to GestureAction.NextSong)))
        val t = GestureRules.table(s, GestureMode.Page, HudPage.Glance)
        assertEquals(GestureAction.NextSong, t[Gesture.Tap])
        assertEquals(GestureAction.CloseApp, t[Gesture.DoubleTap])
        assertEquals(GestureDefaults.pageTable(HudPage.Workout), GestureRules.table(s, GestureMode.Page, HudPage.Workout))
        assertEquals(6, t.size)
    }
}
```

- [ ] **Step 2: Run test to verify it fails**

Run: `export JAVA_HOME=$(/usr/libexec/java_home -v 17) && ./gradlew :core:model:test --tests '*GestureRulesTest*'`
Expected: FAIL — `GestureRules`, `GestureChange` unresolved.

- [ ] **Step 3: Implement `GestureRules.kt`**

```kotlin
package com.debasish.livefit.model

sealed interface GestureChange {
    data class Applied(val settings: GestureSettings) : GestureChange
    data class Refused(val reason: String) : GestureChange
}

/**
 * Spec §4.4 rules, shared by the phone UI (refuses unsafe changes with a reason) and the glasses (validate what they
 * receive, per page). Safety: every page's page-mode table, disabled pages included, needs ≥ 1 Close app and ≥ 1
 * Next page or Previous page. Scroll tables only need valid actions (✕ Back and the idle timeout always exit).
 */
object GestureRules {
    private val COMMON: List<GestureAction> = listOf(
        GestureAction.None, GestureAction.Talk, GestureAction.NextPage, GestureAction.PreviousPage, GestureAction.NextPage2,
        GestureAction.PreviousPage2, GestureAction.CloseApp, GestureAction.PlayPause, GestureAction.NextSong,
        GestureAction.PreviousSong, GestureAction.VolumeUp, GestureAction.VolumeDown, GestureAction.LikeSong,
    )

    /** Only actions valid for the context are offered (and accepted). */
    fun validActions(mode: GestureMode, page: HudPage): List<GestureAction> = when (mode) {
        GestureMode.Page -> if (page in GestureDefaults.SCROLL_PAGES) COMMON + GestureAction.EnterScroll else COMMON
        GestureMode.Scroll -> when (page) {
            HudPage.Playlist -> COMMON + listOf(
                GestureAction.ExitScroll, GestureAction.HighlightNext, GestureAction.HighlightPrevious,
                GestureAction.HighlightNext2, GestureAction.HighlightPrevious2, GestureAction.PlayHighlighted,
            )
            HudPage.MusicControls -> COMMON + listOf(
                GestureAction.ExitScroll, GestureAction.SelectorNext, GestureAction.SelectorPrevious, GestureAction.PressSelected,
            )
            else -> emptyList()
        }
    }

    fun defaults(mode: GestureMode, page: HudPage): Map<Gesture, GestureAction> =
        if (mode == GestureMode.Page) GestureDefaults.pageTable(page) else GestureDefaults.scrollTable(page)

    /** All six gestures of a context; gestures missing from [settings] take their default. */
    fun table(settings: GestureSettings, mode: GestureMode, page: HudPage): Map<Gesture, GestureAction> {
        val stored = if (mode == GestureMode.Page) settings.page[page] else settings.scroll[page]
        val d = defaults(mode, page)
        return Gesture.entries.associateWith { stored?.get(it) ?: d[it] ?: GestureAction.None }
    }

    /** Why [table] is unacceptable for the context, or null. */
    fun problem(table: Map<Gesture, GestureAction>, mode: GestureMode, page: HudPage): String? {
        val valid = validActions(mode, page)
        table.values.firstOrNull { it !in valid }?.let { return "${it.label} isn't available on ${page.label}" + if (mode == GestureMode.Scroll) " in scroll mode" else "" }
        if (mode == GestureMode.Page) {
            if (GestureAction.CloseApp !in table.values) return "${page.label} needs a gesture for Close app"
            if (GestureAction.NextPage !in table.values && GestureAction.PreviousPage !in table.values) return "${page.label} needs a gesture for Next or Previous page"
        }
        return null
    }

    fun problems(settings: GestureSettings): List<String> =
        HudPage.entries.mapNotNull { problem(table(settings, GestureMode.Page, it), GestureMode.Page, it) } +
            GestureDefaults.SCROLL_PAGES.mapNotNull { problem(table(settings, GestureMode.Scroll, it), GestureMode.Scroll, it) }

    /** Glasses side: per context, an invalid received table keeps [lastValid]'s (defaults if that is invalid too). */
    fun sanitized(received: GestureSettings, lastValid: GestureSettings = GestureSettings()): GestureSettings {
        fun pick(mode: GestureMode, page: HudPage): Map<Gesture, GestureAction> {
            val t = table(received, mode, page)
            if (problem(t, mode, page) == null) return t
            val l = table(lastValid, mode, page)
            return if (problem(l, mode, page) == null) l else defaults(mode, page)
        }
        return GestureSettings(
            page = HudPage.entries.associateWith { pick(GestureMode.Page, it) },
            scroll = GestureDefaults.SCROLL_PAGES.associateWith { pick(GestureMode.Scroll, it) },
            idleTimeoutS = received.idleTimeoutS.coerceIn(GestureDefaults.IDLE_MIN_S, GestureDefaults.IDLE_MAX_S),
            askBeforeClose = received.askBeforeClose,
        )
    }

    /** Phone side: applies one gesture → action change unless it breaks the rules for that page. */
    fun change(settings: GestureSettings, mode: GestureMode, page: HudPage, gesture: Gesture, action: GestureAction): GestureChange {
        if (mode == GestureMode.Scroll && page !in GestureDefaults.SCROLL_PAGES) return GestureChange.Refused("${page.label} has no scroll mode")
        val next = table(settings, mode, page) + (gesture to action)
        problem(next, mode, page)?.let { return GestureChange.Refused(it) }
        return GestureChange.Applied(
            if (mode == GestureMode.Page) settings.copy(page = settings.page + (page to next))
            else settings.copy(scroll = settings.scroll + (page to next)),
        )
    }

    fun withIdleTimeout(settings: GestureSettings, seconds: Int): GestureSettings =
        settings.copy(idleTimeoutS = seconds.coerceIn(GestureDefaults.IDLE_MIN_S, GestureDefaults.IDLE_MAX_S))
}
```

- [ ] **Step 4: Run tests to verify they pass**

Run: `export JAVA_HOME=$(/usr/libexec/java_home -v 17) && ./gradlew :core:model:test`
Expected: PASS.

- [ ] **Step 5: Commit**

```bash
git add core/model
git commit -m "feat(model): gesture catalogue per context, per-page safety rules, sanitize and change"
```

---

### Task 7: Map scene, HUD palette and render cadence

**Files:**
- Create: `core/map/src/main/kotlin/com/debasish/livefit/map/MapScene.kt`
- Create: `core/map/src/main/kotlin/com/debasish/livefit/map/HudPalette.kt`
- Create: `core/map/src/main/kotlin/com/debasish/livefit/map/MapCadence.kt`
- Test: `core/map/src/test/kotlin/com/debasish/livefit/map/MapSceneTest.kt`, `core/map/src/test/kotlin/com/debasish/livefit/map/HudPaletteTest.kt`, `core/map/src/test/kotlin/com/debasish/livefit/map/MapCadenceTest.kt`

**Interfaces:**
- Consumes: `Viewport`, `Px`, `OSM_ATTRIBUTION` (Task 2); `RouteState`, `RoutePoint`, `LivePosition`, `GpsStatus`, `Geo` (Task 3).
- Produces:
  - `data class MapArrow(at: Px, bearingDeg: Float?, hollow: Boolean)`, `data class ScaleBar(lengthPx: Float, label: String)`, `data class MapScene(viewport: Viewport?, route: List<Px>, start: Px?, arrow: MapArrow?, scale: ScaleBar?, caption: String?, attribution: String = OSM_ATTRIBUTION)`
  - `object MapSceneBuilder { const val NO_TILES_CAPTION = "No map — route only"; fun caption(status: GpsStatus): String?; fun build(state: RouteState, zoom: Int, widthPx: Int, heightPx: Int): MapScene; fun decimate(points: List<Px>, minStepPx = 2f, max = 600): List<Px>; fun scaleBar(vp: Viewport): ScaleBar }`
  - `enum class OsmClass { Background, Water, Park, MinorRoad, MajorRoad }`; `object HudPalette { HUD_GREEN = 0x3CFF6E; MINOR_LEVEL = 0.30f; MAJOR_LEVEL = 0.45f; fun classify(argb: Int): OsmClass; fun convert(argb: Int, tint: Int = HUD_GREEN): Int; fun scaled(tint: Int, level: Float): Int; fun convertAll(pixels: IntArray, tint: Int = HUD_GREEN) }`
  - `class MapCadence(periodMs = 3_000, moveM = 25.0, minIntervalMs = 1_000) { fun due(nowMs: Long, lat: Double?, lon: Double?): Boolean; fun rendered(nowMs: Long, lat: Double?, lon: Double?); fun reset() }`

- [ ] **Step 1: Write the failing tests**

`core/map/src/test/kotlin/com/debasish/livefit/map/HudPaletteTest.kt`:

```kotlin
package com.debasish.livefit.map

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class HudPaletteTest {
    // A sample "tile": the OSM standard style's main colours, as they appear in a real 256×256 tile.
    private val land = 0xFFF2EFE9.toInt()
    private val residential = 0xFFE0DFDF.toInt()
    private val building = 0xFFD9D0C9.toInt()
    private val water = 0xFFAAD3DF.toInt()
    private val park = 0xFFC8FACC.toInt()
    private val grass = 0xFFCDEBB0.toInt()
    private val forest = 0xFFADD19E.toInt()
    private val minorRoad = 0xFFFFFFFF.toInt()
    private val secondary = 0xFFF7FABF.toInt()
    private val primary = 0xFFFCD6A4.toInt()
    private val motorway = 0xFFE892A2.toInt()
    private val label = 0xFF333333.toInt()
    private val transparent = 0x00FFFFFF

    @Test fun classifiesTheOsmStandardPalette() {
        for (c in listOf(land, residential, building, label, transparent)) assertEquals(OsmClass.Background, HudPalette.classify(c), "%08x".format(c))
        assertEquals(OsmClass.Water, HudPalette.classify(water))
        for (c in listOf(park, grass, forest)) assertEquals(OsmClass.Park, HudPalette.classify(c), "%08x".format(c))
        assertEquals(OsmClass.MinorRoad, HudPalette.classify(minorRoad))
        for (c in listOf(secondary, primary, motorway)) assertEquals(OsmClass.MajorRoad, HudPalette.classify(c), "%08x".format(c))
    }

    /** Spec §2.5: black background, streets as dim green luminance, water/park dropped. */
    @Test fun convertsASampleTileToTheHudPalette() {
        val tile = IntArray(256 * 256) { i -> listOf(land, water, park, minorRoad, primary, building, label)[i % 7] }
        HudPalette.convertAll(tile)
        val black = 0xFF000000.toInt()
        assertEquals(black, tile[0], "land → black (transparent on the HUD)")
        assertEquals(black, tile[1], "water dropped")
        assertEquals(black, tile[2], "park dropped")
        assertEquals(HudPalette.scaled(HudPalette.HUD_GREEN, HudPalette.MINOR_LEVEL), tile[3])
        assertEquals(HudPalette.scaled(HudPalette.HUD_GREEN, HudPalette.MAJOR_LEVEL), tile[4])
        assertEquals(3, tile.distinct().size, "three levels only, so the PNG stays small")
    }

    @Test fun streetsAreDimNotBright() {
        val g = (HudPalette.convert(primary) shr 8) and 0xFF
        assertTrue(g in 100..120, "major road green channel $g ≈ 45 % of 0xFF")
        assertEquals(0xFF000000.toInt() or (0x06 shl 16) or (0x3A shl 8) or 0x30, HudPalette.convert(minorRoad, tint = 0x14C3A2), "watch mint tint, minor road")
    }
}
```

`core/map/src/test/kotlin/com/debasish/livefit/map/MapSceneTest.kt`:

```kotlin
package com.debasish.livefit.map

import com.debasish.livefit.model.FixSource
import com.debasish.livefit.model.GpsStatus
import com.debasish.livefit.model.LivePosition
import com.debasish.livefit.model.RoutePoint
import com.debasish.livefit.model.RouteState
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class MapSceneTest {
    private val lat0 = 20.0
    private val lon0 = 77.0
    private fun pt(northM: Double, t: Long = 0) = RoutePoint(FixSource.Watch, lat0 + northM / 111_195.0, lon0, 5f, t, t)

    @Test fun waitingWithoutPointsIsCaptionOnly() {
        val s = MapSceneBuilder.build(RouteState(sessionId = "s"), 18, 480, 480)
        assertNull(s.viewport)
        assertEquals("Waiting for GPS…", s.caption)
        assertEquals("© OpenStreetMap contributors", s.attribution, "attribution always present")
    }

    @Test fun waitingWithStoredPointsCentresOnTheLastPoint() {
        val s = MapSceneBuilder.build(RouteState(sessionId = "s", route = listOf(pt(0.0), pt(50.0))), 18, 480, 480)
        val vp = assertNotNull(s.viewport)
        assertEquals(lat0 + 50.0 / 111_195.0, vp.centerLat, 1e-9)
        assertNull(s.arrow)
        assertEquals("Waiting for GPS…", s.caption)
    }

    @Test fun liveArrowIsCentredAndSolid() {
        val live = LivePosition(lat0, lon0, 45f, FixSource.Watch, 0)
        val s = MapSceneBuilder.build(RouteState(sessionId = "s", route = listOf(pt(-40.0), pt(0.0)), start = pt(-40.0), live = live, status = GpsStatus.Live), 18, 480, 480)
        val a = assertNotNull(s.arrow)
        assertEquals(Px(240f, 240f), a.at)
        assertFalse(a.hollow)
        assertNull(s.caption)
        assertTrue(assertNotNull(s.start).y > 240f, "start is south of the current position (below)")
    }

    /** Spec §2.1: hollow arrow + "GPS delayed" for 10–30 s, "GPS lost" after. */
    @Test fun degradedStatesAreHollow() {
        val live = LivePosition(lat0, lon0, null, FixSource.Phone, 0)
        val delayed = MapSceneBuilder.build(RouteState(sessionId = "s", live = live, status = GpsStatus.Delayed), 18, 480, 480)
        assertTrue(assertNotNull(delayed.arrow).hollow); assertEquals("GPS delayed", delayed.caption)
        val lost = MapSceneBuilder.build(RouteState(sessionId = "s", live = live, status = GpsStatus.Lost), 18, 480, 480)
        assertTrue(assertNotNull(lost.arrow).hollow); assertEquals("GPS lost", lost.caption)
    }

    @Test fun decimationDropsSubPixelStepsAndCaps() {
        val line = List(10_000) { Px(it * 0.1f, 0f) }
        val d = MapSceneBuilder.decimate(line)
        assertTrue(d.size <= 600, "${d.size}")
        assertEquals(line.first(), d.first()); assertEquals(line.last(), d.last())
        val zigzag = List(5_000) { Px(it * 3f, if (it % 2 == 0) 0f else 3f) }
        assertTrue(MapSceneBuilder.decimate(zigzag).size <= 600)
    }

    @Test fun scaleBarPicksANiceLength() {
        val z18 = MapSceneBuilder.scaleBar(Viewport(lat0, lon0, 18, 480, 480))
        assertEquals("50 m", z18.label)
        assertEquals(89.1f, z18.lengthPx, 1f)
        assertEquals("1 km", MapSceneBuilder.scaleBar(Viewport(lat0, lon0, 14, 480, 480)).label)
    }
}
```

`core/map/src/test/kotlin/com/debasish/livefit/map/MapCadenceTest.kt`:

```kotlin
package com.debasish.livefit.map

import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class MapCadenceTest {
    private val lat = 12.9716
    private val lon = 77.5946
    private fun north(m: Double) = lat + m / 111_195.0

    /** Spec §2.5: every 3 s or after ≥ 25 m, whichever first; never more than 1/s. */
    @Test fun everyThreeSecondsOrTwentyFiveMetresButAtMostOncePerSecond() {
        val c = MapCadence()
        assertTrue(c.due(0, lat, lon)); c.rendered(0, lat, lon)
        assertFalse(c.due(2_999, lat, lon))
        assertTrue(c.due(3_000, lat, lon))
        c.rendered(3_000, lat, lon)
        assertFalse(c.due(3_800, north(26.0), lon), "≤ 1/s even when moving fast")
        assertTrue(c.due(4_200, north(26.0), lon), "≥ 25 m moved")
        assertFalse(c.due(4_200, north(24.0), lon))
    }

    @Test fun resetMakesTheNextRenderImmediate() {
        val c = MapCadence()
        c.rendered(0, lat, lon)
        c.reset()
        assertTrue(c.due(10, lat, lon))
    }

    @Test fun withoutAPositionOnlyThePeriodCounts() {
        val c = MapCadence()
        c.rendered(0, null, null)
        assertFalse(c.due(2_000, lat, lon))
        assertTrue(c.due(3_000, null, null))
    }
}
```

- [ ] **Step 2: Run tests to verify they fail**

Run: `export JAVA_HOME=$(/usr/libexec/java_home -v 17) && ./gradlew :core:map:test`
Expected: FAIL — `HudPalette`, `MapSceneBuilder`, `MapCadence` unresolved.

- [ ] **Step 3: Implement `HudPalette.kt`**

```kotlin
package com.debasish.livefit.map

enum class OsmClass { Background, Water, Park, MinorRoad, MajorRoad }

/**
 * OSM standard tile pixels → HUD palette (spec §2.5): black background (transparent on the glasses), streets as dim
 * green, water and parks dropped, labels and buildings dropped. Only three output values, so PNGs compress well.
 * The watch uses the same mapping with its mint tint.
 */
object HudPalette {
    const val HUD_GREEN = 0x3CFF6E
    const val MINOR_LEVEL = 0.30f
    const val MAJOR_LEVEL = 0.45f

    fun classify(argb: Int): OsmClass {
        if ((argb ushr 24) < 128) return OsmClass.Background
        val r = (argb shr 16) and 0xFF
        val g = (argb shr 8) and 0xFF
        val b = argb and 0xFF
        if (b >= r + 20 && b >= g) return OsmClass.Water
        if (g >= r + 12 && g >= b + 12) return OsmClass.Park
        if (r >= 0xE0 && r - b >= 0x30) return OsmClass.MajorRoad // yellow / orange / pink road casings
        val lum = (0.2126 * r + 0.7152 * g + 0.0722 * b) / 255.0
        return if (lum >= 0.97) OsmClass.MinorRoad else OsmClass.Background // white streets; beige land is ~0.94
    }

    fun convert(argb: Int, tint: Int = HUD_GREEN): Int = when (classify(argb)) {
        OsmClass.MinorRoad -> scaled(tint, MINOR_LEVEL)
        OsmClass.MajorRoad -> scaled(tint, MAJOR_LEVEL)
        else -> 0xFF000000.toInt()
    }

    /** Opaque [tint] scaled to [level] brightness (black = off). */
    fun scaled(tint: Int, level: Float): Int {
        val r = (((tint shr 16) and 0xFF) * level).toInt()
        val g = (((tint shr 8) and 0xFF) * level).toInt()
        val b = ((tint and 0xFF) * level).toInt()
        return (0xFF shl 24) or (r shl 16) or (g shl 8) or b
    }

    fun convertAll(pixels: IntArray, tint: Int = HUD_GREEN) {
        for (i in pixels.indices) pixels[i] = convert(pixels[i], tint)
    }
}
```

- [ ] **Step 4: Implement `MapScene.kt`**

```kotlin
package com.debasish.livefit.map

import com.debasish.livefit.model.GpsStatus
import com.debasish.livefit.model.RouteState
import kotlin.math.hypot

data class MapArrow(val at: Px, val bearingDeg: Float?, val hollow: Boolean)
data class ScaleBar(val lengthPx: Float, val label: String)

/** Everything a map renderer draws; the glasses PNG renderer and the watch Canvas draw the same scene. */
data class MapScene(
    val viewport: Viewport?,
    val route: List<Px>,
    val start: Px?,
    val arrow: MapArrow?,
    val scale: ScaleBar?,
    val caption: String?,
    val attribution: String = OSM_ATTRIBUTION,
)

object MapSceneBuilder {
    const val MAX_ROUTE_POINTS = 600
    const val MIN_STEP_PX = 2f
    const val NO_TILES_CAPTION = "No map — route only"
    private val NICE_M = listOf(10, 20, 50, 100, 200, 500, 1_000, 2_000, 5_000)

    fun caption(status: GpsStatus): String? = when (status) {
        GpsStatus.Waiting -> "Waiting for GPS…"
        GpsStatus.Delayed -> "GPS delayed"
        GpsStatus.Lost -> "GPS lost"
        GpsStatus.Live -> null
    }

    /** North-up, centred on the marker (or the last route point while waiting); no point at all = caption only. */
    fun build(state: RouteState, zoom: Int, widthPx: Int, heightPx: Int): MapScene {
        val caption = caption(state.status)
        val centre = state.live?.let { it.lat to it.lon } ?: state.route.lastOrNull()?.let { it.lat to it.lon }
            ?: return MapScene(null, emptyList(), null, null, null, caption)
        val vp = Viewport(centre.first, centre.second, zoom, widthPx, heightPx)
        return MapScene(
            viewport = vp,
            route = decimate(state.route.map { vp.project(it.lat, it.lon) }),
            start = state.start?.let { vp.project(it.lat, it.lon) },
            arrow = state.live?.let { MapArrow(vp.project(it.lat, it.lon), it.bearingDeg, hollow = state.status != GpsStatus.Live) },
            scale = scaleBar(vp),
            caption = caption,
        )
    }

    /** Drops points closer than [minStepPx] to the last kept one and caps the count at [max]; first and last are kept. */
    fun decimate(points: List<Px>, minStepPx: Float = MIN_STEP_PX, max: Int = MAX_ROUTE_POINTS): List<Px> {
        if (points.size <= 2) return points
        val kept = ArrayList<Px>()
        kept += points.first()
        for (i in 1 until points.size - 1) {
            val p = points[i]; val l = kept.last()
            if (hypot(p.x - l.x, p.y - l.y) >= minStepPx) kept += p
        }
        kept += points.last()
        if (kept.size <= max) return kept
        val step = (kept.size + max - 2) / (max - 1)
        val thinned = kept.filterIndexed { i, _ -> i % step == 0 }.toMutableList()
        if (thinned.last() != kept.last()) thinned += kept.last()
        return thinned
    }

    /** The longest "nice" length that fits a quarter of the width. */
    fun scaleBar(vp: Viewport): ScaleBar {
        val mpp = vp.metersPerPixel()
        val maxM = vp.widthPx / 4.0 * mpp
        val m = NICE_M.lastOrNull { it <= maxM } ?: NICE_M.first()
        return ScaleBar((m / mpp).toFloat(), if (m >= 1_000) "${m / 1_000} km" else "$m m")
    }
}
```

- [ ] **Step 5: Implement `MapCadence.kt`**

```kotlin
package com.debasish.livefit.map

import com.debasish.livefit.model.Geo

/** Glasses map cadence (spec §2.5): every [periodMs] or after ≥ [moveM] movement, whichever first; never within [minIntervalMs]. */
class MapCadence(private val periodMs: Long = 3_000, private val moveM: Double = 25.0, private val minIntervalMs: Long = 1_000) {
    private var lastMs: Long? = null
    private var lastLat: Double? = null
    private var lastLon: Double? = null

    fun due(nowMs: Long, lat: Double?, lon: Double?): Boolean {
        val last = lastMs ?: return true
        val elapsed = nowMs - last
        if (elapsed < minIntervalMs) return false
        if (elapsed >= periodMs) return true
        val pLat = lastLat; val pLon = lastLon
        if (lat == null || lon == null || pLat == null || pLon == null) return false
        return Geo.distanceM(pLat, pLon, lat, lon) >= moveM
    }

    /** Called when a render was attempted (a failed send retries on the next cadence, spec §7). */
    fun rendered(nowMs: Long, lat: Double?, lon: Double?) { lastMs = nowMs; lastLat = lat; lastLon = lon }

    fun reset() { lastMs = null; lastLat = null; lastLon = null }
}
```

- [ ] **Step 6: Run tests to verify they pass**

Run: `export JAVA_HOME=$(/usr/libexec/java_home -v 17) && ./gradlew :core:map:test`
Expected: PASS.

- [ ] **Step 7: Commit**

```bash
git add core/map
git commit -m "feat(map): map scene builder, HUD palette conversion and glasses render cadence"
```

---

### Task 8: Tile source, disk cache, HTTP fetcher and non-blocking tile loader

**Files:**
- Modify: `core/map/build.gradle.kts`
- Create: `core/map/src/main/kotlin/com/debasish/livefit/map/Tiles.kt`
- Create: `core/map/src/main/kotlin/com/debasish/livefit/map/TileLoader.kt`
- Test: `core/map/src/test/kotlin/com/debasish/livefit/map/TilesTest.kt`, `core/map/src/test/kotlin/com/debasish/livefit/map/TileLoaderTest.kt`

**Interfaces:**
- Consumes: `TileId`, `OSM_ATTRIBUTION` (Task 2).
- Produces:
  - `:core:map` gains `api("org.jetbrains.kotlinx:kotlinx-coroutines-core:1.9.0")` (TileLoader exposes `StateFlow`/`Job`).
  - `interface TileSource { val userAgent: String; val attribution: String; fun url(tile: TileId): String }`
  - `class OsmTileSource(userAgent = USER_AGENT) : TileSource` — `https://tile.openstreetmap.org/{z}/{x}/{y}.png`
  - `data class TileMeta(expiresAtMs: Long, etag: String? = null, lastModified: String? = null)`
  - `class TileDiskCache(dir: File, maxBytes: Long, nowMs: () -> Long = System::currentTimeMillis) { class Entry(bytes: ByteArray, fresh: Boolean, meta: TileMeta); fun get(tile): Entry?; fun put(tile, bytes, meta: TileMeta); fun refresh(tile, meta: TileMeta): Boolean; fun sizeBytes(): Long; companion { PHONE_MAX_BYTES = 50 MB; WATCH_MAX_BYTES = 20 MB; FALLBACK_LIFETIME_MS = 7 days } }`
  - `class HttpTileFetcher(source: TileSource, cache: TileDiskCache, nowMs: () -> Long = System::currentTimeMillis, open: (URL) -> HttpURLConnection = …, timeoutMs: Int = 5_000, retryAfterFailureMs: Long = 30_000) { fun fetch(tile: TileId): ByteArray? /* blocking: call on an IO thread */; companion { fun lifetimeMs(cacheControl: String?, expires: String?, date: String?, nowMs: Long): Long } }` — server lifetime honoured (max-age, else Expires − Date), 7 days only when neither header is present; an expired entry is revalidated with `If-None-Match` / `If-Modified-Since`; `304` keeps the bytes and refreshes the metadata.
  - `class TileLoader<T : Any>(scope: CoroutineScope, load: suspend (TileId) -> T?, maxConcurrent: Int = 4, retryEveryMs: Long = 5_000, maxCached: Int = 30, log: (String) -> Unit = {}) { val tiles: StateFlow<Map<TileId, T>>; fun show(visible: Collection<TileId>); fun start(): Job }` — `show` never waits; each missing visible tile loads on its own (at most `maxConcurrent` at once, slot released in `finally`); `start()` re-requests still-missing **visible** tiles every `retryEveryMs` (the fetcher's 30 s backoff decides whether the network is hit). Used by the phone renderer (Task 18) and the watch (Task 12).

- [ ] **Step 1: Coroutines for `:core:map`** — in `core/map/build.gradle.kts` replace the `dependencies { … }` block with:

```kotlin
dependencies {
    api(project(":core:model"))
    api("org.jetbrains.kotlinx:kotlinx-coroutines-core:1.9.0")
    testImplementation(kotlin("test"))
    testImplementation("org.jetbrains.kotlinx:kotlinx-coroutines-test:1.9.0")
}
```

- [ ] **Step 2: Write the failing tests** — `core/map/src/test/kotlin/com/debasish/livefit/map/TilesTest.kt`:

```kotlin
package com.debasish.livefit.map

import com.sun.net.httpserver.HttpServer
import java.io.File
import java.net.InetSocketAddress
import java.nio.file.Files
import java.time.Instant
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.atomic.AtomicInteger
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class TilesTest {
    private lateinit var server: HttpServer
    private val requests = AtomicInteger()
    private val userAgents = CopyOnWriteArrayList<String>()
    private val ifNoneMatch = CopyOnWriteArrayList<String?>()
    private val ifModifiedSince = CopyOnWriteArrayList<String?>()
    @Volatile private var status = 200
    @Volatile private var cacheControl: String? = "max-age=60"
    @Volatile private var expires: String? = null
    @Volatile private var etag: String? = null
    @Volatile private var lastModified: String? = null
    private val png = byteArrayOf(-119, 80, 78, 71, 1, 2, 3)
    private var now = 1_000_000L
    private val tile = TileId(18, 1, 2)
    private val day = 24L * 3600 * 1000

    @BeforeTest fun up() {
        server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
        server.createContext("/") { ex ->
            requests.incrementAndGet()
            userAgents += ex.requestHeaders.getFirst("User-Agent").orEmpty()
            ifNoneMatch += ex.requestHeaders.getFirst("If-None-Match")
            ifModifiedSince += ex.requestHeaders.getFirst("If-Modified-Since")
            cacheControl?.let { ex.responseHeaders.add("Cache-Control", it) }
            expires?.let { ex.responseHeaders.add("Expires", it) }
            etag?.let { ex.responseHeaders.add("ETag", it) }
            lastModified?.let { ex.responseHeaders.add("Last-Modified", it) }
            when (status) {
                200 -> { ex.sendResponseHeaders(200, png.size.toLong()); ex.responseBody.use { it.write(png) } }
                else -> { ex.sendResponseHeaders(status, -1); ex.close() }
            }
        }
        server.start()
    }

    @AfterTest fun down() = server.stop(0)

    private val source = object : TileSource {
        override val userAgent = "RokidLiveFit/test"
        override val attribution = OSM_ATTRIBUTION
        override fun url(tile: TileId) = "http://127.0.0.1:${server.address.port}/${tile.z}/${tile.x}/${tile.y}.png"
    }

    private fun cache(dir: File = Files.createTempDirectory("tiles").toFile()) = TileDiskCache(dir, 1_000_000, { now })
    private fun fetcher(c: TileDiskCache = cache()) = HttpTileFetcher(source, c, { now })
    private fun http(ms: Long): String = DateTimeFormatter.RFC_1123_DATE_TIME.format(Instant.ofEpochMilli(ms).atZone(ZoneOffset.UTC))

    @Test fun osmSourceUrlAndAttribution() {
        val osm = OsmTileSource()
        assertEquals("https://tile.openstreetmap.org/18/1/2.png", osm.url(tile))
        assertEquals("© OpenStreetMap contributors", osm.attribution)
        assertTrue(osm.userAgent.startsWith("RokidLiveFit/"), "app-specific User-Agent")
    }

    @Test fun sendsTheAppUserAgentAndServesRepeatsFromCache() {
        val f = fetcher()
        assertContentEquals(png, f.fetch(tile))
        assertContentEquals(png, f.fetch(tile))
        assertEquals(1, requests.get())
        assertEquals(listOf("RokidLiveFit/test"), userAgents.toList())
        assertNull(ifNoneMatch.single(), "a first download carries no validators")
    }

    @Test fun honoursMaxAge() {
        val f = fetcher()
        f.fetch(tile)
        now += 59_000; f.fetch(tile)
        assertEquals(1, requests.get())
        now += 2_000; f.fetch(tile)
        assertEquals(2, requests.get())
    }

    /** Review #11 / OSM tile policy §3.2: a server lifetime longer than 7 days is honoured, not capped. */
    @Test fun longMaxAgeIsPreserved() {
        assertEquals(99_999_999_000L, HttpTileFetcher.lifetimeMs("public, max-age=99999999", null, null, now))
        cacheControl = "max-age=1209600" // 14 days
        val f = fetcher()
        f.fetch(tile)
        now += 8 * day; f.fetch(tile)
        assertEquals(1, requests.get(), "still fresh after 8 days")
        now += 7 * day; f.fetch(tile)
        assertEquals(2, requests.get())
    }

    @Test fun expiresIsHonouredWhenThereIsNoMaxAge() {
        assertEquals(7_200_000L, HttpTileFetcher.lifetimeMs(null, http(now + 7_200_000), http(now), now))
        assertEquals(0L, HttpTileFetcher.lifetimeMs(null, "0", null, now), "an invalid Expires means already expired")
        assertEquals(30_000L, HttpTileFetcher.lifetimeMs("max-age=30", http(now + 7_200_000), null, now), "max-age wins over Expires")
        // The test server stamps a real Date header, so this part runs on the real clock (100 s margins either side).
        now = System.currentTimeMillis()
        cacheControl = null; expires = http(now + 7_200_000)
        val f = fetcher()
        f.fetch(tile)
        now += 7_100_000; f.fetch(tile)
        assertEquals(1, requests.get())
        now += 200_000; f.fetch(tile)
        assertEquals(2, requests.get())
    }

    /** Spec §2.4: 7 days only when the server sends neither max-age nor Expires. */
    @Test fun headerlessResponseFallsBackToSevenDays() {
        assertEquals(7 * day, HttpTileFetcher.lifetimeMs(null, null, null, now))
        assertEquals(7 * day, HttpTileFetcher.lifetimeMs("public", null, null, now))
        cacheControl = null
        val f = fetcher()
        f.fetch(tile)
        now += 7 * day - 1; f.fetch(tile)
        assertEquals(1, requests.get())
        now += 2; f.fetch(tile)
        assertEquals(2, requests.get())
    }

    /** Review #11: an expired entry is revalidated with its ETag and Last-Modified. */
    @Test fun expiredEntrySendsValidators() {
        etag = "\"abc\""; lastModified = http(now - day)
        val f = fetcher()
        f.fetch(tile)
        now += 61_000; f.fetch(tile)
        assertEquals(listOf(null, "\"abc\""), ifNoneMatch.toList())
        assertEquals(listOf(null, http(now - 61_000 - day)), ifModifiedSince.toList())
    }

    /** Review #11: 304 keeps the cached bytes and refreshes the lifetime (and validators) from the new headers. */
    @Test fun notModifiedKeepsTheBytesAndRefreshesMetadata() {
        etag = "\"abc\""
        val c = cache()
        val f = fetcher(c)
        f.fetch(tile)
        now += 61_000; status = 304; cacheControl = "max-age=120"; etag = "\"abd\""
        assertContentEquals(png, f.fetch(tile))
        assertEquals(2, requests.get())
        val e = assertNotNull(c.get(tile))
        assertTrue(e.fresh)
        assertEquals("\"abd\"", e.meta.etag)
        assertContentEquals(png, e.bytes)
        now += 100_000; f.fetch(tile)
        assertEquals(2, requests.get(), "fresh again for the new 120 s")
    }

    @Test fun offlineServesTheStaleCopy() {
        val f = fetcher()
        f.fetch(tile)
        now += 61_000; status = 500
        assertContentEquals(png, f.fetch(tile))
        assertEquals(2, requests.get())
    }

    @Test fun failuresBackOffThirtySeconds() {
        status = 503
        val f = fetcher()
        assertNull(f.fetch(tile))
        assertNull(f.fetch(tile))
        assertEquals(1, requests.get(), "no retry storm while offline")
        now += 30_001
        assertNull(f.fetch(tile))
        assertEquals(2, requests.get())
    }

    @Test fun lruEvictsTheLeastRecentlyUsed() {
        val dir = Files.createTempDirectory("lru").toFile()
        val overhead = 8 + 2 + 2 // expiry + two empty validator strings
        val cache = TileDiskCache(dir, maxBytes = 2 * (overhead + 100) + 50L, nowMs = { now })
        val a = TileId(18, 0, 0); val b = TileId(18, 0, 1); val c = TileId(18, 0, 2)
        cache.put(a, ByteArray(100), TileMeta(now + 60_000)); now += 10_000
        cache.put(b, ByteArray(100), TileMeta(now + 60_000)); now += 10_000
        assertNotNull(cache.get(a)); now += 10_000 // a is now more recent than b
        cache.put(c, ByteArray(100), TileMeta(now + 60_000))
        assertNull(cache.get(b), "least recently used evicted")
        assertNotNull(cache.get(a)); assertNotNull(cache.get(c))
        assertTrue(cache.sizeBytes() <= 2 * (overhead + 100) + 50L)
    }

    @Test fun expiredEntryIsMarkedStale() {
        val cache = TileDiskCache(Files.createTempDirectory("exp").toFile(), 1_000_000, { now })
        cache.put(tile, png, TileMeta(now + 1_000, etag = "\"e\""))
        assertTrue(cache.get(tile)!!.fresh)
        now += 1_001
        val stale = cache.get(tile)!!
        assertFalse(stale.fresh)
        assertEquals("\"e\"", stale.meta.etag)
        assertFalse(cache.refresh(TileId(18, 9, 9), TileMeta(now + 1_000)), "nothing to refresh")
    }
}
```

`core/map/src/test/kotlin/com/debasish/livefit/map/TileLoaderTest.kt`:

```kotlin
package com.debasish.livefit.map

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import java.io.IOException
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

@OptIn(ExperimentalCoroutinesApi::class)
class TileLoaderTest {
    private val a = TileId(18, 1, 1)
    private val b = TileId(18, 1, 2)

    /** Review #5: show() never waits for the network, and one slow tile never holds back another. */
    @Test fun showNeverBlocksAndTilesArriveIndependently() = runTest {
        val gates = mapOf(a to CompletableDeferred<String?>(), b to CompletableDeferred<String?>())
        val loader = TileLoader(backgroundScope, load = { t: TileId -> gates.getValue(t).await() })
        loader.show(listOf(a, b)); runCurrent()
        assertTrue(loader.tiles.value.isEmpty())
        gates.getValue(b).complete("B"); runCurrent()
        assertEquals(setOf(b), loader.tiles.value.keys, "b does not wait for a")
        gates.getValue(a).complete("A"); runCurrent()
        assertEquals(mapOf(a to "A", b to "B"), loader.tiles.value)
    }

    @Test fun atMostMaxConcurrentLoadsRunAtOnce() = runTest {
        var running = 0
        var peak = 0
        val release = CompletableDeferred<Unit>()
        val loader = TileLoader(backgroundScope, load = { _: TileId -> running++; peak = maxOf(peak, running); release.await(); running--; "x" }, maxConcurrent = 3)
        loader.show((0 until 9).map { TileId(18, it, 0) }); runCurrent()
        assertEquals(3, peak)
        release.complete(Unit); runCurrent()
        assertEquals(9, loader.tiles.value.size)
        assertEquals(3, peak)
    }

    /** Review #6: a fixed viewport whose tiles failed is retried while visible; nothing else has to change. */
    @Test fun missingVisibleTilesAreRetriedWithoutAViewportChange() = runTest {
        var online = false
        var calls = 0
        val loader = TileLoader(backgroundScope, load = { _: TileId -> calls++; if (online) "t" else null }, retryEveryMs = 5_000)
        loader.start()
        loader.show(listOf(a)); runCurrent()
        assertTrue(loader.tiles.value.isEmpty())
        assertEquals(1, calls)
        online = true
        advanceTimeBy(5_001); runCurrent()
        assertEquals("t", loader.tiles.value[a])
        val n = calls
        advanceTimeBy(20_000); runCurrent()
        assertEquals(n, calls, "a loaded tile is not loaded again")
    }

    @Test fun tilesThatLeftTheViewAreNotRetried() = runTest {
        var calls = 0
        val loader = TileLoader(backgroundScope, load = { _: TileId -> calls++; null as String? }, retryEveryMs = 5_000)
        loader.start()
        loader.show(listOf(a)); runCurrent()
        loader.show(emptyList())
        advanceTimeBy(30_000); runCurrent()
        assertEquals(1, calls)
    }

    /** A load that throws releases its slot and in-flight mark (finally), so the next retry runs. */
    @Test fun aThrowingLoadIsRetried() = runTest {
        var calls = 0
        val loader = TileLoader(backgroundScope, load = { _: TileId -> if (calls++ == 0) throw IOException("reset") else "ok" }, maxConcurrent = 1, retryEveryMs = 1_000)
        loader.start()
        loader.show(listOf(a)); runCurrent()
        advanceTimeBy(1_001); runCurrent()
        assertEquals("ok", loader.tiles.value[a])
    }

    @Test fun visibleTilesAreNeverEvicted() = runTest {
        val loader = TileLoader(backgroundScope, load = { t: TileId -> "t${t.x}" }, maxCached = 2)
        loader.show(listOf(TileId(18, 0, 0))); runCurrent()
        loader.show(listOf(TileId(18, 1, 0))); runCurrent()
        loader.show(listOf(TileId(18, 2, 0), TileId(18, 3, 0), TileId(18, 4, 0))); runCurrent()
        assertEquals(setOf(2, 3, 4), loader.tiles.value.keys.map { it.x }.toSet(), "over the cap only invisible tiles go")
    }
}
```

- [ ] **Step 3: Run tests to verify they fail**

Run: `export JAVA_HOME=$(/usr/libexec/java_home -v 17) && ./gradlew :core:map:test --tests '*TilesTest*' --tests '*TileLoaderTest*'`
Expected: FAIL — `TileSource`, `TileDiskCache`, `TileMeta`, `HttpTileFetcher`, `TileLoader` unresolved.

- [ ] **Step 4: Implement `Tiles.kt`**

```kotlin
package com.debasish.livefit.map

import java.io.DataInputStream
import java.io.DataOutputStream
import java.io.File
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL
import java.time.ZonedDateTime
import java.time.format.DateTimeFormatter

/** Where tiles come from (spec §2.4): a constant URL behind an interface so the provider can be swapped. */
interface TileSource {
    val userAgent: String
    val attribution: String
    fun url(tile: TileId): String
}

class OsmTileSource(override val userAgent: String = USER_AGENT) : TileSource {
    override val attribution: String = OSM_ATTRIBUTION
    override fun url(tile: TileId): String = "https://tile.openstreetmap.org/${tile.z}/${tile.x}/${tile.y}.png"

    companion object {
        /** OSM tile policy: an app-specific User-Agent. */
        const val USER_AGENT = "RokidLiveFit/0.1 (com.debasish.livefit; personal fitness HUD)"
    }
}

/** Cache metadata of one tile: absolute expiry and the validators for revalidation (OSM tile policy §3.2). */
data class TileMeta(val expiresAtMs: Long, val etag: String? = null, val lastModified: String? = null)

/**
 * On-disk LRU tile cache; each file = expiry (Long) + ETag (UTF) + Last-Modified (UTF) + PNG. Least recently used files
 * go first once over [maxBytes]. A stale entry is still returned (offline use, revalidation).
 */
class TileDiskCache(private val dir: File, private val maxBytes: Long, private val nowMs: () -> Long = System::currentTimeMillis) {
    class Entry(val bytes: ByteArray, val fresh: Boolean, val meta: TileMeta)

    init { dir.mkdirs() }

    @Synchronized
    fun get(tile: TileId): Entry? {
        val f = file(tile)
        if (!f.exists()) return null
        return try {
            DataInputStream(f.inputStream().buffered()).use { input ->
                val meta = TileMeta(input.readLong(), input.readUTF().ifEmpty { null }, input.readUTF().ifEmpty { null })
                val bytes = input.readBytes()
                f.setLastModified(nowMs())
                Entry(bytes, nowMs() < meta.expiresAtMs, meta)
            }
        } catch (e: IOException) {
            f.delete(); null
        }
    }

    @Synchronized
    fun put(tile: TileId, bytes: ByteArray, meta: TileMeta) {
        if (!write(tile, bytes, meta)) return
        trim()
    }

    /** A 304: same bytes, new metadata. False when there is no entry to refresh. */
    @Synchronized
    fun refresh(tile: TileId, meta: TileMeta): Boolean {
        val e = get(tile) ?: return false
        return write(tile, e.bytes, meta)
    }

    fun sizeBytes(): Long = files().sumOf { it.length() }

    private fun write(tile: TileId, bytes: ByteArray, meta: TileMeta): Boolean {
        val f = file(tile)
        val tmp = File(dir, f.name + ".tmp")
        return try {
            DataOutputStream(tmp.outputStream().buffered()).use { out ->
                out.writeLong(meta.expiresAtMs)
                out.writeUTF(meta.etag.orEmpty())
                out.writeUTF(meta.lastModified.orEmpty())
                out.write(bytes)
            }
            if (!tmp.renameTo(f)) { f.delete(); tmp.renameTo(f) }
            f.setLastModified(nowMs())
            true
        } catch (e: IOException) {
            tmp.delete(); false
        }
    }

    private fun files(): List<File> = dir.listFiles { f -> f.name.endsWith(".tile") }.orEmpty().toList()

    private fun trim() {
        var total = sizeBytes()
        if (total <= maxBytes) return
        for (f in files().sortedBy { it.lastModified() }) {
            if (total <= maxBytes) break
            total -= f.length()
            f.delete()
        }
    }

    private fun file(t: TileId) = File(dir, "${t.z}_${t.x}_${t.y}.tile")

    companion object {
        const val PHONE_MAX_BYTES = 50L * 1024 * 1024
        const val WATCH_MAX_BYTES = 20L * 1024 * 1024
        /** Spec §2.4 "7-day max-age": the lifetime used only when the server gives none (plan ruling, review #11). */
        const val FALLBACK_LIFETIME_MS = 7L * 24 * 60 * 60 * 1000
    }
}

/**
 * Fresh cache → network → stale cache → null (spec §2.4, OSM tile policy §3.2). The server's lifetime is honoured
 * (max-age, else Expires − Date; 7 days only without either); an expired entry is revalidated with If-None-Match /
 * If-Modified-Since and a 304 keeps the bytes with refreshed metadata. A failed tile is not retried for
 * [retryAfterFailureMs]. Blocking: call it on an IO thread. Only ever asked for the visible tiles (no prefetch).
 */
class HttpTileFetcher(
    private val source: TileSource,
    private val cache: TileDiskCache,
    private val nowMs: () -> Long = System::currentTimeMillis,
    private val open: (URL) -> HttpURLConnection = { it.openConnection() as HttpURLConnection },
    private val timeoutMs: Int = 5_000,
    private val retryAfterFailureMs: Long = 30_000,
) {
    private val failedUntil = HashMap<TileId, Long>()

    private sealed interface Result {
        class Ok(val bytes: ByteArray, val meta: TileMeta) : Result
        class NotModified(val meta: TileMeta) : Result
    }

    fun fetch(tile: TileId): ByteArray? {
        val cached = cache.get(tile)
        if (cached?.fresh == true) return cached.bytes
        val blocked = synchronized(failedUntil) { failedUntil[tile] }
        if (blocked != null && nowMs() < blocked) return cached?.bytes
        val result = try { download(tile, cached?.meta) } catch (e: IOException) { null }
        return when (result) {
            is Result.Ok -> { clearFailure(tile); cache.put(tile, result.bytes, result.meta); result.bytes }
            is Result.NotModified -> if (cached != null) { clearFailure(tile); cache.refresh(tile, result.meta); cached.bytes } else fail(tile, null)
            null -> fail(tile, cached?.bytes)
        }
    }

    private fun fail(tile: TileId, fallback: ByteArray?): ByteArray? {
        synchronized(failedUntil) { failedUntil[tile] = nowMs() + retryAfterFailureMs }
        return fallback
    }

    private fun clearFailure(tile: TileId) = synchronized(failedUntil) { failedUntil.remove(tile) }

    private fun download(tile: TileId, validators: TileMeta?): Result? {
        val c = open(URL(source.url(tile)))
        try {
            c.connectTimeout = timeoutMs
            c.readTimeout = timeoutMs
            c.useCaches = false
            c.setRequestProperty("User-Agent", source.userAgent)
            validators?.etag?.let { c.setRequestProperty("If-None-Match", it) }
            validators?.lastModified?.let { c.setRequestProperty("If-Modified-Since", it) }
            val code = c.responseCode
            val now = nowMs()
            val lifetime = lifetimeMs(c.getHeaderField("Cache-Control"), c.getHeaderField("Expires"), c.getHeaderField("Date"), now)
            val meta = TileMeta(now + lifetime, c.getHeaderField("ETag") ?: validators?.etag, c.getHeaderField("Last-Modified") ?: validators?.lastModified)
            return when (code) {
                200 -> Result.Ok(c.inputStream.use { it.readBytes() }, meta)
                304 -> Result.NotModified(meta)
                else -> null
            }
        } finally {
            c.disconnect()
        }
    }

    companion object {
        private val MAX_AGE = Regex("(?:^|[,\\s])max-age=(\\d+)")

        /** Server lifetime: Cache-Control max-age, else Expires − (Date or now); an unparsable Expires = expired; neither → 7 days. */
        fun lifetimeMs(cacheControl: String?, expires: String?, date: String?, nowMs: Long): Long {
            MAX_AGE.find(cacheControl.orEmpty())?.groupValues?.get(1)?.toLongOrNull()?.let { return it * 1_000 }
            if (expires != null) {
                val exp = httpDate(expires) ?: return 0
                return (exp - (date?.let(::httpDate) ?: nowMs)).coerceAtLeast(0)
            }
            return TileDiskCache.FALLBACK_LIFETIME_MS
        }

        private fun httpDate(s: String): Long? =
            runCatching { ZonedDateTime.parse(s.trim(), DateTimeFormatter.RFC_1123_DATE_TIME).toInstant().toEpochMilli() }.getOrNull()
    }
}
```

- [ ] **Step 5: Implement `TileLoader.kt`**

```kotlin
package com.debasish.livefit.map

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit

/**
 * Loads the visible tiles in the background (review #5/#6): [show] returns at once; each missing visible tile loads on
 * its own, at most [maxConcurrent] at a time; a failed or throwing load frees its slot in `finally` and is retried every
 * [retryEveryMs] while the tile stays visible ([load] is expected to honour HttpTileFetcher's backoff). Renderers draw
 * whatever [tiles] holds right now. At most [maxCached] decoded tiles are kept; visible ones are never evicted.
 */
class TileLoader<T : Any>(
    private val scope: CoroutineScope,
    private val load: suspend (TileId) -> T?,
    private val maxConcurrent: Int = 4,
    private val retryEveryMs: Long = 5_000,
    private val maxCached: Int = 30,
    private val log: (String) -> Unit = {},
) {
    private val _tiles = MutableStateFlow<Map<TileId, T>>(emptyMap())
    val tiles: StateFlow<Map<TileId, T>> = _tiles
    private val lock = Any()
    private var visible: Set<TileId> = emptySet()
    private val inFlight = HashSet<TileId>()
    private val slots = Semaphore(maxConcurrent)

    /** The tiles on screen now (empty when the map is not shown). Never suspends or blocks. */
    fun show(visible: Collection<TileId>) {
        synchronized(lock) { this.visible = visible.toSet() }
        launchMissing()
    }

    /** Retries still-missing visible tiles every [retryEveryMs]. */
    fun start(): Job = scope.launch { while (isActive) { delay(retryEveryMs); launchMissing() } }

    private fun launchMissing() {
        val todo = synchronized(lock) { visible.filter { it !in _tiles.value && inFlight.add(it) } }
        for (t in todo) scope.launch {
            try {
                val value = slots.withPermit { load(t) }
                if (value != null) store(t, value)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                log("tile $t failed: $e")
            } finally {
                synchronized(lock) { inFlight.remove(t) }
            }
        }
    }

    private fun store(t: TileId, value: T) = synchronized(lock) {
        val next = LinkedHashMap(_tiles.value)
        next.remove(t)
        next[t] = value
        val keys = next.keys.iterator()
        while (next.size > maxCached && keys.hasNext()) if (keys.next() !in visible) keys.remove()
        _tiles.value = next
    }
}
```

- [ ] **Step 6: Run tests to verify they pass**

Run: `export JAVA_HOME=$(/usr/libexec/java_home -v 17) && ./gradlew :core:map:test`
Expected: PASS.

- [ ] **Step 7: Commit**

```bash
git add core/map
git commit -m "feat(map): OSM tile source, validating LRU disk cache, backing-off HTTP fetcher and non-blocking tile loader"
```

---

### Task 9: Glasses map stream — phone streamer and glasses image gate

**Files:**
- Create: `services/sync/src/main/kotlin/com/debasish/livefit/sync/GlassesMapStreamer.kt`
- Create: `services/sync/src/main/kotlin/com/debasish/livefit/sync/MapImageGate.kt`
- Test: `services/sync/src/test/kotlin/com/debasish/livefit/sync/MapStreamTest.kt`

**Interfaces:**
- Consumes: `MapFrame`, `MapFrameKind`, `HudPage` (Task 1); `RouteState`, `LivePosition` (Task 3); `MapCadence` (Task 7); `Clock`.
- Produces:
  - `class GlassesMapStreamer(scope, clock, render: suspend (RouteState) -> ByteArray?, send: suspend (MapFrame, ByteArray?) -> Boolean, newEpoch: () -> Long = { Random.nextLong() }, tickMs: Long = 250, sendTimeoutMs: Long = 5_000, log: (String) -> Unit = {}) { val renderEpoch: Long; val mapVisible: Boolean; fun onConnected(); fun onDisconnected(); fun onPageState(page: HudPage, seq: Long); fun onRoute(state: RouteState); fun start(): Job; suspend fun step() }` — all calls on one thread (the hub's Main scope).
  - `class MapImageGate { val currentEpoch: Long?; fun accept(frame: MapFrame, sessionId: String?): Boolean }`

- [ ] **Step 1: Write the failing test** — `services/sync/src/test/kotlin/com/debasish/livefit/sync/MapStreamTest.kt` (`:core:map` is already visible through `:services:sync`, Task 2):

```kotlin
package com.debasish.livefit.sync

import com.debasish.livefit.model.FixSource
import com.debasish.livefit.model.GpsStatus
import com.debasish.livefit.model.HudPage
import com.debasish.livefit.model.LivePosition
import com.debasish.livefit.model.MapFrame
import com.debasish.livefit.model.MapFrameKind
import com.debasish.livefit.model.RouteState
import com.debasish.livefit.services.Clock
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

@OptIn(ExperimentalCoroutinesApi::class)
class MapStreamTest {
    private class Rig(scope: TestScope, render: suspend (RouteState) -> ByteArray? = { byteArrayOf(1, 2, 3) }) {
        val sent = mutableListOf<Pair<MapFrame, ByteArray?>>()
        var renders = 0
        var sendOk = true
        private var e = 0L
        val streamer = GlassesMapStreamer(
            scope.backgroundScope, Clock { scope.testScheduler.currentTime },
            render = { s -> renders++; render(s) },
            send = { f, png -> if (sendOk || f.kind == MapFrameKind.Epoch) { sent += f to png; true } else false },
            newEpoch = { ++e },
        )
        val images get() = sent.filter { it.first.kind == MapFrameKind.Image }.map { it.first }
        val epochs get() = sent.filter { it.first.kind == MapFrameKind.Epoch }.map { it.first.renderEpoch }
    }

    private fun at(northM: Double) = RouteState(
        sessionId = "s", live = LivePosition(12.9716 + northM / 111_195.0, 77.5946, null, FixSource.Watch, 0), status = GpsStatus.Live,
    )

    private fun TestScope.onMap(r: Rig, seq: Long = 1) {
        r.streamer.start(); r.streamer.onRoute(at(0.0)); r.streamer.onConnected(); r.streamer.onPageState(HudPage.Map, seq)
    }

    @Test fun announcesTheEpochBeforeAnyImage() = runTest {
        val r = Rig(this); onMap(r)
        advanceTimeBy(300); runCurrent()
        assertEquals(MapFrameKind.Epoch, r.sent.first().first.kind)
        assertNull(r.sent.first().second, "the epoch header carries no image")
        val img = r.images.single()
        assertEquals(r.sent.first().first.renderEpoch, img.renderEpoch)
        assertEquals(1L, img.renderSeq)
        assertEquals("s", img.sessionId)
    }

    /** Spec §2.5: when not on the Map page nothing image-related is sent. */
    @Test fun nothingImageRelatedWhenNotOnTheMapPage() = runTest {
        val r = Rig(this)
        r.streamer.start(); r.streamer.onRoute(at(0.0)); r.streamer.onConnected(); r.streamer.onPageState(HudPage.Workout, 1)
        advanceTimeBy(10_000); runCurrent()
        assertTrue(r.images.isEmpty()); assertEquals(0, r.renders)
        assertEquals(1, r.epochs.size)
    }

    /** Review Focus #5: leaving the Map page (page change or Map no longer eligible) stops the images. */
    @Test fun leavingMapStopsImages() = runTest {
        val r = Rig(this); onMap(r)
        advanceTimeBy(1_000); runCurrent()
        r.streamer.onPageState(HudPage.Workout, 2)
        advanceTimeBy(10_000); runCurrent()
        assertEquals(1, r.images.size)
        assertFalse(r.streamer.mapVisible)
    }

    @Test fun imagesFollowTheThreeSecondCadence() = runTest {
        val r = Rig(this); onMap(r)
        advanceTimeBy(10_000); runCurrent()
        assertEquals(listOf(1L, 2L, 3L, 4L), r.images.map { it.renderSeq }, "t = 0, 3, 6, 9 s")
    }

    @Test fun movingTwentyFiveMetresRendersEarly() = runTest {
        val r = Rig(this); onMap(r)
        advanceTimeBy(1_400); runCurrent()
        r.streamer.onRoute(at(30.0))
        advanceTimeBy(200); runCurrent()
        assertEquals(2, r.images.size)
    }

    @Test fun newEpochOnEveryReconnectAndTheSequenceRestarts() = runTest {
        val r = Rig(this); onMap(r)
        advanceTimeBy(300); runCurrent()
        r.streamer.onDisconnected(); r.streamer.onConnected(); r.streamer.onPageState(HudPage.Map, 1)
        advanceTimeBy(300); runCurrent()
        assertEquals(listOf(2L, 3L), r.epochs, "epoch 1 was the process-start epoch, never announced")
        assertEquals(listOf(2L to 1L, 3L to 1L), r.images.map { it.renderEpoch to it.renderSeq })
    }

    /** Review Focus #3: the link drops and returns while the glasses stay on Map; images resume once they re-report. */
    @Test fun reconnectWithoutPageChangeResumesAfterPageReport() = runTest {
        val r = Rig(this); onMap(r, seq = 4)
        advanceTimeBy(300); runCurrent()
        r.streamer.onDisconnected(); r.streamer.onConnected()
        advanceTimeBy(5_000); runCurrent()
        assertEquals(1, r.images.size, "visibility is cleared on disconnect and not assumed after reconnect")
        r.streamer.onPageState(HudPage.Map, 5) // the glasses answer the new epoch announcement with their page
        advanceTimeBy(300); runCurrent()
        assertEquals(r.epochs.last(), r.images.last().renderEpoch)
        assertEquals(2, r.images.size)
    }

    @Test fun olderPageStateIsIgnored() = runTest {
        val r = Rig(this); onMap(r, seq = 5)
        r.streamer.onPageState(HudPage.Workout, 4)
        assertTrue(r.streamer.mapVisible)
    }

    /** Spec §7: a failed image send is retried by the next cadence and never blocks anything else. */
    @Test fun failedSendRetriesOnTheNextCadence() = runTest {
        val r = Rig(this); r.sendOk = false; onMap(r)
        advanceTimeBy(1_000); runCurrent()
        assertTrue(r.images.isEmpty())
        r.sendOk = true
        advanceTimeBy(2_500); runCurrent()
        assertEquals(listOf(2L), r.images.map { it.renderSeq })
    }

    @Test fun renderFailureDoesNotStopTheLoop() = runTest {
        var n = 0
        val r = Rig(this, render = { if (n++ == 0) error("tiles down") else byteArrayOf(9) }); onMap(r)
        advanceTimeBy(3_500); runCurrent()
        assertEquals(1, r.images.size)
    }

    @Test fun noSessionNoImages() = runTest {
        val r = Rig(this)
        r.streamer.start(); r.streamer.onRoute(RouteState()); r.streamer.onConnected(); r.streamer.onPageState(HudPage.Map, 1)
        advanceTimeBy(5_000); runCurrent()
        assertTrue(r.images.isEmpty())
    }

    // ---- Glasses side ----

    private fun epoch(e: Long) = MapFrame(kind = MapFrameKind.Epoch, renderEpoch = e)
    private fun image(e: Long, seq: Long, session: String = "s") = MapFrame(kind = MapFrameKind.Image, renderEpoch = e, sessionId = session, renderSeq = seq)

    @Test fun acceptsOnlyTheNewestAnnouncedEpoch() {
        val g = MapImageGate()
        assertFalse(g.accept(epoch(10), "s"))
        assertTrue(g.accept(image(10, 1), "s"))
        g.accept(epoch(20), "s")
        assertFalse(g.accept(image(10, 2), "s"), "older epoch")
        assertTrue(g.accept(image(20, 1), "s"))
        assertEquals(20L, g.currentEpoch)
    }

    @Test fun rejectsOlderOrRepeatedSequenceNumbers() {
        val g = MapImageGate().apply { accept(epoch(1), "s") }
        assertTrue(g.accept(image(1, 3), "s"))
        assertFalse(g.accept(image(1, 3), "s"))
        assertFalse(g.accept(image(1, 2), "s"))
        assertTrue(g.accept(image(1, 4), "s"))
    }

    @Test fun rejectsAnotherSession() {
        val g = MapImageGate().apply { accept(epoch(1), "s") }
        assertFalse(g.accept(image(1, 1, session = "old"), "s"))
        assertFalse(g.accept(image(1, 1), null), "no workout on the glasses")
    }

    @Test fun imageBeforeAnyEpochIsRejected() = assertFalse(MapImageGate().accept(image(1, 1), "s"))

    /** Review Focus #3: a restarted phone's new epoch starts again at seq 1 although the old one reached 50. */
    @Test fun restartedPhoneNewEpochResetsSequence() {
        val g = MapImageGate().apply { accept(epoch(1), "s") }
        assertTrue(g.accept(image(1, 50), "s"))
        g.accept(epoch(-7), "s")
        assertTrue(g.accept(image(-7, 1), "s"))
        assertFalse(g.accept(image(1, 51), "s"), "a late image of the old epoch")
    }
}
```

- [ ] **Step 2: Run test to verify it fails**

Run: `export JAVA_HOME=$(/usr/libexec/java_home -v 17) && ./gradlew :services:sync:test --tests '*MapStreamTest*'`
Expected: FAIL — `GlassesMapStreamer`, `MapImageGate` unresolved.

- [ ] **Step 3: Implement `GlassesMapStreamer.kt`**

```kotlin
package com.debasish.livefit.sync

import com.debasish.livefit.map.MapCadence
import com.debasish.livefit.model.HudPage
import com.debasish.livefit.model.MapFrame
import com.debasish.livefit.model.MapFrameKind
import com.debasish.livefit.model.RouteState
import com.debasish.livefit.services.Clock
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
import kotlin.random.Random

/**
 * Phone → glasses map images on lf_map (spec §2.5). A new random [renderEpoch] at construction (process start) and on
 * every glasses (re)connect, announced first with an image-less header. Images only while the glasses' latest
 * lf_page_state says Map (cleared on disconnect and on connect until they report again), every 3 s or after ≥ 25 m,
 * never more than 1/s ([MapCadence]). A failed render or send just waits for the next cadence. Single-threaded.
 */
class GlassesMapStreamer(
    private val scope: CoroutineScope,
    private val clock: Clock,
    private val render: suspend (RouteState) -> ByteArray?,
    private val send: suspend (MapFrame, ByteArray?) -> Boolean,
    private val newEpoch: () -> Long = { Random.nextLong() },
    private val tickMs: Long = 250,
    private val sendTimeoutMs: Long = 5_000,
    private val log: (String) -> Unit = {},
) {
    var renderEpoch: Long = newEpoch()
        private set
    private var connected = false
    private var announced = false
    private var visible = false
    private var lastPageSeq = Long.MIN_VALUE
    private var seq = 0L
    private var state = RouteState()
    private val cadence = MapCadence()

    val mapVisible: Boolean get() = visible

    fun onConnected() {
        connected = true
        renderEpoch = newEpoch()
        announced = false
        visible = false
        lastPageSeq = Long.MIN_VALUE
        seq = 0
        cadence.reset()
    }

    fun onDisconnected() { connected = false; announced = false; visible = false }

    /** lf_page_state; an older seq than the last one seen on this connection is ignored. */
    fun onPageState(page: HudPage, seq: Long) {
        if (seq <= lastPageSeq) return
        lastPageSeq = seq
        val v = page == HudPage.Map
        if (v && !visible) cadence.reset() // arriving on the Map page renders at once
        visible = v
    }

    fun onRoute(s: RouteState) { state = s }

    fun start(): Job = scope.launch { while (isActive) { step(); delay(tickMs) } }

    suspend fun step() {
        if (!connected) return
        if (!announced) {
            announced = trySend(MapFrame(kind = MapFrameKind.Epoch, renderEpoch = renderEpoch), null)
            if (!announced) return
        }
        if (!visible) return
        val s = state
        val id = s.sessionId ?: return
        val lat = s.live?.lat ?: s.route.lastOrNull()?.lat
        val lon = s.live?.lon ?: s.route.lastOrNull()?.lon
        val now = clock.nowMs()
        if (!cadence.due(now, lat, lon)) return
        cadence.rendered(now, lat, lon)
        val epoch = renderEpoch
        val png = try { render(s) } catch (e: CancellationException) { throw e } catch (e: Exception) { log("render failed: $e"); null } ?: return
        if (!connected || !visible || epoch != renderEpoch) return // the link changed while rendering
        val frame = MapFrame(kind = MapFrameKind.Image, renderEpoch = epoch, sessionId = id, renderSeq = ++seq)
        if (trySend(frame, png)) log("sent seq=${frame.renderSeq} bytes=${png.size} epoch=$epoch")
    }

    private suspend fun trySend(frame: MapFrame, png: ByteArray?): Boolean = try {
        withTimeoutOrNull(sendTimeoutMs) { send(frame, png) } ?: false
    } catch (e: CancellationException) {
        throw e
    } catch (e: Exception) {
        log("send failed: $e"); false
    }
}
```

- [ ] **Step 4: Implement `MapImageGate.kt`**

```kotlin
package com.debasish.livefit.sync

import com.debasish.livefit.model.MapFrame
import com.debasish.livefit.model.MapFrameKind

/**
 * Glasses side of lf_map (spec §2.5): accept the newest announced epoch, reset its last-seen renderSeq, and drop images
 * from any older epoch, another session, or an older/repeated renderSeq within the epoch.
 */
class MapImageGate {
    private var epoch: Long? = null
    private var lastSeq = 0L

    val currentEpoch: Long? get() = epoch

    /** True when [frame] is an image to show while the glasses' workout is [sessionId]. */
    @Synchronized
    fun accept(frame: MapFrame, sessionId: String?): Boolean = when (frame.kind) {
        MapFrameKind.Epoch -> { epoch = frame.renderEpoch; lastSeq = 0; false }
        MapFrameKind.Image -> {
            val ok = epoch == frame.renderEpoch && sessionId != null && frame.sessionId == sessionId && frame.renderSeq > lastSeq
            if (ok) lastSeq = frame.renderSeq
            ok
        }
    }
}
```

- [ ] **Step 5: Run tests to verify they pass**

Run: `export JAVA_HOME=$(/usr/libexec/java_home -v 17) && ./gradlew :services:sync:test`
Expected: PASS.

- [ ] **Step 6: Commit**

```bash
git add services/sync
git commit -m "feat(sync): glasses map streamer with render epochs and cadence, and the glasses image gate"
```

---

## Phase 1 — Watch

### Task 10: Watch recorder — location fixes in deltas, `Started.gps`, route file

**Files:**
- Create: `services/sync/src/main/kotlin/com/debasish/livefit/sync/WatchRouteFile.kt`
- Modify: `services/sync/src/main/kotlin/com/debasish/livefit/sync/WatchSessionRecorder.kt`
- Modify: `services/sync/src/main/kotlin/com/debasish/livefit/sync/WatchExerciseController.kt`
- Modify: `services/workout/src/main/kotlin/com/debasish/livefit/services/workout/SessionAssembler.kt`
- Modify: `services/workout/src/main/kotlin/com/debasish/livefit/services/workout/HubWorkoutService.kt`
- Test: `services/sync/src/test/kotlin/com/debasish/livefit/sync/WatchLocationTest.kt`, `services/workout/src/test/kotlin/com/debasish/livefit/services/workout/GpsSnapshotTest.kt`

**Interfaces:**
- Consumes: `LocationFix`, `SessionDelta.locations`, `SessionEvent.Started.gps`, `WorkoutSnapshot.gps` (Task 1).
- Produces:
  - `ExerciseBackend.locationGranted(): Boolean` (default `true`; Health Services overrides in Task 11); `BackendUpdate.Locations(fixes: List<LocationFix>)`
  - `WatchSessionRecorder.begin(sessionId, type, tMs, gps: Boolean = false)`, `WatchSessionRecorder.locations(fixes: List<LocationFix>)`, constructor param `onFinalAcked: (String) -> Unit = {}` (last parameter)
  - `WatchExerciseController(…, routes: WatchRouteFile? = null)` (new last parameter); `val activeGps: Boolean`
  - `class WatchRouteFile(root: File) { data class SessionRoute(sessionId: String, fixes: List<LocationFix>); val route: StateFlow<SessionRoute?>; fun open(sessionId); fun append(sessionId, fixes); fun load(sessionId): List<LocationFix>; fun markAcked(sessionId); fun markDismissed(sessionId); fun sweep(); companion { RECORD = 32 } }` — `root/<sessionId>/route.bin`, 32-byte records; `append` first truncates a torn tail to the last whole record (review #4); unknown accuracy is stored as NaN and read back as null.
  - `SessionAssembler.gps(): Boolean` (= Started.gps, GPS requested); `SessionAssembler.snapshot()` fills `gps`; `HubWorkoutService` Starting snapshot carries `gps = gpsFor(type)`.

- [ ] **Step 1: Write the failing tests**

`services/sync/src/test/kotlin/com/debasish/livefit/sync/WatchLocationTest.kt`:

```kotlin
package com.debasish.livefit.sync

import com.debasish.livefit.model.DeltaAck
import com.debasish.livefit.model.EndReason
import com.debasish.livefit.model.ExerciseOp
import com.debasish.livefit.model.ExerciseRequest
import com.debasish.livefit.model.LocationFix
import com.debasish.livefit.model.Provenance
import com.debasish.livefit.model.Sample
import com.debasish.livefit.model.SessionDelta
import com.debasish.livefit.model.SessionEvent
import com.debasish.livefit.model.WorkoutType
import com.debasish.livefit.services.Clock
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import java.io.File
import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

@OptIn(ExperimentalCoroutinesApi::class)
class WatchLocationTest {
    private val live = Provenance.Live("galaxy-watch/health-services")
    private fun tmp(): File = Files.createTempDirectory("wl").toFile()
    private fun fix(t: Long, northM: Double = 0.0) = LocationFix(12.9716 + northM / 111_195.0, 77.5946, 5f, 10f, t)

    private class Backend(var location: Boolean = true) : ExerciseBackend {
        val calls = mutableListOf<String>()
        override val updates = MutableSharedFlow<BackendUpdate>(extraBufferCapacity = 16)
        override fun missingPermissions() = emptyList<String>()
        override fun locationGranted() = location
        override suspend fun otherAppTracking(): String? = null
        override suspend fun start(type: WorkoutType, useGps: Boolean): Boolean { calls += "start:$useGps"; return true }
        override suspend fun pause() = true
        override suspend fun resume() = true
        override suspend fun end(): Boolean { updates.emit(BackendUpdate.Ended(EndReason.User)); return true }
        override suspend fun reattach(last: Sample?): Boolean? = true
    }

    private class Rig(val controller: WatchExerciseController, val recorder: WatchSessionRecorder, val routes: WatchRouteFile)

    private fun TestScope.rig(root: File, backend: Backend, sent: MutableList<SessionDelta>): Rig {
        val routes = WatchRouteFile(File(root, "routes"))
        val rec = WatchSessionRecorder(File(root, "buffer"), live, backgroundScope, send = { sent += it }, onFinalAcked = routes::markAcked)
        val c = WatchExerciseController(
            backgroundScope, backend, rec, Clock { testScheduler.currentTime }, sendResult = {}, sendState = {},
            gpsPrefs = GpsPreferences(File(root, "gps.json")), routes = routes,
        )
        return Rig(c, rec, routes)
    }

    private fun start(gps: Boolean, type: WorkoutType = WorkoutType.Run) =
        ExerciseRequest(requestId = "r", sessionId = "s", op = ExerciseOp.Start(type, gps = gps))

    @Test fun recorderPutsFixesInDeltasAndGpsInStarted() = runTest {
        val sent = mutableListOf<SessionDelta>()
        val rec = WatchSessionRecorder(tmp(), live, backgroundScope, send = { sent += it })
        rec.begin("s", WorkoutType.Run, 1_000, gps = true)
        rec.locations(listOf(fix(2_000), fix(3_000, 10.0)))
        rec.locations(emptyList())
        assertTrue((sent[0].events.single() as SessionEvent.Started).gps)
        assertEquals(listOf(2_000L, 3_000L), sent[1].locations.map { it.fixTimeMs })
        assertEquals(2, sent.size, "an empty batch records nothing")
    }

    /** Spec §2.1: GPS needs ACCESS_FINE_LOCATION; denial = Health Services without GPS (FGS health only, Task 11), never a crash. */
    @Test fun gpsStartNeedsLocationPermission() = runTest {
        val sent = mutableListOf<SessionDelta>()
        val b = Backend(location = false)
        val r = rig(tmp(), b, sent); runCurrent()
        r.controller.handle(start(gps = true))
        assertEquals(listOf("start:false"), b.calls, "Health Services runs without GPS")
        assertTrue((sent[0].events.single() as SessionEvent.Started).gps, "still a GPS workout: Map page + phone fallback cover it")
        assertTrue(r.controller.activeGps)
    }

    @Test fun locationUpdatesAreRecordedAndAppendedToTheRouteFile() = runTest {
        val sent = mutableListOf<SessionDelta>()
        val b = Backend()
        val r = rig(tmp(), b, sent); runCurrent()
        r.controller.handle(start(gps = true))
        assertTrue(r.controller.activeGps)
        b.updates.emit(BackendUpdate.Locations(listOf(fix(5_000), fix(6_000, 8.0)))); runCurrent()
        assertEquals(2, sent.last().locations.size)
        assertEquals("s", r.routes.route.value!!.sessionId)
        assertEquals(listOf(5_000L, 6_000L), r.routes.route.value!!.fixes.map { it.fixTimeMs })
    }

    /** Spec §2.6 / §8: acknowledged deltas are deleted, but the route (and its start) reloads after watch process death. */
    @Test fun routeSurvivesAckedDeltaDeletionAndProcessDeath() = runTest {
        val sent = mutableListOf<SessionDelta>()
        val b = Backend(); val root = tmp()
        val r = rig(root, b, sent); runCurrent()
        r.controller.handle(start(gps = true, type = WorkoutType.Walk))
        b.updates.emit(BackendUpdate.Locations(listOf(fix(5_000), fix(6_000, 8.0)))); runCurrent()
        r.recorder.onAck(DeltaAck(sessionId = "s", seq = sent.last().seq))
        val reborn = WatchRouteFile(File(root, "routes")).apply { open("s") }
        assertEquals(listOf(5_000L, 6_000L), reborn.route.value!!.fixes.map { it.fixTimeMs })
        val rec2 = WatchSessionRecorder(File(root, "buffer"), live, backgroundScope, send = {})
        assertEquals("s", rec2.sessionId, "the running session is still held, so WatchRuntime reopens its route")
    }

    @Test fun routeIsDeletedOnlyWhenFinalizedAndDismissed() {
        val root = tmp()
        val r = WatchRouteFile(root)
        r.append("s", listOf(fix(1_000)))
        r.markAcked("s")
        assertTrue(r.load("s").isNotEmpty(), "summary not dismissed yet")
        r.markDismissed("s")
        assertTrue(r.load("s").isEmpty())
        assertNull(r.route.value)
        r.append("t", listOf(fix(1)))
        r.markDismissed("t")
        assertTrue(r.load("t").isNotEmpty(), "not finalized on the phone yet")
    }

    @Test fun finalAckMarksTheRoute() = runTest {
        val sent = mutableListOf<SessionDelta>()
        val b = Backend(); val root = tmp()
        val r = rig(root, b, sent); runCurrent()
        r.controller.handle(start(gps = true))
        b.updates.emit(BackendUpdate.Locations(listOf(fix(5_000)))); runCurrent()
        r.controller.handle(ExerciseRequest(requestId = "stop", sessionId = "s", op = ExerciseOp.Stop))
        r.recorder.onAck(DeltaAck(sessionId = "s", seq = sent.last().seq))
        assertTrue(File(root, "routes/s/acked").exists())
        r.routes.markDismissed("s")
        assertFalse(File(root, "routes/s").exists())
    }

    @Test fun aTornTailRecordIsIgnored() {
        val root = tmp()
        WatchRouteFile(root).append("s", listOf(fix(1_000), fix(2_000, 5.0)))
        File(root, "s/route.bin").appendBytes(ByteArray(7)) // process died mid-write
        val restored = WatchRouteFile(root).load("s")
        assertEquals(2, restored.size)
        assertEquals(10f, restored[0].bearingDeg)
    }

    /** Review #4: an append after a torn tail must not misalign this and every later record. */
    @Test fun appendAfterATornTailKeepsRecordsAligned() {
        val root = tmp()
        WatchRouteFile(root).append("s", listOf(fix(1_000), fix(2_000, 5.0)))
        File(root, "s/route.bin").appendBytes(ByteArray(7) { 0x55 }) // process died mid-write
        val reopened = WatchRouteFile(root).apply { open("s") }
        reopened.append("s", listOf(fix(3_000, 10.0), fix(4_000, 15.0)))
        assertEquals(4L * WatchRouteFile.RECORD, File(root, "s/route.bin").length(), "tail truncated to the record boundary")
        val back = WatchRouteFile(root).load("s")
        assertEquals(listOf(1_000L, 2_000L, 3_000L, 4_000L), back.map { it.fixTimeMs })
        assertEquals(fix(4_000, 15.0), back.last(), "exact values after the append")
        assertEquals(back, reopened.route.value!!.fixes, "the in-memory route matches the file")
    }

    @Test fun unknownAccuracyRoundTripsAsNull() {
        val r = WatchRouteFile(tmp())
        r.append("s", listOf(LocationFix(1.0, 2.0, null, 7f, 4)))
        val back = r.load("s").single()
        assertNull(back.accuracyM)
        assertEquals(7f, back.bearingDeg)
    }

    @Test fun missingBearingRoundTrips() {
        val r = WatchRouteFile(tmp())
        r.append("s", listOf(LocationFix(1.0, 2.0, 3f, null, 4)))
        assertNull(r.load("s").single().bearingDeg)
    }

    @Test fun sweepDeletesFinishedRoutes() {
        val root = tmp()
        WatchRouteFile(root).apply { append("old", listOf(fix(1))); markAcked("old") }
        File(root, "old/dismissed").writeText("") // both markers, but the process died before deleting
        WatchRouteFile(root).sweep()
        assertFalse(File(root, "old").exists())
    }
}
```

`services/workout/src/test/kotlin/com/debasish/livefit/services/workout/GpsSnapshotTest.kt`:

```kotlin
package com.debasish.livefit.services.workout

import com.debasish.livefit.confirm.DefaultConfirmationService
import com.debasish.livefit.model.ExerciseOp
import com.debasish.livefit.model.Provenance
import com.debasish.livefit.model.SessionDelta
import com.debasish.livefit.model.SessionEvent
import com.debasish.livefit.model.WorkoutType
import com.debasish.livefit.services.Clock
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

@OptIn(ExperimentalCoroutinesApi::class)
class GpsSnapshotTest {
    @Test fun snapshotReportsGpsFromTheStartedEvent() {
        val a = SessionAssembler("s")
        a.add(SessionDelta(sessionId = "s", seq = 0, events = listOf(SessionEvent.Started(1, WorkoutType.Walk, gps = true)), provenance = Provenance.Fake))
        assertTrue(a.gps())
        assertTrue(a.snapshot().gps)
    }

    @Test fun noStartedEventMeansNoGps() = assertFalse(SessionAssembler("s").snapshot().gps)

    @Test fun startingSnapshotCarriesTheHubsGpsChoice() = runTest {
        val gateway = FakeWatchGateway()
        val clock = Clock { testScheduler.currentTime }
        val hub = HubWorkoutService(backgroundScope, gateway, InMemorySessionStore(), DefaultConfirmationService(clock), clock, gpsFor = { true })
        runCurrent()
        hub.start(WorkoutType.Walk); runCurrent()
        assertTrue(hub.snapshot.value.gps)
        assertEquals(ExerciseOp.Start(WorkoutType.Walk, gps = true), gateway.sent.single().op)
    }
}
```

- [ ] **Step 2: Run tests to verify they fail**

Run: `export JAVA_HOME=$(/usr/libexec/java_home -v 17) && ./gradlew :services:sync:test --tests '*WatchLocationTest*' :services:workout:test --tests '*GpsSnapshotTest*'`
Expected: FAIL — `WatchRouteFile`, `BackendUpdate.Locations`, `locations()`, `gps()` unresolved.

- [ ] **Step 3: Create `WatchRouteFile.kt`**

```kotlin
package com.debasish.livefit.sync

import com.debasish.livefit.model.LocationFix
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import java.io.File
import java.io.IOException
import java.io.RandomAccessFile
import java.nio.ByteBuffer

/**
 * Watch-side full-session route (spec §2.6): `root/<sessionId>/route.bin`, append-only 32-byte records
 * (lat, lon: Double; accuracy (NaN = unknown), bearing (NaN = none): Float; fix time: Long), independent of delta
 * resend retention. Deleted once the session is finalized on the phone (final ack, [markAcked]) and the watch summary is
 * dismissed ([markDismissed]). A torn trailing record after process death is ignored on load and cut off before the
 * next append, so later records stay aligned (review #4).
 */
class WatchRouteFile(private val root: File) {
    data class SessionRoute(val sessionId: String, val fixes: List<LocationFix>)

    private val _route = MutableStateFlow<SessionRoute?>(null)
    /** The route of the session most recently opened or appended to. */
    val route: StateFlow<SessionRoute?> = _route

    init { root.mkdirs() }

    @Synchronized
    fun open(sessionId: String) { _route.value = SessionRoute(sessionId, load(sessionId)) }

    @Synchronized
    fun append(sessionId: String, fixes: List<LocationFix>) {
        if (fixes.isEmpty()) return
        try {
            val f = file(sessionId)
            f.parentFile?.mkdirs()
            RandomAccessFile(f, "rw").use { raf ->
                val whole = raf.length() / RECORD * RECORD
                if (whole != raf.length()) raf.setLength(whole) // drop the torn tail first (review #4)
                raf.seek(whole)
                raf.write(encode(fixes))
            }
        } catch (e: IOException) {
            java.util.logging.Logger.getLogger("WatchRouteFile").warning("route append failed: $e") // the deltas still carry the fixes
        }
        val cur = _route.value
        _route.value = if (cur?.sessionId == sessionId) cur.copy(fixes = cur.fixes + fixes) else SessionRoute(sessionId, load(sessionId))
    }

    @Synchronized
    fun load(sessionId: String): List<LocationFix> {
        val f = file(sessionId)
        if (!f.exists()) return emptyList()
        val bytes = try { f.readBytes() } catch (e: IOException) { return emptyList() }
        val buf = ByteBuffer.wrap(bytes)
        val out = ArrayList<LocationFix>(bytes.size / RECORD)
        while (buf.remaining() >= RECORD) {
            val lat = buf.double; val lon = buf.double; val acc = buf.float; val bearing = buf.float; val t = buf.long
            out += LocationFix(lat, lon, acc.takeUnless { it.isNaN() }, bearing.takeUnless { it.isNaN() }, t)
        }
        return out
    }

    fun markAcked(sessionId: String) = mark(sessionId, ACKED)
    fun markDismissed(sessionId: String) = mark(sessionId, DISMISSED)

    /** At process start: delete routes whose session is already finalized and dismissed. */
    @Synchronized
    fun sweep() { root.listFiles { f -> f.isDirectory }.orEmpty().forEach { deleteIfDone(it.name) } }

    @Synchronized
    private fun mark(sessionId: String, name: String) {
        val dir = File(root, sessionId)
        if (!dir.exists()) return // no GPS for that session: nothing kept
        try { File(dir, name).writeText("") } catch (e: IOException) { return }
        deleteIfDone(sessionId)
    }

    private fun deleteIfDone(sessionId: String) {
        val dir = File(root, sessionId)
        if (File(dir, ACKED).exists() && File(dir, DISMISSED).exists()) {
            dir.deleteRecursively()
            if (_route.value?.sessionId == sessionId) _route.value = null
        }
    }

    private fun file(sessionId: String) = File(File(root, sessionId), "route.bin")

    private fun encode(fixes: List<LocationFix>): ByteArray {
        val b = ByteBuffer.allocate(RECORD * fixes.size)
        for (f in fixes) b.putDouble(f.lat).putDouble(f.lon).putFloat(f.accuracyM ?: Float.NaN).putFloat(f.bearingDeg ?: Float.NaN).putLong(f.fixTimeMs)
        return b.array()
    }

    companion object {
        const val RECORD = 32
        const val ACKED = "acked"
        const val DISMISSED = "dismissed"
    }
}
```

- [ ] **Step 4: Update `WatchSessionRecorder.kt`**

Add the import `import com.debasish.livefit.model.LocationFix`. Add a last constructor parameter after `sendTimeoutMs`:

```kotlin
    /** The phone acked this session's final delta: it is finalized there (WatchRouteFile.markAcked). */
    private val onFinalAcked: (String) -> Unit = {},
```

Replace `begin`:

```kotlin
    fun begin(sessionId: String, type: WorkoutType, tMs: Long, gps: Boolean = false) {
        check(newest == null || isFinalized) { "session ${this.sessionId} is still recording" }
        val buffer = FileDeltaBuffer(File(root, sessionId))
        val h = WatchSessionHeader(sessionId, type, tMs, lastSeq = -1).also { buffer.writeHeader(it) }
        held += Held(buffer, h, SessionAssembler(sessionId))
        record(listOf(SessionEvent.Started(tMs, type, gps)), emptyList(), final = false)
    }
```

Add after `fun samples(...)`:

```kotlin
    /** One Health Services location batch is one delta (spec §2.1: fixes travel ordered, acked and offline-buffered). */
    fun locations(fixes: List<LocationFix>) { if (fixes.isNotEmpty()) record(emptyList(), emptyList(), final = false, locations = fixes) }
```

Replace the first two lines of `record` (signature and the `SessionDelta(...)` construction):

```kotlin
    private fun record(events: List<SessionEvent>, samples: List<Sample>, final: Boolean, locations: List<LocationFix> = emptyList()) {
        val h = newest?.takeIf { it.header.finalSeq == null } ?: return
        val d = SessionDelta(sessionId = h.header.sessionId, seq = h.header.lastSeq + 1, events = events, samples = samples, provenance = provenance, final = final, locations = locations)
```

In `onAck`, replace `h.buffer.delete()` with:

```kotlin
        h.buffer.delete()
        onFinalAcked(ack.sessionId)
```

- [ ] **Step 5: Update `WatchExerciseController.kt`**

Add the import `import com.debasish.livefit.model.LocationFix`. In `interface ExerciseBackend` add after `missingPermissions()`:

```kotlin
    /** ACCESS_FINE_LOCATION granted on the watch; without it a GPS workout runs health-only (spec §2.1). */
    fun locationGranted(): Boolean = true
```

In `sealed interface BackendUpdate` add:

```kotlin
    /** Health Services location points of one update, fix times on the watch clock. */
    data class Locations(val fixes: List<LocationFix>) : BackendUpdate
```

Add the last constructor parameter after `endTimeoutMs`:

```kotlin
    /** Full-session route kept independently of delta retention (spec §2.6); null in tests that don't need it. */
    private val routes: WatchRouteFile? = null,
```

Add below `val activeSessionId`:

```kotlin
    /** The recording session is a GPS workout (Started.gps): the watch Map page is eligible and the FGS asks for `location`. */
    val activeGps: Boolean get() = activeSessionId != null && recorder.assembler?.gps() == true
```

In the `init` collector's `when (u)` add a branch:

```kotlin
                    is BackendUpdate.Locations -> onLocations(u.fixes)
```

Add next to `onReadings`:

```kotlin
    private fun onLocations(fixes: List<LocationFix>) {
        val id = activeSessionId ?: return
        if (fixes.isEmpty()) return
        recorder.locations(fixes)
        routes?.append(id, fixes)
    }
```

In `doStartExercise` replace the three lines from `if (!backend.start(type, gps))` to `recorder.begin(...)` with:

```kotlin
        val gpsOn = gps && backend.locationGranted()
        if (!backend.start(type, gpsOn)) return ExerciseError.SensorUnavailable
        detector.reset()
        val id = sessionId() // made only once the start succeeded
        recorder.begin(id, type, clock.nowMs(), gps = gps) // requested GPS = a GPS workout, even if the watch can't locate
        routes?.open(id)
        return null
```

(and delete the old `return null` that followed).

- [ ] **Step 6: Update `SessionAssembler.kt`** — add after `startedAtMs()`:

```kotlin
    /** GPS was requested for this session (Started.gps). */
    fun gps(): Boolean = events().filterIsInstance<SessionEvent.Started>().firstOrNull()?.gps ?: false
```

and in `snapshot(...)` add the argument `gps = gps(),` after `latestSampleMs = last?.tMs,`.

- [ ] **Step 7: Update `HubWorkoutService.kt`** — in `start(type)` replace the snapshot line with:

```kotlin
        _snapshot.value = WorkoutSnapshot(sessionId = id, phase = WorkoutPhase.Starting, type = type, gps = gpsFor(type))
```

and in `publish()` replace `if (a.deltaCount == 0) snap = snap.copy(type = claim?.type ?: _snapshot.value.type, phase = WorkoutPhase.Starting)` with:

```kotlin
        if (a.deltaCount == 0) snap = snap.copy(type = claim?.type ?: _snapshot.value.type, phase = WorkoutPhase.Starting, gps = _snapshot.value.gps)
```

- [ ] **Step 8: Run tests to verify they pass**

Run: `export JAVA_HOME=$(/usr/libexec/java_home -v 17) && ./gradlew :services:sync:test :services:workout:test`
Expected: PASS (new tests plus every existing recorder/controller/hub test; the existing `FakeBackend` inherits `locationGranted() = true`, so `start:Run:true` still holds).

- [ ] **Step 9: Commit**

```bash
git add services/sync services/workout
git commit -m "feat(watch-sync): location fixes in session deltas, Started.gps and per-session route file"
```

---

### Task 11: Watch platform — Health Services location, FGS `health|location`, time-sync responder, settings and queue intake

**Files:**
- Modify: `watch/src/main/AndroidManifest.xml`
- Create: `watch/src/main/java/com/debasish/livefit/watch/WatchFgs.kt`
- Create: `watch/src/main/java/com/debasish/livefit/watch/TimeSyncResponder.kt`
- Create: `watch/src/main/java/com/debasish/livefit/watch/PageSettingsFile.kt`
- Modify: `watch/src/main/java/com/debasish/livefit/watch/ExerciseService.kt`
- Modify: `watch/src/main/java/com/debasish/livefit/watch/HealthServicesExercise.kt`
- Modify: `watch/src/main/java/com/debasish/livefit/watch/WatchRuntime.kt`
- Modify: `watch/src/main/java/com/debasish/livefit/watch/PhoneCommandListener.kt`
- Modify: `watch/src/main/java/com/debasish/livefit/watch/WatchClient.kt`
- Modify: `watch/src/main/java/com/debasish/livefit/watch/MainActivity.kt`
- Test: `watch/src/test/java/com/debasish/livefit/watch/WatchPlatformTest.kt`

**Interfaces:**
- Consumes: `WatchRouteFile`, `ExerciseBackend.locationGranted`, `BackendUpdate.Locations`, `WatchExerciseController.activeGps`, recorder `onFinalAcked` (Task 10); `TimeSyncRequest/Response`, `WatchSettingsFrame`, `QueueFrame`, `PageSettings`, `WatchPaths.TIME_REQ/TIME_RES/QUEUE` (Task 1).
- Produces:
  - `object WatchFgs { fun types(gpsWorkout: Boolean, fineLocationGranted: Boolean): Int }`
  - `object TimeSyncResponder { fun reply(request: String, nowMs: Long): ByteArray? }`
  - `class PageSettingsFile(file: File) { fun load(): PageSettings; fun save(p: PageSettings) }`
  - `WatchRuntime.routes: WatchRouteFile`
  - `WatchUiState.pages: PageSettings`, `WatchUiState.queue: QueueWindow`, `WatchUiState.route: List<LocationFix>`; `WatchClient.onSettings(json: String)`, `WatchClient.onQueue(json: String)`

- [ ] **Step 1: Write the failing test** — `watch/src/test/java/com/debasish/livefit/watch/WatchPlatformTest.kt`:

```kotlin
package com.debasish.livefit.watch

import android.content.pm.ServiceInfo
import com.debasish.livefit.model.HudPage
import com.debasish.livefit.model.PageSettings
import com.debasish.livefit.model.TimeSyncRequest
import com.debasish.livefit.model.TimeSyncResponse
import com.debasish.livefit.model.Wire
import java.io.File
import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull

class WatchPlatformTest {
    /** Spec §2.1: health|location during GPS workouts; health only without ACCESS_FINE_LOCATION (never crash). */
    @Test fun fgsAddsLocationOnlyForAGpsWorkoutWithPermission() {
        val health = ServiceInfo.FOREGROUND_SERVICE_TYPE_HEALTH
        val location = ServiceInfo.FOREGROUND_SERVICE_TYPE_LOCATION
        assertEquals(health or location, WatchFgs.types(gpsWorkout = true, fineLocationGranted = true))
        assertEquals(health, WatchFgs.types(gpsWorkout = true, fineLocationGranted = false))
        assertEquals(health, WatchFgs.types(gpsWorkout = false, fineLocationGranted = true))
    }

    @Test fun timeSyncReplyEchoesIdAndT0WithTheWatchClock() {
        val bytes = assertNotNull(TimeSyncResponder.reply(Wire.encode(TimeSyncRequest(id = 7, t0 = 1_000)), nowMs = 6_100))
        assertEquals(TimeSyncResponse(id = 7, t0 = 1_000, tw = 6_100), Wire.decode<TimeSyncResponse>(String(bytes)))
    }

    @Test fun timeSyncIgnoresOtherVersions() {
        assertNull(TimeSyncResponder.reply("""{"protocolVersion":3,"id":1,"t0":1}""", 5))
        assertNull(TimeSyncResponder.reply("garbage", 5))
    }

    @Test fun pageSettingsPersistAcrossRestarts() {
        val dir = Files.createTempDirectory("pages").toFile()
        val f = File(dir, "pages.json")
        PageSettingsFile(f).save(PageSettings(disabled = setOf(HudPage.Map)))
        assertEquals(setOf(HudPage.Map), PageSettingsFile(f).load().disabled)
        assertEquals(PageSettings(), PageSettingsFile(File(dir, "missing.json")).load())
    }
}
```

- [ ] **Step 2: Run test to verify it fails**

Run: `export JAVA_HOME=$(/usr/libexec/java_home -v 17) && ./gradlew :watch:testDebugUnitTest --tests '*WatchPlatformTest*'`
Expected: FAIL — `WatchFgs`, `TimeSyncResponder`, `PageSettingsFile` unresolved.

- [ ] **Step 3: Create the three small classes**

`watch/src/main/java/com/debasish/livefit/watch/WatchFgs.kt`:

```kotlin
package com.debasish.livefit.watch

import android.content.pm.ServiceInfo

/** Foreground-service types for the exercise service (spec §2.1): `health`, plus `location` for a GPS workout with permission. */
object WatchFgs {
    fun types(gpsWorkout: Boolean, fineLocationGranted: Boolean): Int =
        ServiceInfo.FOREGROUND_SERVICE_TYPE_HEALTH or if (gpsWorkout && fineLocationGranted) ServiceInfo.FOREGROUND_SERVICE_TYPE_LOCATION else 0
}
```

`watch/src/main/java/com/debasish/livefit/watch/TimeSyncResponder.kt`:

```kotlin
package com.debasish.livefit.watch

import com.debasish.livefit.model.PROTOCOL_VERSION
import com.debasish.livefit.model.TimeSyncRequest
import com.debasish.livefit.model.TimeSyncResponse
import com.debasish.livefit.model.Wire

/** Answers the phone's clock-calibration ping at once with the watch wall clock (spec §2.1). */
object TimeSyncResponder {
    fun reply(request: String, nowMs: Long): ByteArray? {
        if (Wire.versionOf(request) != PROTOCOL_VERSION) return null
        val r = runCatching { Wire.decode<TimeSyncRequest>(request) }.getOrNull() ?: return null
        return Wire.encode(TimeSyncResponse(id = r.id, t0 = r.t0, tw = nowMs)).toByteArray()
    }
}
```

`watch/src/main/java/com/debasish/livefit/watch/PageSettingsFile.kt`:

```kotlin
package com.debasish.livefit.watch

import com.debasish.livefit.model.PageSettings
import com.debasish.livefit.model.Wire
import java.io.File
import java.io.IOException

/** The last page set from the phone, kept so offline workouts and restarts show the same pages (spec §3.2). */
class PageSettingsFile(private val file: File) {
    fun load(): PageSettings = runCatching { Wire.decode<PageSettings>(file.readText()) }.getOrDefault(PageSettings())

    fun save(p: PageSettings) {
        try {
            file.parentFile?.mkdirs()
            val tmp = File(file.path + ".tmp")
            tmp.writeText(Wire.encode(p))
            tmp.renameTo(file)
        } catch (e: IOException) {
            android.util.Log.w(WatchRuntime.TAG, "pages.json write failed", e)
        }
    }
}
```

- [ ] **Step 4: Run the test to verify it passes**

Run: `export JAVA_HOME=$(/usr/libexec/java_home -v 17) && ./gradlew :watch:testDebugUnitTest --tests '*WatchPlatformTest*'`
Expected: PASS (4 tests).

- [ ] **Step 5: Manifest** — in `watch/src/main/AndroidManifest.xml` add next to the other permissions:

```xml
    <!-- Map tiles over the watch's own connection (phone proxy, Wi-Fi or LTE). -->
    <uses-permission android:name="android.permission.INTERNET" />
    <uses-permission android:name="android.permission.FOREGROUND_SERVICE_LOCATION" />
```

and change the exercise service's type:

```xml
        <service
            android:name=".ExerciseService"
            android:exported="false"
            android:foregroundServiceType="health|location" />
```

- [ ] **Step 6: `ExerciseService.kt`** — add imports `android.Manifest`, `android.content.pm.PackageManager`, `androidx.core.content.ContextCompat`, `android.util.Log`. Add the field `private var notification: Notification? = null`. In `onCreate` replace `ServiceCompat.startForeground(this, ID, builder.build(), ServiceInfo.FOREGROUND_SERVICE_TYPE_HEALTH)` with:

```kotlin
        notification = builder.build()
        promote()
```

Replace `override fun onStartCommand(intent: Intent?, flags: Int, startId: Int) = START_STICKY` with:

```kotlin
    /** Every start re-promotes: a GPS session started after the service came up needs `location` too. */
    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        promote()
        return START_STICKY
    }

    /** health|location for a GPS workout with fine location; health only otherwise — and never crash on a refusal. */
    private fun promote() {
        val n = notification ?: return
        val fine = ContextCompat.checkSelfPermission(this, Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED
        val types = WatchFgs.types(WatchRuntime.controller.activeGps, fine)
        runCatching { ServiceCompat.startForeground(this, ID, n, types) }.onFailure { e ->
            Log.w(WatchRuntime.TAG, "startForeground($types) refused; health only", e)
            runCatching { ServiceCompat.startForeground(this, ID, n, ServiceInfo.FOREGROUND_SERVICE_TYPE_HEALTH) }
        }
    }
```

- [ ] **Step 7: `HealthServicesExercise.kt`** — add imports `androidx.health.services.client.data.LocationAccuracy`, `com.debasish.livefit.model.LocationFix`. Add the override:

```kotlin
    override fun locationGranted(): Boolean =
        ContextCompat.checkSelfPermission(app, Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED
```

In `start(...)` replace `val wanted = setOf(DataType.HEART_RATE_BPM, DataType.STEPS_TOTAL, DataType.DISTANCE_TOTAL, DataType.CALORIES_TOTAL, DataType.SPEED)` with:

```kotlin
        // useGps already includes the permission check (WatchExerciseController): LOCATION only for GPS workouts.
        val wanted: Set<DataType<*, *>> = setOf<DataType<*, *>>(DataType.HEART_RATE_BPM, DataType.STEPS_TOTAL, DataType.DISTANCE_TOTAL, DataType.CALORIES_TOTAL, DataType.SPEED) +
            listOfNotNull<DataType<*, *>>(DataType.LOCATION.takeIf { useGps })
```

and add, right after the existing `Log.d(TAG, "exercise $exerciseType supported=…")` line:

```kotlin
        Log.i(TAG, "gps=$useGps location supported=${DataType.LOCATION in supported}")
```

Replace `setIsGpsEnabled(useGps && ContextCompat.checkSelfPermission(app, Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED)` with `setIsGpsEnabled(useGps && locationGranted())`.

Replace `batchingOverrides` with:

```kotlin
    /**
     * Spec §2.1: Health Services may batch location while the screen is off; request every override this watch supports
     * (HEART_RATE_5_SECONDS today, and any location override a newer Health Services adds). The list is logged at start.
     */
    private fun batchingOverrides(supported: Set<BatchingMode>): Set<BatchingMode> = supported
```

In the callback, after the line `batchSamples(hr, now, …)…` add:

```kotlin
            // Location points, each at its own time (screen-off batches arrive late, spec §2.1).
            val fixes = m.getData(DataType.LOCATION).map { dp ->
                val v = dp.value
                // No accuracy = unknown (null, review #8): kept in the delta and route.bin, never routed or live (FixQuality).
                val acc = (dp.accuracy as? LocationAccuracy)?.horizontalPositionErrorMeters?.toFloat()
                    .also { if (it == null) Log.w(TAG, "location point without accuracy (unknown)") }
                LocationFix(v.latitude, v.longitude, acc, v.bearing.takeIf { it.isFinite() && it >= 0 }?.toFloat(), dp.getTimeInstant(boot).toEpochMilli().coerceAtMost(now))
            }
            if (fixes.isNotEmpty()) queue.trySend(BackendUpdate.Locations(fixes))
```

- [ ] **Step 8: `WatchRuntime.kt`** — add the import `com.debasish.livefit.sync.WatchRouteFile`, the field `lateinit var routes: WatchRouteFile; private set`, and in `init(context)` before `recorder = …`:

```kotlin
        routes = WatchRouteFile(File(app.filesDir, "routes")).also { it.sweep() }
```

Give the recorder its last argument `onFinalAcked = { id -> routes.markAcked(id) },` and the controller its last argument `routes = routes,`. After `controller = …` add:

```kotlin
        recorder.sessionId?.let(routes::open) // route + start marker survive process death (spec §2.6)
```

- [ ] **Step 9: `PhoneCommandListener.kt`** — make the time-sync ping the very first thing (no runtime init, so the reply is immediate). At the top of `onMessageReceived`:

```kotlin
        if (event.path == WatchPaths.TIME_REQ) {
            TimeSyncResponder.reply(String(event.data), System.currentTimeMillis())
                ?.let { Wearable.getMessageClient(this).sendMessage(event.sourceNodeId, WatchPaths.TIME_RES, it) }
            return
        }
```

and in the `when (event.path)` inside `WatchRuntime.scope.launch` add:

```kotlin
                    WatchPaths.SETTINGS -> WatchClient.onSettings(text)
                    WatchPaths.QUEUE -> WatchClient.onQueue(text)
```

- [ ] **Step 10: `WatchClient.kt`** — add imports `com.debasish.livefit.model.LocationFix`, `com.debasish.livefit.model.PageSettings`, `com.debasish.livefit.model.QueueFrame`, `com.debasish.livefit.model.QueueWindow`, `com.debasish.livefit.model.WatchSettingsFrame`, `java.io.File`. Extend `WatchUiState` with:

```kotlin
    /** Settings → Pages from the phone (spec §3.2). */
    val pages: PageSettings = PageSettings(),
    /** YouTube Music queue window for the Playlist page (spec §6). */
    val queue: QueueWindow = QueueWindow(),
    /** This session's own route from route.bin (spec §2.6: the watch map never uses phone fixes). */
    val route: List<LocationFix> = emptyList(),
```

Add fields to `object WatchClient`:

```kotlin
    private val pagesFile by lazy { PageSettingsFile(File(WatchRuntime.app.filesDir, "pages.json")) }
    private var pages = PageSettings()
    private var queue = QueueWindow()
```

In `start()` after `started = true` add:

```kotlin
        pages = pagesFile.load()
        WatchRuntime.scope.launch { WatchRuntime.routes.route.collect { refresh() } }
```

Add:

```kotlin
    fun onSettings(json: String) {
        val f = runCatching { Wire.decode<WatchSettingsFrame>(json) }.getOrNull() ?: return
        pages = f.pages
        pagesFile.save(f.pages)
        refresh()
    }

    fun onQueue(json: String) {
        queue = runCatching { Wire.decode<QueueFrame>(json).window }.getOrNull() ?: return
        refresh()
    }

    private fun routeFor(sessionId: String?): List<LocationFix> =
        WatchRuntime.routes.route.value?.takeIf { it.sessionId == sessionId }?.fixes ?: emptyList()
```

In `onFrame`, right after decoding `frame`, add (a summary dismissed on another device also counts):

```kotlin
        lastFrame?.workout?.takeIf { it.phase == WorkoutPhase.Summary && frame.workout.phase != WorkoutPhase.Summary }
            ?.sessionId?.let(WatchRuntime.routes::markDismissed)
```

In `refresh()`, add to the offline `WatchUiState(...)`: `pages = pages, queue = QueueWindow(), route = routeFor(snap.sessionId),` (music is unavailable offline, so Playlist shows its empty state) and to the online one: `pages = pages, queue = queue, route = routeFor(f?.workout?.sessionId),`.

In `command(c)`, as the first line inside `WatchRuntime.scope.launch { try {`:

```kotlin
                if (c == Command.DismissSummary) _ui.value.snapshot.sessionId?.let(WatchRuntime.routes::markDismissed)
```

- [ ] **Step 11: `MainActivity.kt`** — in `onResume()` add `WatchRuntime.ensureExerciseService()` after `super.onResume()` (a visible activity lets the service re-promote with `location`).

- [ ] **Step 12: Build and run the watch tests**

Run: `export JAVA_HOME=$(/usr/libexec/java_home -v 17) && ./gradlew :watch:assembleDebug :watch:testDebugUnitTest`
Expected: BUILD SUCCESSFUL, tests PASS.

- [ ] **Step 13: Commit**

```bash
git add watch
git commit -m "feat(watch): GPS fixes from Health Services, health|location FGS, time-sync responder, pages and queue intake"
```

---

### Task 12: Watch pages UI — Glance, Stats, Playlist, Map, Music controls and the bezel

**Files:**
- Create: `watch/src/main/java/com/debasish/livefit/watch/WatchPageModel.kt`
- Create: `watch/src/main/java/com/debasish/livefit/watch/map/WatchMapModel.kt`
- Create: `watch/src/main/java/com/debasish/livefit/watch/map/WatchTiles.kt`
- Create: `watch/src/main/java/com/debasish/livefit/watch/ui/WatchPages.kt`
- Create: `watch/src/main/java/com/debasish/livefit/watch/ui/WatchMap.kt`
- Modify: `watch/src/main/java/com/debasish/livefit/watch/ui/WatchApp.kt`
- Modify: `watch/src/main/java/com/debasish/livefit/watch/WatchRuntime.kt`
- Modify: `watch/src/main/java/com/debasish/livefit/watch/WatchClient.kt`
- Modify: `watch/src/main/java/com/debasish/livefit/watch/MainActivity.kt`
- Modify: `watch/build.gradle.kts`
- Test: `watch/src/test/java/com/debasish/livefit/watch/WatchPagesTest.kt`

**Interfaces:**
- Consumes: `PageSet` (Task 5); `Viewport`, `MapSceneBuilder`, `MapScene`, `MapArrow`, `HudPalette`, `HttpTileFetcher`, `TileDiskCache`, `OsmTileSource`, `TileId`, `TileLoader` (Tasks 2, 7, 8); `RouteTrack`, `RoutePoint`, `RouteState`, `LivePosition`, `toRouteFix` (Task 3); `Freshness` (Task 4); `WatchRouteFile.SessionRoute` (Task 10); `WatchUiState.pages/queue/route`, `WatchClient` route collector (Task 11). `:core:map` reaches the watch through `:services:sync` (Task 2).
- Produces:
  - `object WatchPageModel { fun pages(state: WatchUiState): List<HudPage>; fun initialIndex(pages: List<HudPage>, shown: HudPage): Int }`
  - `class WatchMapTracker { val live: LivePosition?; fun onRoute(route: WatchRouteFile.SessionRoute?, nowMs: Long); fun liveFor(sessionId: String?): LivePosition? }` — the marker moves only on a fix that is usable-live **when it arrives** (`Freshness.isUsableLive` on the watch clock, the same predicate as the phone); aged batches still grow the route; a route restored after process death has no marker until a live fix (review #7).
  - `object WatchMapModel { fun state(fixes: List<LocationFix>, live: LivePosition?, sessionId: String?, type: WorkoutType, nowMs: Long): RouteState; fun zoomStep(zoom: Int, scrollPixels: Float): Int }`
  - `object WatchTilePolicy { RETRY_MS = 5_000L; MAX_TILES = 30; MAX_CONCURRENT = 3; fun <T : Any> loader(scope, load: suspend (TileId) -> T?): TileLoader<T> }`
  - `class WatchTiles(context, scope, tint = 0x14C3A2) { val bitmaps: StateFlow<Map<TileId, ImageBitmap>>; fun show(tiles: Collection<TileId>) }` — missing visible tiles are retried every 5 s while visible (review #6); `WatchRuntime.tiles: WatchTiles`
  - `WatchUiState.live: LivePosition?`; `WatchApp(state, onCommand, onVolume, onGrantPermissions, tiles: WatchTiles, ambient = false)`

- [ ] **Step 1: Write the failing test** — in `watch/build.gradle.kts` add `testImplementation("org.jetbrains.kotlinx:kotlinx-coroutines-test:1.9.0")` next to the other test dependencies, then create `watch/src/test/java/com/debasish/livefit/watch/WatchPagesTest.kt`:

```kotlin
package com.debasish.livefit.watch

import com.debasish.livefit.map.TileId
import com.debasish.livefit.model.GpsStatus
import com.debasish.livefit.model.HudPage
import com.debasish.livefit.model.LocationFix
import com.debasish.livefit.model.PageSettings
import com.debasish.livefit.model.WorkoutPhase
import com.debasish.livefit.model.WorkoutSnapshot
import com.debasish.livefit.model.WorkoutType
import com.debasish.livefit.sync.WatchRouteFile.SessionRoute
import com.debasish.livefit.watch.map.WatchMapModel
import com.debasish.livefit.watch.map.WatchMapTracker
import com.debasish.livefit.watch.map.WatchTilePolicy
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

@OptIn(ExperimentalCoroutinesApi::class)
class WatchPagesTest {
    private fun fix(t: Long, northM: Double, acc: Float? = 5f) = LocationFix(12.9716 + northM / 111_195.0, 77.5946, acc, null, t)
    private val gpsRun = WorkoutSnapshot(phase = WorkoutPhase.Active, type = WorkoutType.Run, sessionId = "s", gps = true)

    /** Feeds route.bin growth to the tracker the way WatchClient does: each emission at its arrival time. */
    private class Feed {
        val tracker = WatchMapTracker()
        val fixes = mutableListOf<LocationFix>()
        init { tracker.onRoute(SessionRoute("s", emptyList()), 0) } // session opened (WatchRouteFile.open)
        fun arrive(nowMs: Long, vararg batch: LocationFix) { fixes += batch; tracker.onRoute(SessionRoute("s", fixes.toList()), nowMs) }
        fun state(nowMs: Long) = WatchMapModel.state(fixes, tracker.liveFor("s"), "s", WorkoutType.Walk, nowMs)
    }

    /** Spec §3.1: the same page set as the glasses; Glance and Playlist are new on the watch. */
    @Test fun pagesFollowSettingsAndMapEligibility() {
        assertEquals(HudPage.entries, WatchPageModel.pages(WatchUiState(snapshot = gpsRun)))
        val noGps = WatchPageModel.pages(WatchUiState(snapshot = gpsRun.copy(gps = false), pages = PageSettings(disabled = setOf(HudPage.Glance))))
        assertEquals(listOf(HudPage.Workout, HudPage.Stats, HudPage.Playlist, HudPage.MusicControls), noGps)
    }

    @Test fun aVanishedPageReopensOnWorkout() {
        val pages = listOf(HudPage.Glance, HudPage.Workout, HudPage.Playlist)
        assertEquals(1, WatchPageModel.initialIndex(pages, HudPage.Stats))
        assertEquals(2, WatchPageModel.initialIndex(pages, HudPage.Playlist))
    }

    /** Spec §2.6: the watch map draws its own fixes, start marker and live arrow. */
    @Test fun ownFixesBecomeTheRouteWithStartAndMarker() {
        val now = 100_000L
        val f = Feed()
        f.arrive(now - 3_000, fix(now - 3_000, 0.0)); f.arrive(now - 2_000, fix(now - 2_000, 10.0)); f.arrive(now - 1_000, fix(now - 1_000, 20.0))
        val s = f.state(now)
        assertEquals(3, s.route.size)
        assertEquals(s.route.first(), s.start)
        assertEquals(GpsStatus.Live, s.status)
        assertEquals(0f, assertNotNull(assertNotNull(s.live).bearingDeg), 0.5f, "bearing from the last two points")
    }

    @Test fun staleOwnFixIsDelayedThenLost() {
        val f = Feed()
        f.arrive(0, fix(0, 0.0))
        assertEquals(GpsStatus.Delayed, f.state(20_000).status)
        val lost = f.state(31_000)
        assertEquals(GpsStatus.Lost, lost.status)
        assertNotNull(lost.live, "last point kept")
    }

    /** Review #7: live A, then an aged batch B → the route grows, the marker stays on A and the status degrades; live C moves it. */
    @Test fun agedBatchGrowsTheRouteButNeverMovesTheMarker() {
        val f = Feed()
        f.arrive(1_000, fix(1_000, 0.0))                                                   // A, live on arrival
        f.arrive(25_000, fix(2_000, 10.0), fix(3_000, 20.0), fix(4_000, 30.0))             // B, measured 21–23 s ago
        val s = f.state(25_000)
        assertEquals(4, s.route.size, "B is drawn")
        assertEquals(1_000L, assertNotNull(s.live).fixTimeMs, "the marker stays on A")
        assertEquals(GpsStatus.Delayed, s.status, "from A's age, not B's")
        f.arrive(26_000, fix(26_000, 40.0))                                                 // C, live
        assertEquals(26_000L, f.state(26_000).live?.fixTimeMs)
        assertEquals(GpsStatus.Live, f.state(26_000).status)
    }

    /** Review #7: a route reloaded from route.bin after process death is drawn but has no marker until a live fix arrives. */
    @Test fun restoredRouteHasNoMarkerUntilALiveFix() {
        val t = WatchMapTracker()
        val cached = listOf(fix(1_000, 0.0), fix(2_000, 10.0))
        t.onRoute(SessionRoute("s", cached), 2_500) // process restarted: route.bin reopened
        assertNull(t.liveFor("s"))
        val s = WatchMapModel.state(cached, t.liveFor("s"), "s", WorkoutType.Walk, 2_500)
        assertEquals(2, s.route.size)
        assertEquals(GpsStatus.Waiting, s.status)
        t.onRoute(SessionRoute("s", cached + fix(3_000, 20.0)), 3_000)
        assertEquals(3_000L, t.liveFor("s")?.fixTimeMs)
        assertNull(t.liveFor("other"), "another session never shows this marker")
    }

    @Test fun inaccurateFixesNeverBecomeTheMarker() {
        val f = Feed()
        f.arrive(1_000, fix(1_000, 0.0, acc = 80f))
        val s = f.state(1_000)
        assertNull(s.live)
        assertTrue(s.route.isEmpty())
        assertEquals(GpsStatus.Waiting, s.status)
    }

    /** Review #8: fresh watch fixes without accuracy neither draw nor move the marker. */
    @Test fun unknownAccuracyFixesNeverBecomeTheMarker() {
        val f = Feed()
        f.arrive(1_000, fix(1_000, 0.0, acc = null))
        f.arrive(2_000, fix(2_000, 10.0, acc = null))
        val s = f.state(2_000)
        assertNull(s.live)
        assertTrue(s.route.isEmpty())
        assertEquals(GpsStatus.Waiting, s.status)
    }

    /** Review #6: the Map page's viewport does not move; the first fetch fails, the network returns, tiles appear. */
    @Test fun fixedViewportGetsItsTilesOnceTheNetworkReturns() = runTest {
        var online = false
        val loader = WatchTilePolicy.loader(backgroundScope) { t: TileId -> if (online) "tile ${t.x}" else null }
        loader.start()
        val visible = listOf(TileId(18, 5, 5), TileId(18, 6, 5))
        loader.show(visible); runCurrent()
        assertTrue(loader.tiles.value.isEmpty())
        online = true
        advanceTimeBy(WatchTilePolicy.RETRY_MS + 1); runCurrent()
        assertEquals(visible.toSet(), loader.tiles.value.keys, "no navigation or zoom needed")
    }

    /** Spec §2.6: bezel zoom 14–18. */
    @Test fun bezelZoomIsClamped() {
        assertEquals(18, WatchMapModel.zoomStep(18, 12f))
        assertEquals(17, WatchMapModel.zoomStep(18, -12f))
        assertEquals(14, WatchMapModel.zoomStep(14, -3f))
        assertEquals(15, WatchMapModel.zoomStep(14, 3f))
        assertEquals(16, WatchMapModel.zoomStep(16, 0f))
    }
}
```

- [ ] **Step 2: Run test to verify it fails**

Run: `export JAVA_HOME=$(/usr/libexec/java_home -v 17) && ./gradlew :watch:testDebugUnitTest --tests '*WatchPagesTest*'`
Expected: FAIL — `WatchPageModel`, `WatchMapModel`, `WatchMapTracker`, `WatchTilePolicy` unresolved.

- [ ] **Step 3: Implement the pure models**

`watch/src/main/java/com/debasish/livefit/watch/WatchPageModel.kt`:

```kotlin
package com.debasish.livefit.watch

import com.debasish.livefit.model.HudPage
import com.debasish.livefit.model.PageSet

/** Watch pager pages (spec §3): the shared page set, Map only during a recording GPS workout. */
object WatchPageModel {
    fun pages(state: WatchUiState): List<HudPage> = PageSet.available(state.pages, PageSet.mapEligible(state.snapshot))

    /** Where the pager opens: the shown page while it still exists, else Workout (spec §3.3). */
    fun initialIndex(pages: List<HudPage>, shown: HudPage): Int = pages.indexOf(PageSet.resolve(shown, pages)).coerceAtLeast(0)
}
```

`watch/src/main/java/com/debasish/livefit/watch/map/WatchMapModel.kt`:

```kotlin
package com.debasish.livefit.watch.map

import com.debasish.livefit.map.TileId
import com.debasish.livefit.map.TileLoader
import com.debasish.livefit.map.Viewport
import com.debasish.livefit.model.FixSource
import com.debasish.livefit.model.LivePosition
import com.debasish.livefit.model.LocationFix
import com.debasish.livefit.model.RouteState
import com.debasish.livefit.model.RouteTrack
import com.debasish.livefit.model.WorkoutType
import com.debasish.livefit.model.toRouteFix
import com.debasish.livefit.sync.Freshness
import com.debasish.livefit.sync.WatchRouteFile
import kotlinx.coroutines.CoroutineScope

/**
 * The watch marker (spec §2.1/§2.6, review #7). Fed every route.bin emission at its arrival time: only a fix that is
 * usable-live when it arrives ([Freshness.isUsableLive] on the watch's own clock — the phone's predicate) moves it; aged
 * batches still grow the route. A route restored after process death (or first seen mid-session) has no marker until
 * the next live fix.
 */
class WatchMapTracker {
    private var sessionId: String? = null
    private var seen = 0

    var live: LivePosition? = null
        private set

    fun onRoute(route: WatchRouteFile.SessionRoute?, nowMs: Long) {
        if (route == null || route.sessionId != sessionId || route.fixes.size < seen) {
            sessionId = route?.sessionId
            seen = route?.fixes?.size ?: 0
            live = null
            return
        }
        for (f in route.fixes.subList(seen, route.fixes.size)) {
            if (Freshness.isUsableLive(f.fixTimeMs, f.accuracyM, nowMs) && f.fixTimeMs > (live?.fixTimeMs ?: Long.MIN_VALUE)) {
                live = LivePosition(f.lat, f.lon, f.bearingDeg, FixSource.Watch, f.fixTimeMs)
            }
        }
        seen = route.fixes.size
    }

    fun liveFor(sessionId: String?): LivePosition? = live?.takeIf { sessionId != null && sessionId == this.sessionId }
}

/** The watch map from the watch's own fixes only (spec §2.6); its clock is its own, so no offset. */
object WatchMapModel {
    fun state(fixes: List<LocationFix>, live: LivePosition?, sessionId: String?, type: WorkoutType, nowMs: Long): RouteState {
        val track = RouteTrack.of(fixes.mapNotNull { it.toRouteFix(FixSource.Watch, phoneTimeMs = it.fixTimeMs)?.point() }, nowMs)
        val drawn = track.drawn()
        val marker = live?.let { it.copy(bearingDeg = it.bearingDeg ?: track.lastBearing()) }
        return RouteState(sessionId, type, drawn, drawn.firstOrNull(), marker, Freshness.status(live?.fixTimeMs, nowMs))
    }

    /** One bezel detent = one zoom level, 14–18; the map always re-centres on the current position. */
    fun zoomStep(zoom: Int, scrollPixels: Float): Int =
        (zoom + when { scrollPixels > 0 -> 1; scrollPixels < 0 -> -1; else -> 0 }).coerceIn(Viewport.MIN_ZOOM, Viewport.MAX_ZOOM)
}

/** The watch's tile loading policy (review #6): 3 at a time, missing visible tiles retried every 5 s, 30 kept. */
object WatchTilePolicy {
    const val RETRY_MS = 5_000L
    const val MAX_TILES = 30
    const val MAX_CONCURRENT = 3

    fun <T : Any> loader(scope: CoroutineScope, load: suspend (TileId) -> T?): TileLoader<T> =
        TileLoader(scope, load, maxConcurrent = MAX_CONCURRENT, retryEveryMs = RETRY_MS, maxCached = MAX_TILES)
}
```

- [ ] **Step 4: Run test to verify it passes**

Run: `export JAVA_HOME=$(/usr/libexec/java_home -v 17) && ./gradlew :watch:testDebugUnitTest --tests '*WatchPagesTest*'`
Expected: PASS (10 tests).

- [ ] **Step 5: Tiles on the watch** — `watch/src/main/java/com/debasish/livefit/watch/map/WatchTiles.kt`:

```kotlin
package com.debasish.livefit.watch.map

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import com.debasish.livefit.map.HttpTileFetcher
import com.debasish.livefit.map.HudPalette
import com.debasish.livefit.map.OsmTileSource
import com.debasish.livefit.map.TileDiskCache
import com.debasish.livefit.map.TileId
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.withContext
import java.io.File

/**
 * Watch tiles over the watch's own connection (spec §2.4/§2.6): 20 MB LRU disk cache, palette-converted with the
 * watch mint so it matches the glasses look; only the visible tiles are ever requested. A visible tile that failed is
 * retried every 5 s while it stays visible (the fetcher's 30 s backoff limits real requests), so a fixed viewport fills
 * in when the network returns (review #6).
 */
class WatchTiles(context: Context, scope: CoroutineScope, private val tint: Int = 0x14C3A2) {
    private val fetcher = HttpTileFetcher(OsmTileSource(), TileDiskCache(File(context.cacheDir, "tiles"), TileDiskCache.WATCH_MAX_BYTES))
    private val loader = WatchTilePolicy.loader(scope) { t -> withContext(Dispatchers.IO) { fetcher.fetch(t)?.let(::decode) } }
        .also { it.start() }
    val bitmaps: StateFlow<Map<TileId, ImageBitmap>> = loader.tiles

    /** The tiles of the current viewport, on every viewport change; empty when the Map page is not shown. */
    fun show(tiles: Collection<TileId>) = loader.show(tiles)

    private fun decode(bytes: ByteArray): ImageBitmap? {
        val src = BitmapFactory.decodeByteArray(bytes, 0, bytes.size) ?: return null
        val px = IntArray(src.width * src.height)
        src.getPixels(px, 0, src.width, 0, 0, src.width, src.height)
        HudPalette.convertAll(px, tint)
        return Bitmap.createBitmap(px, src.width, src.height, Bitmap.Config.ARGB_8888).asImageBitmap()
    }
}
```

In `WatchRuntime.kt` add `import com.debasish.livefit.watch.map.WatchTiles` and:

```kotlin
    /** Map tiles for the watch Map page (created on first use). */
    val tiles: WatchTiles by lazy { WatchTiles(app, scope) }
```

In `WatchClient.kt` (the marker, review #7) add the imports `com.debasish.livefit.model.LivePosition`, `com.debasish.livefit.watch.map.WatchMapTracker`; extend `WatchUiState` with

```kotlin
    /** The watch Map marker: last fix that was usable-live on arrival (WatchMapTracker). */
    val live: LivePosition? = null,
```

add the field `private val mapTracker = WatchMapTracker()`, replace the Task 11 collector line in `start()` with

```kotlin
        WatchRuntime.scope.launch { WatchRuntime.routes.route.collect { mapTracker.onRoute(it, System.currentTimeMillis()); refresh() } }
```

and in `refresh()` add `live = mapTracker.liveFor(snap.sessionId),` to the offline `WatchUiState(...)` and `live = mapTracker.liveFor(f?.workout?.sessionId),` to the online one.

- [ ] **Step 6: Map page** — `watch/src/main/java/com/debasish/livefit/watch/ui/WatchMap.kt`:

```kotlin
package com.debasish.livefit.watch.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.focusable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.rotate
import androidx.compose.ui.graphics.drawscope.translate
import androidx.compose.ui.input.rotary.onRotaryScrollEvent
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.wear.compose.material.Text
import com.debasish.livefit.map.MapArrow
import com.debasish.livefit.map.MapSceneBuilder
import com.debasish.livefit.map.Viewport
import com.debasish.livefit.model.LivePosition
import com.debasish.livefit.model.LocationFix
import com.debasish.livefit.model.WorkoutType
import com.debasish.livefit.watch.map.WatchMapModel
import com.debasish.livefit.watch.map.WatchTiles
import kotlinx.coroutines.delay

/** Watch Map page (spec §2.6): own fixes over tiles, bezel = zoom 14–18, offline = route only on black. */
@Composable
internal fun WatchMapPage(route: List<LocationFix>, live: LivePosition?, sessionId: String?, type: WorkoutType, tiles: WatchTiles) {
    var zoom by remember(type) { mutableIntStateOf(Viewport.zoomFor(type)) }
    var now by remember { mutableLongStateOf(System.currentTimeMillis()) }
    val focus = remember { FocusRequester() }
    LaunchedEffect(Unit) {
        runCatching { focus.requestFocus() }
        while (true) { delay(1_000); now = System.currentTimeMillis() } // status ages even without new fixes
    }
    val state = remember(route.size, route.lastOrNull(), live, sessionId, now / 1_000) { WatchMapModel.state(route, live, sessionId, type, now) }
    DisposableEffect(Unit) { onDispose { tiles.show(emptyList()) } } // off the Map page: nothing visible, nothing retried
    val bitmaps by tiles.bitmaps.collectAsState()
    BoxWithConstraints(
        Modifier.fillMaxSize().background(Color.Black)
            .onRotaryScrollEvent { e -> zoom = WatchMapModel.zoomStep(zoom, e.verticalScrollPixels); true }
            .focusRequester(focus).focusable(),
    ) {
        val w = constraints.maxWidth
        val h = constraints.maxHeight
        val scene = remember(state, zoom, w, h) { MapSceneBuilder.build(state, zoom, w, h) }
        val visible = remember(scene.viewport) { scene.viewport?.tiles().orEmpty() }
        LaunchedEffect(visible) { tiles.show(visible.map { it.tile }) } // missing ones keep retrying while visible (review #6)
        val anyTile = visible.any { it.tile in bitmaps }
        Canvas(Modifier.fillMaxSize()) {
            for (t in visible) bitmaps[t.tile]?.let { drawImage(it, topLeft = Offset(t.left, t.top)) }
            if (scene.route.size >= 2) {
                val path = Path().apply {
                    moveTo(scene.route[0].x, scene.route[0].y)
                    for (p in scene.route.drop(1)) lineTo(p.x, p.y)
                }
                drawPath(path, W.Mint, style = Stroke(4.dp.toPx(), cap = StrokeCap.Round, join = StrokeJoin.Round))
            }
            scene.start?.let { drawCircle(W.Mint.copy(alpha = 0.6f), 6.dp.toPx(), Offset(it.x, it.y), style = Stroke(2.dp.toPx())) }
            scene.arrow?.let { drawArrow(it, Color.White) }
            scene.scale?.let { s ->
                val y = size.height * 0.80f
                val x0 = size.width / 2 - s.lengthPx / 2
                drawLine(Color.White.copy(alpha = 0.6f), Offset(x0, y), Offset(x0 + s.lengthPx, y), strokeWidth = 2.dp.toPx())
            }
        }
        Column(Modifier.fillMaxSize().padding(top = 26.dp, bottom = 14.dp), horizontalAlignment = Alignment.CenterHorizontally) {
            val caption = listOfNotNull(scene.caption, MapSceneBuilder.NO_TILES_CAPTION.takeIf { scene.viewport != null && !anyTile }).joinToString(" · ")
            if (caption.isNotEmpty()) Text(caption, fontSize = 12.sp, color = W.Amber, textAlign = TextAlign.Center)
            Box(Modifier.weight(1f))
            scene.scale?.let { Text(it.label, fontSize = 10.sp, color = W.Dim) }
            Text(scene.attribution, fontSize = 9.sp, color = W.Dim) // always visible (spec §2.4)
        }
    }
}

private fun DrawScope.drawArrow(a: MapArrow, color: Color) {
    val c = Offset(a.at.x, a.at.y)
    val r = 10.dp.toPx()
    val stroke = Stroke(3.dp.toPx())
    val bearing = a.bearingDeg
    if (bearing == null) {
        if (a.hollow) drawCircle(color, r * 0.7f, c, style = stroke) else drawCircle(color, r * 0.7f, c)
        return
    }
    val path = Path().apply { moveTo(0f, -r); lineTo(r * 0.7f, r * 0.8f); lineTo(0f, r * 0.4f); lineTo(-r * 0.7f, r * 0.8f); close() }
    rotate(bearing, pivot = c) { translate(c.x, c.y) { if (a.hollow) drawPath(path, color, style = stroke) else drawPath(path, color) } }
}
```

- [ ] **Step 7: Glance and Playlist pages** — `watch/src/main/java/com/debasish/livefit/watch/ui/WatchPages.kt`:

```kotlin
package com.debasish.livefit.watch.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.focusable
import androidx.compose.foundation.gestures.scrollBy
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Favorite
import androidx.compose.material.icons.rounded.PlayArrow
import androidx.compose.material.icons.rounded.Timer
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.rotary.onRotaryScrollEvent
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.wear.compose.foundation.lazy.ScalingLazyColumn
import androidx.wear.compose.foundation.lazy.itemsIndexed
import androidx.wear.compose.foundation.lazy.rememberScalingLazyListState
import androidx.wear.compose.material.Icon
import androidx.wear.compose.material.Text
import com.debasish.livefit.model.Command
import com.debasish.livefit.model.QueueWindow
import com.debasish.livefit.model.WorkoutSnapshot
import com.debasish.livefit.model.formatElapsed
import kotlinx.coroutines.launch

/** Glance: timer + heart rate, large (spec §3.1). */
@Composable
internal fun GlancePage(s: WorkoutSnapshot) {
    Column(Modifier.fillMaxSize(), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.Center) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Icon(Icons.Rounded.Timer, contentDescription = "Timer", tint = W.Dim, modifier = Modifier.size(22.dp))
            Text(" ${formatElapsed(s.elapsedMs)}", fontSize = 34.sp, fontWeight = FontWeight.Bold)
        }
        Spacer(Modifier.size(6.dp))
        Row(verticalAlignment = Alignment.CenterVertically) {
            Icon(Icons.Rounded.Favorite, contentDescription = "Heart rate", tint = W.Coral, modifier = Modifier.size(30.dp))
            Text(" ${s.metrics.heartRate ?: "--"}", fontSize = 56.sp, fontWeight = FontWeight.Bold)
        }
    }
}

/**
 * Playlist: the YouTube Music queue window from the phone (spec §6). Tap = play that song (play/pause on the current
 * one, PlayQueueItem otherwise); the bezel scrolls. Empty → "Nothing queued — start music on the phone".
 */
@Composable
internal fun PlaylistPage(queue: QueueWindow, onCommand: (Command) -> Unit) {
    if (queue.items.isEmpty()) {
        Box(Modifier.fillMaxSize().padding(28.dp), contentAlignment = Alignment.Center) {
            Text("Nothing queued — start music on the phone", fontSize = 14.sp, color = W.Dim, textAlign = TextAlign.Center)
        }
        return
    }
    val listState = rememberScalingLazyListState(initialCenterItemIndex = queue.currentIndex ?: 0)
    val focus = remember { FocusRequester() }
    val scope = rememberCoroutineScope()
    LaunchedEffect(Unit) { runCatching { focus.requestFocus() } }
    ScalingLazyColumn(
        Modifier.fillMaxSize()
            .onRotaryScrollEvent { e -> scope.launch { listState.scrollBy(e.verticalScrollPixels) }; true }
            .focusRequester(focus).focusable(),
        state = listState,
    ) {
        itemsIndexed(queue.items) { i, item ->
            val current = i == queue.currentIndex
            Row(
                Modifier.fillMaxWidth().clip(RoundedCornerShape(16.dp)).background(if (current) W.Pill else Color.Transparent)
                    .clickable { onCommand(if (current) Command.PlayPause else Command.PlayQueueItem(item.queueId)) }
                    .padding(horizontal = 12.dp, vertical = 6.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                if (current) { Icon(Icons.Rounded.PlayArrow, contentDescription = "Playing", tint = W.Rose, modifier = Modifier.size(16.dp)); Spacer(Modifier.width(4.dp)) }
                Column {
                    Text(item.title, fontSize = 14.sp, maxLines = 1, fontWeight = if (current) FontWeight.Bold else FontWeight.Normal)
                    if (item.artist.isNotEmpty()) Text(item.artist, fontSize = 11.sp, color = W.Dim, maxLines = 1)
                }
            }
        }
    }
}
```

- [ ] **Step 8: `WatchApp.kt`** — change `private object W` to `internal object W`. Add imports:

```kotlin
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.snapshotFlow
import com.debasish.livefit.model.HudPage
import com.debasish.livefit.watch.WatchPageModel
import com.debasish.livefit.watch.map.WatchTiles
```

Change the `WatchApp` signature to `fun WatchApp(state: WatchUiState, onCommand: (Command) -> Unit, onVolume: (Float) -> Unit, onGrantPermissions: () -> Unit, tiles: WatchTiles, ambient: Boolean = false)` and its live branch to `else -> Live(s, state, onCommand, onVolume, tiles)`.

Replace the whole `Live(...)` composable with:

```kotlin
/**
 * Live pager over the shared page set (spec §3). The pager is rebuilt when the page set changes, opening on the page
 * that was shown or, if it vanished (disabled, Map ineligible), on Workout. A new session starts on Workout.
 */
@Composable
private fun Live(s: WorkoutSnapshot, state: WatchUiState, onCommand: (Command) -> Unit, onVolume: (Float) -> Unit, tiles: WatchTiles) {
    val pages = WatchPageModel.pages(state)
    val shown = remember(s.sessionId) { mutableStateOf(HudPage.Workout) }
    key(pages) {
        val pager = rememberPagerState(initialPage = WatchPageModel.initialIndex(pages, shown.value), pageCount = { pages.size })
        LaunchedEffect(pager) { snapshotFlow { pager.settledPage }.collect { i -> pages.getOrNull(i)?.let { shown.value = it } } }
        Box(Modifier.fillMaxSize()) {
            HorizontalPager(pager, Modifier.fillMaxSize()) { i ->
                when (pages[i]) {
                    HudPage.Glance -> GlancePage(s)
                    HudPage.Workout -> HeartPage(s, state.hrHistory, onCommand)
                    HudPage.Stats -> StatsPage(s)
                    HudPage.Playlist -> PlaylistPage(state.queue, onCommand)
                    HudPage.Map -> WatchMapPage(state.route, state.live, s.sessionId, s.type, tiles)
                    HudPage.MusicControls -> MusicPage(state.music, onCommand, onVolume)
                }
            }
            HorizontalPageIndicator(
                pageIndicatorState = remember(pager) {
                    object : PageIndicatorState {
                        override val pageOffset get() = pager.currentPageOffsetFraction
                        override val selectedPage get() = pager.currentPage
                        override val pageCount get() = pages.size
                    }
                },
                modifier = Modifier.padding(bottom = 6.dp),
            )
        }
    }
}
```

In `StatsPage` replace the four `Spacer(Modifier.height(6.dp))` with `Spacer(Modifier.height(4.dp))` and append after the speed pill:

```kotlin
        Spacer(Modifier.height(4.dp))
        Pill(Icons.Rounded.Favorite, W.Coral, "${s.avgHeartRate ?: "--"}/${s.maxHeartRate ?: "--"}", "avg/max")
```

In `MusicPage`, right after the artist `Text(...)` line, add the progress line (spec §3.1 "progress"):

```kotlin
            if (np != null && np.durationMs > 0) {
                Spacer(Modifier.height(6.dp))
                Box(Modifier.width(90.dp).height(3.dp).clip(RoundedCornerShape(2.dp)).background(W.Pill)) {
                    Box(Modifier.fillMaxHeight().fillMaxWidth((np.positionMs.toFloat() / np.durationMs).coerceIn(0f, 1f)).background(W.Rose))
                }
            }
```

- [ ] **Step 9: `MainActivity.kt`** — pass the tiles: `WatchApp(state, onCommand = WatchClient::command, onVolume = WatchClient::setVolume, onGrantPermissions = { permissionRequest.launch(perms) }, tiles = WatchRuntime.tiles, ambient = tick != null)`.

- [ ] **Step 10: Build and test**

Run: `export JAVA_HOME=$(/usr/libexec/java_home -v 17) && ./gradlew :watch:assembleDebug :watch:testDebugUnitTest`
Expected: BUILD SUCCESSFUL, tests PASS.

- [ ] **Step 11: Commit**

```bash
git add watch
git commit -m "feat(watch): shared page set, live Map with arrival-gated marker and retrying tiles, bezel zoom"
```

---

## Phase 2 — Phone

### Task 13: Route storage in Room

**Files:**
- Modify: `core/services/src/main/kotlin/com/debasish/livefit/services/Services.kt`
- Modify: `services/history/src/main/kotlin/com/debasish/livefit/history/Entities.kt`
- Modify: `services/history/src/main/kotlin/com/debasish/livefit/history/HistoryDao.kt`
- Modify: `services/history/src/main/kotlin/com/debasish/livefit/history/HistoryDatabase.kt`
- Modify: `services/history/src/main/kotlin/com/debasish/livefit/history/RoomSessionStore.kt`
- Test: `services/history/src/test/kotlin/com/debasish/livefit/history/RoomRouteStoreTest.kt`

**Interfaces:**
- Consumes: `RouteFix`, `FixSource`, `LocationFix.toRouteFix`, `FixQuality` (Tasks 1, 3).
- Produces:
  - `interface RouteStore { suspend fun storeRouteFixes(sessionId: String, fixes: List<RouteFix>); suspend fun normalizeWatchTimes(sessionId: String, watchOffsetMs: Long); suspend fun routeFixes(sessionId: String): List<RouteFix> }` in `:core:services` — identity `(sessionId, source, deviceTimeMs)`, insert-or-ignore; nothing is written for a Discarded or Cleared session (tombstone); `normalizeWatchTimes` sets `phoneTimeMs = deviceTimeMs − offset` on every Watch row of the session that is null or mapped with another offset; `routeFixes` is ordered by phone time (watch first on ties) with uncalibrated rows last by device time.
  - `RoomSessionStore : HistoryStore, RouteStore`; **`storeDelta` writes the delta's accurate location fixes as route rows (phoneTimeMs = null) in the same transaction as the delta** — so once a delta is stored (and therefore acked) its route rows exist (review #1). `HistoryDatabase` version 2 with `HistoryDatabase.MIGRATION_1_2`; `discard` and `clearFinished` also delete route rows.
  - Table `route_point(sessionId, source, fixTimeMs /* device clock */, phoneTimeMs /* nullable */, lat, lon, accuracyM, bearingDeg)` PK `(sessionId, source, fixTimeMs)`.

- [ ] **Step 1: Write the failing test** — `services/history/src/test/kotlin/com/debasish/livefit/history/RoomRouteStoreTest.kt`:

```kotlin
package com.debasish.livefit.history

import android.content.Context
import android.database.sqlite.SQLiteDatabase
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.debasish.livefit.model.FixSource
import com.debasish.livefit.model.LocationFix
import com.debasish.livefit.model.Provenance
import com.debasish.livefit.model.RouteFix
import com.debasish.livefit.model.SessionDelta
import com.debasish.livefit.model.SessionStatus
import com.debasish.livefit.model.SessionSummary
import com.debasish.livefit.model.WorkoutType
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class RoomRouteStoreTest {
    private val ctx: Context = ApplicationProvider.getApplicationContext()
    private fun store() = RoomSessionStore(HistoryDatabase.create(ctx, inMemory = true))
    private fun row(src: FixSource, device: Long, phone: Long?, northM: Double = 0.0) =
        RouteFix(src, 12.9716 + northM / 111_195.0, 77.5946, 5f, deviceTimeMs = device, phoneTimeMs = phone, bearingDeg = 90f)

    @Test fun returnsRowsInPhoneTimeOrderWithUncalibratedRowsLast() = runTest {
        val s = store()
        s.storeRouteFixes("s", listOf(row(FixSource.Watch, 10_000, 5_000), row(FixSource.Phone, 1_000, 1_000), row(FixSource.Watch, 2_000, -3_000), row(FixSource.Watch, 900, null)))
        val back = s.routeFixes("s")
        assertEquals(listOf(-3_000L, 1_000L, 5_000L, null), back.map { it.phoneTimeMs })
        assertEquals(listOf(2_000L, 1_000L, 10_000L, 900L), back.map { it.deviceTimeMs })
        assertEquals(90f, back.first().bearingDeg!!, 0f)
    }

    /** Spec §2.2: unique key (sessionId, source, device fix time) — a replay re-mapped with a newer offset is not a second row. */
    @Test fun deviceTimeIdentityMakesReplaysIdempotent() = runTest {
        val s = store()
        s.storeRouteFixes("s", listOf(row(FixSource.Watch, 10_000, null)))
        s.storeRouteFixes("s", listOf(row(FixSource.Watch, 10_000, 5_300)))
        assertEquals(1, s.routeFixes("s").size)
        assertNull("insert never overwrites; normalizeWatchTimes does", s.routeFixes("s").single().phoneTimeMs)
    }

    /** Review #2: calibration rewrites the phone time of every watch row (null or older offset) without touching identity. */
    @Test fun normalizeRewritesWatchPhoneTimesOnly() = runTest {
        val s = store()
        s.storeRouteFixes("s", listOf(row(FixSource.Watch, 10_000, null), row(FixSource.Watch, 11_000, 9_000), row(FixSource.Phone, 7_000, 7_000)))
        s.storeRouteFixes("t", listOf(row(FixSource.Watch, 10_000, null)))
        s.normalizeWatchTimes("s", watchOffsetMs = 3_000)
        val back = s.routeFixes("s")
        assertEquals(listOf(10_000L to 7_000L, 7_000L to 7_000L, 11_000L to 8_000L), back.map { it.deviceTimeMs to it.phoneTimeMs })
        assertEquals(listOf(FixSource.Watch, FixSource.Phone, FixSource.Watch), back.map { it.source }, "watch first on the 7 s tie; the phone row is untouched")
        assertNull("another session is untouched", s.routeFixes("t").single().phoneTimeMs)
    }

    @Test fun sameTimeDifferentSourceKeepsBothWatchFirst() = runTest {
        val s = store()
        s.storeRouteFixes("s", listOf(row(FixSource.Phone, 1_000, 1_000), row(FixSource.Watch, 1_000, 1_000)))
        assertEquals(listOf(FixSource.Watch, FixSource.Phone), s.routeFixes("s").map { it.source })
    }

    /** Review #1: a stored (so acked) delta already has its route rows — nothing can be lost between ack and route insert. */
    @Test fun storingADeltaStoresItsRouteRowsInTheSameTransaction() = runTest {
        val s = store()
        val fixes = listOf(
            LocationFix(12.9716, 77.5946, 4f, 10f, 5_000),
            LocationFix(12.9717, 77.5946, 50f, null, 6_000),   // inaccurate: no row
            LocationFix(12.9718, 77.5946, null, null, 7_000),  // unknown accuracy: no row
            LocationFix(12.9719, 77.5946, 6f, null, 8_000),
        )
        s.storeDelta(SessionDelta(sessionId = "s", seq = 0, locations = fixes, provenance = Provenance.Fake))
        val back = s.routeFixes("s")
        assertEquals(listOf(5_000L, 8_000L), back.map { it.deviceTimeMs })
        assertTrue(back.all { it.source == FixSource.Watch && it.phoneTimeMs == null })
        s.storeDelta(SessionDelta(sessionId = "s", seq = 0, locations = fixes, provenance = Provenance.Fake)) // resend
        assertEquals(2, s.routeFixes("s").size)
    }

    /** A Discarded session's tombstone also blocks late route rows (from RouteHub or from a late delta). */
    @Test fun discardDeletesTheRouteAndTheTombstoneBlocksLateRows() = runTest {
        val s = store()
        s.storeDelta(SessionDelta(sessionId = "s", seq = 0, locations = listOf(LocationFix(1.0, 2.0, 3f, null, 4)), provenance = Provenance.Fake))
        s.discard("s")
        assertTrue(s.routeFixes("s").isEmpty())
        s.storeRouteFixes("s", listOf(row(FixSource.Watch, 1, 1)))
        s.storeDelta(SessionDelta(sessionId = "s", seq = 1, locations = listOf(LocationFix(1.0, 2.0, 3f, null, 5)), provenance = Provenance.Fake))
        assertTrue(s.routeFixes("s").isEmpty())
    }

    @Test fun clearHistoryDeletesFinishedRoutesOnlyAndKeepsThemDeleted() = runTest {
        val s = store()
        s.storeDelta(SessionDelta(sessionId = "done", seq = 0, provenance = Provenance.Fake))
        s.finalize(SessionSummary(id = "done", type = WorkoutType.Run, startMs = 0, activeMs = 1, provenance = Provenance.Fake, status = SessionStatus.Complete))
        s.storeRouteFixes("done", listOf(row(FixSource.Watch, 1, 1)))
        s.storeRouteFixes("open", listOf(row(FixSource.Watch, 1, 1)))
        s.clearFinished()
        assertTrue(s.routeFixes("done").isEmpty())
        assertEquals(1, s.routeFixes("open").size)
        s.storeRouteFixes("done", listOf(row(FixSource.Watch, 2, 2))) // a late replay of a cleared session
        assertTrue(s.routeFixes("done").isEmpty())
    }

    /** The owner's phone already has a v1 history database: the upgrade keeps it and adds route_point. */
    @Test fun upgradeFromVersion1KeepsHistoryAndAddsRoutes() = runTest {
        val file = ctx.getDatabasePath("upgrade-test.db").also { it.parentFile?.mkdirs(); it.delete() }
        SQLiteDatabase.openOrCreateDatabase(file, null).use { db ->
            listOf(
                "CREATE TABLE IF NOT EXISTS `session` (`id` TEXT NOT NULL, `summaryJson` TEXT, `status` TEXT NOT NULL, `startMs` INTEGER NOT NULL, `createdAtMs` INTEGER NOT NULL, `endedAtMs` INTEGER, `endReason` TEXT, PRIMARY KEY(`id`))",
                "CREATE TABLE IF NOT EXISTS `delta` (`sessionId` TEXT NOT NULL, `seq` INTEGER NOT NULL, `json` TEXT NOT NULL, PRIMARY KEY(`sessionId`, `seq`))",
                "CREATE TABLE IF NOT EXISTS `sample` (`sessionId` TEXT NOT NULL, `tMs` INTEGER NOT NULL, `hr` INTEGER, `steps` INTEGER NOT NULL, `distanceKm` REAL NOT NULL, `kcal` REAL NOT NULL, `speedKmh` REAL, `provenance` TEXT NOT NULL, PRIMARY KEY(`sessionId`, `tMs`))",
                "CREATE INDEX IF NOT EXISTS `index_sample_sessionId` ON `sample` (`sessionId`)",
                "INSERT INTO session VALUES ('old', NULL, 'Active', 0, 0, NULL, NULL)",
                "INSERT INTO delta VALUES ('old', 0, '{}')",
            ).forEach(db::execSQL)
            db.version = 1
        }
        val room = Room.databaseBuilder(ctx, HistoryDatabase::class.java, file.absolutePath)
            .addMigrations(HistoryDatabase.MIGRATION_1_2).allowMainThreadQueries().build()
        val s = RoomSessionStore(room)
        assertEquals(listOf("old"), s.openSessionIds())
        s.storeRouteFixes("old", listOf(row(FixSource.Watch, 1, null)))
        assertNull(s.routeFixes("old").single().phoneTimeMs)
        room.close()
    }
}
```

- [ ] **Step 2: Run test to verify it fails**

Run: `export JAVA_HOME=$(/usr/libexec/java_home -v 17) && ./gradlew :services:history:testDebugUnitTest --tests '*RoomRouteStoreTest*'`
Expected: FAIL — `storeRouteFixes`, `routeFixes`, `normalizeWatchTimes`, `MIGRATION_1_2` unresolved.

- [ ] **Step 3: `Services.kt`** — add after `interface HistoryStore`:

```kotlin
/**
 * Durable route rows per session (spec §2.2, route_point). Identity (sessionId, source, deviceTimeMs) never changes;
 * phoneTimeMs is null until the watch clock is calibrated and is rewritten by [normalizeWatchTimes] (review #2).
 */
interface RouteStore {
    /** Insert-or-ignore by identity; nothing is written for a Discarded (or Cleared) session — its tombstone wins. */
    suspend fun storeRouteFixes(sessionId: String, fixes: List<com.debasish.livefit.model.RouteFix>)
    /** phoneTimeMs = deviceTimeMs − [watchOffsetMs] for every Watch row of [sessionId] that is null or mapped with another offset. */
    suspend fun normalizeWatchTimes(sessionId: String, watchOffsetMs: Long)
    /** Phone-time order, watch first on ties; rows without a phone time last, by device time. */
    suspend fun routeFixes(sessionId: String): List<com.debasish.livefit.model.RouteFix>
}
```

- [ ] **Step 4: `Entities.kt`** — append:

```kotlin
/**
 * One route row (spec §2.2). [fixTimeMs] is the measuring device's own clock — the replay-safe identity, never changed;
 * [phoneTimeMs] is the calibrated phone time used for ordering, null until the watch clock is calibrated.
 */
@Entity(tableName = "route_point", primaryKeys = ["sessionId", "source", "fixTimeMs"], indices = [Index("sessionId")])
data class RoutePointEntity(
    val sessionId: String,
    val source: String,
    val fixTimeMs: Long,
    val phoneTimeMs: Long?,
    val lat: Double,
    val lon: Double,
    val accuracyM: Float,
    val bearingDeg: Float?,
)
```

- [ ] **Step 5: `HistoryDao.kt`** — add the queries:

```kotlin
    @Insert(onConflict = OnConflictStrategy.IGNORE) suspend fun insertRoutePoints(p: List<RoutePointEntity>)
    /** Phone-time order; "Watch" sorts after "Phone", so DESC puts the watch first on equal times (spec §2.2); NULL phone times last. */
    @Query("SELECT * FROM route_point WHERE sessionId = :id ORDER BY phoneTimeMs IS NULL, phoneTimeMs, source DESC, fixTimeMs") suspend fun routePoints(id: String): List<RoutePointEntity>
    /** Review #2: only rows that are null or mapped with another offset are written. */
    @Query("UPDATE route_point SET phoneTimeMs = fixTimeMs - :offsetMs WHERE sessionId = :id AND source = 'Watch' AND (phoneTimeMs IS NULL OR phoneTimeMs != fixTimeMs - :offsetMs)")
    suspend fun normalizeWatchTimes(id: String, offsetMs: Long)
    @Query("DELETE FROM route_point WHERE sessionId = :id") suspend fun deleteRoute(id: String)
    @Query("DELETE FROM route_point WHERE sessionId IN (SELECT id FROM session WHERE summaryJson IS NOT NULL)") suspend fun clearFinishedRoutes()

    /** Route rows are never written for a Discarded or Cleared session: its tombstone wins, even after a restart. */
    @Transaction
    suspend fun storeRoutePoints(id: String, rows: List<RoutePointEntity>) {
        if (rows.isEmpty()) return
        val status = session(id)?.status
        if (status == DISCARDED || status == CLEARED) return
        insertRoutePoints(rows)
    }
```

Replace `storeDelta(...)` with (route rows in the same transaction as the delta, review #1; `RoomSessionStore` is its only caller):

```kotlin
    @Transaction
    suspend fun storeDelta(session: SessionEntity, delta: DeltaEntity, samples: List<SampleEntity>, routes: List<RoutePointEntity>): List<Long> {
        insertSession(session)
        insertDelta(delta)
        insertSamples(samples)
        storeRoutePoints(delta.sessionId, routes)
        return seqs(delta.sessionId)
    }
```

In `discard(id, now)` change the first line to `deleteDeltas(id); deleteSamples(id); deleteRoute(id)`, and replace `clearFinished()` with:

```kotlin
    @Transaction
    suspend fun clearFinished() { clearFinishedDeltas(); clearFinishedSamples(); clearFinishedRoutes(); hideFinishedSessions() }
```

- [ ] **Step 6: `HistoryDatabase.kt`** — replace with:

```kotlin
package com.debasish.livefit.history

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase

@Database(entities = [SessionEntity::class, DeltaEntity::class, SampleEntity::class, RoutePointEntity::class], version = 2, exportSchema = false)
abstract class HistoryDatabase : RoomDatabase() {
    abstract fun dao(): HistoryDao

    companion object {
        /** v2: route_point (spec §2.2), phoneTimeMs nullable (review #2). Existing history is kept. */
        val MIGRATION_1_2 = object : Migration(1, 2) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL(
                    "CREATE TABLE IF NOT EXISTS `route_point` (`sessionId` TEXT NOT NULL, `source` TEXT NOT NULL, `fixTimeMs` INTEGER NOT NULL, " +
                        "`phoneTimeMs` INTEGER, `lat` REAL NOT NULL, `lon` REAL NOT NULL, `accuracyM` REAL NOT NULL, `bearingDeg` REAL, " +
                        "PRIMARY KEY(`sessionId`, `source`, `fixTimeMs`))",
                )
                db.execSQL("CREATE INDEX IF NOT EXISTS `index_route_point_sessionId` ON `route_point` (`sessionId`)")
            }
        }

        fun create(context: Context, inMemory: Boolean = false): HistoryDatabase =
            (if (inMemory) Room.inMemoryDatabaseBuilder(context, HistoryDatabase::class.java).allowMainThreadQueries()
            else Room.databaseBuilder(context, HistoryDatabase::class.java, "livefit-history.db"))
                .addMigrations(MIGRATION_1_2).build()

        @Volatile private var instance: HistoryDatabase? = null

        /** One Room instance per process (two instances on one file break change notifications). */
        fun shared(context: Context): HistoryDatabase =
            instance ?: synchronized(this) { instance ?: create(context.applicationContext).also { instance = it } }
    }
}
```

- [ ] **Step 7: `RoomSessionStore.kt`** — add imports `com.debasish.livefit.model.FixSource`, `com.debasish.livefit.model.RouteFix`, `com.debasish.livefit.model.toRouteFix`, `com.debasish.livefit.services.RouteStore`; change the class header to `class RoomSessionStore(db: HistoryDatabase, private val now: () -> Long = System::currentTimeMillis) : HistoryStore, RouteStore {`. In `storeDelta` add the argument after `samples = …,`:

```kotlin
            // Review #1: the delta's accurate fixes become route rows in the same transaction (uncalibrated: phoneTimeMs = null).
            routes = delta.locations.mapNotNull { it.toRouteFix(FixSource.Watch, phoneTimeMs = null) }.map { entity(delta.sessionId, it) },
```

and add:

```kotlin
    override suspend fun storeRouteFixes(sessionId: String, fixes: List<RouteFix>) = dao.storeRoutePoints(sessionId, fixes.map { entity(sessionId, it) })

    override suspend fun normalizeWatchTimes(sessionId: String, watchOffsetMs: Long) = dao.normalizeWatchTimes(sessionId, watchOffsetMs)

    override suspend fun routeFixes(sessionId: String): List<RouteFix> = dao.routePoints(sessionId).map {
        RouteFix(FixSource.valueOf(it.source), it.lat, it.lon, it.accuracyM, deviceTimeMs = it.fixTimeMs, phoneTimeMs = it.phoneTimeMs, bearingDeg = it.bearingDeg)
    }

    private fun entity(sessionId: String, f: RouteFix) =
        RoutePointEntity(sessionId, f.source.name, f.deviceTimeMs, f.phoneTimeMs, f.lat, f.lon, f.accuracyM, f.bearingDeg)
```

- [ ] **Step 8: Run tests to verify they pass**

Run: `export JAVA_HOME=$(/usr/libexec/java_home -v 17) && ./gradlew :services:history:testDebugUnitTest`
Expected: PASS (new 8 tests and the existing RoomSessionStore tests).

- [ ] **Step 9: Commit**

```bash
git add core/services services/history
git commit -m "feat(history): route_point with device-time identity, nullable phone time, tombstones and rows stored with their delta"
```

---

### Task 14: RouteHub — merge, live source, fallback decision, calibration normalization and durable route rows

**Files:**
- Create: `services/sync/src/main/kotlin/com/debasish/livefit/sync/RouteHub.kt`
- Test: `services/sync/src/test/kotlin/com/debasish/livefit/sync/RouteHubTest.kt`

**Interfaces:**
- Consumes: `RouteTrack`, `RoutePoint`, `RouteFix`, `LocationFix.toRouteFix`, `RouteState`, `GpsStatus` (Task 3); `WatchClockSync`, `LiveLocationSelector` (Task 4); `PageSet.mapEligible` (Task 5); `RouteStore` (Task 13); `SessionStore.deltas` (existing `:core:services`); `InMemorySessionStore` (existing `:services:workout`, tests); `SessionDelta.locations` (Task 1).
- Produces: `class RouteHub(scope, clock, clockSync: WatchClockSync, store: RouteStore, sessions: SessionStore, tickMs: Long = 1_000, log: (String) -> Unit = {}) { val state: StateFlow<RouteState>; val fallbackWanted: StateFlow<Boolean>; fun start(): Job; suspend fun onWorkout(snapshot: WorkoutSnapshot); suspend fun onWatchDelta(d: SessionDelta); suspend fun onPhoneFix(f: LocationFix) }`

Design (review #1/#2, one coherent rule set):
- **Identity vs order.** Every accurate fix is a `RouteFix` keyed by `(source, deviceTimeMs)`. Watch rows get `phoneTimeMs = deviceTimeMs − offset` once calibrated, `null` before. Only rows with a phone time take part in ordering, the ±5 s overlap rule and the 2-min future rejection; uncalibrated rows are stored but not drawn yet (plan ruling), and never live.
- **Calibration.** Whenever `clockSync.offsetMs` differs from the offset the in-memory rows were mapped with, every watch row of the current session (and every unsaved row) is re-mapped, the `RouteTrack` is rebuilt chronologically from scratch (so the duplicate filter can never block a repair), and `normalizeWatchTimes` is queued for every session that received watch rows. The selector is *not* replayed: re-mapped old fixes never become live.
- **Durability.** `RoomSessionStore.storeDelta` already writes a delta's rows before the ack (Task 13). RouteHub additionally (a) writes its own rows (phone fixes, watch rows with phone times, another session's replay) through an unsaved queue that is retried on every tick and every new fix until the store accepts it, and (b) on loading a session (every phone restart) rebuilds rows missing from `route_point` out of the session's stored deltas. The store's tombstone check drops rows for Discarded/Cleared sessions, so retries never resurrect them.

- [ ] **Step 1: Write the failing test** — `services/sync/src/test/kotlin/com/debasish/livefit/sync/RouteHubTest.kt`:

```kotlin
package com.debasish.livefit.sync

import com.debasish.livefit.model.FixSource
import com.debasish.livefit.model.GpsStatus
import com.debasish.livefit.model.LocationFix
import com.debasish.livefit.model.Provenance
import com.debasish.livefit.model.RouteFix
import com.debasish.livefit.model.SessionDelta
import com.debasish.livefit.model.WorkoutPhase
import com.debasish.livefit.model.WorkoutSnapshot
import com.debasish.livefit.model.WorkoutType
import com.debasish.livefit.services.Clock
import com.debasish.livefit.services.RouteStore
import com.debasish.livefit.services.workout.InMemorySessionStore
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

@OptIn(ExperimentalCoroutinesApi::class)
class RouteHubTest {
    /** route_point in memory, with the same identity, ordering, normalization and tombstone rules as Room (Task 13). */
    private class MemoryRoutes : RouteStore {
        val rows = LinkedHashMap<Triple<String, FixSource, Long>, RouteFix>()
        val discarded = mutableSetOf<String>()
        var fail = false
        var failNextWrites = 0
        override suspend fun storeRouteFixes(sessionId: String, fixes: List<RouteFix>) {
            if (fail) error("disk full")
            if (failNextWrites > 0) { failNextWrites--; error("disk busy") }
            if (sessionId in discarded) return
            for (f in fixes) rows.putIfAbsent(Triple(sessionId, f.source, f.deviceTimeMs), f)
        }
        override suspend fun normalizeWatchTimes(sessionId: String, watchOffsetMs: Long) {
            if (fail) error("disk full")
            rows.replaceAll { k, f -> if (k.first == sessionId && f.source == FixSource.Watch) f.copy(phoneTimeMs = f.deviceTimeMs - watchOffsetMs) else f }
        }
        override suspend fun routeFixes(sessionId: String): List<RouteFix> = rows.filterKeys { it.first == sessionId }.values
            .sortedWith(compareBy<RouteFix>({ it.phoneTimeMs == null }, { it.phoneTimeMs }, { it.source != FixSource.Watch }, { it.deviceTimeMs }))
    }

    private class Rig(scope: TestScope, val sessions: InMemorySessionStore = InMemorySessionStore(), val store: MemoryRoutes = MemoryRoutes()) {
        val clock = Clock { scope.testScheduler.currentTime }
        val sync = WatchClockSync(clock)
        val hub = RouteHub(scope.backgroundScope, clock, sync, store, sessions)
    }

    private fun fix(t: Long, northM: Double, acc: Float? = 5f) = LocationFix(12.9716 + northM / 111_195.0, 77.5946, acc, null, t)
    private fun delta(fixes: List<LocationFix>, session: String = "s", seq: Long = 1) =
        SessionDelta(sessionId = session, seq = seq, locations = fixes, provenance = Provenance.Fake)
    private val running = WorkoutSnapshot(phase = WorkoutPhase.Active, type = WorkoutType.Run, sessionId = "s", gps = true)

    @Test fun watchFixesBuildTheRouteInPhoneTime() = runTest {
        val r = Rig(this)
        r.sync.calibrate { it + 5_000 } // watch clock 5 s ahead
        advanceTimeBy(10_000)
        r.hub.onWorkout(running)
        r.hub.onWatchDelta(delta(listOf(fix(14_000, 0.0), fix(15_000, 10.0))))
        val s = r.hub.state.value
        assertEquals(listOf(9_000L, 10_000L), s.route.map { it.fixTimeMs })
        assertEquals(listOf(14_000L, 15_000L), s.route.map { it.deviceTimeMs })
        assertEquals(GpsStatus.Live, s.status)
        assertEquals(FixSource.Watch, s.live?.source)
        assertEquals(0f, s.live?.bearingDeg ?: -1f, 0.5f, "bearing from the last two points")
        assertEquals(listOf(9_000L, 10_000L), r.store.routeFixes("s").map { it.phoneTimeMs })
    }

    @Test fun uncalibratedWatchFixesAreStoredButNeitherDrawnNorLive() = runTest {
        val r = Rig(this)
        advanceTimeBy(1_000)
        r.hub.onWorkout(running)
        r.hub.onWatchDelta(delta(listOf(fix(1_000, 0.0), fix(2_000, 10.0))))
        assertTrue(r.hub.state.value.route.isEmpty(), "no phone time yet: not ordered, not drawn")
        assertEquals(listOf(null, null), r.store.routeFixes("s").map { it.phoneTimeMs }, "durable by device time")
        assertNull(r.hub.state.value.live)
        assertEquals(GpsStatus.Waiting, r.hub.state.value.status)
        advanceTimeBy(15_000)
        r.hub.onWatchDelta(delta(listOf(fix(16_000, 20.0)), seq = 2))
        assertTrue(r.hub.fallbackWanted.value, "uncalibrated fixes can't stop the phone fallback")
    }

    /**
     * Review #2: the watch replays before the first sync, the phone then calibrates (small and > 2 min offsets),
     * the replay continues — nothing is lost or rejected as "future", the order is corrected (phone fallback points
     * under the replay get hidden), every stored row gets its phone time, and no marker appears until a live fix.
     */
    @Test fun replayBeforeSyncThenCalibrationSmallOffset() = runTest { replayAcrossCalibration(offsetMs = 1_000) }

    @Test fun replayBeforeSyncThenCalibrationMoreThanTwoMinutesAhead() = runTest { replayAcrossCalibration(offsetMs = 180_000) }

    private suspend fun TestScope.replayAcrossCalibration(offsetMs: Long) {
        val r = Rig(this)
        advanceTimeBy(600_000)
        r.hub.onWorkout(running)
        fun watchAt(phoneT: Long, n: Int) = fix(phoneT + offsetMs, 100.0 + n * 5) // measured at phone time phoneT
        r.hub.onPhoneFix(fix(530_000, 252.0)) // a fallback point recorded while the watch was away
        r.hub.onWatchDelta(delta((0 until 60).map { watchAt(500_000L + it * 1_000, it) }))
        assertEquals(listOf(FixSource.Phone), r.hub.state.value.route.map { it.source }, "uncalibrated replay not drawn yet")
        assertNull(r.hub.state.value.live)

        r.sync.calibrate { it + offsetMs }
        r.hub.onWatchDelta(delta((60 until 90).map { watchAt(500_000L + it * 1_000, it) }, seq = 2))
        val s = r.hub.state.value
        assertEquals(90, s.route.size, "no fix lost, none rejected as future")
        assertTrue(s.route.all { it.source == FixSource.Watch }, "the phone point under the replay is now hidden")
        assertEquals((0 until 90).map { 500_000L + it * 1_000 }, s.route.map { it.fixTimeMs }, "corrected chronological order")
        assertNull(s.live, "replayed fixes never move the marker")
        assertNotEquals(GpsStatus.Live, s.status)
        val stored = r.store.routeFixes("s")
        assertEquals(91, stored.size, "phone row kept for diagnostics")
        assertTrue(stored.filter { it.source == FixSource.Watch }.all { it.phoneTimeMs == it.deviceTimeMs - offsetMs }, "normalized")

        r.hub.onWatchDelta(delta(listOf(watchAt(600_000, 200)), seq = 3)) // first fix that is live on arrival
        assertEquals(FixSource.Watch, r.hub.state.value.live?.source)
        assertEquals(600_000L, r.hub.state.value.live?.fixTimeMs)
    }

    /** Review Focus #1: phone restart; the watch's minutes-old replay arrives before, and right after, the first time-sync. */
    @Test fun delayedFirstReplayAfterPhoneRestartDoesNotLookLive() = runTest {
        val r = Rig(this)
        r.store.storeRouteFixes("s", listOf(RouteFix(FixSource.Watch, 12.9716, 77.5946, 5f, 1_000, 1_000))) // before the restart
        advanceTimeBy(600_000)
        r.hub.onWorkout(running)
        assertEquals(1, r.hub.state.value.route.size, "stored route reloaded")
        r.hub.onWatchDelta(delta((0 until 60).map { fix(300_000L + it * 1_000, 50.0 + it * 5) }))
        assertNull(r.hub.state.value.live)
        r.sync.calibrate { it }
        r.hub.onWatchDelta(delta((60 until 120).map { fix(300_000L + it * 1_000, 50.0 + it * 5) }, seq = 2))
        val s = r.hub.state.value
        assertNotEquals(GpsStatus.Live, s.status)
        assertNull(s.live)
        assertEquals(121, s.route.size)
        assertEquals(s.route.map { it.fixTimeMs }.sorted(), s.route.map { it.fixTimeMs }, "drawn in time order")
        advanceTimeBy(15_000)
        r.hub.onWorkout(running)
        assertTrue(r.hub.fallbackWanted.value, "replay never stopped the fallback")
    }

    /** Review #1: the phone died after a delta was stored and acked but before RouteHub wrote any row; a restart rebuilds them. */
    @Test fun routeRowsMissingAfterAnAckAreRebuiltFromStoredDeltas() = runTest {
        val sessions = InMemorySessionStore()
        val fixes = (0 until 30).map { fix(1_000L + it * 1_000, it * 5.0) }
        sessions.storeDelta(delta(fixes.take(15), seq = 0))
        sessions.storeDelta(delta(fixes.drop(15), seq = 1))
        val r = Rig(this, sessions) // a fresh phone process: empty route_point
        r.sync.calibrate { it }
        advanceTimeBy(60_000)
        r.hub.onWorkout(running)
        assertEquals(30, r.hub.state.value.route.size, "full route after the restart")
        val stored = r.store.routeFixes("s")
        assertEquals(30, stored.size, "missing rows written back")
        assertTrue(stored.all { it.phoneTimeMs == it.deviceTimeMs })
        assertNull(r.hub.state.value.live, "rebuilt fixes are history, not live")
    }

    /** Review #1: a route write fails once; it is retried by the ticker (no new fix needed) and a replay stays idempotent. */
    @Test fun aFailedRouteWriteIsRetriedAndReplayIsIdempotent() = runTest {
        val r = Rig(this)
        r.sync.calibrate { it }
        r.hub.start()
        r.hub.onWorkout(running)
        r.store.failNextWrites = 1
        val d = delta(listOf(fix(0, 0.0), fix(1_000, 10.0)))
        r.hub.onWatchDelta(d)
        assertTrue(r.store.routeFixes("s").isEmpty(), "first write failed")
        assertEquals(2, r.hub.state.value.route.size, "tracking unaffected")
        advanceTimeBy(1_100); runCurrent()
        assertEquals(2, r.store.routeFixes("s").size, "retried by the ticker")
        r.hub.onWatchDelta(d) // the watch resends after a lost ack
        assertEquals(2, r.store.routeFixes("s").size)
        assertEquals(2, r.hub.state.value.route.size)
    }

    @Test fun aFailedWriteIsAlsoRecoveredByTheNextReplay() = runTest {
        val r = Rig(this)
        r.sync.calibrate { it }
        r.hub.onWorkout(running)
        r.store.failNextWrites = 1
        val d = delta(listOf(fix(0, 0.0), fix(1_000, 10.0)))
        r.hub.onWatchDelta(d)
        r.hub.onWatchDelta(d)
        assertEquals(listOf(0L, 1_000L), r.store.routeFixes("s").map { it.deviceTimeMs })
    }

    @Test fun phoneFallbackFixesDriveTheMarkerWhileTheWatchIsSilent() = runTest {
        val r = Rig(this)
        r.sync.calibrate { it }
        r.hub.onWorkout(running)
        advanceTimeBy(16_000)
        r.hub.onPhoneFix(fix(16_000, 0.0))
        val s = r.hub.state.value
        assertTrue(r.hub.fallbackWanted.value)
        assertEquals(FixSource.Phone, s.live?.source)
        assertEquals(GpsStatus.Live, s.status)
        assertEquals(listOf(FixSource.Phone), s.route.map { it.source })
    }

    /** Review #8: fixes without accuracy are neither stored, drawn nor live (phone or watch). */
    @Test fun unknownAccuracyIsIgnoredEverywhere() = runTest {
        val r = Rig(this)
        r.sync.calibrate { it }
        r.hub.onWorkout(running)
        advanceTimeBy(16_000)
        r.hub.onPhoneFix(fix(16_000, 0.0, acc = null))
        r.hub.onWatchDelta(delta(listOf(fix(16_000, 5.0, acc = null))))
        assertTrue(r.hub.state.value.route.isEmpty())
        assertNull(r.hub.state.value.live)
        assertTrue(r.store.routeFixes("s").isEmpty())
        assertTrue(r.hub.fallbackWanted.value)
    }

    @Test fun anotherSessionsReplayIsStoredNotDrawn() = runTest {
        val r = Rig(this)
        r.hub.onWorkout(running)
        r.hub.onWatchDelta(delta(listOf(fix(1_000, 0.0), fix(2_000, 9.0, acc = 50f)), session = "old"))
        assertTrue(r.hub.state.value.route.isEmpty())
        assertEquals(1, r.store.routeFixes("old").size, "inaccurate fix not stored")
    }

    /** A discarded session's tombstone: its late replay is dropped by the store, and RouteHub does not keep retrying it. */
    @Test fun discardedSessionsReplayIsNeverStored() = runTest {
        val r = Rig(this)
        r.store.discarded += "gone"
        r.hub.start()
        r.hub.onWorkout(running)
        r.hub.onWatchDelta(delta(listOf(fix(1_000, 0.0)), session = "gone"))
        advanceTimeBy(5_000); runCurrent()
        assertTrue(r.store.routeFixes("gone").isEmpty())
    }

    @Test fun storageFailureDoesNotBreakTracking() = runTest {
        val r = Rig(this)
        r.sync.calibrate { it }
        r.store.fail = true
        r.hub.onWorkout(running)
        r.hub.onWatchDelta(delta(listOf(fix(0, 0.0), fix(1_000, 10.0))))
        assertEquals(2, r.hub.state.value.route.size)
    }

    @Test fun phoneFixesIgnoredOutsideAGpsWorkout() = runTest {
        val r = Rig(this)
        r.hub.onWorkout(running.copy(gps = false))
        advanceTimeBy(20_000)
        r.hub.onPhoneFix(fix(20_000, 0.0))
        assertTrue(r.hub.state.value.route.isEmpty())
        assertFalse(r.hub.fallbackWanted.value)
    }

    @Test fun idleClearsTheRoute() = runTest {
        val r = Rig(this)
        r.sync.calibrate { it }
        r.hub.onWorkout(running)
        r.hub.onWatchDelta(delta(listOf(fix(0, 0.0))))
        assertEquals(1, r.hub.state.value.route.size)
        r.hub.onWorkout(WorkoutSnapshot())
        assertNull(r.hub.state.value.sessionId)
        assertTrue(r.hub.state.value.route.isEmpty())
    }

    @Test fun theTickerAgesTheStatus() = runTest {
        val r = Rig(this)
        r.sync.calibrate { it }
        r.hub.start()
        r.hub.onWorkout(running)
        r.hub.onWatchDelta(delta(listOf(fix(0, 0.0))))
        advanceTimeBy(15_500); runCurrent()
        assertEquals(GpsStatus.Delayed, r.hub.state.value.status)
        advanceTimeBy(16_000); runCurrent()
        assertEquals(GpsStatus.Lost, r.hub.state.value.status)
    }
}
```

- [ ] **Step 2: Run test to verify it fails**

Run: `export JAVA_HOME=$(/usr/libexec/java_home -v 17) && ./gradlew :services:sync:test --tests '*RouteHubTest*'`
Expected: FAIL — `RouteHub` unresolved.

- [ ] **Step 3: Implement `RouteHub.kt`**

```kotlin
package com.debasish.livefit.sync

import com.debasish.livefit.model.FixSource
import com.debasish.livefit.model.LocationFix
import com.debasish.livefit.model.PageSet
import com.debasish.livefit.model.RouteFix
import com.debasish.livefit.model.RouteState
import com.debasish.livefit.model.RouteTrack
import com.debasish.livefit.model.SessionDelta
import com.debasish.livefit.model.WorkoutPhase
import com.debasish.livefit.model.WorkoutSnapshot
import com.debasish.livefit.model.WorkoutType
import com.debasish.livefit.model.toRouteFix
import com.debasish.livefit.services.Clock
import com.debasish.livefit.services.RouteStore
import com.debasish.livefit.services.SessionStore
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

private typealias FixKey = Pair<FixSource, Long>

private val RouteFix.key: FixKey get() = source to deviceTimeMs

/**
 * Phone hub `LocationSource` (spec §2.1/§2.2): merges the current session's watch fixes and phone fallback fixes into one
 * [RouteTrack], decides whether the phone GPS should run, and publishes what the glasses map draws.
 * - Rows are identified by device time; watch rows get a phone time only once calibrated, and every calibration change
 *   re-maps all of them and rebuilds the track (review #2). Uncalibrated rows are durable but not drawn, never live.
 * - Writes go through an unsaved queue retried on every tick and new fix; loading a session rebuilds rows missing from
 *   the store out of its stored deltas (review #1). The store's tombstones drop rows of discarded sessions.
 */
class RouteHub(
    private val scope: CoroutineScope,
    private val clock: Clock,
    private val clockSync: WatchClockSync,
    private val store: RouteStore,
    private val sessions: SessionStore,
    private val tickMs: Long = 1_000,
    private val log: (String) -> Unit = {},
) {
    private val _state = MutableStateFlow(RouteState())
    val state: StateFlow<RouteState> = _state
    private val _fallbackWanted = MutableStateFlow(false)
    val fallbackWanted: StateFlow<Boolean> = _fallbackWanted

    private val mutex = Mutex()
    private var sessionId: String? = null
    private var type = WorkoutType.Walk
    private var gpsActive = false
    /** Every accurate fix of the current session, by identity — the in-memory copy of its route_point rows. */
    private val fixes = LinkedHashMap<FixKey, RouteFix>()
    /** The watch offset [fixes] are mapped with; null = not mapped by this process yet. */
    private var appliedOffset: Long? = null
    private var track = RouteTrack()
    private val selector = LiveLocationSelector()
    /** Rows the store has not accepted yet, per session, oldest session first. */
    private val unsaved = LinkedHashMap<String, LinkedHashMap<FixKey, RouteFix>>()
    /** Sessions with watch rows that may lack the current offset's phone time (normalized after the inserts). */
    private val unnormalized = LinkedHashSet<String>()

    /** Every [tickMs]: apply a new calibration, retry unsaved rows, and re-publish so status and fallback timers advance. */
    fun start(): Job = scope.launch { while (isActive) { delay(tickMs); mutex.withLock { applyOffset(); flush(); publish() } } }

    suspend fun onWorkout(snapshot: WorkoutSnapshot) = mutex.withLock {
        val id = snapshot.sessionId?.takeIf { snapshot.phase != WorkoutPhase.Idle }
        val active = PageSet.mapEligible(snapshot)
        val now = clock.nowMs()
        if (id != sessionId) {
            sessionId = id
            load(id)
            selector.begin(active, now)
        } else if (active != gpsActive) {
            selector.setGpsWorkout(active, now)
        }
        type = snapshot.type
        gpsActive = active
        applyOffset()
        flush()
        publish()
    }

    suspend fun onWatchDelta(d: SessionDelta) {
        if (d.locations.isEmpty()) return
        mutex.withLock {
            applyOffset()
            val now = clock.nowMs()
            val offset = appliedOffset
            val rows = d.locations.mapNotNull { f -> f.toRouteFix(FixSource.Watch, offset?.let { f.fixTimeMs - it }) }
            if (d.sessionId == sessionId) {
                for (r in rows) if (fixes.putIfAbsent(r.key, r) == null) r.point()?.let { track.add(it, now) }
                for (f in d.locations) selector.onWatchFix(f, clockSync.toPhoneTime(f.fixTimeMs), now)
            }
            enqueue(d.sessionId, rows) // another session's replay: history only
            unnormalized += d.sessionId // its delta may have stored the same rows with phoneTimeMs = null
            flush()
            publish()
        }
    }

    suspend fun onPhoneFix(f: LocationFix) = mutex.withLock {
        val id = sessionId ?: return@withLock
        if (!gpsActive) return@withLock
        applyOffset()
        val now = clock.nowMs()
        selector.onPhoneFix(f, now)
        val r = f.toRouteFix(FixSource.Phone, phoneTimeMs = f.fixTimeMs)
        if (r != null && fixes.putIfAbsent(r.key, r) == null) {
            r.point()?.let { track.add(it, now) }
            enqueue(id, listOf(r))
        }
        flush()
        publish()
    }

    /** Stored rows, plus rows rebuilt from the session's stored deltas that never reached route_point (review #1). */
    private suspend fun load(id: String?) {
        fixes.clear()
        appliedOffset = null
        if (id != null) {
            guard("route load") { store.routeFixes(id) }?.forEach { fixes[it.key] = it }
            val fromDeltas = guard("delta load") { sessions.deltas(id) }.orEmpty()
                .flatMap { it.locations }.mapNotNull { it.toRouteFix(FixSource.Watch, phoneTimeMs = null) }
            val missing = fromDeltas.filter { fixes.putIfAbsent(it.key, it) == null }
            if (missing.isNotEmpty()) {
                log("rebuilt ${missing.size} route rows from stored deltas")
                enqueue(id, missing)
            }
            unnormalized += id
        }
        rebuild()
    }

    /** A new (or first) calibration: re-map every watch row, rebuild the track chronologically (review #2). */
    private fun applyOffset() {
        val offset = clockSync.offsetMs.value ?: return
        if (offset == appliedOffset) return
        appliedOffset = offset
        val remap = { _: FixKey, r: RouteFix -> if (r.source == FixSource.Watch) r.copy(phoneTimeMs = r.deviceTimeMs - offset) else r }
        fixes.replaceAll(remap)
        unsaved.values.forEach { it.replaceAll(remap) }
        sessionId?.let { unnormalized += it }
        rebuild()
    }

    private fun rebuild() { track = RouteTrack.of(fixes.values.mapNotNull { it.point() }, clock.nowMs()) }

    private fun enqueue(id: String, rows: List<RouteFix>) {
        if (rows.isEmpty()) return
        val q = unsaved.getOrPut(id) { LinkedHashMap() }
        for (r in rows) q[r.key] = r
    }

    /** Inserts first, then normalization (so rows a concurrent storeDelta wrote with null get their phone time). */
    private suspend fun flush() {
        for (id in unsaved.keys.toList()) {
            val rows = unsaved.getValue(id).values.toList()
            if (guard("route store") { store.storeRouteFixes(id, rows) } == null) return
            unsaved.remove(id)
        }
        val offset = appliedOffset ?: return
        for (id in unnormalized.toList()) {
            if (guard("route normalize") { store.normalizeWatchTimes(id, offset) } == null) return
            unnormalized.remove(id)
        }
    }

    private suspend fun <T> guard(what: String, block: suspend () -> T): T? = try {
        block()
    } catch (e: CancellationException) {
        throw e
    } catch (e: Exception) {
        log("$what failed: $e"); null
    }

    private fun publish() {
        val now = clock.nowMs()
        _fallbackWanted.value = selector.update(now)
        val drawn = track.drawn()
        val live = selector.current(now)?.let { it.copy(bearingDeg = it.bearingDeg ?: track.lastBearing()) }
        _state.value = RouteState(sessionId, type, drawn, drawn.firstOrNull(), live, selector.status(now))
    }
}
```

- [ ] **Step 4: Run tests to verify they pass**

Run: `export JAVA_HOME=$(/usr/libexec/java_home -v 17) && ./gradlew :services:sync:test`
Expected: PASS.

- [ ] **Step 5: Commit**

```bash
git add services/sync
git commit -m "feat(sync): RouteHub with device-time identity, calibration normalization, durable retried route rows and phone fallback"
```

---

### Task 15: Link plumbing — time-sync driver, watch settings/queue, `lf_map` send, `lf_page_state` inbound

**Files:**
- Modify: `core/services/src/main/kotlin/com/debasish/livefit/services/Services.kt`
- Modify: `services/glasses-link/src/main/kotlin/com/debasish/livefit/services/glasses/GlassesInbound.kt`
- Modify: `services/glasses-link/src/main/kotlin/com/debasish/livefit/services/glasses/CxrGlassesLink.kt`
- Modify: `services/watch-link/build.gradle.kts`
- Modify: `services/watch-link/src/main/kotlin/com/debasish/livefit/services/watch/WatchMessageCodec.kt`
- Modify: `services/watch-link/src/main/kotlin/com/debasish/livefit/services/watch/DataLayerWatchLink.kt`
- Modify: `phone/src/debug/java/com/debasish/livefit/phone/DebugReceiver.kt` (debug build only; no other task touches it)
- Test: `services/glasses-link/src/test/kotlin/com/debasish/livefit/services/glasses/GlassesPageStateTest.kt`, `services/watch-link/src/test/kotlin/com/debasish/livefit/services/watch/WatchMessageCodecV4Test.kt`

**Interfaces:**
- Consumes: `MapFrame`, `PageState`, `WatchSettingsFrame`, `QueueFrame`, `TimeSyncRequest/Response`, channel/path names (Task 1); `WatchClockSync` (Task 4).
- Produces:
  - `GlassesLinkService.pushMap(frame: MapFrame, png: ByteArray?): Boolean` (default false) and `GlassesLinkService.pageStates: Flow<PageState>` (default empty).
  - `WatchLinkService.pushSettings(frame: WatchSettingsFrame)` and `WatchLinkService.pushQueue(frame: QueueFrame)` (default no-ops).
  - `GlassesInbound.pageState(cmd: String, text: String): PageState?`
  - `WatchInbound.TimeRes(response: TimeSyncResponse)`
  - `DataLayerWatchLink.clockSync: WatchClockSync` — calibrated on every Connected transition, then every 5 min (every 30 s while never calibrated).
  - `CxrGlassesLink.MAP_AS_BASE64 = false` switch.
  - Debug-only `DebugReceiver` command `map_probe` (extras `kb`, `b64`): sends an epoch header and one PNG-sized payload on `lf_map` and logs `LiveFitMap: map_probe … bytes=… crc=…` — the phone half of device check D1 (Task 22 Step 9).

- [ ] **Step 1: Write the failing tests**

`services/glasses-link/src/test/kotlin/com/debasish/livefit/services/glasses/GlassesPageStateTest.kt`:

```kotlin
package com.debasish.livefit.services.glasses

import com.debasish.livefit.model.GlassesChannels
import com.debasish.livefit.model.HudPage
import com.debasish.livefit.model.PageState
import com.debasish.livefit.model.Wire
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class GlassesPageStateTest {
    @Test fun pageStateIsDecoded() = assertEquals(
        PageState(page = HudPage.Map, seq = 3),
        GlassesInbound().pageState(GlassesChannels.PAGE_STATE, Wire.encode(PageState(page = HudPage.Map, seq = 3))),
    )

    @Test fun otherChannelsAndVersionsAreNotPageStates() {
        val g = GlassesInbound()
        assertNull(g.pageState(GlassesChannels.COMMAND, Wire.encode(PageState(page = HudPage.Map, seq = 3))))
        assertNull(g.pageState(GlassesChannels.PAGE_STATE, """{"protocolVersion":3,"page":"Map","seq":1}"""))
    }
}
```

`services/watch-link/src/test/kotlin/com/debasish/livefit/services/watch/WatchMessageCodecV4Test.kt`:

```kotlin
package com.debasish.livefit.services.watch

import com.debasish.livefit.model.LocationFix
import com.debasish.livefit.model.Provenance
import com.debasish.livefit.model.SessionDelta
import com.debasish.livefit.model.TimeSyncResponse
import com.debasish.livefit.model.WatchPaths
import com.debasish.livefit.model.Wire
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs

class WatchMessageCodecV4Test {
    @Test fun timeSyncResponseDecodes() {
        val r = TimeSyncResponse(id = 3, t0 = 10, tw = 20)
        assertEquals(WatchInbound.TimeRes(r), WatchMessageCodec.decode(WatchPaths.TIME_RES, Wire.encode(r).toByteArray()))
    }

    @Test fun outdatedTimeSyncIsReported() = assertEquals(
        WatchInbound.Outdated(3),
        WatchMessageCodec.decode(WatchPaths.TIME_RES, """{"protocolVersion":3,"id":1,"t0":1,"tw":2}""".toByteArray()),
    )

    @Test fun deltaWithLocationsDecodes() {
        val d = SessionDelta(sessionId = "s", seq = 1, locations = listOf(LocationFix(1.0, 2.0, 3f, null, 4)), provenance = Provenance.Fake)
        assertEquals(d.locations, assertIs<WatchInbound.Delta>(WatchMessageCodec.decode(WatchPaths.DELTA, Wire.encode(d).toByteArray())).delta.locations)
    }
}
```

- [ ] **Step 2: Run tests to verify they fail**

Run: `export JAVA_HOME=$(/usr/libexec/java_home -v 17) && ./gradlew :services:glasses-link:testDebugUnitTest :services:watch-link:testDebugUnitTest`
Expected: FAIL — `pageState`, `WatchInbound.TimeRes` unresolved.

- [ ] **Step 3: `Services.kt`** — in `interface GlassesLinkService` add:

```kotlin
    /** Map epoch header or image on lf_map (spec §2.5); false when not sent (not connected or the send failed). */
    suspend fun pushMap(frame: com.debasish.livefit.model.MapFrame, png: ByteArray?): Boolean = false
    /** lf_page_state from the glasses: the visible page, on every page change and every (re)connect. */
    val pageStates: Flow<com.debasish.livefit.model.PageState> get() = kotlinx.coroutines.flow.emptyFlow()
```

In `interface WatchLinkService` add:

```kotlin
    /** Settings → Pages on /lf/settings, on change and on every (re)connect (spec §3.2). */
    suspend fun pushSettings(frame: com.debasish.livefit.model.WatchSettingsFrame) {}
    /** Queue window on /lf/queue for the watch Playlist page, on change and on every (re)connect (spec §6). */
    suspend fun pushQueue(frame: com.debasish.livefit.model.QueueFrame) {}
```

- [ ] **Step 4: `GlassesInbound.kt`** — add the import `com.debasish.livefit.model.PageState` and:

```kotlin
    /** lf_page_state (spec §2.5); another version is ignored (lf_cmd already reports a mismatch). */
    fun pageState(cmd: String, text: String): PageState? = if (cmd == GlassesChannels.PAGE_STATE) PageState.parse(text) else null
```

- [ ] **Step 5: `CxrGlassesLink.kt`** — add imports `com.debasish.livefit.model.MapFrame`, `com.debasish.livefit.model.PageState`, `java.util.Base64`. Add fields:

```kotlin
    private val _pageStates = MutableSharedFlow<PageState>(extraBufferCapacity = 16)
    override val pageStates: SharedFlow<PageState> = _pageStates
```

In `onGlassesMessage`, after `if (text.isNullOrEmpty()) return` add:

```kotlin
        inbound.pageState(cmd, text)?.let { _pageStates.tryEmit(it); return }
```

Add:

```kotlin
    /** PNG in the CXR bytes argument (spec §2.5); [MAP_AS_BASE64] puts it in the JSON instead if the device test needs it. */
    override suspend fun pushMap(frame: MapFrame, png: ByteArray?): Boolean {
        if (MAP_AS_BASE64 && png != null) return send(GlassesChannels.MAP, Wire.encode(frame.copy(pngBase64 = Base64.getEncoder().encodeToString(png))))
        return send(GlassesChannels.MAP, Wire.encode(frame), png ?: ByteArray(0))
    }
```

Change `private fun send(channel: String, json: String): Boolean` to `private fun send(channel: String, json: String, bytes: ByteArray = ByteArray(0)): Boolean` and its `sendCustomCmd(channel, Caps().apply { write(json) }, ByteArray(0))` to `sendCustomCmd(channel, Caps().apply { write(json) }, bytes)`. In the companion add:

```kotlin
        /** Device-test switch (plan decision): false = PNG in the CXR bytes argument; true = Base64 inside the MapFrame JSON. */
        const val MAP_AS_BASE64 = false
```

- [ ] **Step 6: `services/watch-link/build.gradle.kts`** — add to the first `dependencies { … }`:

```kotlin
    api(project(":services:sync")) // WatchClockSync (pure)
```

- [ ] **Step 7: `WatchMessageCodec.kt`** — add the import `com.debasish.livefit.model.TimeSyncResponse`, the subtype `data class TimeRes(val response: TimeSyncResponse) : WatchInbound`, the branch `WatchPaths.TIME_RES -> WatchInbound.TimeRes(Wire.decode(text))` inside the `when (path)` (before `else`), and `WatchPaths.TIME_RES` to `jsonPaths`.

- [ ] **Step 8: `DataLayerWatchLink.kt`** — add imports `com.debasish.livefit.model.QueueFrame`, `com.debasish.livefit.model.TimeSyncRequest`, `com.debasish.livefit.model.TimeSyncResponse`, `com.debasish.livefit.model.WatchSettingsFrame`, `com.debasish.livefit.sync.WatchClockSync`, `kotlinx.coroutines.CompletableDeferred`, `kotlinx.coroutines.withTimeoutOrNull`, `kotlin.random.Random`. Add fields:

```kotlin
    /** Watch clock calibration (spec §2.1), in memory only: a new phone process starts uncalibrated. */
    val clockSync = WatchClockSync(Clock { System.currentTimeMillis() })
    /** Touched only on [scope] (Main). */
    private val pendingSync = HashMap<Long, CompletableDeferred<TimeSyncResponse>>()
```

In `init { … }` add `scope.launch { calibrationLoop() }`. Add:

```kotlin
    /** On every Connected transition and every 5 min (every 30 s while never calibrated): up to 5 pings with RTT ≤ 1 s. */
    private suspend fun calibrationLoop() {
        var wasConnected = false
        var nextAtMs = 0L
        while (true) {
            val connected = _status.value.link == LinkState.Connected
            if (connected && (!wasConnected || System.currentTimeMillis() >= nextAtMs)) {
                val ok = clockSync.calibrate(::ping)
                Log.i(TAG, "time sync ok=$ok offset=${clockSync.offsetMs.value}")
                nextAtMs = System.currentTimeMillis() + if (clockSync.calibrated) WatchClockSync.PERIOD_MS else WatchClockSync.RETRY_UNCALIBRATED_MS
            }
            wasConnected = connected
            delay(1_000)
        }
    }

    /** One ping: the watch answers on /lf/time_res with its clock; null after 1 s (calibrate() would reject it anyway). */
    private suspend fun ping(t0: Long): Long? {
        val id = Random.nextLong()
        val reply = CompletableDeferred<TimeSyncResponse>()
        pendingSync[id] = reply
        try {
            if (!sendRaw(WatchPaths.TIME_REQ, Wire.encode(TimeSyncRequest(id = id, t0 = t0)).toByteArray())) return null
            return withTimeoutOrNull(1_000) { reply.await() }?.tw
        } finally {
            pendingSync.remove(id)
        }
    }

    override suspend fun pushSettings(frame: WatchSettingsFrame) {
        if (_status.value.link == LinkState.Connected) sendRaw(WatchPaths.SETTINGS, Wire.encode(frame).toByteArray())
    }

    override suspend fun pushQueue(frame: QueueFrame) {
        if (_status.value.link == LinkState.Connected) sendRaw(WatchPaths.QUEUE, Wire.encode(frame).toByteArray())
    }
```

In `onMessage`'s `when` add:

```kotlin
            is WatchInbound.TimeRes -> pendingSync.remove(m.response.id)?.complete(m.response)
```

- [ ] **Step 9: Map probe for device check D1** — in `phone/src/debug/java/com/debasish/livefit/phone/DebugReceiver.kt` add the imports `com.debasish.livefit.model.MapFrame`, `com.debasish.livefit.model.MapFrameKind`, and inside `when (cmd)`:

```kotlin
            // Device check D1 (plan Task 22): one PNG-sized payload on lf_map over CXR — raw bytes, or Base64 in the JSON (b64).
            // adb shell am broadcast -n com.debasish.livefit/.phone.DebugReceiver --es cmd map_probe --ei kb 38 [--ez b64 true]
            "map_probe" -> CoroutineScope(Dispatchers.Main).launch {
                val size = intent.getIntExtra("kb", 38) * 1024
                val b64 = intent.getBooleanExtra("b64", false)
                val payload = ByteArray(size).also { java.util.Random(42).nextBytes(it); byteArrayOf(-119, 80, 78, 71, 13, 10, 26, 10).copyInto(it) }
                val crc = java.util.zip.CRC32().apply { update(payload) }.value.toString(16)
                val glasses = app.services.glasses
                val epoch = MapFrame(kind = MapFrameKind.Epoch, renderEpoch = 4242)
                val image = MapFrame(kind = MapFrameKind.Image, renderEpoch = 4242, sessionId = "probe", renderSeq = System.currentTimeMillis() / 1_000)
                val sentEpoch = glasses.pushMap(epoch, null)
                val sentImage = if (b64) glasses.pushMap(image.copy(pngBase64 = java.util.Base64.getEncoder().encodeToString(payload)), null)
                else glasses.pushMap(image, payload)
                android.util.Log.i("LiveFitMap", "map_probe kb=${size / 1024} b64=$b64 epochSent=$sentEpoch imageSent=$sentImage bytes=$size crc=$crc")
            }
```

- [ ] **Step 10: Run tests and build the phone**

Run: `export JAVA_HOME=$(/usr/libexec/java_home -v 17) && ./gradlew :services:glasses-link:testDebugUnitTest :services:watch-link:testDebugUnitTest :phone:assembleDebug`
Expected: PASS and BUILD SUCCESSFUL (the new interface members have defaults; nothing in the phone uses them yet).

- [ ] **Step 11: Commit**

```bash
git add core/services services/glasses-link services/watch-link phone/src/debug
git commit -m "feat(links): watch time-sync driver, watch settings and queue, lf_map send and lf_page_state inbound"
```

---

### Task 16: Phone GPS — LocationSource provider, hub re-promotion, setup step and status

**Files:**
- Modify: `phone/src/main/AndroidManifest.xml`
- Create: `phone/src/main/java/com/debasish/livefit/phone/HubLocationPolicy.kt`
- Create: `phone/src/main/java/com/debasish/livefit/phone/location/PhoneLocationProvider.kt`
- Modify: `phone/src/main/java/com/debasish/livefit/phone/LiveFitHubService.kt`
- Modify: `phone/src/main/java/com/debasish/livefit/phone/ui/AppActivity.kt`
- Modify: `phone/src/main/java/com/debasish/livefit/phone/setup/SetupFlow.kt`
- Modify: `phone/src/main/java/com/debasish/livefit/phone/setup/SetupScreen.kt`
- Modify: `phone/src/main/java/com/debasish/livefit/phone/ui/list/sources/PermissionSource.kt`
- Modify: `phone/src/main/java/com/debasish/livefit/phone/ui/settings/SettingsScreen.kt`
- Test: `phone/src/test/java/com/debasish/livefit/phone/HubLocationPolicyTest.kt`, `phone/src/test/java/com/debasish/livefit/phone/setup/SetupFlowTest.kt` (modify)

**Interfaces:**
- Consumes: `LocationFix` (Task 1).
- Produces:
  - `object HubLocationPolicy { fun fgsTypes(fineGranted: Boolean, activityVisible: Boolean): Int; fun phoneGpsLabel(fineGranted: Boolean, hubHasLocation: Boolean): String; fun providerFor(sdk: Int, hasFused: Boolean): String; fun fixTimeMs(nowMs: Long, nowElapsedNanos: Long, fixElapsedNanos: Long): Long }`
  - `class PhoneLocationProvider(context, onFix: (LocationFix) -> Unit) { val running: Boolean; fun start(): Boolean; fun stop() }` — 1 Hz, phone-clock fix times, Main looper.
  - `LiveFitHubService.locationCapable: StateFlow<Boolean>`; `LiveFitHubService.promoteLocation(context)`
  - `SetupStep.Map` (between Music and Voice)

- [ ] **Step 1: Write the failing tests**

`phone/src/test/java/com/debasish/livefit/phone/HubLocationPolicyTest.kt`:

```kotlin
package com.debasish.livefit.phone

import android.content.pm.ServiceInfo
import kotlin.test.Test
import kotlin.test.assertEquals

class HubLocationPolicyTest {
    /** Spec §2.1: a hub started from the background can't hold location; the visible Activity re-promotes it. */
    @Test fun hubHoldsLocationOnlyWhenVisibleAndGranted() {
        val cd = ServiceInfo.FOREGROUND_SERVICE_TYPE_CONNECTED_DEVICE
        val loc = ServiceInfo.FOREGROUND_SERVICE_TYPE_LOCATION
        assertEquals(cd or loc, HubLocationPolicy.fgsTypes(fineGranted = true, activityVisible = true))
        assertEquals(cd, HubLocationPolicy.fgsTypes(fineGranted = true, activityVisible = false))
        assertEquals(cd, HubLocationPolicy.fgsTypes(fineGranted = false, activityVisible = true), "denial: connectedDevice only")
    }

    @Test fun phoneGpsStatusLabels() {
        assertEquals("Phone GPS off", HubLocationPolicy.phoneGpsLabel(fineGranted = false, hubHasLocation = false))
        assertEquals("Phone GPS available after opening LiveFit", HubLocationPolicy.phoneGpsLabel(fineGranted = true, hubHasLocation = false))
        assertEquals("Phone GPS ready (map fallback)", HubLocationPolicy.phoneGpsLabel(fineGranted = true, hubHasLocation = true))
    }

    @Test fun fusedProviderOnAndroid12Plus() {
        assertEquals("fused", HubLocationPolicy.providerFor(31, hasFused = true))
        assertEquals("gps", HubLocationPolicy.providerFor(31, hasFused = false))
        assertEquals("gps", HubLocationPolicy.providerFor(30, hasFused = true))
    }

    @Test fun fixTimeUsesTheFixsOwnElapsedTime() =
        assertEquals(9_500L, HubLocationPolicy.fixTimeMs(nowMs = 10_000, nowElapsedNanos = 5_000_000_000, fixElapsedNanos = 4_500_000_000))
}
```

In `phone/src/test/java/com/debasish/livefit/phone/setup/SetupFlowTest.kt` change `walksAllSteps` to `repeat(6)` and add:

```kotlin
    /** Spec §2.1: "Map fallback" is an optional setup step; skipping it leads on to Voice. */
    @Test fun mapFallbackStepIsOptional() {
        val f = SetupFlow(SetupStep.Map); f.skip()
        assertEquals(SetupStep.Voice, f.step)
        assertEquals(SetupStep.Map, SetupFlow(SetupStep.Music).also { it.next() }.step)
    }
```

- [ ] **Step 2: Run tests to verify they fail**

Run: `export JAVA_HOME=$(/usr/libexec/java_home -v 17) && ./gradlew :phone:testDebugUnitTest --tests '*HubLocationPolicyTest*' --tests '*SetupFlowTest*'`
Expected: FAIL — `HubLocationPolicy`, `SetupStep.Map` unresolved.

- [ ] **Step 3: `HubLocationPolicy.kt`**

```kotlin
package com.debasish.livefit.phone

import android.content.pm.ServiceInfo

/** Phone GPS fallback rules that don't need a device (spec §2.1). */
object HubLocationPolicy {
    /** connectedDevice always; location only while LiveFit's Activity is visible and fine location is granted. */
    fun fgsTypes(fineGranted: Boolean, activityVisible: Boolean): Int =
        ServiceInfo.FOREGROUND_SERVICE_TYPE_CONNECTED_DEVICE or if (fineGranted && activityVisible) ServiceInfo.FOREGROUND_SERVICE_TYPE_LOCATION else 0

    /** Settings → Linked services row. */
    fun phoneGpsLabel(fineGranted: Boolean, hubHasLocation: Boolean): String = when {
        !fineGranted -> "Phone GPS off"
        !hubHasLocation -> "Phone GPS available after opening LiveFit"
        else -> "Phone GPS ready (map fallback)"
    }

    /** LocationManager.FUSED_PROVIDER ("fused", API 31+) when present, else GPS_PROVIDER ("gps"). */
    fun providerFor(sdk: Int, hasFused: Boolean): String = if (sdk >= 31 && hasFused) "fused" else "gps"

    /** The fix's wall-clock time from its own elapsed-realtime stamp (Location.time may be the GNSS clock). */
    fun fixTimeMs(nowMs: Long, nowElapsedNanos: Long, fixElapsedNanos: Long): Long = nowMs - (nowElapsedNanos - fixElapsedNanos) / 1_000_000
}
```

- [ ] **Step 4: `SetupFlow.kt`** — change the enum to:

```kotlin
/** First-run steps (spec §6.1). Map fallback (location) and Voice are optional. */
enum class SetupStep { Welcome, Glasses, Watch, Music, Map, Voice, Done }
```

- [ ] **Step 5: Run the tests to verify they pass**

Run: `export JAVA_HOME=$(/usr/libexec/java_home -v 17) && ./gradlew :phone:testDebugUnitTest --tests '*HubLocationPolicyTest*' --tests '*SetupFlowTest*'`
Expected: PASS.

- [ ] **Step 6: `PhoneLocationProvider.kt`**

```kotlin
package com.debasish.livefit.phone.location

import android.Manifest
import android.annotation.SuppressLint
import android.content.Context
import android.content.pm.PackageManager
import android.location.Location
import android.location.LocationListener
import android.location.LocationManager
import android.os.Build
import android.os.Bundle
import android.os.Looper
import android.os.SystemClock
import android.util.Log
import androidx.core.content.ContextCompat
import com.debasish.livefit.model.LocationFix
import com.debasish.livefit.phone.HubLocationPolicy

/** Phone fallback fixes at 1 Hz (spec §2.1); started/stopped by the hub's fallback decision. Main thread only. */
class PhoneLocationProvider(context: Context, private val onFix: (LocationFix) -> Unit) {
    private val app = context.applicationContext
    private val lm = app.getSystemService(LocationManager::class.java)
    private var listener: LocationListener? = null

    val running: Boolean get() = listener != null

    @SuppressLint("MissingPermission")
    fun start(): Boolean {
        if (listener != null) return true
        if (ContextCompat.checkSelfPermission(app, Manifest.permission.ACCESS_FINE_LOCATION) != PackageManager.PERMISSION_GRANTED) return false
        val provider = HubLocationPolicy.providerFor(Build.VERSION.SDK_INT, Build.VERSION.SDK_INT >= 31 && lm.hasProvider(LocationManager.FUSED_PROVIDER))
        val l = object : LocationListener {
            override fun onLocationChanged(loc: Location) {
                onFix(
                    LocationFix(
                        loc.latitude, loc.longitude,
                        accuracyM = if (loc.hasAccuracy()) loc.accuracy else null, // unknown: never usable-live (review #8)
                        bearingDeg = if (loc.hasBearing()) loc.bearing else null,
                        fixTimeMs = HubLocationPolicy.fixTimeMs(System.currentTimeMillis(), SystemClock.elapsedRealtimeNanos(), loc.elapsedRealtimeNanos),
                    ),
                )
            }
            @Deprecated("Deprecated in Java") override fun onStatusChanged(provider: String?, status: Int, extras: Bundle?) {}
            override fun onProviderEnabled(provider: String) {}
            override fun onProviderDisabled(provider: String) {}
        }
        return try {
            lm.requestLocationUpdates(provider, 1_000L, 0f, l, Looper.getMainLooper())
            listener = l
            Log.i(TAG, "phone GPS on ($provider)")
            true
        } catch (e: Exception) {
            Log.w(TAG, "phone GPS refused", e) // e.g. the hub has no location FGS type: fallback unavailable, nothing else breaks
            false
        }
    }

    fun stop() {
        val l = listener ?: return
        runCatching { lm.removeUpdates(l) }
        listener = null
        Log.i(TAG, "phone GPS off")
    }

    companion object {
        const val TAG = "LiveFitPhoneGps"
    }
}
```

- [ ] **Step 7: Manifest** — in `phone/src/main/AndroidManifest.xml` add:

```xml
    <!-- Optional (setup "Map fallback"): phone GPS when the watch has no fix (spec §2.1). -->
    <uses-permission android:name="android.permission.ACCESS_FINE_LOCATION" />
    <uses-permission android:name="android.permission.ACCESS_COARSE_LOCATION" />
    <uses-permission android:name="android.permission.FOREGROUND_SERVICE_LOCATION" />
```

and change the hub service to `android:foregroundServiceType="connectedDevice|location"`.

- [ ] **Step 8: `LiveFitHubService.kt`** — add imports `android.util.Log`, `kotlinx.coroutines.flow.MutableStateFlow`, `kotlinx.coroutines.flow.StateFlow`. Add the field `private var lastText = READY`; in `HubNotification.postChanges(...)` callback set `lastText = text` before `nm.notify(...)`. At the end of `onCreate` (after `running = true` … `watcher = …`) add:

```kotlin
        if (promoteOnCreate) { promoteOnCreate = false; promoteLocation() } // started from the visible Activity
```

Replace `override fun onStartCommand(...) = START_STICKY` with:

```kotlin
    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_PROMOTE_LOCATION) promoteLocation()
        return START_STICKY
    }

    /** Spec §2.1: `CONNECTED_DEVICE | LOCATION` while the Activity is visible and fine location is granted; refusal is harmless. */
    private fun promoteLocation() {
        val fine = ContextCompat.checkSelfPermission(this, Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED
        val types = HubLocationPolicy.fgsTypes(fine, activityVisible = true)
        if (types and ServiceInfo.FOREGROUND_SERVICE_TYPE_LOCATION == 0) { _locationCapable.value = false; return }
        runCatching { ServiceCompat.startForeground(this, ID, notification(lastText), types) }
            .onSuccess { _locationCapable.value = true }
            .onFailure { Log.w("LiveFitHub", "location FGS refused", it); _locationCapable.value = false }
    }
```

In `onDestroy` add `_locationCapable.value = false` before `super.onDestroy()`. In the companion add:

```kotlin
        private const val ACTION_PROMOTE_LOCATION = "com.debasish.livefit.PROMOTE_LOCATION"
        @Volatile private var promoteOnCreate = false
        private val _locationCapable = MutableStateFlow(false)
        /** The hub currently holds the `location` FGS type, so the phone fallback can run. */
        val locationCapable: StateFlow<Boolean> = _locationCapable

        /** Called from the visible Activity (onResume, after a location grant). */
        fun promoteLocation(context: Context) {
            if (running) runCatching { context.startService(Intent(context, LiveFitHubService::class.java).setAction(ACTION_PROMOTE_LOCATION)) }
            else promoteOnCreate = true // ensureRunning() just started it; onCreate promotes
        }
```

- [ ] **Step 9: `AppActivity.kt`** — in `onResume()` after `LiveFitHubService.ensureRunning(this)` add `LiveFitHubService.promoteLocation(this)`.

- [ ] **Step 10: `SetupScreen.kt`** — add imports `android.Manifest` (if not present) and `androidx.compose.material.icons.rounded.MyLocation`. Next to the existing `permissions` launcher add:

```kotlin
    val locationPermission = rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) {
        LiveFitHubService.promoteLocation(activity) // granted: the visible app re-promotes the hub with `location`
    }
```

In the `(icon, title, body)` `when` add:

```kotlin
            SetupStep.Map -> Triple(Icons.Rounded.MyLocation, "Map fallback", "Allow location so your phone can draw the route when the watch has no GPS fix. Optional — workouts record without it.")
```

and in the button `when (step)` add:

```kotlin
            SetupStep.Map -> Button(onClick = { locationPermission.launch(arrayOf(Manifest.permission.ACCESS_FINE_LOCATION, Manifest.permission.ACCESS_COARSE_LOCATION)) }) { Text("Allow location") }
```

- [ ] **Step 11: `PermissionSource.kt`** — add the import `androidx.compose.material.icons.rounded.MyLocation` and, after the `bt` row:

```kotlin
        row("location", "Location", "Phone map fallback when the watch has no GPS", Icons.Rounded.MyLocation, granted(Manifest.permission.ACCESS_FINE_LOCATION)),
```

- [ ] **Step 12: `SettingsScreen.kt`** — add imports `android.Manifest`, `android.content.pm.PackageManager`, `androidx.core.content.ContextCompat`, `com.debasish.livefit.phone.HubLocationPolicy`, `com.debasish.livefit.phone.LiveFitHubService`. Near the other `collectAsStateWithLifecycle` lines add:

```kotlin
    val hubHasLocation by LiveFitHubService.locationCapable.collectAsStateWithLifecycle()
    val fineLocation = ContextCompat.checkSelfPermission(LocalContext.current, Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED
```

and in the "Linked services" group after the Nearby devices row:

```kotlin
            Divider()
            ChipRow(Icons.Rounded.MyLocation, LiveFitColors.ChipAmber, "Phone GPS", HubLocationPolicy.phoneGpsLabel(fineLocation, hubHasLocation), { onNavigate("permissions") })
```

- [ ] **Step 13: Build and test**

Run: `export JAVA_HOME=$(/usr/libexec/java_home -v 17) && ./gradlew :phone:assembleDebug :phone:testDebugUnitTest`
Expected: BUILD SUCCESSFUL, tests PASS.

- [ ] **Step 14: Commit**

```bash
git add phone
git commit -m "feat(phone): optional phone GPS fallback provider, location FGS re-promotion, setup step and status row"
```

---

### Task 17: Settings store pages + gestures, settings frames, voice page gate

**Files:**
- Modify: `phone/src/main/java/com/debasish/livefit/phone/SettingsStore.kt`
- Modify: `phone/src/main/java/com/debasish/livefit/phone/ServiceGraph.kt`
- Modify: `phone/src/main/java/com/debasish/livefit/phone/ui/settings/SettingsScreen.kt`
- Modify: `services/sync/src/main/kotlin/com/debasish/livefit/sync/HubCommandRouter.kt`
- Test: `services/sync/src/test/kotlin/com/debasish/livefit/sync/HubCommandRouterTest.kt` (add tests)

**Interfaces:**
- Consumes: `PageSettings`, `GestureSettings`, `HudSettingsFrame.pages/gestures`, `WatchSettingsFrame`, `QueueFrame`, `HudPage.label` (Task 1); `GestureRules`, `GestureChange` (Task 6); `RouteStore`, `RoomSessionStore : RouteStore` (Task 13); `WatchLinkService.pushSettings/pushQueue` (Task 15); `SettingsScreen` edits of Task 16 (same lane).
- Produces:
  - `SettingsStore.pages: StateFlow<PageSettings>`, `setPageEnabled(page: HudPage, enabled: Boolean)`, `gestures: StateFlow<GestureSettings>`, `changeGesture(mode, page, gesture, action): GestureChange`, `setIdleTimeout(seconds: Int)`, `setAskBeforeClose(on: Boolean)`, `resetGestures()`
  - `HubCommandRouter(…, pageEnabled: (HudPage) -> Boolean = { true })` — disabled page → toast "<Page> page is turned off in Settings", nothing sent; success toast uses `page.label`.
  - `ServiceGraph.routes: RouteStore`; GPS workout = "Use GPS outdoors" for every type; HUD settings frame carries pages + gestures; watch gets `WatchSettingsFrame` and `QueueFrame` on change and on every (re)connect.

- [ ] **Step 1: Write the failing test** — add `import com.debasish.livefit.model.HudPage` to `HubCommandRouterTest.kt` and these tests inside the class:

```kotlin
    /** Spec §5: a disabled page explains itself and is not shown; voice page commands move the glasses only. */
    @Test fun disabledPageIsNotShownAndExplains() = runTest {
        val shown = mutableListOf<HudPage>()
        val r = HubCommandRouter(workout, music, confirm, backgroundScope, toast = { toasts += it }, showGlassesPage = { shown += it }, pageEnabled = { it != HudPage.Map })
        r.dispatchVoice(Command.ShowGlassesPage(HudPage.Map))
        r.dispatchVoice(Command.ShowGlassesPage(HudPage.MusicControls))
        assertEquals(listOf(HudPage.MusicControls), shown)
        assertEquals(listOf("Map page is turned off in Settings", "Music controls view"), toasts)
    }

    @Test fun startStillLandsOnWorkoutWhateverThePageSettings() = runTest {
        val shown = mutableListOf<HudPage>()
        val r = HubCommandRouter(workout, music, confirm, backgroundScope, toast = { toasts += it }, showGlassesPage = { shown += it }, pageEnabled = { false })
        r.dispatch(env("s1", Command.StartWorkout(WorkoutType.Run)))
        assertEquals(listOf(HudPage.Workout), shown)
    }
```

- [ ] **Step 2: Run test to verify it fails**

Run: `export JAVA_HOME=$(/usr/libexec/java_home -v 17) && ./gradlew :services:sync:test --tests '*HubCommandRouterTest*'`
Expected: FAIL — no parameter `pageEnabled`.

- [ ] **Step 3: `HubCommandRouter.kt`** — add the last constructor parameter:

```kotlin
    /** Settings → Pages (spec §5): a voice view of a disabled page only toasts. Workout is always enabled. */
    private val pageEnabled: (HudPage) -> Boolean = { true },
```

In `apply(...)` replace the `is Command.ShowGlassesPage -> showGlassesPage(command.page)` branch with:

```kotlin
            is Command.ShowGlassesPage -> if (!pageEnabled(command.page)) {
                toast("${command.page.label} page is turned off in Settings")
                return
            } else showGlassesPage(command.page)
```

and in `describe(...)` replace `"${command.page.name} view"` with `"${command.page.label} view"`.

- [ ] **Step 4: Run tests to verify they pass**

Run: `export JAVA_HOME=$(/usr/libexec/java_home -v 17) && ./gradlew :services:sync:test`
Expected: PASS.

- [ ] **Step 5: `SettingsStore.kt`** — add imports `com.debasish.livefit.model.Gesture`, `com.debasish.livefit.model.GestureAction`, `com.debasish.livefit.model.GestureChange`, `com.debasish.livefit.model.GestureMode`, `com.debasish.livefit.model.GestureRules`, `com.debasish.livefit.model.GestureSettings`, `com.debasish.livefit.model.HudPage`, `com.debasish.livefit.model.PageSettings`, and add before `_setupDone`:

```kotlin
    /** Settings → Pages (spec §3.2): one switch per page, shared by glasses and watch. */
    private val _pages = pref("pages", PageSettings()) { runCatching { Wire.decode<PageSettings>(it) }.getOrNull() }
    val pages: StateFlow<PageSettings> = _pages
    fun setPageEnabled(page: HudPage, enabled: Boolean) {
        if (page == HudPage.Workout) return // can't be disabled
        val d = _pages.value.disabled
        val next = PageSettings(if (enabled) d - page else d + page)
        _pages.value = next; prefs.edit().putString("pages", Wire.encode(next)).apply()
    }

    /** Settings → Glasses gestures (spec §4.4); stored tables are re-validated on load. */
    private val _gestures = pref("gestures", GestureSettings()) { runCatching { GestureRules.sanitized(Wire.decode<GestureSettings>(it)) }.getOrNull() }
    val gestures: StateFlow<GestureSettings> = _gestures
    private fun saveGestures(g: GestureSettings) { _gestures.value = g; prefs.edit().putString("gestures", Wire.encode(g)).apply() }

    /** Refused changes (no Close app / no page move left on a page) are returned with the reason and not saved. */
    fun changeGesture(mode: GestureMode, page: HudPage, gesture: Gesture, action: GestureAction): GestureChange =
        GestureRules.change(_gestures.value, mode, page, gesture, action).also { if (it is GestureChange.Applied) saveGestures(it.settings) }
    fun setIdleTimeout(seconds: Int) = saveGestures(GestureRules.withIdleTimeout(_gestures.value, seconds))
    fun setAskBeforeClose(on: Boolean) = saveGestures(_gestures.value.copy(askBeforeClose = on))
    fun resetGestures() = saveGestures(GestureSettings())
```

- [ ] **Step 6: `ServiceGraph.kt`** — add imports `com.debasish.livefit.model.WatchSettingsFrame`, `com.debasish.livefit.services.RouteStore`. Replace `val history: HistoryStore = RoomSessionStore(HistoryDatabase.shared(app))` with:

```kotlin
    private val room = RoomSessionStore(HistoryDatabase.shared(app))
    val history: HistoryStore = room
    /** Route points per session (spec §2.2); history detail draws them. */
    val routes: RouteStore = room
```

Replace the `gpsFor` argument with `gpsFor = { settings.gpsOutdoors.value }` (spec §2.1: GPS workouts include Walk when "GPS outdoors" is on). Add to the router construction the argument `pageEnabled = { settings.pages.value.isEnabled(it) }`. Replace the "HUD settings" block in `start()` with:

```kotlin
        // HUD settings (+ pages and gestures, spec §3.2/§4.4): on change and whenever the glasses (re)connect.
        scope.launch {
            combine(settings.hud, settings.pages, settings.gestures, glasses.status) { hud, pages, gestures, st ->
                HudSettingsFrame(settings = hud, pages = pages, gestures = gestures) to st.link
            }.distinctUntilChanged().collect { (frame, link) -> if (link == LinkState.Connected) glasses.pushSettings(frame) }
        }
        // Watch: the page set and the queue window, on change and whenever the watch (re)connects (spec §3.2, §6).
        scope.launch {
            combine(settings.pages, watch.status) { p, st -> p to st.link }.distinctUntilChanged()
                .collect { (p, link) -> if (link == LinkState.Connected) watch.pushSettings(WatchSettingsFrame(pages = p)) }
        }
        scope.launch {
            combine(music.queue, watch.status) { q, st -> q to st.link }.distinctUntilChanged()
                .collect { (q, link) -> if (link == LinkState.Connected) watch.pushQueue(QueueFrame(window = q)) }
        }
```

- [ ] **Step 7: `SettingsScreen.kt`** — change the GPS row subtitle from `"Run, Cycle, Auto"` to `"Walk, Run, Cycle, Auto · live map"`.

- [ ] **Step 8: Build and test**

Run: `export JAVA_HOME=$(/usr/libexec/java_home -v 17) && ./gradlew :phone:assembleDebug :phone:testDebugUnitTest :services:sync:test`
Expected: BUILD SUCCESSFUL, tests PASS.

- [ ] **Step 9: Commit**

```bash
git add phone services/sync
git commit -m "feat(phone): pages and gesture settings in the settings frames, watch settings and queue, disabled-page voice toast"
```

---

### Task 18: Map pipeline wiring — glasses renderer and ServiceGraph

**Files:**
- Create: `phone/src/main/java/com/debasish/livefit/phone/map/GlassesMapText.kt`
- Create: `phone/src/main/java/com/debasish/livefit/phone/map/GlassesMapPlan.kt`
- Create: `phone/src/main/java/com/debasish/livefit/phone/map/GlassesMapRenderer.kt`
- Modify: `phone/src/main/java/com/debasish/livefit/phone/ServiceGraph.kt`
- Test: `phone/src/test/java/com/debasish/livefit/phone/map/GlassesMapTextTest.kt`, `phone/src/test/java/com/debasish/livefit/phone/map/GlassesMapPlanTest.kt`

**Interfaces:**
- Consumes: `RouteHub(…, store, sessions, …)` (Task 14); `GlassesMapStreamer` (Task 9); `MapSceneBuilder`, `MapScene`, `HudPalette`, `Viewport`, `PlacedTile`, `TileId`, `HttpTileFetcher`, `TileDiskCache`, `OsmTileSource`, `TileLoader` (Tasks 2, 7, 8); `PhoneLocationProvider`, `LiveFitHubService.locationCapable` (Task 16); `DataLayerWatchLink.clockSync`, `GlassesLinkService.pushMap/pageStates` (Task 15); `ServiceGraph.routes`, `ServiceGraph.history` (Task 17). (Device check D1, Task 22 Step 9, may flip `CxrGlassesLink.MAP_AS_BASE64`; nothing in this task depends on its outcome.)
- Produces:
  - `object GlassesMapText { fun captionLines(scene: MapScene, drewTile: Boolean): List<String> }`
  - `data class GlassesMapPlan<T>(scene: MapScene, wanted: List<TileId>, drawn: List<Pair<PlacedTile, T>>, captions: List<String>) { companion fun <T> of(state: RouteState, sizePx: Int, loaded: Map<TileId, T>): GlassesMapPlan<T> }` — pure: what one image shows from the tiles loaded **right now**.
  - `class GlassesMapRenderer(tiles: TileLoader<Bitmap>, sizePx: Int = 480) { suspend fun render(state: RouteState): ByteArray?; companion { fun tileLoader(scope, fetcher: HttpTileFetcher): TileLoader<Bitmap> } }` — never waits for the network: draws the cached/loaded tiles (or route only) with the latest state and asks the loader for the missing ones (review #5); logs `LiveFitMap` when a PNG exceeds 40 KB.
  - `ServiceGraph.routeHub: RouteHub`; the phone GPS runs exactly while `routeHub.fallbackWanted && LiveFitHubService.locationCapable`; images stream only while the glasses report Map; leaving Map (or disconnecting) empties the loader's visible set.

- [ ] **Step 1: Write the failing tests** — `phone/src/test/java/com/debasish/livefit/phone/map/GlassesMapTextTest.kt`:

```kotlin
package com.debasish.livefit.phone.map

import com.debasish.livefit.map.MapSceneBuilder
import com.debasish.livefit.model.FixSource
import com.debasish.livefit.model.GpsStatus
import com.debasish.livefit.model.LivePosition
import com.debasish.livefit.model.RouteState
import kotlin.test.Test
import kotlin.test.assertEquals

class GlassesMapTextTest {
    private val live = LivePosition(12.97, 77.59, null, FixSource.Watch, 0)

    @Test fun waitingWithoutAPointShowsOnlyTheGpsCaption() =
        assertEquals(listOf("Waiting for GPS…"), GlassesMapText.captionLines(MapSceneBuilder.build(RouteState(sessionId = "s"), 18, 480, 480), drewTile = false))

    @Test fun liveWithTilesHasNoCaption() =
        assertEquals(emptyList(), GlassesMapText.captionLines(MapSceneBuilder.build(RouteState(sessionId = "s", live = live, status = GpsStatus.Live), 18, 480, 480), drewTile = true))

    /** Spec §2.5/§7: offline → route only on black with "No map — route only". */
    @Test fun offlineAndDelayedShowsBoth() = assertEquals(
        listOf("GPS delayed", "No map — route only"),
        GlassesMapText.captionLines(MapSceneBuilder.build(RouteState(sessionId = "s", live = live, status = GpsStatus.Delayed), 18, 480, 480), drewTile = false),
    )
}
```

`phone/src/test/java/com/debasish/livefit/phone/map/GlassesMapPlanTest.kt`:

```kotlin
package com.debasish.livefit.phone.map

import com.debasish.livefit.map.TileId
import com.debasish.livefit.map.TileLoader
import com.debasish.livefit.model.FixSource
import com.debasish.livefit.model.GpsStatus
import com.debasish.livefit.model.HudPage
import com.debasish.livefit.model.LivePosition
import com.debasish.livefit.model.MapFrameKind
import com.debasish.livefit.model.RouteState
import com.debasish.livefit.services.Clock
import com.debasish.livefit.sync.GlassesMapStreamer
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

@OptIn(ExperimentalCoroutinesApi::class)
class GlassesMapPlanTest {
    private val live = LivePosition(12.9716, 77.5946, null, FixSource.Watch, 0)

    /**
     * Review #5: every tile response hangs. Images still go out on the 3 s cadence with route, marker and captions
     * ("GPS delayed", "No map — route only"); once the tiles are released they appear in the next image, no restart.
     */
    @Test fun blockedTilesNeverHoldBackTheImage() = runTest {
        val gate = CompletableDeferred<Unit>()
        val loader = TileLoader(backgroundScope, load = { t: TileId -> gate.await(); "tile ${t.x}/${t.y}" }, maxConcurrent = 4)
        val plans = mutableListOf<GlassesMapPlan<String>>()
        var images = 0
        val streamer = GlassesMapStreamer(
            backgroundScope, Clock { testScheduler.currentTime },
            render = { s -> GlassesMapPlan.of(s, 480, loader.tiles.value).also { plans += it; loader.show(it.wanted) }; byteArrayOf(1) },
            send = { f, _ -> if (f.kind == MapFrameKind.Image) images++; true },
            newEpoch = { 1L },
        )
        streamer.start()
        streamer.onRoute(RouteState(sessionId = "s", live = live, status = GpsStatus.Delayed))
        streamer.onConnected()
        streamer.onPageState(HudPage.Map, 1)
        advanceTimeBy(6_500); runCurrent()
        assertEquals(3, images, "t = 0, 3, 6 s although no tile has arrived")
        assertTrue(plans.all { it.drawn.isEmpty() && it.scene.arrow != null }, "marker drawn without tiles")
        assertEquals(listOf("GPS delayed", "No map — route only"), plans.last().captions)
        gate.complete(Unit); runCurrent()
        advanceTimeBy(3_000); runCurrent()
        assertEquals(4, images)
        assertEquals(plans.last().wanted.size, plans.last().drawn.size, "every visible tile drawn once loaded")
        assertEquals(listOf("GPS delayed"), plans.last().captions)
    }

    @Test fun planOnlyUsesLoadedTilesOfTheViewport() {
        val state = RouteState(sessionId = "s", live = live, status = GpsStatus.Live)
        val first = GlassesMapPlan.of(state, 480, emptyMap<TileId, String>())
        assertTrue(first.wanted.size in 4..9)
        val one = first.wanted.first()
        val p = GlassesMapPlan.of(state, 480, mapOf(one to "a", TileId(3, 0, 0) to "elsewhere"))
        assertEquals(listOf(one to "a"), p.drawn.map { it.first.tile to it.second })
        assertEquals(emptyList(), p.captions)
    }
}
```

- [ ] **Step 2: Run tests to verify they fail**

Run: `export JAVA_HOME=$(/usr/libexec/java_home -v 17) && ./gradlew :phone:testDebugUnitTest --tests '*GlassesMapTextTest*' --tests '*GlassesMapPlanTest*'`
Expected: FAIL — `GlassesMapText`, `GlassesMapPlan` unresolved.

- [ ] **Step 3: `GlassesMapText.kt` and `GlassesMapPlan.kt`**

```kotlin
package com.debasish.livefit.phone.map

import com.debasish.livefit.map.MapScene
import com.debasish.livefit.map.MapSceneBuilder

/** Text lines at the top of the glasses map image. */
object GlassesMapText {
    fun captionLines(scene: MapScene, drewTile: Boolean): List<String> =
        listOfNotNull(scene.caption, MapSceneBuilder.NO_TILES_CAPTION.takeIf { scene.viewport != null && !drewTile })
}
```

`phone/src/main/java/com/debasish/livefit/phone/map/GlassesMapPlan.kt`:

```kotlin
package com.debasish.livefit.phone.map

import com.debasish.livefit.map.MapScene
import com.debasish.livefit.map.MapSceneBuilder
import com.debasish.livefit.map.PlacedTile
import com.debasish.livefit.map.TileId
import com.debasish.livefit.map.Viewport
import com.debasish.livefit.model.RouteState

/**
 * What one glasses image shows (pure, review #5): the scene for the latest state, the visible tiles it [wanted], the ones
 * already [loaded] that get [drawn], and the caption lines. Nothing here waits for a tile.
 */
data class GlassesMapPlan<T>(val scene: MapScene, val wanted: List<TileId>, val drawn: List<Pair<PlacedTile, T>>, val captions: List<String>) {
    companion object {
        fun <T> of(state: RouteState, sizePx: Int, loaded: Map<TileId, T>): GlassesMapPlan<T> {
            val scene = MapSceneBuilder.build(state, Viewport.zoomFor(state.type), sizePx, sizePx)
            val visible = scene.viewport?.tiles().orEmpty()
            val drawn = visible.mapNotNull { pt -> loaded[pt.tile]?.let { pt to it } }
            return GlassesMapPlan(scene, visible.map { it.tile }, drawn, GlassesMapText.captionLines(scene, drewTile = drawn.isNotEmpty()))
        }
    }
}
```

- [ ] **Step 4: Run tests to verify they pass**

Run: `export JAVA_HOME=$(/usr/libexec/java_home -v 17) && ./gradlew :phone:testDebugUnitTest --tests '*GlassesMapTextTest*' --tests '*GlassesMapPlanTest*'`
Expected: PASS (5 tests).

- [ ] **Step 5: `GlassesMapRenderer.kt`**

```kotlin
package com.debasish.livefit.phone.map

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Path
import android.util.Log
import com.debasish.livefit.map.HttpTileFetcher
import com.debasish.livefit.map.HudPalette
import com.debasish.livefit.map.MapArrow
import com.debasish.livefit.map.MapScene
import com.debasish.livefit.map.TileLoader
import com.debasish.livefit.model.RouteState
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.ByteArrayOutputStream

/**
 * The glasses map image (spec §2.5): 480×480 PNG on black, tiles converted to the HUD palette, bright route, position
 * arrow (hollow while degraded), start marker, scale bar and the always-visible attribution. It never waits for a tile
 * (review #5): it draws what [tiles] holds now — or route only with "No map — route only" — and asks the loader for the
 * missing visible tiles, which then appear in a later image.
 */
class GlassesMapRenderer(private val tiles: TileLoader<Bitmap>, private val sizePx: Int = SIZE_PX) {
    private val green = 0xFF000000.toInt() or HudPalette.HUD_GREEN
    private val dim = HudPalette.scaled(HudPalette.HUD_GREEN, 0.6f)
    private val routePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = green; style = Paint.Style.STROKE; strokeWidth = 6f; strokeCap = Paint.Cap.ROUND; strokeJoin = Paint.Join.ROUND }
    private val dimStroke = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = dim; style = Paint.Style.STROKE; strokeWidth = 3f }
    private val arrowFill = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = green; style = Paint.Style.FILL }
    private val arrowHollow = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = green; style = Paint.Style.STROKE; strokeWidth = 4f }
    private val caption = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = green; textSize = 28f; textAlign = Paint.Align.CENTER; isFakeBoldText = true }
    private val small = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = dim; textSize = 18f }
    private val attribution = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = dim; textSize = 16f; textAlign = Paint.Align.RIGHT }

    suspend fun render(state: RouteState): ByteArray? = withContext(Dispatchers.Default) {
        val plan = GlassesMapPlan.of(state, sizePx, tiles.tiles.value)
        tiles.show(plan.wanted) // returns at once; missing tiles load (and retry) in the background
        val bmp = Bitmap.createBitmap(sizePx, sizePx, Bitmap.Config.ARGB_8888)
        val c = Canvas(bmp)
        c.drawColor(Color.BLACK)
        for ((pt, tile) in plan.drawn) c.drawBitmap(tile, pt.left, pt.top, null)
        drawOverlay(c, plan.scene, plan.captions)
        val out = ByteArrayOutputStream()
        bmp.compress(Bitmap.CompressFormat.PNG, 100, out)
        out.toByteArray().also { if (it.size > MAX_PNG_BYTES) Log.w(TAG, "map PNG ${it.size} B exceeds the ${MAX_PNG_BYTES} B target") }
    }

    private fun drawOverlay(c: Canvas, scene: MapScene, captions: List<String>) {
        if (scene.route.size >= 2) {
            val path = Path().apply { moveTo(scene.route[0].x, scene.route[0].y); for (p in scene.route.drop(1)) lineTo(p.x, p.y) }
            c.drawPath(path, routePaint)
        }
        scene.start?.let { c.drawCircle(it.x, it.y, 9f, dimStroke) }
        scene.arrow?.let { drawArrow(c, it) }
        scene.scale?.let { s ->
            val y = sizePx - 46f
            c.drawLine(16f, y, 16f + s.lengthPx, y, dimStroke)
            c.drawText(s.label, 16f, y - 8f, small)
        }
        captions.forEachIndexed { i, line -> c.drawText(line, sizePx / 2f, 40f + i * 34f, caption) }
        c.drawText(scene.attribution, sizePx - 12f, sizePx - 14f, attribution)
    }

    private fun drawArrow(c: Canvas, a: MapArrow) {
        val paint = if (a.hollow) arrowHollow else arrowFill
        val bearing = a.bearingDeg
        if (bearing == null) { c.drawCircle(a.at.x, a.at.y, 10f, paint); return }
        val r = 16f
        val path = Path().apply { moveTo(0f, -r); lineTo(r * 0.7f, r * 0.8f); lineTo(0f, r * 0.4f); lineTo(-r * 0.7f, r * 0.8f); close() }
        c.save()
        c.translate(a.at.x, a.at.y)
        c.rotate(bearing)
        c.drawPath(path, paint)
        c.restore()
    }

    companion object {
        const val TAG = "LiveFitMap"
        const val SIZE_PX = 480
        const val MAX_PNG_BYTES = 40 * 1024

        /** Up to 4 tiles at once, missing visible tiles retried every 5 s (the fetcher's backoff limits real requests). */
        fun tileLoader(scope: CoroutineScope, fetcher: HttpTileFetcher): TileLoader<Bitmap> =
            TileLoader(scope, load = { t -> withContext(Dispatchers.IO) { fetcher.fetch(t)?.let(::toHud) } }, maxConcurrent = 4, retryEveryMs = 5_000, maxCached = 30)

        private fun toHud(bytes: ByteArray): Bitmap? {
            val src = BitmapFactory.decodeByteArray(bytes, 0, bytes.size) ?: return null
            val px = IntArray(src.width * src.height)
            src.getPixels(px, 0, src.width, 0, 0, src.width, src.height)
            HudPalette.convertAll(px)
            return Bitmap.createBitmap(px, src.width, src.height, Bitmap.Config.ARGB_8888)
        }
    }
}
```

- [ ] **Step 6: `ServiceGraph.kt`** — add imports:

```kotlin
import com.debasish.livefit.map.HttpTileFetcher
import com.debasish.livefit.map.OsmTileSource
import com.debasish.livefit.map.TileDiskCache
import com.debasish.livefit.phone.location.PhoneLocationProvider
import com.debasish.livefit.phone.map.GlassesMapRenderer
import com.debasish.livefit.sync.GlassesMapStreamer
import com.debasish.livefit.sync.RouteHub
import com.debasish.livefit.sync.WatchClockSync
import java.io.File
```

After the `val workout = HubWorkoutService(...)` declaration add:

```kotlin
    // ---- Live map (spec §2) ----
    /** Route rows in `routes`; missing rows are rebuilt from the deltas in `history` on every session load (review #1). */
    val routeHub = RouteHub(scope, clock, dataLayer?.clockSync ?: WatchClockSync(clock), routes, history, log = { Log.d("LiveFitMap", it) })
    private val phoneGps = PhoneLocationProvider(app) { fix -> scope.launch { routeHub.onPhoneFix(fix) } }
    private val mapTiles = GlassesMapRenderer.tileLoader(scope, HttpTileFetcher(OsmTileSource(), TileDiskCache(File(app.cacheDir, "tiles"), TileDiskCache.PHONE_MAX_BYTES)))
    private val mapRenderer = GlassesMapRenderer(mapTiles)
    private val mapStreamer = GlassesMapStreamer(scope, clock, render = mapRenderer::render, send = { f, png -> glasses.pushMap(f, png) }, log = { Log.i("LiveFitMap", it) })
```

In `start()`, before `// ---- Link wiring ----`, add:

```kotlin
        // ---- Live map: route merge, phone fallback, glasses images (spec §2) ----
        routeHub.start()
        mapTiles.start()
        mapStreamer.start()
        scope.launch { workout.snapshot.collect { routeHub.onWorkout(it) } }
        scope.launch { watchGateway.deltas.collect { routeHub.onWatchDelta(it) } }
        scope.launch { routeHub.state.collect(mapStreamer::onRoute) }
        scope.launch {
            combine(routeHub.fallbackWanted, LiveFitHubService.locationCapable) { want, can -> want && can }.distinctUntilChanged()
                .collect { on -> if (on) phoneGps.start() else phoneGps.stop() }
        }
        scope.launch {
            glasses.status.map { it.link == LinkState.Connected }.distinctUntilChanged().collect { connected ->
                if (connected) mapStreamer.onConnected() else { mapStreamer.onDisconnected(); mapTiles.show(emptyList()) }
            }
        }
        scope.launch {
            glasses.pageStates.collect {
                mapStreamer.onPageState(it.page, it.seq)
                if (!mapStreamer.mapVisible) mapTiles.show(emptyList()) // off the Map page nothing is fetched or retried
            }
        }
```

- [ ] **Step 7: Build and test**

Run: `export JAVA_HOME=$(/usr/libexec/java_home -v 17) && ./gradlew :phone:assembleDebug :phone:testDebugUnitTest`
Expected: BUILD SUCCESSFUL, tests PASS. (Rendering, size and cadence are verified on the glasses in Task 25.)

- [ ] **Step 8: Commit**

```bash
git add phone
git commit -m "feat(phone): non-blocking glasses map renderer and wiring of route hub, phone fallback and lf_map stream"
```

---

### Task 19: Settings UI — Pages list and hierarchical Glasses gestures

**Files:**
- Create: `phone/src/main/java/com/debasish/livefit/phone/ui/list/sources/PagesSource.kt`
- Create: `phone/src/main/java/com/debasish/livefit/phone/ui/list/sources/GestureMenu.kt`
- Create: `phone/src/main/java/com/debasish/livefit/phone/ui/list/sources/GestureSource.kt`
- Modify: `phone/src/main/java/com/debasish/livefit/phone/ui/list/ListSource.kt`
- Modify: `phone/src/main/java/com/debasish/livefit/phone/ui/settings/SettingsScreen.kt`
- Test: `phone/src/test/java/com/debasish/livefit/phone/ui/list/sources/SettingsSourcesTest.kt`

**Interfaces:**
- Consumes: `ListSource`, `ListItem`, `ItemAction`, `ActionResult`, `ItemStatus` (existing); `SettingsStore.pages/setPageEnabled/gestures/changeGesture/setIdleTimeout/setAskBeforeClose/resetGestures` (Task 17); `GestureRules`, `GestureChange` (Task 6).
- Produces:
  - `ListSources.PAGES = "pages"`, `ListSources.GESTURES = "glasses-gestures"`
  - `class PagesSource(current: () -> PageSettings, setEnabled: (HudPage, Boolean) -> Unit) : ListSource`
  - `sealed interface GestureMenuLevel { Root; Pages(mode); Gestures(mode, page); Actions(mode, page, gesture); Idle }`, `data class MenuRow(id, title, subtitle?, toggle?, target: GestureMenuLevel?)`, `object GestureMenu { fun encode(level): String; fun decode(s: String?): GestureMenuLevel; fun title(level): String; fun rows(level, settings): List<MenuRow> }`
  - `class GestureSource(current, change, setIdle, setAsk, reset, open, routeFor: (menu: String) -> String) : ListSource { fun items(menu: String?): List<ListItem> }` — the route filter is `{"menu": "<GestureMenu.encode(level)>"}`.

- [ ] **Step 1: Write the failing test** — `phone/src/test/java/com/debasish/livefit/phone/ui/list/sources/SettingsSourcesTest.kt`:

```kotlin
package com.debasish.livefit.phone.ui.list.sources

import com.debasish.livefit.model.Gesture
import com.debasish.livefit.model.GestureAction
import com.debasish.livefit.model.GestureChange
import com.debasish.livefit.model.GestureRules
import com.debasish.livefit.model.GestureSettings
import com.debasish.livefit.model.HudPage
import com.debasish.livefit.model.PageSettings
import com.debasish.livefit.phone.ui.list.ActionResult
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class SettingsSourcesTest {
    // ---- Settings → Pages (spec §3.2) ----

    @Test fun oneSwitchPerPageWithWorkoutLockedOn() = runTest {
        var p = PageSettings(disabled = setOf(HudPage.Map))
        val s = PagesSource({ p }, { page, on -> p = PageSettings(if (on) p.disabled - page else p.disabled + page) })
        val items = s.load(null)
        assertEquals(HudPage.entries.map { it.label }, items.map { it.title })
        assertEquals("Glasses + Watch", items.first { it.id == "Glance" }.subtitle)
        assertEquals(false, items.first { it.id == "Map" }.toggle)
        assertNull(s.actionFor(items.first { it.id == "Workout" }), "Workout can't be turned off")
        s.actionFor(items.first { it.id == "Stats" })!!.run {}
        assertTrue(HudPage.Stats in p.disabled)
        s.actionFor(items.first { it.id == "Map" })!!.run {}
        assertFalse(HudPage.Map in p.disabled)
    }

    // ---- Settings → Glasses gestures (spec §4.4) ----

    private var g = GestureSettings()
    private val opened = mutableListOf<String>()
    private fun gestures() = GestureSource(
        current = { g },
        change = { m, p, ge, a -> GestureRules.change(g, m, p, ge, a).also { if (it is GestureChange.Applied) g = it.settings } },
        setIdle = { g = GestureRules.withIdleTimeout(g, it) },
        setAsk = { g = g.copy(askBeforeClose = it) },
        reset = { g = GestureSettings() },
        open = { opened += it },
        routeFor = { "menu=$it" },
    )

    @Test fun rootListsModesAndGeneral() {
        val items = gestures().items(null)
        assertEquals(listOf("Page mode", "Scroll mode", "Idle timeout", "Ask before closing during a workout", "Reset to defaults"), items.map { it.title })
        assertEquals("5 s · leaves scroll mode", items.first { it.id == "idle" }.subtitle)
        assertEquals(true, items.first { it.id == "ask" }.toggle)
    }

    @Test fun hierarchyPageModeThenPageThenGestureThenAction() = runTest {
        val s = gestures()
        s.actionFor(s.items(null).first { it.id == "page" })!!.run {}
        assertEquals("menu=pages:Page", opened.last())
        val pages = s.items("pages:Page")
        assertEquals(HudPage.entries.map { it.label }, pages.map { it.title })
        s.actionFor(pages.first { it.id == "Glance" })!!.run {}
        assertEquals("menu=gestures:Page:Glance", opened.last())
        val perGesture = s.items("gestures:Page:Glance")
        assertEquals(Gesture.entries.map { it.label }, perGesture.map { it.title })
        assertEquals("Talk (voice)", perGesture.first { it.id == "Tap" }.subtitle)
        s.actionFor(perGesture.first { it.id == "Tap" })!!.run {}
        assertEquals("menu=actions:Page:Glance:Tap", opened.last())
    }

    @Test fun scrollModeListsOnlyScrollPages() =
        assertEquals(listOf("Playlist", "Music controls"), gestures().items("pages:Scroll").map { it.title })

    @Test fun actionPickerOffersOnlyValidActionsAndMarksTheCurrentOne() {
        val items = gestures().items("actions:Scroll:MusicControls:Tap")
        val ids = items.map { it.id }
        assertTrue("PressSelected" in ids)
        assertFalse("PlayHighlighted" in ids)
        assertEquals(listOf("PressSelected"), items.filter { it.toggle == true }.map { it.id })
    }

    @Test fun unsafeChangeIsRefusedWithTheReason() = runTest {
        val s = gestures()
        val r = s.actionFor(s.items("actions:Page:Glance:DoubleTap").first { it.id == "Talk" })!!.run {}
        assertEquals(ActionResult.Failed("Glance needs a gesture for Close app"), r)
        assertEquals(GestureAction.CloseApp, g.page.getValue(HudPage.Glance).getValue(Gesture.DoubleTap))
    }

    @Test fun safeChangeApplies() = runTest {
        val s = gestures()
        assertEquals(ActionResult.Silent, s.actionFor(s.items("actions:Page:Glance:Tap").first { it.id == "NextSong" })!!.run {})
        assertEquals(GestureAction.NextSong, g.page.getValue(HudPage.Glance).getValue(Gesture.Tap))
    }

    @Test fun idleTimeoutPickerAndAskToggle() = runTest {
        val s = gestures()
        val idle = s.items("idle")
        assertEquals((3..15).map { "$it s" }, idle.map { it.title })
        s.actionFor(idle.first { it.id == "8" })!!.run {}
        assertEquals(8, g.idleTimeoutS)
        s.actionFor(s.items(null).first { it.id == "ask" })!!.run {}
        assertFalse(g.askBeforeClose)
    }

    @Test fun resetAsksFirstThenRestoresDefaults() = runTest {
        val s = gestures()
        g = GestureRules.withIdleTimeout(g, 12)
        val action = s.actionFor(s.items(null).first { it.id == "reset" })!!
        assertEquals("Reset glasses gestures?", action.confirmTitle)
        action.run {}
        assertEquals(GestureSettings(), g)
    }

    @Test fun unknownMenuFallsBackToRootAndTitlesFollowTheLevel() {
        assertEquals(GestureMenuLevel.Root, GestureMenu.decode("nonsense:1"))
        assertEquals("Map · page mode", GestureMenu.title(GestureMenu.decode("gestures:Page:Map")))
        assertEquals("actions:Scroll:Playlist:LongBack", GestureMenu.encode(GestureMenuLevel.Actions(com.debasish.livefit.model.GestureMode.Scroll, HudPage.Playlist, Gesture.LongBack)))
    }
}
```

- [ ] **Step 2: Run test to verify it fails**

Run: `export JAVA_HOME=$(/usr/libexec/java_home -v 17) && ./gradlew :phone:testDebugUnitTest --tests '*SettingsSourcesTest*'`
Expected: FAIL — `PagesSource`, `GestureSource`, `GestureMenu` unresolved.

- [ ] **Step 3: `PagesSource.kt`**

```kotlin
package com.debasish.livefit.phone.ui.list.sources

import com.debasish.livefit.model.HudPage
import com.debasish.livefit.model.PageSettings
import com.debasish.livefit.phone.ui.list.ActionResult
import com.debasish.livefit.phone.ui.list.ItemAction
import com.debasish.livefit.phone.ui.list.ListItem
import com.debasish.livefit.phone.ui.list.ListSource
import org.json.JSONObject

/** Settings → Pages (spec §3.2): one switch per page, in cycle order; Workout shown locked on. */
class PagesSource(private val current: () -> PageSettings, private val setEnabled: (HudPage, Boolean) -> Unit) : ListSource {
    override val title = "Pages"
    override val searchHint = "Search pages"
    override val sortable = false // cycle order

    override suspend fun load(filter: JSONObject?): List<ListItem> {
        val p = current()
        return HudPage.entries.map { page ->
            ListItem(
                id = page.name,
                title = page.label,
                subtitle = if (page == HudPage.Workout) "Glasses + Watch · always on" else "Glasses + Watch",
                glyph = page.label.take(1),
                toggle = p.isEnabled(page),
            )
        }
    }

    override fun actionFor(item: ListItem): ItemAction? {
        val page = HudPage.entries.firstOrNull { it.name == item.id }?.takeIf { it != HudPage.Workout } ?: return null
        return ItemAction { setEnabled(page, item.toggle != true); ActionResult.Silent }
    }
}
```

- [ ] **Step 4: `GestureMenu.kt`**

```kotlin
package com.debasish.livefit.phone.ui.list.sources

import com.debasish.livefit.model.Gesture
import com.debasish.livefit.model.GestureDefaults
import com.debasish.livefit.model.GestureMode
import com.debasish.livefit.model.GestureRules
import com.debasish.livefit.model.GestureSettings
import com.debasish.livefit.model.HudPage

/** Levels of Settings → Glasses gestures (spec §4.4): Page/Scroll mode → page → gesture → action; General rows on the root. */
sealed interface GestureMenuLevel {
    data object Root : GestureMenuLevel
    data class Pages(val mode: GestureMode) : GestureMenuLevel
    data class Gestures(val mode: GestureMode, val page: HudPage) : GestureMenuLevel
    data class Actions(val mode: GestureMode, val page: HudPage, val gesture: Gesture) : GestureMenuLevel
    data object Idle : GestureMenuLevel
}

data class MenuRow(val id: String, val title: String, val subtitle: String? = null, val toggle: Boolean? = null, val target: GestureMenuLevel? = null)

object GestureMenu {
    fun encode(l: GestureMenuLevel): String = when (l) {
        GestureMenuLevel.Root -> "root"
        is GestureMenuLevel.Pages -> "pages:${l.mode}"
        is GestureMenuLevel.Gestures -> "gestures:${l.mode}:${l.page}"
        is GestureMenuLevel.Actions -> "actions:${l.mode}:${l.page}:${l.gesture}"
        GestureMenuLevel.Idle -> "idle"
    }

    fun decode(s: String?): GestureMenuLevel = runCatching {
        val p = s.orEmpty().split(":")
        when (p[0]) {
            "pages" -> GestureMenuLevel.Pages(GestureMode.valueOf(p[1]))
            "gestures" -> GestureMenuLevel.Gestures(GestureMode.valueOf(p[1]), HudPage.valueOf(p[2]))
            "actions" -> GestureMenuLevel.Actions(GestureMode.valueOf(p[1]), HudPage.valueOf(p[2]), Gesture.valueOf(p[3]))
            "idle" -> GestureMenuLevel.Idle
            else -> GestureMenuLevel.Root
        }
    }.getOrDefault(GestureMenuLevel.Root)

    private fun modeName(m: GestureMode) = if (m == GestureMode.Page) "page mode" else "scroll mode"

    fun title(l: GestureMenuLevel): String = when (l) {
        GestureMenuLevel.Root -> "Glasses gestures"
        is GestureMenuLevel.Pages -> if (l.mode == GestureMode.Page) "Page mode" else "Scroll mode"
        is GestureMenuLevel.Gestures -> "${l.page.label} · ${modeName(l.mode)}"
        is GestureMenuLevel.Actions -> l.gesture.label
        GestureMenuLevel.Idle -> "Idle timeout"
    }

    fun rows(l: GestureMenuLevel, s: GestureSettings): List<MenuRow> = when (l) {
        GestureMenuLevel.Root -> listOf(
            MenuRow("page", "Page mode", "Per page: Glance, Workout, Stats, Playlist, Map, Music controls", target = GestureMenuLevel.Pages(GestureMode.Page)),
            MenuRow("scroll", "Scroll mode", "Playlist, Music controls", target = GestureMenuLevel.Pages(GestureMode.Scroll)),
            MenuRow("idle", "Idle timeout", "${s.idleTimeoutS} s · leaves scroll mode", target = GestureMenuLevel.Idle),
            MenuRow("ask", "Ask before closing during a workout", toggle = s.askBeforeClose),
            MenuRow("reset", "Reset to defaults", "All pages and modes"),
        )
        is GestureMenuLevel.Pages -> HudPage.entries.filter { l.mode == GestureMode.Page || it in GestureDefaults.SCROLL_PAGES }.map { p ->
            val t = GestureRules.table(s, l.mode, p)
            MenuRow(p.name, p.label, "Tap: ${t.getValue(Gesture.Tap).label} · Double tap: ${t.getValue(Gesture.DoubleTap).label}", target = GestureMenuLevel.Gestures(l.mode, p))
        }
        is GestureMenuLevel.Gestures -> {
            val t = GestureRules.table(s, l.mode, l.page)
            Gesture.entries.map { g -> MenuRow(g.name, g.label, t.getValue(g).label, target = GestureMenuLevel.Actions(l.mode, l.page, g)) }
        }
        is GestureMenuLevel.Actions -> {
            val current = GestureRules.table(s, l.mode, l.page).getValue(l.gesture)
            GestureRules.validActions(l.mode, l.page).map { a -> MenuRow(a.name, a.label, toggle = a == current) }
        }
        GestureMenuLevel.Idle -> (3..15).map { MenuRow("$it", "$it s", toggle = it == s.idleTimeoutS) }
    }
}
```

- [ ] **Step 5: `GestureSource.kt`**

```kotlin
package com.debasish.livefit.phone.ui.list.sources

import com.debasish.livefit.model.Gesture
import com.debasish.livefit.model.GestureAction
import com.debasish.livefit.model.GestureChange
import com.debasish.livefit.model.GestureMode
import com.debasish.livefit.model.GestureSettings
import com.debasish.livefit.model.HudPage
import com.debasish.livefit.phone.ui.list.ActionResult
import com.debasish.livefit.phone.ui.list.ItemAction
import com.debasish.livefit.phone.ui.list.ItemStatus
import com.debasish.livefit.phone.ui.list.ListItem
import com.debasish.livefit.phone.ui.list.ListSource
import org.json.JSONObject

/**
 * Settings → Glasses gestures (spec §4.4) on the generic list screen; each level is the same source with
 * filter {"menu": "<level>"}. A change that would leave a page without Close app or without a page move is refused
 * with the reason (the list shows it as a message); applied changes reach the glasses in the next settings frame.
 */
class GestureSource(
    private val current: () -> GestureSettings,
    private val change: (GestureMode, HudPage, Gesture, GestureAction) -> GestureChange,
    private val setIdle: (Int) -> Unit,
    private val setAsk: (Boolean) -> Unit,
    private val reset: () -> Unit,
    private val open: (String) -> Unit,
    private val routeFor: (menu: String) -> String,
) : ListSource {
    override val title = "Glasses gestures"
    override val searchHint = "Search gestures"
    override val sortable = false
    private var level: GestureMenuLevel = GestureMenuLevel.Root

    override fun titleFor(filter: JSONObject?): String = GestureMenu.title(GestureMenu.decode(filter?.optString("menu")))

    override suspend fun load(filter: JSONObject?): List<ListItem> = items(filter?.optString("menu"))

    fun items(menu: String?): List<ListItem> {
        level = GestureMenu.decode(menu)
        return GestureMenu.rows(level, current()).map {
            ListItem(id = it.id, title = it.title, subtitle = it.subtitle, glyph = it.title.take(1), toggle = it.toggle,
                status = if (it.target != null) ItemStatus.ActionNeeded else ItemStatus.None)
        }
    }

    override fun actionFor(item: ListItem): ItemAction? {
        val l = level
        val row = GestureMenu.rows(l, current()).firstOrNull { it.id == item.id } ?: return null
        row.target?.let { t -> return ItemAction { open(routeFor(GestureMenu.encode(t))); ActionResult.Silent } }
        return when (l) {
            GestureMenuLevel.Root -> when (row.id) {
                "ask" -> ItemAction { setAsk(item.toggle != true); ActionResult.Silent }
                "reset" -> ItemAction(confirmTitle = "Reset glasses gestures?", confirmMessage = "Every page and mode goes back to the default gestures.", confirmLabel = "Reset") {
                    reset(); ActionResult.Message("Gestures reset")
                }
                else -> null
            }
            is GestureMenuLevel.Actions -> ItemAction {
                when (val r = change(l.mode, l.page, l.gesture, GestureAction.valueOf(row.id))) {
                    is GestureChange.Applied -> ActionResult.Silent
                    is GestureChange.Refused -> ActionResult.Failed(r.reason)
                }
            }
            GestureMenuLevel.Idle -> ItemAction { setIdle(row.id.toInt()); ActionResult.Silent }
            else -> null
        }
    }
}
```

- [ ] **Step 6: Run test to verify it passes**

Run: `export JAVA_HOME=$(/usr/libexec/java_home -v 17) && ./gradlew :phone:testDebugUnitTest --tests '*SettingsSourcesTest*'`
Expected: PASS (10 tests).

- [ ] **Step 7: Register the sources** — in `ListSource.kt` add imports `com.debasish.livefit.phone.ui.AppActivity`, `com.debasish.livefit.phone.ui.list.sources.GestureSource`, `com.debasish.livefit.phone.ui.list.sources.PagesSource`; in `object ListSources` add:

```kotlin
    /** Settings → Pages (spec §3.2). */
    const val PAGES = "pages"
    /** Settings → Glasses gestures (spec §4.4), hierarchical via filter {"menu": …}. */
    const val GESTURES = "glasses-gestures"
```

and in `create(...)` before `else ->`:

```kotlin
        PAGES -> PagesSource({ services.settings.pages.value }, services.settings::setPageEnabled)
        GESTURES -> GestureSource(
            current = { services.settings.gestures.value },
            change = services.settings::changeGesture,
            setIdle = services.settings::setIdleTimeout,
            setAsk = services.settings::setAskBeforeClose,
            reset = services.settings::resetGestures,
            open = open,
            routeFor = { menu -> AppActivity.listRoute(GESTURES, JSONObject().put("menu", menu).toString()) },
        )
```

- [ ] **Step 8: Settings rows** — in `SettingsScreen.kt` add imports `androidx.compose.material.icons.rounded.TouchApp`, `androidx.compose.material.icons.rounded.ViewCarousel`, `com.debasish.livefit.phone.ui.AppActivity`, `com.debasish.livefit.phone.ui.list.ListSources`, and replace the "Glasses" group with:

```kotlin
        SectionLabel("Glasses")
        Group {
            ChipRow(Icons.Rounded.Dashboard, LiveFitColors.ChipSky, "Glasses display", "Size, position, metrics", { onNavigate("hud") })
            Divider()
            val pagesOff = services.settings.pages.collectAsStateWithLifecycle().value.disabled.size
            ChipRow(Icons.Rounded.ViewCarousel, LiveFitColors.ChipMint, "Pages", "Glasses + Watch · " + if (pagesOff == 0) "all on" else "$pagesOff turned off",
                { onNavigate(AppActivity.listRoute(ListSources.PAGES, filter = null)) })
            Divider()
            ChipRow(Icons.Rounded.TouchApp, LiveFitColors.ChipViolet, "Glasses gestures", "Per page and mode",
                { onNavigate(AppActivity.listRoute(ListSources.GESTURES, filter = null)) })
        }
```

- [ ] **Step 9: Build and test**

Run: `export JAVA_HOME=$(/usr/libexec/java_home -v 17) && ./gradlew :phone:assembleDebug :phone:testDebugUnitTest`
Expected: BUILD SUCCESSFUL, tests PASS.

- [ ] **Step 10: Commit**

```bash
git add phone
git commit -m "feat(phone): Settings → Pages and hierarchical Glasses gestures with safety refusals"
```

---

### Task 20: History detail route thumbnail

**Files:**
- Create: `core/map/src/main/kotlin/com/debasish/livefit/map/RouteThumbnail.kt`
- Modify: `phone/src/main/java/com/debasish/livefit/phone/ui/history/SessionDetailScreen.kt`
- Test: `core/map/src/test/kotlin/com/debasish/livefit/map/RouteThumbnailTest.kt`

**Interfaces:**
- Consumes: `Viewport.fit` (Task 2), `MapSceneBuilder.decimate` (Task 7), `RouteTrack.of(...).drawn()`, `RouteFix.historyPoint()` (Task 3), `RouteStore.routeFixes` (Task 13), `ServiceGraph.routes` (Task 17).
- Produces: `object RouteThumbnail { fun project(points: List<RoutePoint>, widthPx: Int, heightPx: Int, paddingPx: Int = 8): List<Px> }`

- [ ] **Step 1: Write the failing test** — `core/map/src/test/kotlin/com/debasish/livefit/map/RouteThumbnailTest.kt`:

```kotlin
package com.debasish.livefit.map

import com.debasish.livefit.model.FixSource
import com.debasish.livefit.model.RoutePoint
import kotlin.test.Test
import kotlin.test.assertTrue

class RouteThumbnailTest {
    private fun pt(n: Int) = RoutePoint(FixSource.Watch, 12.97 + n * 0.0001, 77.59 + n * 0.00005, 5f, n * 1_000L, n * 1_000L)

    /** Spec §2.2: history detail shows a static route thumbnail. */
    @Test fun routeFitsInsideThePadding() {
        val px = RouteThumbnail.project((0 until 300).map(::pt), 600, 300, paddingPx = 8)
        assertTrue(px.size in 2..600)
        assertTrue(px.all { it.x in 7.9f..592.1f && it.y in 7.9f..292.1f })
    }

    @Test fun noPointsNoThumbnail() = assertTrue(RouteThumbnail.project(emptyList(), 600, 300).isEmpty())
}
```

- [ ] **Step 2: Run test to verify it fails**

Run: `export JAVA_HOME=$(/usr/libexec/java_home -v 17) && ./gradlew :core:map:test --tests '*RouteThumbnailTest*'`
Expected: FAIL — `RouteThumbnail` unresolved.

- [ ] **Step 3: `RouteThumbnail.kt`**

```kotlin
package com.debasish.livefit.map

import com.debasish.livefit.model.RoutePoint

/** The whole route fitted into a box (history detail, spec §2.2); route only, no tiles. */
object RouteThumbnail {
    fun project(points: List<RoutePoint>, widthPx: Int, heightPx: Int, paddingPx: Int = 8): List<Px> {
        val vp = Viewport.fit(points.map { it.lat to it.lon }, widthPx, heightPx, paddingPx) ?: return emptyList()
        return MapSceneBuilder.decimate(points.map { vp.project(it.lat, it.lon) })
    }
}
```

- [ ] **Step 4: Run test to verify it passes**

Run: `export JAVA_HOME=$(/usr/libexec/java_home -v 17) && ./gradlew :core:map:test`
Expected: PASS.

- [ ] **Step 5: `SessionDetailScreen.kt`** — add imports `com.debasish.livefit.map.RouteThumbnail`, `com.debasish.livefit.model.RoutePoint`, `com.debasish.livefit.model.RouteTrack`, `androidx.compose.ui.geometry.Offset`. Add state next to `samples`:

```kotlin
    var route by remember { mutableStateOf<List<RoutePoint>>(emptyList()) }
```

inside the `LaunchedEffect`'s `runCatching { … }` add:

```kotlin
            // Rows never normalized (no successful time sync before the session ended) fall back to device time here only.
            route = RouteTrack.of(services.routes.routeFixes(sessionId).map { it.historyPoint() }).drawn()
```

and after the heart-rate `SoftCard { … }` add:

```kotlin
        if (route.size >= 2) {
            SectionLabel("Route")
            SoftCard(Modifier.padding(horizontal = 16.dp).fillMaxWidth().height(200.dp)) {
                Canvas(Modifier.fillMaxSize().padding(16.dp)) {
                    val px = RouteThumbnail.project(route, size.width.toInt(), size.height.toInt())
                    if (px.size < 2) return@Canvas
                    val path = Path().apply { moveTo(px[0].x, px[0].y); for (p in px.drop(1)) lineTo(p.x, p.y) }
                    drawPath(path, LiveFitColors.Mint, style = Stroke(4.dp.toPx(), cap = StrokeCap.Round))
                    drawCircle(LiveFitColors.Mint, 5.dp.toPx(), Offset(px.first().x, px.first().y), style = Stroke(2.dp.toPx()))
                    drawCircle(LiveFitColors.Mint, 5.dp.toPx(), Offset(px.last().x, px.last().y))
                }
            }
        }
```

- [ ] **Step 6: Build**

Run: `export JAVA_HOME=$(/usr/libexec/java_home -v 17) && ./gradlew :phone:assembleDebug`
Expected: BUILD SUCCESSFUL.

- [ ] **Step 7: Commit**

```bash
git add core/map phone
git commit -m "feat(history): static route thumbnail on the workout detail screen"
```

---

## Phase 3 — Glasses

### Task 21: Glasses HudNav v4 — gesture table resolution, scroll mode, page availability

**Files:**
- Modify (rewrite): `glasses/src/main/java/com/debasish/livefit/glasses/hud/HudNav.kt`
- Modify: `glasses/src/main/java/com/debasish/livefit/glasses/hud/DoubleTap.kt`
- Modify: `glasses/src/main/java/com/debasish/livefit/glasses/MainActivity.kt`
- Test (rewrite): `glasses/src/test/java/com/debasish/livefit/glasses/hud/HudNavTest.kt`; Create: `glasses/src/test/java/com/debasish/livefit/glasses/hud/CloseActionTest.kt`

**Interfaces:**
- Consumes: `PageSet` (Task 5); `GestureRules`, `GestureSettings`, `GestureDefaults`, `Gesture`, `GestureMode`, `GestureAction` (Tasks 1, 6); `Swipe` (existing `SwipeClassifier.kt`).
- Produces:
  - `fun Swipe.gesture(): Gesture`
  - `enum class MusicControl { Previous, PlayPause, Next, Back }`
  - `data class NavContext(queue: QueueWindow, gestures: GestureSettings, available: List<HudPage>)`
  - `data class NavOutcome(nav: HudNav, command: Command? = null, talk: Boolean = false, close: Boolean = false)`
  - `data class HudNav(page = Workout, mode = GestureMode.Page, highlightId: Long? = null, highlightBack = false, selector = MusicControl.PlayPause, lastInputMs = 0) { fun onGesture(g, ctx, nowMs): NavOutcome; fun go(target): HudNav; fun show(target, available): HudNav; fun reconcile(available): HudNav; fun timedOut(nowMs, idleMs): HudNav; fun resumeIdle(nowMs): HudNav; fun highlightRow(queue): Int?; fun visibleSelector(): MusicControl? }` — `highlightRow` = queue row index, or `queue.items.size` for the ✕ Back row, null outside Playlist scroll mode.
  - `data class IdleGate(paused: Boolean = false) { fun onOverlay(up: Boolean, nav: HudNav, nowMs: Long): Pair<IdleGate, HudNav>; fun deadlineMs(nav: HudNav, idleMs: Long): Long?; fun tick(nav: HudNav, idleMs: Long, nowMs: Long): HudNav }` — the scroll idle timer in one ordered path: a dismissed overlay restarts the timer **before** any deadline is computed; the deadline is a function of the current `idleMs`, so a new timeout applies without a gesture (review #9).
  - `CloseConfirm.onClose(phase: WorkoutPhase?, ask: Boolean, nowMs: Long): Pair<CloseConfirm, DoubleTapAction>`
  - `fun visibleRows(size, highlight, rows): IntRange` (unchanged)

- [ ] **Step 1: Write the failing tests** — replace `glasses/src/test/java/com/debasish/livefit/glasses/hud/HudNavTest.kt` with:

```kotlin
package com.debasish.livefit.glasses.hud

import com.debasish.livefit.model.Command
import com.debasish.livefit.model.Gesture
import com.debasish.livefit.model.GestureAction
import com.debasish.livefit.model.GestureChange
import com.debasish.livefit.model.GestureMode
import com.debasish.livefit.model.GestureRules
import com.debasish.livefit.model.GestureSettings
import com.debasish.livefit.model.HudPage
import com.debasish.livefit.model.PageSet
import com.debasish.livefit.model.PageSettings
import com.debasish.livefit.model.QueueItem
import com.debasish.livefit.model.QueueWindow
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue

class HudNavTest {
    private val q = QueueWindow((10L..14L).map { QueueItem(it, "Song $it") }, currentIndex = 2) // current = 12, Back row = 5
    private val all = PageSet.available(PageSettings(), mapEligible = true)
    private fun ctx(available: List<HudPage> = all, gestures: GestureSettings = GestureSettings(), queue: QueueWindow = q) = NavContext(queue, gestures, available)
    private fun HudNav.g(gesture: Gesture, c: NavContext = ctx(), now: Long = 0) = onGesture(gesture, c, now)
    private fun scrollOn(page: HudPage, now: Long = 0, c: NavContext = ctx()) = HudNav(page = page).g(Gesture.Tap, c, now).nav

    @Test fun startsOnWorkoutInPageMode() {
        assertEquals(HudPage.Workout, HudNav().page)
        assertEquals(GestureMode.Page, HudNav().mode)
    }

    @Test fun swipesMapToGestures() {
        assertEquals(Gesture.ShortForward, Swipe(forward = true, long = false).gesture())
        assertEquals(Gesture.ShortBack, Swipe(forward = false, long = false).gesture())
        assertEquals(Gesture.LongForward, Swipe(forward = true, long = true).gesture())
        assertEquals(Gesture.LongBack, Swipe(forward = false, long = true).gesture())
    }

    /** Spec §4.3: short swipe = next/previous page, long = ±2, cycling Glance → … → Music controls. */
    @Test fun defaultSwipesMovePagesInCycleOrder() {
        assertEquals(HudPage.Stats, HudNav().g(Gesture.ShortForward).nav.page)
        assertEquals(HudPage.Playlist, HudNav().g(Gesture.LongForward).nav.page)
        assertEquals(HudPage.MusicControls, HudNav(page = HudPage.Glance).g(Gesture.ShortBack).nav.page)
        assertEquals(HudPage.Workout, HudNav(page = HudPage.MusicControls).g(Gesture.LongForward).nav.page)
    }

    @Test fun disabledAndIneligiblePagesAreSkipped() {
        val c = ctx(available = PageSet.available(PageSettings(disabled = setOf(HudPage.Stats)), mapEligible = false))
        assertEquals(HudPage.Playlist, HudNav().g(Gesture.ShortForward, c).nav.page)
        assertEquals(HudPage.MusicControls, HudNav(page = HudPage.Playlist).g(Gesture.ShortForward, c).nav.page)
    }

    @Test fun tapIsTalkOnGlanceWorkoutStatsAndMap() {
        for (p in listOf(HudPage.Glance, HudPage.Workout, HudPage.Stats, HudPage.Map)) {
            val o = HudNav(page = p).g(Gesture.Tap)
            assertTrue(o.talk, "$p"); assertNull(o.command); assertEquals(HudNav(page = p), o.nav)
        }
    }

    @Test fun doubleTapClosesOnEveryPageAndInScrollMode() {
        for (p in HudPage.entries) assertTrue(HudNav(page = p).g(Gesture.DoubleTap).close, "$p")
        assertTrue(scrollOn(HudPage.Playlist).g(Gesture.DoubleTap).close)
        assertTrue(scrollOn(HudPage.MusicControls).g(Gesture.DoubleTap).close)
    }

    @Test fun tapOnPlaylistEntersScrollWithTheHighlightOnTheCurrentSong() {
        val n = scrollOn(HudPage.Playlist, now = 1_000)
        assertEquals(GestureMode.Scroll, n.mode)
        assertEquals(2, n.highlightRow(q))
        assertEquals(1_000L, n.lastInputMs)
        assertNull(HudNav(page = HudPage.Playlist).highlightRow(q), "page mode shows no highlight")
    }

    @Test fun scrollSwipesMoveTheHighlightAndStopAtTheBackRow() {
        var n = scrollOn(HudPage.Playlist)
        n = n.g(Gesture.ShortForward).nav; assertEquals(3, n.highlightRow(q))
        n = n.g(Gesture.LongForward).nav; assertEquals(5, n.highlightRow(q), "✕ Back row after the songs")
        n = n.g(Gesture.ShortForward).nav; assertEquals(5, n.highlightRow(q), "stops at the end")
        n = n.g(Gesture.LongBack).nav; assertEquals(3, n.highlightRow(q))
        repeat(5) { n = n.g(Gesture.ShortBack).nav }; assertEquals(0, n.highlightRow(q))
    }

    /** Spec §3.3: scroll mode stays after Play highlighted. */
    @Test fun playHighlightedStaysInScroll() {
        val onCurrent = scrollOn(HudPage.Playlist).g(Gesture.Tap)
        assertEquals(Command.PlayPause, onCurrent.command)
        assertEquals(GestureMode.Scroll, onCurrent.nav.mode)
        val other = scrollOn(HudPage.Playlist).g(Gesture.ShortForward).nav.g(Gesture.Tap)
        assertEquals(Command.PlayQueueItem(13), other.command)
        assertEquals(GestureMode.Scroll, other.nav.mode)
        assertEquals(3, other.nav.highlightRow(q))
    }

    @Test fun theBackRowExitsScroll() {
        var n = scrollOn(HudPage.Playlist)
        repeat(3) { n = n.g(Gesture.ShortForward).nav }
        val o = n.g(Gesture.Tap)
        assertNull(o.command)
        assertEquals(HudNav(page = HudPage.Playlist), o.nav)
    }

    @Test fun emptyQueueOffersOnlyBack() {
        val c = ctx(queue = QueueWindow())
        val n = scrollOn(HudPage.Playlist, c = c)
        assertEquals(0, n.highlightRow(QueueWindow()))
        assertEquals(GestureMode.Page, n.g(Gesture.Tap, c).nav.mode)
    }

    @Test fun musicControlsSelectorCyclesAndPresses() {
        var n = scrollOn(HudPage.MusicControls)
        assertEquals(MusicControl.PlayPause, n.visibleSelector())
        assertEquals(Command.PlayPause, n.g(Gesture.Tap).command)
        n = n.g(Gesture.ShortForward).nav; assertEquals(MusicControl.Next, n.visibleSelector())
        val next = n.g(Gesture.Tap); assertEquals(Command.NextTrack, next.command); assertEquals(GestureMode.Scroll, next.nav.mode)
        n = n.g(Gesture.ShortForward).nav; assertEquals(MusicControl.Back, n.visibleSelector())
        assertEquals(MusicControl.Previous, n.g(Gesture.ShortForward).nav.visibleSelector(), "the selector wraps")
        assertEquals(Command.PreviousTrack, n.g(Gesture.ShortForward).nav.g(Gesture.Tap).command)
        assertEquals(HudNav(page = HudPage.MusicControls), n.g(Gesture.Tap).nav, "✕ leaves scroll mode")
        assertNull(HudNav(page = HudPage.MusicControls).visibleSelector())
    }

    @Test fun musicControlsLongSwipesChangeVolume() {
        val n = scrollOn(HudPage.MusicControls)
        assertEquals(Command.Volume(up = true), n.g(Gesture.LongForward).command)
        assertEquals(Command.Volume(up = false), n.g(Gesture.LongBack).command)
    }

    /** Spec §3.3: any actual page change clears scroll mode and the highlight. */
    @Test fun pageChangeClearsScrollAndHighlight() {
        val custom = assertIs<GestureChange.Applied>(GestureRules.change(GestureSettings(), GestureMode.Scroll, HudPage.Playlist, Gesture.LongForward, GestureAction.NextPage)).settings
        val c = ctx(gestures = custom)
        val moved = scrollOn(HudPage.Playlist, c = c).g(Gesture.ShortForward, c).nav
        assertEquals(HudNav(page = HudPage.Map), moved.g(Gesture.LongForward, c).nav)
    }

    /** Spec §3.3: the idle timer restarts on every gesture handled in scroll mode. */
    @Test fun idleTimeoutExitsScrollAndRestartsOnEveryScrollGesture() {
        val n = scrollOn(HudPage.Playlist, now = 0).g(Gesture.ShortForward, now = 4_000).nav
        assertEquals(GestureMode.Scroll, n.timedOut(8_999, idleMs = 5_000).mode)
        assertEquals(HudNav(page = HudPage.Playlist), n.timedOut(9_000, idleMs = 5_000))
        assertEquals(n, n.timedOut(4_500, 5_000), "not idle yet")
    }

    /** Spec §3.3: confirmations pause the timer; it restarts when they close. */
    @Test fun resumeIdleRestartsTheTimerAfterAConfirmation() {
        val n = scrollOn(HudPage.MusicControls, now = 0).resumeIdle(20_000)
        assertEquals(GestureMode.Scroll, n.timedOut(24_999, 5_000).mode)
        assertEquals(HudNav(page = HudPage.Workout), HudNav().resumeIdle(20_000), "no timer in page mode")
    }

    /** Review #9: a confirmation longer than the idle timeout; after it closes Scroll mode lasts the full resumed interval. */
    @Test fun confirmationLongerThanTheTimeoutResumesTheFullInterval() {
        var nav = scrollOn(HudPage.Playlist, now = 0)
        var gate = IdleGate()
        gate.onOverlay(true, nav, 1_000).let { (g, n) -> gate = g; nav = n }
        assertNull(gate.deadlineMs(nav, 5_000), "paused while the confirmation is up")
        assertEquals(GestureMode.Scroll, gate.tick(nav, 5_000, 19_999).mode)
        gate.onOverlay(false, nav, 20_000).let { (g, n) -> gate = g; nav = n }
        assertEquals(25_000L, gate.deadlineMs(nav, 5_000), "deadline computed after the resume")
        assertEquals(GestureMode.Scroll, gate.tick(nav, 5_000, 20_000).mode, "not dropped at the moment of dismissal")
        assertEquals(GestureMode.Scroll, gate.tick(nav, 5_000, 24_999).mode)
        assertEquals(HudNav(page = HudPage.Playlist), gate.tick(nav, 5_000, 25_000))
    }

    /** Review #9: the phone changes the idle timeout while the wearer scrolls; it applies without another gesture. */
    @Test fun changingTheTimeoutWhileScrollingAppliesWithoutAGesture() {
        val nav = scrollOn(HudPage.MusicControls, now = 0)
        val gate = IdleGate()
        assertEquals(GestureMode.Scroll, gate.tick(nav, 15_000, 6_000).mode)
        assertEquals(HudNav(page = HudPage.MusicControls), gate.tick(nav, 5_000, 6_000), "a shorter timeout has already elapsed")
        assertEquals(10_000L, gate.deadlineMs(nav, 10_000), "a longer one moves the deadline out")
        assertNull(IdleGate().deadlineMs(HudNav(page = HudPage.MusicControls), 5_000), "no timer in page mode")
    }

    /** Review Focus #5 / spec §3.3: the visible page disappears → Workout immediately, page mode, nothing highlighted. */
    @Test fun disablingVisibleScrollPageReturnsToWorkoutInPageMode() {
        val n = scrollOn(HudPage.Playlist).g(Gesture.ShortForward).nav
        assertEquals(HudNav(), n.reconcile(PageSet.available(PageSettings(disabled = setOf(HudPage.Playlist)), mapEligible = true)))
        assertEquals(HudNav(), HudNav(page = HudPage.Map).reconcile(PageSet.available(PageSettings(), mapEligible = false)), "GPS workout ended")
        assertEquals(n, n.reconcile(all), "still available: untouched")
    }

    /** Spec §4.4: a custom mapping changes what each gesture does. */
    @Test fun customMappingAppliedLive() {
        val s1 = assertIs<GestureChange.Applied>(GestureRules.change(GestureSettings(), GestureMode.Page, HudPage.Glance, Gesture.Tap, GestureAction.CloseApp)).settings
        val s2 = assertIs<GestureChange.Applied>(GestureRules.change(s1, GestureMode.Page, HudPage.Glance, Gesture.DoubleTap, GestureAction.NextSong)).settings
        val c = ctx(gestures = s2)
        assertTrue(HudNav(page = HudPage.Glance).g(Gesture.Tap, c).close)
        assertEquals(Command.NextTrack, HudNav(page = HudPage.Glance).g(Gesture.DoubleTap, c).command)
        assertTrue(HudNav(page = HudPage.Workout).g(Gesture.Tap, c).talk, "other pages keep their table")
    }

    @Test fun voiceShowGoesToAvailablePagesOnlyAndIsIdempotent() {
        val noMap = PageSet.available(PageSettings(), mapEligible = false)
        assertEquals(HudNav(), HudNav().show(HudPage.Map, noMap))
        assertEquals(HudNav(page = HudPage.Stats), HudNav().show(HudPage.Stats, noMap))
        val scrolling = scrollOn(HudPage.Playlist).g(Gesture.ShortForward).nav
        assertEquals(scrolling, scrolling.show(HudPage.Playlist, all), "same page again: nothing changes")
    }

    @Test fun visibleRowsKeepTheHighlightInView() {
        assertEquals(0 until 3, visibleRows(size = 3, highlight = 1, rows = 7))
        assertEquals(0 until 7, visibleRows(size = 25, highlight = 0, rows = 7))
        assertEquals(7 until 14, visibleRows(size = 25, highlight = 10, rows = 7))
        assertEquals(18 until 25, visibleRows(size = 25, highlight = 24, rows = 7))
        assertEquals(IntRange.EMPTY, visibleRows(size = 0, highlight = null, rows = 7))
    }
}
```

`glasses/src/test/java/com/debasish/livefit/glasses/hud/CloseActionTest.kt`:

```kotlin
package com.debasish.livefit.glasses.hud

import com.debasish.livefit.model.WorkoutPhase
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class CloseActionTest {
    /** Spec §4.4 General: "Ask before closing during a workout" (on/off). */
    @Test fun asksOnlyWhileRecordingAndWhenEnabled() {
        val (asked, a1) = CloseConfirm().onClose(WorkoutPhase.Active, ask = true, nowMs = 5)
        assertEquals(DoubleTapAction.AskClose, a1); assertTrue(asked.shown)
        val (left, a2) = CloseConfirm().onClose(WorkoutPhase.Active, ask = false, nowMs = 5)
        assertEquals(DoubleTapAction.Leave, a2); assertFalse(left.shown)
        assertEquals(DoubleTapAction.Leave, CloseConfirm().onClose(WorkoutPhase.Summary, ask = true, nowMs = 5).second)
    }
}
```

- [ ] **Step 2: Run tests to verify they fail**

Run: `export JAVA_HOME=$(/usr/libexec/java_home -v 17) && ./gradlew :glasses:testDebugUnitTest --tests '*HudNavTest*' --tests '*CloseActionTest*'`
Expected: FAIL — `NavContext`, `onGesture`, `gesture()`, `onClose` unresolved.

- [ ] **Step 3: Rewrite `HudNav.kt`**

```kotlin
package com.debasish.livefit.glasses.hud

import com.debasish.livefit.model.Command
import com.debasish.livefit.model.Gesture
import com.debasish.livefit.model.GestureAction
import com.debasish.livefit.model.GestureDefaults
import com.debasish.livefit.model.GestureMode
import com.debasish.livefit.model.GestureRules
import com.debasish.livefit.model.GestureSettings
import com.debasish.livefit.model.HudPage
import com.debasish.livefit.model.PageSet
import com.debasish.livefit.model.QueueWindow

/** One classified swipe as a configurable gesture (spec §4.1). */
fun Swipe.gesture(): Gesture = when {
    forward && long -> Gesture.LongForward
    forward -> Gesture.ShortForward
    long -> Gesture.LongBack
    else -> Gesture.ShortBack
}

/** Music controls selector, in order (spec §4.3: ⏮ ⏯ ⏭ ✕). */
enum class MusicControl { Previous, PlayPause, Next, Back }

/** What a gesture is resolved against: the queue, the (sanitized) gesture table and the pages available now. */
data class NavContext(val queue: QueueWindow, val gestures: GestureSettings, val available: List<HudPage>)

/** A gesture's result: the new [nav], a [command] for the hub, push-to-talk, or the Close app action. */
data class NavOutcome(val nav: HudNav, val command: Command? = null, val talk: Boolean = false, val close: Boolean = false)

/**
 * Glasses navigation outside confirmations (spec §3.3, §4). Every gesture resolves through the table for the current
 * page and mode (Page mode on every page, Scroll mode on Playlist and Music controls). Any actual page change clears
 * scroll mode, the highlight and the selector; scroll mode stays after Play highlighted / Press selected; the idle
 * timer restarts on every gesture handled in scroll mode. The Playlist highlight is kept as a queue id (it stays on its
 * song when the window shifts) and can rest on the ✕ Back row after the songs.
 */
data class HudNav(
    val page: HudPage = HudPage.Workout,
    val mode: GestureMode = GestureMode.Page,
    /** Song the highlight is on; null = the current song. */
    val highlightId: Long? = null,
    /** The highlight is on the ✕ Back row. */
    val highlightBack: Boolean = false,
    val selector: MusicControl = MusicControl.PlayPause,
    /** Last gesture handled in scroll mode (wall clock ms), for the idle timeout. */
    val lastInputMs: Long = 0,
) {
    fun onGesture(g: Gesture, ctx: NavContext, nowMs: Long): NavOutcome {
        val action = GestureRules.table(ctx.gestures, mode, page)[g] ?: GestureAction.None
        val base = if (mode == GestureMode.Scroll) copy(lastInputMs = nowMs) else this
        return base.perform(action, ctx, nowMs)
    }

    private fun perform(a: GestureAction, ctx: NavContext, nowMs: Long): NavOutcome = when (a) {
        GestureAction.None -> NavOutcome(this)
        GestureAction.Talk -> NavOutcome(this, talk = true)
        GestureAction.CloseApp -> NavOutcome(this, close = true)
        GestureAction.NextPage -> NavOutcome(go(PageSet.step(page, 1, ctx.available)))
        GestureAction.PreviousPage -> NavOutcome(go(PageSet.step(page, -1, ctx.available)))
        GestureAction.NextPage2 -> NavOutcome(go(PageSet.step(page, 2, ctx.available)))
        GestureAction.PreviousPage2 -> NavOutcome(go(PageSet.step(page, -2, ctx.available)))
        GestureAction.EnterScroll ->
            NavOutcome(if (page in GestureDefaults.SCROLL_PAGES) HudNav(page = page, mode = GestureMode.Scroll, lastInputMs = nowMs) else this)
        GestureAction.ExitScroll -> NavOutcome(pageMode())
        GestureAction.HighlightNext -> NavOutcome(moveHighlight(1, ctx.queue))
        GestureAction.HighlightPrevious -> NavOutcome(moveHighlight(-1, ctx.queue))
        GestureAction.HighlightNext2 -> NavOutcome(moveHighlight(2, ctx.queue))
        GestureAction.HighlightPrevious2 -> NavOutcome(moveHighlight(-2, ctx.queue))
        GestureAction.PlayHighlighted -> playHighlighted(ctx.queue)
        GestureAction.SelectorNext -> NavOutcome(moveSelector(1))
        GestureAction.SelectorPrevious -> NavOutcome(moveSelector(-1))
        GestureAction.PressSelected -> pressSelected()
        GestureAction.PlayPause -> NavOutcome(this, Command.PlayPause)
        GestureAction.NextSong -> NavOutcome(this, Command.NextTrack)
        GestureAction.PreviousSong -> NavOutcome(this, Command.PreviousTrack)
        GestureAction.VolumeUp -> NavOutcome(this, Command.Volume(up = true))
        GestureAction.VolumeDown -> NavOutcome(this, Command.Volume(up = false))
        GestureAction.LikeSong -> NavOutcome(this, Command.LikeTrack)
    }

    /** Go to [target]; an actual change starts the new page fresh in page mode. */
    fun go(target: HudPage): HudNav = if (target == page) this else HudNav(page = target)

    /** Voice page views (lf_page): only to an available page; the same page again changes nothing. */
    fun show(target: HudPage, available: List<HudPage>): HudNav = if (target in available) go(target) else this

    /** The visible page became unavailable (disabled, Map ineligible) → Workout (spec §3.3). */
    fun reconcile(available: List<HudPage>): HudNav = go(PageSet.resolve(page, available))

    fun timedOut(nowMs: Long, idleMs: Long): HudNav = if (mode == GestureMode.Scroll && nowMs - lastInputMs >= idleMs) pageMode() else this

    /** A confirmation closed: the paused idle timer starts again. */
    fun resumeIdle(nowMs: Long): HudNav = if (mode == GestureMode.Scroll) copy(lastInputMs = nowMs) else this

    /** Playlist scroll mode only: a queue row, or `queue.items.size` for the ✕ Back row. */
    fun highlightRow(queue: QueueWindow): Int? {
        if (mode != GestureMode.Scroll || page != HudPage.Playlist) return null
        if (highlightBack || queue.items.isEmpty()) return queue.items.size
        return queue.items.indexOfFirst { it.queueId == highlightId }.takeIf { it >= 0 } ?: queue.currentIndex ?: 0
    }

    fun visibleSelector(): MusicControl? = if (mode == GestureMode.Scroll && page == HudPage.MusicControls) selector else null

    private fun pageMode() = HudNav(page = page)

    private fun moveHighlight(d: Int, queue: QueueWindow): HudNav {
        val row = highlightRow(queue) ?: return this
        val next = (row + d).coerceIn(0, queue.items.size)
        return if (next == queue.items.size) copy(highlightId = null, highlightBack = true)
        else copy(highlightId = queue.items[next].queueId, highlightBack = false)
    }

    private fun playHighlighted(queue: QueueWindow): NavOutcome {
        val row = highlightRow(queue) ?: return NavOutcome(this)
        if (row == queue.items.size) return NavOutcome(pageMode()) // ✕ Back
        val item = queue.items[row]
        val cmd = if (row == queue.currentIndex) Command.PlayPause else Command.PlayQueueItem(item.queueId)
        return NavOutcome(copy(highlightId = item.queueId, highlightBack = false), cmd)
    }

    private fun moveSelector(d: Int): HudNav {
        if (visibleSelector() == null) return this
        val n = MusicControl.entries.size
        return copy(selector = MusicControl.entries[Math.floorMod(selector.ordinal + d, n)])
    }

    private fun pressSelected(): NavOutcome = when (visibleSelector()) {
        null -> NavOutcome(this)
        MusicControl.Previous -> NavOutcome(this, Command.PreviousTrack)
        MusicControl.PlayPause -> NavOutcome(this, Command.PlayPause)
        MusicControl.Next -> NavOutcome(this, Command.NextTrack)
        MusicControl.Back -> NavOutcome(pageMode())
    }
}

/** The [rows] list rows shown around [highlight] (centred where possible) out of [size]. */
fun visibleRows(size: Int, highlight: Int?, rows: Int): IntRange {
    if (size <= 0) return IntRange.EMPTY
    val start = ((highlight ?: 0) - rows / 2).coerceIn(0, maxOf(0, size - rows))
    return start until minOf(size, start + rows)
}

/**
 * Scroll-mode idle timer with confirmation pauses (spec §3.3), as one ordered path (review #9): an overlay change goes
 * through [onOverlay] first — a dismissal restarts the timer via [HudNav.resumeIdle] — and only then is a deadline
 * computed, from the gate and the *current* idle timeout. So a long confirmation never drops the wearer out of Scroll
 * mode at dismissal, and a new timeout from the phone applies without another gesture.
 */
data class IdleGate(val paused: Boolean = false) {
    fun onOverlay(up: Boolean, nav: HudNav, nowMs: Long): Pair<IdleGate, HudNav> =
        IdleGate(paused = up) to if (paused && !up) nav.resumeIdle(nowMs) else nav

    /** When Scroll mode times out with [idleMs]; null in page mode or while an overlay pauses the timer. */
    fun deadlineMs(nav: HudNav, idleMs: Long): Long? = if (!paused && nav.mode == GestureMode.Scroll) nav.lastInputMs + idleMs else null

    /** [nav] after the timer at [nowMs]. */
    fun tick(nav: HudNav, idleMs: Long, nowMs: Long): HudNav =
        deadlineMs(nav, idleMs)?.takeIf { nowMs >= it }?.let { nav.timedOut(nowMs, idleMs) } ?: nav
}
```

- [ ] **Step 4: `DoubleTap.kt`** — add to `data class CloseConfirm` (after `onDoubleTap`):

```kotlin
    /** The Close app action, whatever gesture is mapped to it: ask while a workout records and [ask] is on, else leave. */
    fun onClose(phase: WorkoutPhase?, ask: Boolean, nowMs: Long): Pair<CloseConfirm, DoubleTapAction> =
        if (ask && phase in RECORDING) show(nowMs) to DoubleTapAction.AskClose else CloseConfirm() to DoubleTapAction.Leave
```

- [ ] **Step 5: Run tests to verify they pass**

Run: `export JAVA_HOME=$(/usr/libexec/java_home -v 17) && ./gradlew :glasses:testDebugUnitTest --tests '*HudNavTest*' --tests '*CloseActionTest*'`
Expected: PASS.

- [ ] **Step 6: `MainActivity.kt` — dispatch every gesture through the table.** Add imports:

```kotlin
import com.debasish.livefit.glasses.hud.IdleGate
import com.debasish.livefit.glasses.hud.NavContext
import com.debasish.livefit.glasses.hud.gesture
import com.debasish.livefit.model.Gesture
import com.debasish.livefit.model.GestureMode
import com.debasish.livefit.model.GestureSettings
import com.debasish.livefit.model.HudPage
import com.debasish.livefit.model.PageSet
import com.debasish.livefit.model.PageSettings
import com.debasish.livefit.model.WorkoutSnapshot
```

Add these members (Task 22 replaces the bodies of `gestures()` and `pageSettings()` with the received settings):

```kotlin
    private fun gestures(): GestureSettings = GestureSettings()
    private fun pageSettings(): PageSettings = PageSettings()
    /** Scroll idle timer pause state (review #9); changed only through IdleGate.onOverlay. */
    private var idleGate by mutableStateOf(IdleGate())
    private fun availablePages(): List<HudPage> =
        PageSet.available(pageSettings(), PageSet.mapEligible(controller.frame.value?.workout ?: WorkoutSnapshot()))
    private fun navContext() = NavContext(controller.queue.value, gestures(), availablePages())
    private fun updateNav(next: HudNav) { nav = next }

    /** Priority (spec §4.2): hub confirmation, then our close prompt, then the configurable table. */
    private fun onGesture(g: Gesture) {
        Log.d(TAG, "gesture $g")
        val now = System.currentTimeMillis()
        val pending = confirmInput.onConfirmation(controller.frame.value?.confirmation).let { confirmInput.hasPending }
        when {
            pending -> when (g) {
                Gesture.Tap -> confirmInput.onTap()?.let { controller.send(it); ptt.stop() }
                Gesture.DoubleTap -> confirmInput.onBack()?.let { controller.send(it); ptt.stop() }
                else -> { confirmInput.onSwipe(); highlightYes = confirmInput.highlightYes }
            }
            closeConfirm.shown -> when (g) {
                Gesture.Tap -> closeConfirm.onTap().let { (c, close) -> closeConfirm = c; if (close == true) closeApp() }
                Gesture.DoubleTap -> closeConfirm = CloseConfirm() // double-tap = stay
                else -> closeConfirm = closeConfirm.onSwipe()
            }
            else -> {
                val out = nav.onGesture(g, navContext(), now)
                updateNav(out.nav)
                out.command?.let(controller::send)
                if (out.talk && controller.connection.value != HudConnection.Outdated) ptt.toggle() // the hub ignores voice from a mismatched app
                if (out.close) {
                    val (c, action) = closeConfirm.onClose(controller.frame.value?.workout?.phase, gestures().askBeforeClose, now)
                    closeConfirm = c
                    if (action == DoubleTapAction.Leave) closeApp()
                }
            }
        }
    }
```

Replace `onSwipe(swipe: Swipe)` with:

```kotlin
    private fun onSwipe(swipe: Swipe) = onGesture(swipe.gesture())
```

Replace `onKeyUp(...)` with:

```kotlin
    override fun onKeyUp(keyCode: Int, event: KeyEvent): Boolean = when (keyCode) {
        KeyEvent.KEYCODE_ENTER, KeyEvent.KEYCODE_DPAD_CENTER -> { onGesture(Gesture.Tap); true }
        else -> super.onKeyUp(keyCode, event)
    }
```

Replace `onDoubleTap()` with:

```kotlin
    /** Double-tap (two key-83 presses or BACK) is a configurable gesture like any other (spec §4.4). */
    private fun onDoubleTap() = onGesture(Gesture.DoubleTap)
```

In `closeApp()` replace `nav = HudNav()` with `updateNav(HudNav())`. In the `HudController(...)` construction replace `onPage = { p -> runOnUiThread { nav = nav.show(p) } }` with `onPage = { p -> runOnUiThread { updateNav(nav.show(p, availablePages())) } }`.

Inside `setContent { … }` replace the `LaunchedEffect(nav.highlightId, nav.lastInputMs) { … }` block with:

```kotlin
            // Scroll-mode idle timeout (spec §4.2/§3.3), one ordered path (review #9): an overlay change updates the gate
            // (a dismissal restarts the timer) before the timer effect — keyed on the gate, the input time and the
            // timeout — computes its deadline. Task 22 makes idleMs collected state, so a new timeout re-keys the effect.
            val overlayUp = frame?.confirmation != null || closeConfirm.shown
            val idleMs = gestures().idleTimeoutS * 1_000L
            androidx.compose.runtime.LaunchedEffect(overlayUp) {
                val (gate, resumed) = idleGate.onOverlay(overlayUp, nav, System.currentTimeMillis())
                idleGate = gate
                updateNav(resumed)
            }
            androidx.compose.runtime.LaunchedEffect(nav.mode, nav.lastInputMs, idleGate, idleMs) {
                val deadline = idleGate.deadlineMs(nav, idleMs) ?: return@LaunchedEffect
                kotlinx.coroutines.delay((deadline - System.currentTimeMillis()).coerceAtLeast(0))
                updateNav(idleGate.tick(nav, idleMs, System.currentTimeMillis()))
            }
            // The visible page disappears (disabled, GPS workout ended) → Workout at once (spec §3.3).
            val available = PageSet.available(pageSettings(), PageSet.mapEligible(frame?.workout ?: WorkoutSnapshot()))
            androidx.compose.runtime.LaunchedEffect(available) { updateNav(nav.reconcile(available)) }
```

and in the `HudScreen(...)` call replace `musicHighlight = nav.visibleHighlight(queue)` with `musicHighlight = nav.highlightRow(queue)`.

- [ ] **Step 7: Build and run all glasses tests**

Run: `export JAVA_HOME=$(/usr/libexec/java_home -v 17) && ./gradlew :glasses:assembleDebug :glasses:testDebugUnitTest`
Expected: BUILD SUCCESSFUL, tests PASS.

- [ ] **Step 8: Commit**

```bash
git add glasses
git commit -m "feat(glasses): resolve every touchpad gesture through the per-page/mode table, scroll mode and page availability"
```

---

### Task 22: Glasses controller — received pages/gestures, page-state reporting, `lf_map` images

**Files:**
- Create: `glasses/src/main/java/com/debasish/livefit/glasses/hud/PageReporter.kt`
- Create: `glasses/src/main/java/com/debasish/livefit/glasses/hud/MapPayload.kt`
- Modify: `glasses/src/main/java/com/debasish/livefit/glasses/hud/HudController.kt`
- Modify: `glasses/src/main/java/com/debasish/livefit/glasses/MainActivity.kt`
- Modify: `glasses/build.gradle.kts`
- Test: `glasses/src/test/java/com/debasish/livefit/glasses/hud/PageReporterTest.kt`, `glasses/src/test/java/com/debasish/livefit/glasses/hud/PageReportRetryTest.kt`, `glasses/src/test/java/com/debasish/livefit/glasses/hud/MapPayloadTest.kt`

**Interfaces:**
- Consumes: `HudSettingsFrame.pages/gestures`, `PageState`, `MapFrame`, `MapFrameKind`, `GlassesChannels.MAP/PAGE_STATE` (Task 1); `GestureRules.sanitized/problems` (Task 6); `MapImageGate`, `GlassesMapStreamer` (Task 9, the latter in tests); `HudNav`, `NavContext`, `IdleGate` and the `gestures()`/`pageSettings()`/`updateNav()` members of `MainActivity` (Task 21); `CxrGlassesLink.pushMap` and the `map_probe` debug command (Task 15) for device check D1.
- Produces:
  - `class PageReporter(send: (String) -> Boolean) { val page: HudPage; val hasPending: Boolean; fun onPage(p: HudPage); fun resend(); fun retryPending() }` — `send` returns false when the bridge refused the message; the latest unsent state is kept (replacing any older pending one) and re-sent by `retryPending()` every second on the same connection (review #10).
  - `HudController.sendRaw(channel, text): Boolean` (= `CXRServiceBridge.sendMessage(...) == 0`, CXR-S docs: 0 = sent).
  - `object MapPayload { fun png(frame: MapFrame, bytes: ByteArray?): ByteArray?; fun crc(bytes: ByteArray?): String }` (`crc` for the D1 arrival log).
  - `class MapImage(sessionId: String, png: ByteArray, seq: Long)`
  - `HudController.pages: StateFlow<PageSettings>`, `.gestures: StateFlow<GestureSettings>` (sanitized against the last valid table), `.mapImage: StateFlow<MapImage?>`, `fun reportPage(page: HudPage)`, `fun onPhoneConnected()`

- [ ] **Step 1: Write the failing tests** — in `glasses/build.gradle.kts` add `testImplementation("org.jetbrains.kotlinx:kotlinx-coroutines-test:1.9.0")` next to the other test dependencies.

`glasses/src/test/java/com/debasish/livefit/glasses/hud/PageReporterTest.kt`:

```kotlin
package com.debasish.livefit.glasses.hud

import com.debasish.livefit.model.HudPage
import com.debasish.livefit.model.PageState
import com.debasish.livefit.model.Wire
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class PageReporterTest {
    /** Spec §2.5: lf_page_state on every page change and on every connect/reconnect. */
    @Test fun reportsEveryChangeAndEveryResendWithAGrowingSeq() {
        val sent = mutableListOf<PageState>()
        val r = PageReporter { sent += Wire.decode<PageState>(it); true }
        r.onPage(HudPage.Workout)
        r.onPage(HudPage.Workout)
        r.onPage(HudPage.Map)
        r.resend()
        assertEquals(listOf(HudPage.Workout to 1L, HudPage.Map to 2L, HudPage.Map to 3L), sent.map { it.page to it.seq })
        assertEquals(HudPage.Map, r.page)
    }

    @Test fun aFailingSendNeverThrows() {
        val r = PageReporter { error("not connected") }
        r.onPage(HudPage.Map)
        assertTrue(r.hasPending, "a throwing send counts as unsent")
    }

    /** Review #10: only the latest unsent state is retried (older pending ones are obsolete), and one success clears it. */
    @Test fun onlyTheLatestUnsentPageIsRetried() {
        val sent = mutableListOf<PageState>()
        var up = false
        val r = PageReporter { if (up) { sent += Wire.decode<PageState>(it); true } else false }
        r.onPage(HudPage.Map); r.onPage(HudPage.Stats); r.onPage(HudPage.Glance)
        up = true
        r.retryPending(); r.retryPending()
        assertEquals(listOf(HudPage.Glance), sent.map { it.page })
        assertFalse(r.hasPending)
    }

    /** Review #10: a failed report of the same page is re-sent even though the page did not change. */
    @Test fun samePageIsReReportedWhileItsLastSendFailed() {
        var ok = false
        val sent = mutableListOf<HudPage>()
        val r = PageReporter { if (ok) { sent += Wire.decode<PageState>(it).page; true } else false }
        r.onPage(HudPage.Map)
        ok = true
        r.onPage(HudPage.Map)
        assertEquals(listOf(HudPage.Map), sent)
        r.onPage(HudPage.Map)
        assertEquals(listOf(HudPage.Map), sent, "once sent, an unchanged page is not repeated")
    }
}
```

`glasses/src/test/java/com/debasish/livefit/glasses/hud/PageReportRetryTest.kt` (the glasses reporter against the real phone streamer):

```kotlin
package com.debasish.livefit.glasses.hud

import com.debasish.livefit.model.FixSource
import com.debasish.livefit.model.GpsStatus
import com.debasish.livefit.model.HudPage
import com.debasish.livefit.model.LivePosition
import com.debasish.livefit.model.MapFrame
import com.debasish.livefit.model.MapFrameKind
import com.debasish.livefit.model.PageState
import com.debasish.livefit.model.RouteState
import com.debasish.livefit.model.Wire
import com.debasish.livefit.services.Clock
import com.debasish.livefit.sync.GlassesMapStreamer
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

@OptIn(ExperimentalCoroutinesApi::class)
class PageReportRetryTest {
    private class Link(scope: TestScope) {
        /** False = the CXR bridge refuses lf_page_state (sendMessage != 0). */
        var up = true
        val images = mutableListOf<MapFrame>()
        val streamer = GlassesMapStreamer(
            scope.backgroundScope, Clock { scope.testScheduler.currentTime },
            render = { byteArrayOf(1) },
            send = { f, _ -> if (f.kind == MapFrameKind.Image) images += f; true },
            newEpoch = { 7L },
        )
        val reporter = PageReporter { json -> if (!up) false else { Wire.decode<PageState>(json).let { streamer.onPageState(it.page, it.seq) }; true } }

        fun connect() {
            streamer.start()
            streamer.onRoute(RouteState(sessionId = "s", live = LivePosition(12.97, 77.59, null, FixSource.Watch, 0), status = GpsStatus.Live))
            streamer.onConnected()
        }
    }

    /** Review #10: the first Map report fails; the retry on the same connection makes the images begin. */
    @Test fun failedMapReportIsRetriedAndImagesBegin() = runTest {
        val l = Link(this)
        l.connect()
        l.reporter.onPage(HudPage.Workout)
        l.up = false
        l.reporter.onPage(HudPage.Map)
        advanceTimeBy(5_000); runCurrent()
        assertTrue(l.images.isEmpty(), "the phone never heard about Map")
        assertTrue(l.reporter.hasPending)
        l.up = true
        l.reporter.retryPending() // HudController calls this every second
        advanceTimeBy(500); runCurrent()
        assertEquals(1, l.images.size)
        assertFalse(l.reporter.hasPending)
    }

    /** Review #10: the report of leaving Map fails; the retry makes the images stop. */
    @Test fun failedLeaveReportIsRetriedAndImagesStop() = runTest {
        val l = Link(this)
        l.connect()
        l.reporter.onPage(HudPage.Map)
        advanceTimeBy(500); runCurrent()
        assertEquals(1, l.images.size)
        l.up = false
        l.reporter.onPage(HudPage.Workout)
        advanceTimeBy(3_500); runCurrent()
        assertEquals(2, l.images.size, "the phone still believes Map is visible")
        l.up = true
        l.reporter.retryPending()
        advanceTimeBy(10_000); runCurrent()
        assertEquals(2, l.images.size, "no images after the retried report")
    }
}
```

`glasses/src/test/java/com/debasish/livefit/glasses/hud/MapPayloadTest.kt`:

```kotlin
package com.debasish.livefit.glasses.hud

import com.debasish.livefit.model.MapFrame
import com.debasish.livefit.model.MapFrameKind
import java.util.Base64
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertNull

class MapPayloadTest {
    private val frame = MapFrame(kind = MapFrameKind.Image, renderEpoch = 1, sessionId = "s", renderSeq = 1)

    @Test fun pngFromTheBytesArgument() = assertContentEquals(byteArrayOf(1, 2), MapPayload.png(frame, byteArrayOf(1, 2)))

    @Test fun base64FallbackWhenNoBytes() = assertContentEquals(
        byteArrayOf(3, 4), MapPayload.png(frame.copy(pngBase64 = Base64.getEncoder().encodeToString(byteArrayOf(3, 4))), ByteArray(0)),
    )

    @Test fun nothingWhenNeitherCarriesAnImage() {
        assertNull(MapPayload.png(frame, null))
        assertNull(MapPayload.png(frame.copy(pngBase64 = "!!not base64"), null))
    }
}
```

- [ ] **Step 2: Run tests to verify they fail**

Run: `export JAVA_HOME=$(/usr/libexec/java_home -v 17) && ./gradlew :glasses:testDebugUnitTest --tests '*PageReporterTest*' --tests '*PageReportRetryTest*' --tests '*MapPayloadTest*'`
Expected: FAIL — `PageReporter`, `MapPayload` unresolved.

- [ ] **Step 3: Implement both**

`glasses/src/main/java/com/debasish/livefit/glasses/hud/PageReporter.kt`:

```kotlin
package com.debasish.livefit.glasses.hud

import com.debasish.livefit.model.HudPage
import com.debasish.livefit.model.PageState
import com.debasish.livefit.model.Wire

/**
 * lf_page_state (spec §2.5): the visible page on every change and on every (re)connect, so the phone knows whether the
 * Map page is visible even after it restarted. [seq] grows per glasses process. [send] returns false (or throws) when
 * the bridge refused the message: the latest page is then pending — replacing any older pending state — and is re-sent
 * by [retryPending] (every second, HudController) and by an unchanged [onPage], until a send succeeds (review #10).
 */
class PageReporter(private val send: (String) -> Boolean) {
    private var seq = 0L
    private var pending = false
    @Volatile var page: HudPage = HudPage.Workout
        private set

    val hasPending: Boolean
        @Synchronized get() = pending

    @Synchronized
    fun onPage(p: HudPage) {
        if (p == page && seq > 0 && !pending) return
        page = p
        emit()
    }

    @Synchronized
    fun resend() = emit()

    @Synchronized
    fun retryPending() { if (pending) emit() }

    private fun emit() {
        seq++
        pending = !runCatching { send(Wire.encode(PageState(page = page, seq = seq))) }.getOrDefault(false)
    }
}
```

`glasses/src/main/java/com/debasish/livefit/glasses/hud/MapPayload.kt`:

```kotlin
package com.debasish.livefit.glasses.hud

import com.debasish.livefit.model.MapFrame
import java.util.Base64

/** A map image on lf_map: PNG in the CXR bytes argument, or (CxrGlassesLink.MAP_AS_BASE64) Base64 in the frame. */
object MapPayload {
    fun png(frame: MapFrame, bytes: ByteArray?): ByteArray? =
        bytes?.takeIf { it.isNotEmpty() }
            ?: frame.pngBase64?.let { runCatching { Base64.getDecoder().decode(it) }.getOrNull() }?.takeIf { it.isNotEmpty() }

    /** CRC-32 (hex) for the device check D1 arrival log; "-" without bytes. */
    fun crc(bytes: ByteArray?): String = bytes?.let { java.util.zip.CRC32().apply { update(it) }.value.toString(16) } ?: "-"
}

/** The newest accepted map image (spec §2.5). Not a data class: a new image must always replace the old one. */
class MapImage(val sessionId: String, val png: ByteArray, val seq: Long)
```

- [ ] **Step 4: Run tests to verify they pass**

Run: `export JAVA_HOME=$(/usr/libexec/java_home -v 17) && ./gradlew :glasses:testDebugUnitTest --tests '*PageReporterTest*' --tests '*PageReportRetryTest*' --tests '*MapPayloadTest*'`
Expected: PASS.

- [ ] **Step 5: `HudController.kt`** — add imports `com.debasish.livefit.model.GestureRules`, `com.debasish.livefit.model.GestureSettings`, `com.debasish.livefit.model.MapFrame`, `com.debasish.livefit.model.MapFrameKind`, `com.debasish.livefit.model.PageSettings`, `com.debasish.livefit.sync.MapImageGate`. Replace `private val _settings = MutableStateFlow(loadSettings())` with:

```kotlin
    /** Last settings frame (layout + pages + last valid gesture table), kept across restarts. */
    private val saved: HudSettingsFrame? = prefs.getString(KEY_FRAME, null)?.let { runCatching { Wire.decode<HudSettingsFrame>(it) }.getOrNull() }
    private val _settings = MutableStateFlow(saved?.settings ?: loadSettings())
    private val _pages = MutableStateFlow(saved?.pages ?: PageSettings())
    /** Settings → Pages from the phone (spec §3.2). */
    val pages: StateFlow<PageSettings> = _pages
    private val _gestures = MutableStateFlow(GestureRules.sanitized(saved?.gestures ?: GestureSettings()))
    /** The gesture table, validated per page (spec §4.4); an invalid page keeps its last valid table. */
    val gestures: StateFlow<GestureSettings> = _gestures
    private val gate = MapImageGate()
    private val _mapImage = MutableStateFlow<MapImage?>(null)
    /** Newest accepted lf_map image (newest epoch, this session, newer seq). */
    val mapImage: StateFlow<MapImage?> = _mapImage
    private val reporter = PageReporter { json -> sendRaw(GlassesChannels.PAGE_STATE, json) }
```

Replace `fun sendRaw(channel: String, text: String) { bridge.sendMessage(channel, Caps().apply { write(text) }) }` with (callers that ignore the result are unchanged):

```kotlin
    /** True when the bridge accepted the message (CXR-S `sendMessage`: 0 = sent, -1 parameter error, -3 internal error). */
    fun sendRaw(channel: String, text: String): Boolean = bridge.sendMessage(channel, Caps().apply { write(text) }) == 0
```

and in `start()` replace `scope.launch { while (true) { refreshConnection(); delay(1_000) } }` with:

```kotlin
        scope.launch { while (true) { refreshConnection(); reporter.retryPending(); delay(1_000) } } // review #10: unsent page state
```

In `start()` also add:

```kotlin
        bridge.subscribe(GlassesChannels.MAP, CXRServiceBridge.MsgCallback { _, caps, bytes -> onMap(caps, bytes) })
```

Replace `onSettings` with:

```kotlin
    private fun onSettings(caps: Caps?) {
        val t = text(caps) ?: return
        if (Wire.versionOf(t) != PROTOCOL_VERSION) return
        val f = runCatching { Wire.decode<HudSettingsFrame>(t) }.getOrNull() ?: return
        GestureRules.problems(f.gestures).forEach { Log.w(TAG, "gesture table: $it — keeping the last valid one") }
        val gestures = GestureRules.sanitized(f.gestures, _gestures.value)
        _settings.value = f.settings
        _pages.value = f.pages
        _gestures.value = gestures
        prefs.edit()
            .putString(KEY_FRAME, Wire.encode(f.copy(gestures = gestures)))
            .putString(KEY_SETTINGS, Wire.encode(f.settings)) // keep layout across restarts
            .apply()
    }
```

Add:

```kotlin
    /** lf_map (spec §2.5): an epoch header resets the gate and re-reports our page; images pass the gate first. */
    private fun onMap(caps: Caps?, bytes: ByteArray?) {
        val frame = text(caps)?.let(MapFrame::parse) ?: return
        // Arrival, before the gate (device check D1): what CXR actually delivered.
        Log.i(MAP_TAG, "lf_map in kind=${frame.kind} epoch=${frame.renderEpoch} seq=${frame.renderSeq} bytes=${bytes?.size ?: -1} crc=${MapPayload.crc(bytes?.takeIf { it.isNotEmpty() })} b64=${frame.pngBase64?.length ?: 0} b64crc=${MapPayload.crc(MapPayload.png(frame, null))}")
        val session = _frame.value?.workout?.sessionId
        if (frame.kind == MapFrameKind.Epoch) {
            gate.accept(frame, session)
            reporter.resend() // a (re)connected or restarted phone learns our page without a page change
            return
        }
        if (!gate.accept(frame, session)) { Log.d(MAP_TAG, "dropped epoch=${frame.renderEpoch} seq=${frame.renderSeq} session=${frame.sessionId}"); return }
        val png = MapPayload.png(frame, bytes) ?: run { Log.w(MAP_TAG, "image without payload (bytes=${bytes?.size})"); return }
        _mapImage.value = MapImage(frame.sessionId ?: return, png, frame.renderSeq)
        Log.i(MAP_TAG, "shown epoch=${frame.renderEpoch} seq=${frame.renderSeq} bytes=${png.size}")
    }

    fun reportPage(page: HudPage) = reporter.onPage(page)

    /** CXR bridge connected: report the visible page again (spec §2.5). */
    fun onPhoneConnected() = reporter.resend()
```

and in the companion add `private const val KEY_FRAME = "hudSettingsFrame"` and `const val MAP_TAG = "LiveFitMap"`.

- [ ] **Step 6: `MainActivity.kt`** — replace the bodies of the Task 21 stand-in members and report pages:

```kotlin
    private fun gestures(): GestureSettings = controller.gestures.value
    private fun pageSettings(): PageSettings = controller.pages.value
    private fun updateNav(next: HudNav) {
        if (next.page != nav.page) controller.reportPage(next.page)
        nav = next
    }
```

In `onCreate` after `controller = HudController(...).also { it.start() }` add `controller.reportPage(nav.page)`, and in the bridge `StatusListener.onConnected` add `if (::controller.isInitialized) controller.onPhoneConnected()` after the log line. Inside `setContent` add next to the other collected flows:

```kotlin
            val pages by controller.pages.collectAsStateWithLifecycle()
            val gestureSettings by controller.gestures.collectAsStateWithLifecycle() // review #9: a new idle timeout re-keys the timer effect
```

change the `available` line to:

```kotlin
            val available = PageSet.available(pages, PageSet.mapEligible(frame?.workout ?: WorkoutSnapshot()))
```

and replace the Task 21 line `val idleMs = gestures().idleTimeoutS * 1_000L` with:

```kotlin
            val idleMs = gestureSettings.idleTimeoutS * 1_000L
```

- [ ] **Step 7: Build and test**

Run: `export JAVA_HOME=$(/usr/libexec/java_home -v 17) && ./gradlew :glasses:assembleDebug :glasses:testDebugUnitTest`
Expected: BUILD SUCCESSFUL, tests PASS.

- [ ] **Step 8: Commit**

```bash
git add glasses
git commit -m "feat(glasses): apply received pages and validated gestures, report (and retry) the visible page, accept lf_map images"
```

- [ ] **Step 9: Early device check D1 — raw PNG over CXR (needs Task 15 merged; run before Task 23 starts)**

The first point where the phone `lf_map` sender (Task 15) and the glasses receiver (this task) both exist. Decide now whether the PNG travels in the CXR `bytes` argument or as Base64 in the JSON, so Task 23 (and Task 25) build on the working path; Task 18 is unaffected either way.

Run (phone debug build + glasses build from the same commit; the watch is not needed):

```bash
export JAVA_HOME=$(/usr/libexec/java_home -v 17) && ./gradlew :phone:assembleDebug :glasses:assembleDebug
source tools/device-tests/common.sh && require PHONE GLASSES
adb -s "$GLASSES" install -r glasses/build/outputs/apk/debug/glasses-debug.apk
adb -s "$PHONE" install -r --user 0 phone/build/outputs/apk/debug/phone-debug.apk
# Open LiveFit on the phone and the glasses app; wait for the HUD to show the phone connected.
adb -s "$PHONE" logcat -c; adb -s "$GLASSES" logcat -c
for kb in 8 38 60; do
  adb -s "$PHONE" shell am broadcast -n com.debasish.livefit/.phone.DebugReceiver --es cmd map_probe --ei kb $kb; sleep 3
done
adb -s "$PHONE" shell am broadcast -n com.debasish.livefit/.phone.DebugReceiver --es cmd map_probe --ei kb 38 --ez b64 true; sleep 3
adb -s "$PHONE" logcat -d -s LiveFitMap | grep map_probe
adb -s "$GLASSES" logcat -d -s LiveFitMap | grep 'lf_map in'
```

Expected and decision (record the four phone/glasses line pairs in the Task 25 acceptance record, row 1):
- For each raw probe the glasses log `lf_map in kind=Image … bytes=<kb×1024> crc=<same as the phone's crc>`. If the 8 KB and 38 KB probes match: keep `CxrGlassesLink.MAP_AS_BASE64 = false`. (60 KB is headroom information only; the renderer targets ≤ 40 KB.)
- If raw probes arrive with `bytes=0`/`-1` or a different crc but the `b64` probe arrives with `b64crc=<phone crc>`: set `CxrGlassesLink.MAP_AS_BASE64 = true` in Task 15's file, commit `fix(glasses-link): map images as Base64 (device check D1)`, rebuild, and repeat the raw-probe loop with `--ez b64 true` to confirm.
- If neither path delivers a matching 38 KB payload: stop and report to the owner before Task 23 (the map stream would need chunking, which this plan does not cover).
- The probe uses render epoch 4242; after it, restart the phone app so the real stream announces a fresh epoch.

---

### Task 23: Glasses pages UI — Stats, Map, Music controls, Playlist scroll mode

**Files:**
- Create: `glasses/src/main/java/com/debasish/livefit/glasses/hud/HudPages.kt`
- Modify: `glasses/src/main/java/com/debasish/livefit/glasses/hud/MusicScreen.kt`
- Modify: `glasses/src/main/java/com/debasish/livefit/glasses/hud/HudScreen.kt`
- Modify: `glasses/src/main/java/com/debasish/livefit/glasses/MainActivity.kt`
- Test: `glasses/src/test/java/com/debasish/livefit/glasses/hud/MusicControlsModelTest.kt`

**Interfaces:**
- Consumes: `HudNav.highlightRow/visibleSelector/mode`, `MusicControl` (Task 21); `HudController.mapImage`, `MapImage` (Task 22); `Glyph`, `Label`, `Hud`, `Footprints`, `visibleRows` (existing).
- Produces:
  - `object MusicControlsModel { fun progress(np: NowPlaying?): Float }`
  - `@Composable StatsScreen(frame: StateFrame)`, `@Composable MapScreen(image: ImageBitmap?)`, `@Composable MusicControlsScreen(np: NowPlaying?, selector: MusicControl?, clock: String)`
  - `MusicScreen(np, queue, highlight, clock, scrolling: Boolean = false)` — draws the ✕ Back row in scroll mode.
  - `HudScreen(…, selector: MusicControl? = null, mapImage: ImageBitmap? = null, scrolling: Boolean = false)`

- [ ] **Step 1: Write the failing test** — `glasses/src/test/java/com/debasish/livefit/glasses/hud/MusicControlsModelTest.kt`:

```kotlin
package com.debasish.livefit.glasses.hud

import com.debasish.livefit.model.NowPlaying
import kotlin.test.Test
import kotlin.test.assertEquals

class MusicControlsModelTest {
    @Test fun progressIsAClampedFraction() {
        assertEquals(0f, MusicControlsModel.progress(null))
        assertEquals(0f, MusicControlsModel.progress(NowPlaying("t", "a", true, positionMs = 10, durationMs = 0)))
        assertEquals(0.25f, MusicControlsModel.progress(NowPlaying("t", "a", true, positionMs = 30_000, durationMs = 120_000)))
        assertEquals(1f, MusicControlsModel.progress(NowPlaying("t", "a", true, positionMs = 200_000, durationMs = 120_000)))
    }
}
```

- [ ] **Step 2: Run test to verify it fails**

Run: `export JAVA_HOME=$(/usr/libexec/java_home -v 17) && ./gradlew :glasses:testDebugUnitTest --tests '*MusicControlsModelTest*'`
Expected: FAIL — `MusicControlsModel` unresolved.

- [ ] **Step 3: `HudPages.kt`**

```kotlin
package com.debasish.livefit.glasses.hud

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.VolumeUp
import androidx.compose.material.icons.outlined.Bolt
import androidx.compose.material.icons.outlined.Close
import androidx.compose.material.icons.outlined.Explore
import androidx.compose.material.icons.outlined.FavoriteBorder
import androidx.compose.material.icons.outlined.LocalFireDepartment
import androidx.compose.material.icons.outlined.MusicNote
import androidx.compose.material.icons.outlined.Pause
import androidx.compose.material.icons.outlined.PlayArrow
import androidx.compose.material.icons.outlined.Route
import androidx.compose.material.icons.outlined.SkipNext
import androidx.compose.material.icons.outlined.SkipPrevious
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.debasish.livefit.model.NowPlaying
import com.debasish.livefit.model.StateFrame

object MusicControlsModel {
    fun progress(np: NowPlaying?): Float =
        if (np == null || np.durationMs <= 0) 0f else (np.positionMs.toFloat() / np.durationMs).coerceIn(0f, 1f)
}

/** Stats page (spec §3.1): steps, distance, speed, calories, avg/max HR. */
@Composable
fun StatsScreen(frame: StateFrame) {
    val w = frame.workout
    val m = w.metrics
    Column(Modifier.fillMaxSize(), verticalArrangement = Arrangement.Center) {
        Label("STATS", 22.sp, Hud.TERTIARY, FontWeight.Bold)
        Spacer(Modifier.height(12.dp))
        StatRow(Footprints, "%,d".format(m.steps), "steps")
        StatRow(Icons.Outlined.Route, "%.2f".format(m.distanceKm), "km")
        StatRow(Icons.Outlined.Bolt, "%.1f".format(m.speedKmh), "km/h")
        StatRow(Icons.Outlined.LocalFireDepartment, "${m.calories}", "kcal")
        StatRow(Icons.Outlined.FavoriteBorder, "${w.avgHeartRate ?: "--"} / ${w.maxHeartRate ?: "--"}", "avg / max")
    }
}

@Composable
private fun StatRow(icon: ImageVector, value: String, unit: String) {
    Row(Modifier.fillMaxWidth().height(56.dp), verticalAlignment = Alignment.CenterVertically) {
        Glyph(icon, 34.dp, Hud.SECONDARY)
        Label(" $value", 40.sp, Hud.PRIMARY, FontWeight.Bold)
        Label(" $unit", 24.sp, Hud.TERTIARY)
    }
}

/** Map page (spec §2.5): the phone-rendered 480×480 image (attribution is inside it). */
@Composable
fun MapScreen(image: ImageBitmap?) {
    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        if (image == null) {
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                Glyph(Icons.Outlined.Explore, 56.dp, Hud.SECONDARY)
                Label("Loading map…", 30.sp, Hud.SECONDARY, FontWeight.Bold)
            }
        } else {
            Image(image, contentDescription = "Map", modifier = Modifier.fillMaxWidth().aspectRatio(1f))
        }
    }
}

/** Music controls page (spec §3.1): title/artist, progress, ⏮ ⏯ ⏭ ✕ (outlined selector in scroll mode), volume bar. */
@Composable
fun MusicControlsScreen(np: NowPlaying?, selector: MusicControl?, clock: String) {
    Column(Modifier.fillMaxSize()) {
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Glyph(Icons.Outlined.MusicNote, 26.dp, Hud.TERTIARY)
            Label(" PLAYER", 22.sp, Hud.TERTIARY, FontWeight.Bold)
            Spacer(Modifier.weight(1f))
            if (clock.isNotEmpty()) Label(clock, 24.sp, Hud.SECONDARY, FontWeight.Bold)
        }
        Spacer(Modifier.height(14.dp))
        Label(np?.title ?: "Nothing playing", 30.sp, Hud.PRIMARY, FontWeight.Bold, overflow = TextOverflow.Ellipsis)
        np?.artist?.takeIf { it.isNotEmpty() }?.let { Label(it, 24.sp, Hud.SECONDARY, overflow = TextOverflow.Ellipsis) }
        Spacer(Modifier.height(12.dp))
        Bar(MusicControlsModel.progress(np), Modifier.fillMaxWidth())
        Spacer(Modifier.height(18.dp))
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceEvenly) {
            ControlButton(Icons.Outlined.SkipPrevious, selector == MusicControl.Previous)
            ControlButton(if (np?.isPlaying == true) Icons.Outlined.Pause else Icons.Outlined.PlayArrow, selector == MusicControl.PlayPause)
            ControlButton(Icons.Outlined.SkipNext, selector == MusicControl.Next)
            ControlButton(Icons.Outlined.Close, selector == MusicControl.Back)
        }
        Spacer(Modifier.height(18.dp))
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Glyph(Icons.AutoMirrored.Outlined.VolumeUp, 28.dp, Hud.SECONDARY)
            Spacer(Modifier.width(10.dp))
            Bar(np?.volume ?: 0f, Modifier.weight(1f))
        }
        Spacer(Modifier.weight(1f))
        Label(if (selector == null) "tap: controls · swipe: pages" else "swipe: choose · tap: press · long swipe: volume", 18.sp, Hud.TERTIARY, maxLines = 2)
    }
}

@Composable
private fun ControlButton(icon: ImageVector, selected: Boolean) {
    val outline = if (selected) Modifier.border(3.dp, Hud.Green.copy(alpha = Hud.PRIMARY), RoundedCornerShape(14.dp))
    else Modifier.border(2.dp, Hud.Green.copy(alpha = Hud.TERTIARY), RoundedCornerShape(14.dp))
    Box(outline.size(64.dp), contentAlignment = Alignment.Center) { Glyph(icon, 36.dp, if (selected) Hud.PRIMARY else Hud.SECONDARY) }
}

@Composable
private fun Bar(fraction: Float, modifier: Modifier) {
    Box(modifier.height(8.dp).border(2.dp, Hud.Green.copy(alpha = Hud.TERTIARY), RoundedCornerShape(4.dp))) {
        Box(Modifier.fillMaxHeight().fillMaxWidth(fraction.coerceIn(0f, 1f)).background(Hud.Green.copy(alpha = Hud.SECONDARY), RoundedCornerShape(4.dp)))
    }
}
```

- [ ] **Step 4: Run test to verify it passes**

Run: `export JAVA_HOME=$(/usr/libexec/java_home -v 17) && ./gradlew :glasses:testDebugUnitTest --tests '*MusicControlsModelTest*'`
Expected: PASS.

- [ ] **Step 5: `MusicScreen.kt` — scroll mode and the ✕ Back row.** Change the signature to `fun MusicScreen(np: NowPlaying?, queue: QueueWindow, highlight: Int?, clock: String, scrolling: Boolean = false)`; change the header's `val position = highlight ?: queue.currentIndex` to `val position = highlight?.takeIf { it < queue.items.size } ?: queue.currentIndex`. Replace the list `Box(...) { … }` and the hint block (from `Box(Modifier.fillMaxWidth().weight(1f).clipToBounds())` to the final `Label(hint, …)`) with:

```kotlin
        Box(Modifier.fillMaxWidth().weight(1f).clipToBounds()) {
            val rows = queue.items.size + if (scrolling) 1 else 0 // scroll mode adds the ✕ Back row
            Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                if (queue.items.isEmpty()) Label("No queue from YouTube Music", 24.sp, Hud.TERTIARY)
                for (i in visibleRows(rows, highlight ?: queue.currentIndex, MUSIC_ROWS)) {
                    if (i == queue.items.size) BackRow(highlighted = i == highlight)
                    else QueueRow(queue.items[i], played = queue.currentIndex != null && i < queue.currentIndex!!, current = i == queue.currentIndex, highlighted = i == highlight)
                }
            }
        }
        Spacer(Modifier.height(6.dp))
        val hint = when {
            !scrolling -> "tap: choose songs · swipe: pages"
            highlight == queue.items.size -> "tap: leave song list"
            highlight == queue.currentIndex -> "swipe: songs · tap: play/pause · ✕ Back: leave"
            else -> "swipe: songs · tap: play song · ✕ Back: leave"
        }
        Label(hint, 18.sp, Hud.TERTIARY, maxLines = 2) // the HUD block is narrow at 40 % size
```

and add next to `QueueRow`:

```kotlin
@Composable
private fun BackRow(highlighted: Boolean) {
    val shape = RoundedCornerShape(10.dp)
    val outline = if (highlighted) Modifier.border(3.dp, Hud.Green.copy(alpha = Hud.PRIMARY), shape) else Modifier
    Row(outline.fillMaxWidth().padding(horizontal = 8.dp, vertical = 4.dp), verticalAlignment = Alignment.CenterVertically) {
        Label("✕ Back", 24.sp, if (highlighted) Hud.PRIMARY else Hud.SECONDARY, FontWeight.Bold)
    }
}
```

- [ ] **Step 6: `HudScreen.kt`** — add imports `androidx.compose.ui.graphics.ImageBitmap`; add the parameters after `musicHighlight`:

```kotlin
    /** Music controls selector in scroll mode; null in page mode. */
    selector: MusicControl? = null,
    /** Newest accepted lf_map image for this session. */
    mapImage: ImageBitmap? = null,
    /** Playlist / Music controls are in scroll mode. */
    scrolling: Boolean = false,
```

and replace the line `page == HudPage.Playlist -> { cornerClock = false; MusicScreen(frame.music, queue, musicHighlight, clock) }` with:

```kotlin
                    page == HudPage.Playlist -> { cornerClock = false; MusicScreen(frame.music, queue, musicHighlight, clock, scrolling) }
                    page == HudPage.MusicControls -> { cornerClock = false; MusicControlsScreen(frame.music, selector, clock) }
                    page == HudPage.Stats && phase != WorkoutPhase.Stopping && phase != WorkoutPhase.Summary -> StatsScreen(frame)
                    page == HudPage.Map && inWorkout -> MapScreen(mapImage)
```

- [ ] **Step 7: `MainActivity.kt`** — add the import `androidx.compose.ui.graphics.asImageBitmap`; inside `setContent` next to the other collected flows:

```kotlin
            val mapImage by controller.mapImage.collectAsStateWithLifecycle()
            val sessionId = frame?.workout?.sessionId
            val mapBitmap = androidx.compose.runtime.remember(mapImage, sessionId) {
                mapImage?.takeIf { it.sessionId == sessionId }?.let { android.graphics.BitmapFactory.decodeByteArray(it.png, 0, it.png.size)?.asImageBitmap() }
            }
```

and extend the `HudScreen(...)` call with `selector = nav.visibleSelector(), mapImage = mapBitmap, scrolling = nav.mode == GestureMode.Scroll`.

- [ ] **Step 8: Build and test**

Run: `export JAVA_HOME=$(/usr/libexec/java_home -v 17) && ./gradlew :glasses:assembleDebug :glasses:testDebugUnitTest`
Expected: BUILD SUCCESSFUL, tests PASS.

- [ ] **Step 9: Commit**

```bash
git add glasses
git commit -m "feat(glasses): Stats, Map and Music controls pages; Playlist scroll mode with ✕ Back"
```

---

## Phase 4 — Voice

### Task 24: Voice phrases for the new pages

**Files:**
- Modify: `services/voice/src/main/kotlin/com/debasish/livefit/services/voice/CommandParser.kt`
- Modify: `services/voice/src/main/kotlin/com/debasish/livefit/services/voice/VoiceCommandGroup.kt`
- Test: `services/voice/src/test/kotlin/com/debasish/livefit/services/voice/CommandParserTest.kt` (modify)

**Interfaces:**
- Consumes: `HudPage.Stats/Map/MusicControls` (Task 1).
- Produces: `CommandParser.parse` → `ShowGlassesPage(Map)` for "map view"…, `ShowGlassesPage(MusicControls)` for "music view" / "player view" / "music controls"…, `ShowGlassesPage(Stats)` for "stats view"…; "playlist view" stays Playlist. (The disabled-page toast is Task 17's router gate.)

- [ ] **Step 1: Write the failing tests** — in `CommandParserTest.kt` replace `playlistView()` with:

```kotlin
    @Test fun playlistView() = assertParses(
        Command.ShowGlassesPage(HudPage.Playlist),
        "playlist view", "Playlist view.", "show playlist", "open playlist", "open the playlist",
        "show queue", "songs view", "go to playlist", "playlist", "show the songs",
    )

    /** Spec §5: "music view" moved from Playlist to Music controls. */
    @Test fun musicControlsView() = assertParses(
        Command.ShowGlassesPage(HudPage.MusicControls),
        "music view", "Music view.", "player view", "music controls", "show music", "switch to music view", "open the player", "show music controls",
    )

    @Test fun mapView() = assertParses(
        Command.ShowGlassesPage(HudPage.Map),
        "map view", "Map view.", "show map", "map", "open the map", "map screen", "switch to map view",
    )

    @Test fun statsView() = assertParses(
        Command.ShowGlassesPage(HudPage.Stats),
        "stats view", "Stats view.", "show stats", "stats page", "statistics view", "go to stats",
    )
```

and add to `pageWordsDoNotStealCommands()`:

```kotlin
        assertParses(Command.PauseMusic, "pause the player")
        assertParses(Command.NextTrack, "next song on the player")
        assertParses(Command.PlayMusic, "play music")
```

- [ ] **Step 2: Run tests to verify they fail**

Run: `export JAVA_HOME=$(/usr/libexec/java_home -v 17) && ./gradlew :services:voice:test --tests '*CommandParserTest*'`
Expected: FAIL — "music view" still parses to Playlist; "map view" / "stats view" don't parse to the new pages.

- [ ] **Step 3: `CommandParser.kt`** — replace the body of `pageOf` with:

```kotlin
        fun any(vararg w: String) = has(w)
        val view = any("view", "screen", "page", "mode", "show", "open", "display", "switch", "go")
        return when {
            any("glance") -> HudPage.Glance
            any("map") -> HudPage.Map
            any("playlist", "queue") || (view && any("songs", "tracks")) -> HudPage.Playlist
            any("controls") || (view && any("music", "player", "song")) -> HudPage.MusicControls
            view && any("stats", "statistics") -> HudPage.Stats
            view && any("workout", "work out", "metrics") -> HudPage.Workout
            else -> null
        }
```

In `parse`, add `"player"` to the music words so "pause the player" stays a music command:

```kotlin
        val music = has("music", "song", "songs", "track", "tune", "playlist", "player")
```

and update the class doc's example list to mention "map view", "stats view", "music view" (Music controls) and "playlist view".

- [ ] **Step 4: `VoiceCommandGroup.kt`** — change the `PageViews` examples to:

```kotlin
    PageViews("Glasses views", "\"glance view\", \"stats view\", \"map view\", \"music view\", \"playlist view\""),
```

- [ ] **Step 5: Run tests to verify they pass**

Run: `export JAVA_HOME=$(/usr/libexec/java_home -v 17) && ./gradlew :services:voice:test`
Expected: PASS (including `CompositeCommandTest` — "pause music and playlist view" still yields `PauseMusic`, `ShowGlassesPage(Playlist)`).

- [ ] **Step 6: Commit**

```bash
git add services/voice
git commit -m "feat(voice): map, stats and music-controls page views; music view moves to Music controls"
```

---

## Phase 5 — Device acceptance

### Task 25: Install, map cadence script and device acceptance

**Files:**
- Create: `tools/device-tests/map-cadence.sh`
- Create: `docs/superpowers/acceptance/2026-10-09-pages-maps-gestures-acceptance.md`

**Interfaces:**
- Consumes: everything above (including the device check D1 result, Task 22 Step 9); log tags `LiveFitMap` (phone `sent seq=… bytes=… epoch=…` / `map_probe …`, glasses `lf_map in …` / `shown epoch=… seq=… bytes=…` / `dropped …` / `image without payload`), `LiveFitPhoneGps` (`phone GPS on|off`), `LiveFitWatchLink` (`time sync ok=… offset=…`), `LiveFitExercise` (batching overrides list, `gps=… location supported=…`).
- Produces: a filled-in acceptance record; the final value of `CxrGlassesLink.MAP_AS_BASE64`.

- [ ] **Step 1: Full JVM test sweep**

Run: `export JAVA_HOME=$(/usr/libexec/java_home -v 17) && ./gradlew :core:model:test :core:map:test :services:sync:test :services:workout:test :services:voice:test :services:history:testDebugUnitTest :services:glasses-link:testDebugUnitTest :services:watch-link:testDebugUnitTest :phone:testDebugUnitTest :watch:testDebugUnitTest :glasses:testDebugUnitTest`
Expected: BUILD SUCCESSFUL, every test passes.

- [ ] **Step 2: Map cadence script** — `tools/device-tests/map-cadence.sh`:

```bash
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
```

Run `chmod +x tools/device-tests/map-cadence.sh`.

- [ ] **Step 3: Install all three APKs from this commit**

Run: `tools/install-all.sh`
Expected: `OK glasses`, `OK watch`, `OK phone`. Open LiveFit on the phone once (re-promotes the hub with `location`); on the watch grant location when asked. Check Settings → Linked services → Phone GPS reads "Phone GPS ready (map fallback)". Check Activity still lists the workouts recorded before the upgrade (Room v1 → v2).

- [ ] **Step 4: Acceptance checklist** — `docs/superpowers/acceptance/2026-10-09-pages-maps-gestures-acceptance.md`:

```markdown
# LiveFit pages, live map & gestures — acceptance (spec §1, §8)

Run on: Galaxy S25 + Galaxy Watch6 Classic + Rokid Glasses, all installed via `tools/install-all.sh` from one commit,
"Use GPS outdoors" on, outdoors.

| # | Check | How | Pass |
|---|---|---|---|
| 1 | lf_map payload path | Paste the D1 probe lines (Task 22 Step 9) here; then start a Run, glasses on Map; `adb -s $GLASSES logcat -s LiveFitMap` | D1 crc pairs match on the chosen path; `shown epoch=… bytes=N` with N > 0. If only `image without payload` appears: set `CxrGlassesLink.MAP_AS_BASE64 = true`, reinstall, repeat |
| 2 | Map on glasses: cadence, size, attribution | Walk 2 min on Map; `tools/device-tests/map-cadence.sh 120` | script PASS; marker moves within ~3 s; "© OpenStreetMap contributors" readable; streets dim green, route bright, water/parks black |
| 3 | Map on watch: tiles, zoom, own fixes | Same walk, watch Map page; turn the bezel both ways | mint streets appear; zoom 14–18 and always re-centred; attribution visible; route matches the glasses |
| 4 | Offline map | Phone airplane mode with Bluetooth on (glasses) / watch Wi-Fi + LTE off, 2 min | glasses "No map — route only", route keeps growing on black; watch route-only on black; Summary distance and history unaffected |
| 5 | Screen off + phone locked | 5-min walk, watch screen off, phone locked in a pocket; look at the glasses every minute | at most a brief "GPS delayed"; `adb -s $PHONE logcat -s LiveFitPhoneGps` shows no on/off flapping; the drawn route has no holes once batches arrive |
| 6 | Delayed first watch replay after a phone restart | Mid-Run: `adb -s $PHONE shell am force-stop com.debasish.livefit`; keep walking 60 s; reopen LiveFit | `LiveFitWatchLink: time sync ok=true` before the glasses arrow turns solid; the replayed stretch appears in time order; the arrow never jumps back along it |
| 7 | Phone restart while Map stays visible | Glasses on Map; force-stop and reopen the phone app | within ~5 s of reconnect the glasses log `shown epoch=<new>`; no `shown` with the old epoch afterwards |
| 8 | Reconnect while Map is visible, no page change | Glasses on Map; phone Bluetooth off 20 s, then on | images resume with a new epoch without touching the glasses |
| 9 | Ack, then watch process death | After ≥ 2 min of a GPS Walk: `adb -s $WATCH shell am crash com.debasish.livefit` | watch app restarts; Map page shows the whole route and the start marker; recording continues |
| 10 | Watch without GPS → phone fallback | `adb -s $WATCH shell pm revoke com.debasish.livefit android.permission.ACCESS_FINE_LOCATION`; LiveFit open on the phone; start a Run | ~15 s later `LiveFitPhoneGps: phone GPS on`; glasses map follows the phone; watch Map says "Waiting for GPS…"; grant again afterwards |
| 11 | Fallback stops when the watch recovers | Continue #10, re-grant location and start a new Run with the phone GPS covering the first 20 s (watch indoors, then outside) | `phone GPS off` about 10 s after watch fixes are live; no flapping |
| 12 | Page toggles on both devices | Settings → Pages: turn Stats off, then on | glasses and watch drop/restore Stats within ~1 s; a device showing Stats jumps to Workout |
| 13 | Map only during GPS workouts | Turn "Use GPS outdoors" off, start a Walk | no Map page on glasses or watch; voice "map view" does nothing |
| 14 | Every default gesture on the glasses | Spec §4.3 table, each row on each page (Glance, Workout, Stats, Playlist, Map, Music controls, both scroll modes); idle 5 s leaves scroll mode; ✕ Back leaves scroll mode | every cell behaves as specified; confirmations still use swipe/tap/double-tap = No |
| 15 | A custom mapping applied live | Settings → Glasses gestures → Page mode → Glance → Tap → Next song; then try Double tap → Talk on Glance | Tap on Glance skips the song at once (no reconnect); the second change is refused with "Glance needs a gesture for Close app" |
| 16 | Voice page views | Say "stats view", "map view", "music view", "playlist view"; disable Stats and say "stats view" | each page opens on the glasses only; toast "Stats page is turned off in Settings" |
| 17 | Coordinated upgrade guard | Install the previous (v3) glasses APK | phone shows "Update LiveFit on your glasses"; no lf_map images accepted |
| 18 | Tiles recover without moving | Stand still on the Map page (glasses and watch) with phone mobile data and watch Wi-Fi/LTE off for 1 min, then turn them back on | images keep coming route-only during the outage; tiles appear on both within ~35 s of reconnecting without zooming or walking |
| 19 | Route survives a phone kill right after a delta | During a GPS Walk: `adb -s $PHONE shell am force-stop com.debasish.livefit` twice, 30 s apart; reopen LiveFit; after the Walk open its history detail | glasses route and history thumbnail have no gaps around the kills |
```

- [ ] **Step 5: Run every acceptance check on the devices** and record date, numbers and PASS/FAIL in the table's Pass column. Any FAIL goes back to the owning task (the table's "How" names the log tag that points at it) before this task is done.

- [ ] **Step 6: Commit**

```bash
git add tools/device-tests/map-cadence.sh docs/superpowers/acceptance/2026-10-09-pages-maps-gestures-acceptance.md services/glasses-link
git commit -m "test: map cadence device script and pages/maps/gestures acceptance record"
```
