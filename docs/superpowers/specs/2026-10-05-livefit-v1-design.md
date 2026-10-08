# Rokid LiveFit — V1 Design (Live core)

- **Date:** 2026-10-05
- **Status:** Draft for review
- **Companion docs:** [V2 design](2026-10-05-livefit-v2-design.md) · [V3 roadmap](2026-10-05-livefit-v3-roadmap.md)
- **Repo:** `live-fitness-tracker/` (Gradle multi-module; mock-up code already present)

---

## 1. Intent

### 1.1 Goal
During a workout, see **live fitness data on Rokid glasses** without looking at the phone or watch, and **control the workout and YouTube Music from any of three touchpoints** — phone, Galaxy Watch, Rokid glasses — with every change **synced across all three automatically**.

### 1.2 Users and distribution
- Personal use by the owner. **Sideloaded local APKs** for phone, watch and glasses. Publishing strategy is decided after real testing (see V3).

### 1.3 Success criteria
1. A full real workout (walk or run) where the glasses HUD shows **live watch data with ≤ 1 s latency** (hard ceiling 2 s), measured from watch sample time to glasses render.
2. Start / pause / resume / stop and music play / pause / next / previous / like / volume work **from every device**, and every device reflects the result.
3. The finished session appears in the phone's **Activity** history.
4. Disconnecting the phone mid-workout loses **no data**.

### 1.4 Out of scope for V1
YouTube playlists and Health Connect (→ V2). Smarter LLM intent, voice launch from the Rokid home screen, other wearables, local languages, accounts, iOS, publishing (→ V3).

---

## 2. Hardware and verified platform facts

Facts below were **verified on the owner's devices** during brainstorming spikes (2026-10-05). Treat them as constraints.

| Device | Details |
|---|---|
| Phone | Samsung Galaxy S25 (SM-S931B), Android 16 / API 36. Has YouTube Music, Hi Rokid `com.rokid.sprite.global.aiapp` G1.14.20, Health Connect, Samsung Health. A work profile exists (user 11) — **always install/act in user 0**. |
| Watch | Galaxy Watch6 Classic 43 mm LTE (SM-R955F), Wear OS 6 / API 36, Health Services present. |
| Glasses | Rokid Glasses (RG-glasses), YodaOS on Android 12 / API 32, **480×640 portrait** green monochrome Micro-LED (right eye), touchpad emits DPAD/ENTER key events; double-tap = two KEYCODE_NOTIFICATION (83, scanCode 204) presses ~150 ms apart (the system moves an app that ignores them to the back), BACK on other firmware. |

### 2.1 Rokid link — CXR-L (verified)
- Use **`com.rokid.cxr:client-l:1.1.2`** on the phone (talks to glasses through the Hi Rokid app) and **`com.rokid.cxr:cxr-service-bridge:1.4`** on the glasses. Repo: `https://maven.rokid.com/repository/maven-public/`.
- **No client secret / `.lc` file is needed.** Authorization: `CxrSessionManager.requestAuthorization(activity, perms)` → Hi Rokid returns a 32-char token silently once authorized.
- `SessionType.CUSTOM_APP` with `glassesPackageName` + `glassesActivityName` **launches our glasses APK in ~1 s**.
- Phone `sendCustomCmd(name, Caps, bytes)` arrives on the glasses via `CXRServiceBridge.subscribe(name, …)`; glasses `bridge.sendMessage(name, Caps)` arrives on the phone in `ICustomCmdSessionCallback.onCustomCmdResult`. Round trip **~30–40 ms**, occasional ~500 ms spikes.
- **Quirks (must be handled):**
  1. CXR-L keeps "use global Hi Rokid app" and granted glass permissions in **static in-memory flags** set only by `requestAuthorization` in the *current process*. After a process restart `connect()` binds the China package (`com.rokid.sprite.aiapp`) and fails, and mic permission reads as not granted. Mitigation: run `requestAuthorization` once per process (silent), plus reflection fallback setting `AuthorizationHelper.a = true`.
  2. `bindService` to Hi Rokid **fails when our app is backgrounded / phone locked** unless we hold a foreground service.
  3. When the glasses leave our app (e.g. Rokid assistant returns to launcher) the session goes `Terminating → Closed (GLASSES_APP_EXIT)` after a 5 s grace period.
- **Voice through Rokid is not usable:** "Hi Rokid" fires `onAiWake`, but the system assistant keeps the microphone (CXR-L audio stream is digital silence), answers by itself and returns to the launcher. `AiInterceptMode.BLOCK_AI` is deprecated and does nothing. CXR-L `startAudioStream` outside AI is also silent.
- **What works for voice:** a plain Android `AudioRecord(MIC, 16 kHz mono PCM16)` **inside our glasses app** records clean audio.
- Rokid assistant cannot open sideloaded apps by voice (store agents only) → V3.

### 2.2 Watch — Health Services (verified)
- `RemoteActivityHelper.startRemoteActivity` launches the watch app even with the screen off; a Data Layer message to a `WearableListenerService` wakes the app even after process death and may start the `health` foreground service.
- Wear OS 6 requires **`android.permission.health.READ_HEART_RATE`** in addition to `BODY_SENSORS`.
- Live HR ~1 Hz; watch → phone Data Layer ~0.5 s; watch → glasses end-to-end ~0.75 s with 1 Hz polling.
- **Samsung Health conflict:** `getCurrentExerciseInfo()` reports `OTHER_APP_IN_PROGRESS` (SH walk was reported as `RUNNING_TREADMILL`). A forced `startExercise` **ends** SH's workout immediately (SH shows its result screen, ~7 s handover). Not reversible.
- Phone and watch apps **must share `applicationId` and signing key** for the Data Layer.

### 2.3 YouTube Music (verified)
- Media session (via `NotificationListenerService` + `MediaSessionManager`) supports play/pause/next/previous; custom actions `thumbs_up_action` (Like), `thumbs_down_action`, `shuffle_action`, `loop_mode_action`. **No playback-speed support.** `METADATA_KEY_MEDIA_ID` is **null** (no video id).
- `MEDIA_PLAY_FROM_SEARCH` to the YTM package starts playback of a search.

### 2.4 Speech recognition (verified)
- `SpeechRecognizer.createOnDeviceSpeechRecognizer` + `EXTRA_AUDIO_SOURCE` with a **pipe** (`ParcelFileDescriptor.createPipe`) fed 16 kHz PCM recognises glasses audio offline ("Start workout"). A regular file descriptor crashes the service — use a pipe.
- On-device packs: query with `checkRecognitionSupport` (API 33), download with `triggerModelDownload(intent, executor, ModelDownloadListener)` (API 34, gives % progress). Google also shows its own size confirmation dialog. Phone currently has `en-IN` and `en-US` installed.

---

## 3. Architecture

### 3.1 Principles
1. **Phone is the hub** and single source of truth for workout state, music state, confirmations, settings and history. Watch and glasses are clients: they send `Command`s and render `StateFrame`s.
2. **Modular services.** One Gradle module per service; each has a **Fake** (demo/tests) and a **Live** implementation behind an interface in `:core:services`. Each app chooses bindings in exactly one wiring file.
3. **Platform decoupling.** `:core:model`, `:core:services` and pure logic (workout state machine, parsers, confirmation, provenance) are **plain Kotlin with no Android imports**, so they can later move to Kotlin Multiplatform for an iOS app. Platform code lives only in Live service modules.
4. **Language registry.** Speech-to-text and command parsing are keyed by locale; adding a language = registering a new `SpeechToText` locale + `CommandParser` (+ yes/no lexicon). No English assumptions in core logic.

### 3.2 Topology
```
                    ┌────────────── PHONE (hub, foreground service) ───────────────┐
 Galaxy Watch       │  ServiceGraph (bindings: Fake | Live per service)            │      Rokid Glasses
 ┌─────────────┐    │                                                              │    ┌──────────────┐
 │ Health Svcs │ ─SessionDelta─► MetricsSource(watch) ─► WorkoutService ◄─ Commands ◄─┼────│ HUD app      │
 │ local buffer│ ◄─StateFrame/ack─ WatchLink (Data Layer) │ (state machine)        │    │ CXR bridge   │
 │ watch UI    │ ───Commands───►                          ▼                        │    │ AudioRecord  │
 └─────────────┘    │  ConfirmationService ◄─► all 3     HistoryStore (Room)        │    └──────────────┘
                    │  MusicService(YTM session)  VoiceService(STT + parser)        │          ▲
                    │  SettingsStore   StateBroadcaster ──push-on-change──► GlassesLink (CXR-L)
                    └──────────────────────────────────────────────────────────────┘
```

### 3.3 Modules
| Module | Kind | Responsibility |
|---|---|---|
| `:core:model` | Kotlin JVM | Shared types and wire protocol (§4). kotlinx.serialization, `classDiscriminator = "cmd"`. |
| `:core:services` | Kotlin JVM | Interfaces: `MetricsSource`, `WorkoutService`, `GlassesLinkService`, `WatchLinkService`, `MusicService`, `VoiceService`, `SpeechToText`, `ConfirmationService`, `HistoryStore`, `SettingsStore`. |
| `:services:workout` | Kotlin JVM | `DefaultWorkoutService`: state machine, session assembly from `SessionDelta`s (events + samples), offline-gap replay, auto type detection, HR history. Used on phone (authoritative) and watch (offline copy). |
| `:services:metrics` | Android lib | `HealthServicesSource` (watch side, produces samples), `WatchSampleSource` (phone side, consumes batches), `FakeMetricsSource`. |
| `:services:watch-link` | Android lib | `DataLayerWatchLink` (phone) + watch-side client: frames, commands, sample batches, acks, battery. |
| `:services:glasses-link` | Android lib | `CxrGlassesLink`: session lifecycle, authorization workaround, reconnect, frames, glasses events, audio chunks. |
| `:services:music` | Android lib | `YtmMediaSessionService` + `FakeMusicService`. |
| `:services:voice` | Kotlin JVM + Android lib split | Pure: `CommandParser` (per language), `YesNoParser`, language registry. Android: `AndroidOnDeviceStt` (`SpeechToText`), `LiveVoiceService`. |
| `:services:confirm` *(new)* | Kotlin JVM | `DefaultConfirmationService`: one pending confirmation, first answer wins, 15 s timeout → No. |
| `:services:history` *(new)* | Android lib | Room: sessions + 1 Hz samples, provenance, queries for the Activity tab. |
| `:phone` | App | Hub UI (Compose), `LiveFitHubService` (foreground), companion-device presence, setup wizard, `ServiceGraph`. |
| `:watch` | App | Wear Compose UI, `ExerciseService` (health FGS + Ongoing Activity), offline buffer, `PhoneHub` client. |
| `:glasses` | App | HUD (Compose), CXR bridge client, touchpad input, `AudioRecord` + VAD push-to-talk. |

Existing mock-up code in these modules is the starting point: `DefaultWorkoutService`, `CommandParser`, `Protocol`, `HudSettings`, `CxrGlassesLink`, `DataLayerWatchLink`, all phone/watch/glasses screens. V1 replaces Fakes with Lives and adds the new modules.

---

## 4. Wire protocol

All messages are JSON (kotlinx.serialization) carrying `protocolVersion` (integer, starts at 1, currently **2**; policy in §4.7). Unknown fields are ignored defensively. Sealed hierarchies use discriminator key **`"cmd"`** (the default `"type"` collides with `StartWorkout.type` — bug found in spikes).

### 4.1 Messages
| Message | Direction | Content |
|---|---|---|
| `StateFrame` | phone → watch, glasses | `workout: WorkoutSnapshot`, `music: NowPlaying?` (incl. `volume` 0..1), `devices` (link state + battery for phone/watch/glasses), `voice: VoiceState`, `confirmation: Confirmation?`, `toast: String?`, `sentAtMs`. |
| `QueueFrame` | phone → glasses | `window: QueueWindow` = `items: [QueueItem{queueId, title, artist}]` + `currentIndex?` (index of the current song in `items`; null when the session reports no or an unknown active item). The glasses music screen's list (§5.5, §6.3). Sent **only when the window changes** and on every (re)connect; title/artist capped at 60 chars so a 50-item frame stays a few KB. |
| `HudSettingsFrame` | phone → glasses | `HudSettings` (scale 0.3–1.0, `HudPosition` 3×3, `items: Set<HudItem>`). Sent on change and on every (re)connect. |
| `Command` | any → phone | `id: String` (UUID, dedup) + one of: `StartWorkout(type)`, `PauseWorkout`, `ResumeWorkout`, `StopWorkout`, `DismissSummary`, `PlayPause`, `PlayMusic`, `PauseMusic`, `NextTrack`, `PreviousTrack`, `LikeTrack`, `Volume(up)` (voice, ±10 %), `SetVolume(level 0..1)` (watch arc/bezel, phone slider), `PlayQueueItem(queueId)` (glasses music screen → `skipToQueueItem`), `Answer(confirmationId, yes)`. |
| `SessionDelta` | watch → phone | `sessionId`, `seq` (monotonic per session), `events: [SessionEvent]`, `samples: [{tMs, hr?, stepsTotal, distanceKmTotal, kcalTotal, speedKmh?}]`, `provenance`, `final: Boolean`. `SessionEvent` = `Started(tMs, type)` · `Paused(tMs)` · `Resumed(tMs)` · `TypeDetected(tMs, type)` · `Stopped(tMs, reason)`. |
| `DeltaAck` | phone → watch | `sessionId`, `seq` — highest contiguous seq **durably stored** on the phone (§4.4). |
| `SessionClaim` | watch → phone | Sent on reconnect when the watch holds an offline session: `sessionId`, `type`, `startMs`, `phase`, `activeMs`, `lastSeq`. |
| `ExerciseRequest` | phone → watch | `requestId`, `sessionId`, `op`: `Start(type, force)` · `Pause` · `Resume` · `Stop` (§4.8). **Every op names its session.** |
| `ExerciseResult` | watch → phone | `requestId`, `sessionId`, `ok`, `error?` (`PermissionMissing(perms)` · `OtherAppTracking(appType)` · `SensorUnavailable` · `WrongSession(activeSessionId)` · `NoSuchSession` · `Internal(msg)`), `state` (actual Health Services exercise state), `activeSessionId?`. |
| `ExerciseStateReport` | watch → phone | Unsolicited, whenever the real exercise state changes: `sessionId`, `state`, `endedBy?` (`User` · `OtherApp` · `System` · `Error`). |
| `ListenRequest` / `AudioChunk` / `ListenEnd` | glasses → phone | Push-to-talk: start, 100 ms PCM16 chunks (binary payload in CXR `bytes`), end (VAD or cap). |
| `BatteryReport` | watch → phone | Percentage, on request and every 60 s. |
| `DiscoverableRequest` | phone → watch, glasses | `seconds` (120). Sent when the user taps Pair: the peer app shows the system `ACTION_REQUEST_DISCOVERABLE` prompt so the companion picker can list the already-bonded device; ignored unless `protocolVersion` matches. The watch also accepts it as a remote launch of `livefit://discoverable?req=<json>`. |

### 4.2 Transports and channel names
- **Glasses (CXR custom cmd names):** `lf_state`, `lf_settings`, `lf_queue`, `lf_discoverable` (phone → glasses); `lf_cmd`, `lf_listen`, `lf_audio`, `lf_listen_end` (glasses → phone). Payload: `Caps` with one JSON string; audio uses the `bytes` argument.
- **Watch (Data Layer message paths):** `/lf/state`, `/lf/settings`, `/lf/cmd`, `/lf/delta`, `/lf/ack`, `/lf/claim`, `/lf/exercise_req`, `/lf/exercise_res`, `/lf/exercise_state`, `/lf/battery_req`, `/lf/battery`, `/lf/discoverable`.
- **Migration:** the mock-up uses `lf_hud` / `lf_cmd` / `lf_listen` (glasses) and `/rf/*` (watch) with a combined `HudFrame`. V1 renames to the names above, splits `HudFrame` into `StateFrame` + `HudSettingsFrame`, and bumps `protocolVersion` to 1. The spike-only `rf_ping` / `rf_metrics` channels, `SpikeActivity` and `DebugReceiver` move behind a debug build type.

### 4.3 Push-on-change and latency
- `StateBroadcaster` emits a `StateFrame` **whenever state changes**, coalescing changes within **100 ms**, plus a **5 s heartbeat**. (Replaces the mock-up's fixed 1 s tick, which added up to 1 s latency.) During an active workout frames flow ~1 Hz anyway (each sample changes state, and the hub republishes the running timer every second, B1); the heartbeat matters when idle or paused.
- **Timer (B1):** `elapsedMs` is computed by the hub from the Started/Paused/Resumed/Stopped events, run to an **estimated watch-clock "now"** = hub clock − the smallest (hub receive time − newest timestamp) seen over the session's deltas, never earlier than the newest recorded timestamp. While Active the hub republishes it every 1 s from its own clock, so phone, HUD and watch keep ticking when the watch sends nothing (screen-off batching, Data Layer stall); a late batch never moves it back, and Paused/Stopped events stay authoritative. Syncing and ended sessions use recorded times only. The watch's offline view does the same from its own clock.
- **Liveness:** a client treats the phone as offline only after **12 s without any frame** (two missed heartbeats plus delivery tolerance), or immediately when the transport reports disconnection (Data Layer node lost / CXR session closed). The same 12 s rule applies to the glasses' "Open LiveFit on your phone" message. A healthy paused session therefore never triggers offline mode.
- Latency budget (typical): watch HS update (1 Hz) → Data Layer 0.3–0.6 s → phone < 50 ms → CXR ~40 ms → render. **Target ≤ 1 s**, worst case < 1.5 s. Each `SessionDelta` sample's `tMs` and each frame's `sentAtMs` are logged so device tests can measure it.

### 4.4 Session deltas, acknowledgement and offline buffer (approach A)
The phone owns the session; the watch only holds a **temporary durable buffer** until the phone has stored everything. No second permanent history is kept on the watch.

**What the watch persists** (small Room/DataStore on the watch, written *before* sending):
- Session header: `sessionId`, `type`, `startMs`, current `phase`, `activeMs`, last `seq`.
- Every `SessionDelta` (events + samples). Events capture pause/resume/stop/type detection, so active duration and pause intervals are reconstructable — cumulative measurements alone are not enough.

**Normal flow (phone reachable):**
1. Watch appends a delta (seq n), persists it, sends it on `/lf/delta`.
2. Phone writes the delta into Room **in one transaction**, then sends `DeltaAck(sessionId, seq = highest contiguous stored)`. Ack is sent **only after durable storage**.
3. Watch deletes deltas `≤ ack.seq` for that `sessionId`. Unacked deltas are re-sent on reconnect and every 5 s while unacked (phone ignores duplicates by `(sessionId, seq)`).

**Phone unreachable** (liveness rule §4.3):
4. Watch switches its UI to a **local `DefaultWorkoutService` copy** seeded from the last frame, shows "Phone offline", keeps recording. Local controls (pause / resume / stop) are applied locally **and recorded as events** in deltas. Music controls are disabled; glasses show their disconnected state.

**Reconnect and adoption:**
5. Watch sends `SessionClaim`. The phone enters **`Syncing`** for that session: it adopts the claimed `sessionId` (creating the session if it only knew it from earlier frames, or if it was started offline on the watch), then accepts the buffered deltas in seq order.
6. While `Syncing`, the phone **rejects workout commands** from any device with toast "Syncing watch data…" (music commands still work). Once the phone has stored up to `claim.lastSeq` it rebuilds totals, active time and HR history from events + samples, sets the phase to the watch's claimed phase, acks, and broadcasts a normal `StateFrame`. The watch then drops its local copy and goes back to rendering phone frames.
7. **Conflict rule:** if the phone has a different active session (only possible with a non-watch source), the session **with samples wins**; an empty phone session is discarded. Two sessions with samples → both are kept; the phone keeps the watch's as active and finalises its own.

**Workout ended while offline:**
8. Watch records `Stopped`, marks the last delta `final = true`, keeps the buffer. After reconnect + claim + replay, the phone finalises the session (Summary + history). The watch deletes the buffer **only after the ack of the final seq**.

**Started on the watch with no phone:** the watch generates `sessionId` (UUID) and `Started` event; adoption happens via steps 5–6.

### 4.5 Commands
- Phone applies commands **in arrival order**, ignores duplicate `id`s, then broadcasts. Clients never mutate shared state locally (except the watch's offline copy in 4.4).

### 4.6 Confirmations
- `Confirmation {id, kind, title, message, yesLabel, noLabel, defaultChoice, expiresAtMs}` is part of `StateFrame`, so all devices show it simultaneously.
- First `Answer(id, yes)` from any device wins; phone clears it; the next frame dismisses it everywhere.
- No answer by `expiresAtMs` (15 s) → **No**; all devices toast "Cancelled".
- V1 kinds: `TakeOverWorkout` (another app tracking), `StopWorkoutByVoice`.
- Every kind uses the same overlay on each device (phone dialog, watch overlay, glasses ✓/✕ band). To make sure the watch and glasses actually show it:
  - frames go to each link through its **own conflated sender** (latest frame wins, 5 s timeout per push), so a slow or hung watch Data Layer push can't hold back the glasses' frame carrying the prompt (and vice versa);
  - the hub opens the watch's LiveFit screen for each new confirmation (§6.2) — otherwise it sits behind Samsung's media controls during a workout with music;
  - the glasses open the answer mic once per confirmation id, independent of touchpad events that sync the prompt first.

### 4.7 Version policy — coordinated upgrades
All three APKs are built from the same commit and share one `protocolVersion`. Any mismatch (not only major) is treated as incompatible: the hub ignores commands from that device and tells it to update; the hub shows which device is outdated. `tools/install-all.sh` installs all three together. Unknown-field tolerance stays only as defensive parsing, not as a compatibility promise.
- **Version history:** **1** — V1 wire format. **2** — glasses music screen: new `lf_queue` channel (`QueueFrame`) and new command `PlayQueueItem` (a v1 phone cannot decode it, so the change is incompatible). The glasses ignore an `lf_queue` frame of another version (the `lf_state` frame already reports the mismatch); the watch has no new messages but shares the number.

### 4.8 Hub → watch exercise control
The phone decides; the watch executes on Health Services and reports what actually happened.
- **Start:** phone sets phase `Starting`, sends `ExerciseRequest(Start(sessionId, type, force=false))`.
  - `ok` → phase `Active` when the first delta arrives.
  - `OtherAppTracking` → phone raises `TakeOverWorkout`; Yes → `Start(..., force=true)`; No/timeout → phase back to `Idle`, toast "Samsung Health is still tracking".
  - `PermissionMissing(perms)` → phase `Idle`; toast on all devices "Watch needs <perm>"; the watch shows a one-tap permission prompt; Linked services → Galaxy Watch shows the missing permission.
  - `SensorUnavailable` / `Internal` → `Idle` + toast with reason.
  - No result within **10 s** → `Idle`, toast "Watch didn't respond"; session A is recorded in the phone's **abandoned-starts list**. A late `ok` for A triggers `Stop(sessionId = A)` — **only A** is stopped, never a newer session.
- **Session scoping on the watch:** the watch tracks at most one active `sessionId`. Any `Pause` / `Resume` / `Stop` whose `sessionId` differs from the active one returns `WrongSession(activeSessionId)` and does nothing. A `Start` for a new session while another is active returns `WrongSession` unless the phone first stops the old one.
- **Reconciling abandoned starts:** on every reconnect the watch reports `activeSessionId` (in `SessionClaim` or an `ExerciseResult`). If it equals an abandoned start the phone sends `Stop(A)` and discards A's data (no history entry); if it equals the current session nothing changes; if it's unknown the phone adopts it via §4.4.
- **Pause / Resume / Stop:** sent as session-scoped requests; the watch calls `pauseExercise` / `resumeExercise` / `endExercise`, so Health Services' own active-time matches ours. The phone updates phase on `ok`; on failure it keeps the previous phase and toasts.
- **Actual state wins:** the watch sends `ExerciseStateReport(sessionId, …)` whenever Health Services' state changes on its own — e.g. **another app ended our workout** (`endedBy = OtherApp`), auto-pause, or a system end. On `Ended` the phone **immediately shows the workout as ended** on all devices ("Workout ended by <reason>", phase `Stopping`), then applies the completion rule (§4.9).
- Requests are idempotent by `requestId`; the watch remembers the last 20.

### 4.9 Session completion rule (single rule for every way a workout ends)
A session becomes **Complete** — Summary data final, history row finalised, eligible for Health Connect (V2) — only when **all deltas through the final `seq` are durably stored on the phone**. This applies equally to a normal stop, a stop while offline, and an unsolicited end (another app / system).
- Until then the phone shows phase `Stopping` ("Saving workout…") with the totals known so far; the Summary screen appears when the session completes.
- The watch keeps sending outstanding deltas (retry rules §4.4); the final delta carries `final = true`.
- **Recovery impossible** — the watch reports its buffer lost (e.g. app data cleared), or the completion condition is still unmet **24 h** after the end event (including missing intermediate deltas, e.g. final seq 100 stored but 99 missing) — the phone finalises with the data it has and marks the session **`Incomplete`** (badge "Incomplete" in Activity; never exported to Health Connect).

---

## 5. Live services (V1)

### 5.1 Watch sensors — `HealthServicesSource` + `ExerciseService`
- `ExerciseService` is a **`health` foreground service** with a Wear **Ongoing Activity** (icon on the watch face; wrist-raise returns to the app; tracking survives screen timeout).
- `prepareExercise` warm-up before `startExercise`.
- Data types (filtered by `getCapabilities`): `HEART_RATE_BPM`, `STEPS_TOTAL`, `DISTANCE_TOTAL`, `CALORIES_TOTAL`, `SPEED`.
- **Batching (B1):** by default Health Services batches heart rate while the screen is off / ambient (step totals keep arriving, so HR looked frozen on the phone and HUD until wake). The `ExerciseConfig` sets `BatchingMode.HEART_RATE_5_SECONDS` when `ExerciseCapabilities.supportedBatchingModeOverrides` contains it (supported modes are logged, tag `LiveFitExercise`).
- **AOD (B1):** `MainActivity` uses `AmbientLifecycleObserver` (androidx.wear 1.3): in ambient a low-power workout screen (type, elapsed in whole minutes, ♥, steps; grey on black, no controls) refreshed on the system's ambient updates (~1/min); the live UI returns on wake with current values. The recording/send pipeline runs on the process-wide `WatchRuntime.scope`, independent of the Activity lifecycle.
- GPS: enabled for Run / Cycle / Auto when Settings → Workout → "Use GPS outdoors" is on (requires location permission). Walk uses step-based distance.
- Before starting: if `OTHER_APP_IN_PROGRESS`, the watch returns `OtherAppTracking`; the phone raises `TakeOverWorkout`; the watch force-starts only on a new `Start(force=true)` (§4.8).
- Fix from spikes: status messages must be sent **before** `stopSelf()` cancels the service scope.
- Permissions: `BODY_SENSORS`, `health.READ_HEART_RATE`, `ACTIVITY_RECOGNITION`, `FOREGROUND_SERVICE_HEALTH`, `POST_NOTIFICATIONS`, optional `ACCESS_FINE_LOCATION`.

### 5.2 Phone hub service — `LiveFitHubService`
- Foreground service (`connectedDevice`). Hosts the `ServiceGraph`; UI screens are views onto it.
- **Started when either the glasses or a wearable is nearby**: `CompanionDeviceManager` associations for the glasses' and watch's Bluetooth devices; `startObservingDevicePresence` → `CompanionDeviceService.onDeviceAppeared` starts the hub. Also started by any incoming watch Data Layer message (which wakes the app) and when the app UI opens.
- **Restart after an APK update or reboot:** a receiver for `MY_PACKAGE_REPLACED` and `BOOT_COMPLETED` calls `ensureRunning()` (both broadcasts are exempt from the background FGS-start restriction; Android 15's `BOOT_COMPLETED` limits don't cover `connectedDevice`; the Android 14 prerequisite — `BLUETOOTH_CONNECT` or a companion association — is checked first, and a refused start is caught). Any process start (MediaListener, companion presence, watch message) also calls `ensureRunning()` from `Application.onCreate`.
- **Glasses on hub start:** when the glasses are linked and nearby (companion presence, or the bonded glasses BT device is connected), the hub makes **one** connect attempt (`connectOnce`, which launches the glasses app via the CXR `CUSTOM_APP` session). It does not mark the glasses present, so a failure does not enter the back-off loop; skipped when a connect is already in flight (app opened).
- Stops when no linked device is present and no workout is active.
- Notification: "LiveFit ready" → "Walk · 12:34 · ♥ 142" during a workout. Re-posted only when its visible text changes and at most once per second (snapshots change several times a second).
- If a device tries to start a workout and the hub cannot be reached, that device shows **"Open LiveFit on your phone"**. (In practice: the watch can always wake the phone; the glasses cannot, because they reach us only through an open CXR session.)

- **Watch link status:** any message from the watch (delta, state report, result, claim, battery, command) marks the watch `Connected` at once and records its node for frame pushes. The 5 s connected-nodes lookup may mark it `Disconnected` only when it lists no node **and** the watch has sent nothing for 15 s (an empty lookup right after a process restart no longer shows the watch offline while live HR is flowing).

### 5.3 Glasses link — `CxrGlassesLink`
- Owned by the hub service. Session `CUSTOM_APP`, `AiInterceptMode.ALLOW_WITH_PAUSE`.
- **Authorization in the background:** companion-device apps are exempt from background-activity-launch limits, so the hub launches a transparent `AuthActivity` that calls `requestAuthorization` (silent when already authorized) and finishes, then connects. Fallback: reflection flag (quirk 2.1.1). Last resort: notification "Open LiveFit to connect glasses".
- Reconnect back-off: 2 s → 5 s → 10 s → 30 s (repeating at 30 s) while a linked glasses device is present.
- Sends `HudSettingsFrame` on every connect, `StateFrame`s on change.
- Glasses app opened manually with no session: after 12 s without a frame (liveness rule §4.3) it shows "Open LiveFit on your phone"; it shows "Connecting…" before that.

### 5.4 Voice — glasses push-to-talk
1. **Tap** on the glasses touchpad → glasses send `ListenRequest`, start `AudioRecord(MIC, 16 kHz mono)`.
2. Glasses run an **energy-based VAD**: stop after **0.8 s of silence** following speech, or at **6 s** max; stream **100 ms `AudioChunk`s** (~3.2 KB each) as they are captured.
3. Phone `LiveVoiceService` pipes chunks into `SpeechToText` immediately (streaming, partial results), locale from Settings → Voice (default **en-IN**).
4. Final text → `CommandParser[locale]` → `Command`; if a `Confirmation` is pending → `YesNoParser[locale]` → `Answer`.
   - **Composite utterances:** the text is first split into clauses on "and", "then", "and then" and commas (per language pack; clauses of filler words such as "hey", "please" are dropped), each clause goes through `CommandParser[locale]`, and the commands run **in spoken order** ("pause music and stop workout" → `PauseMusic`, `StopWorkout`). A clause that needs a confirmation (voice stop) waits for its answer before the next clause runs. If some clauses match nothing, the others still run and the toast names what wasn't understood (`Didn't catch "order a pizza"`); if none match → "Didn't catch that".
   - Recognised text and its parse are logged at debug level (`LiveFitVoice`); audio is never logged.
5. Result toast on all devices ("✓ Next song" / "Didn't catch that").
   - **A capture bound to a confirmation that is no longer pending ends silently.** The capture remembers the prompt pending when it started; if that prompt was answered on another device, expired or was replaced by the time recognition finishes, the result is dropped without any toast — whether something, something unclear ("Say yes or no") or nothing ("Didn't catch that") was heard, and also when the listen guard closed the capture.
- **Phone mic button** uses the same pipeline with the phone microphone.
- **No online recognition.** `SpeechToText` Android implementation = platform on-device recognizer only. If the selected language pack is not installed, voice is disabled with "Voice needs the English (India) pack" → Languages. (Downloaded in the setup wizard, §6.1.)
- `SpeechToText` interface: `start(locale): Session`; `Session.feed(pcm)`, `Session.end()`; flows of partial and final text; errors. Future iOS implementation: Apple on-device recognition. Bundled engines (Whisper/Vosk) are a V3 option.
- Parser coverage (already unit-tested, 18 groups): start walk/run/cycle/auto, stop/end/finish, pause/resume workout, next/skip, previous/go back, like/love/add to liked, play/resume/unpause music, pause/stop/mute music, volume up/down; music words win over workout verbs; bare "stop" ignored. Yes/no lexicon: yes, yeah, yep, ok, okay, sure, confirm, take over, do it / no, nope, cancel, stop, don't, leave it.

### 5.5 Music — `YtmMediaSessionService`
- Requires notification-listener access (Linked services → YouTube Music).
- `play/pause/next/previous` via `TransportControls`; `like` via custom action `thumbs_up_action`; `volume` via `AudioManager.STREAM_MUSIC` — `Volume(up)` = ±10 %, `SetVolume(level)` = absolute; current level published in `NowPlaying.volume`.
- `NowPlaying` from metadata + playback state (title, artist, position, duration, isPlaying, liked if exposed).
- If no YTM session exists (B2; `SearchRoute`, JVM-tested): with LiveFit **in front**, launch YTM with `MEDIA_PLAY_FROM_SEARCH` and return to LiveFit (D5). **In the background** (phone in pocket — Android blocks background activity starts) start **headless**: bind YTM's exported `MediaBrowserService` (its session comes up); if that is absent or refused, send a media-button `KEYCODE_MEDIA_PLAY` (down/up) broadcast addressed to YTM (media resumption) and wait up to 10 s for its session. Once a session answers, `HeadlessStep.onSession`: saved search → `playFromSearch`, otherwise `play()` unless already playing; if it is still not playing 3 s later, one more media-button PLAY. The browser connection is dropped after 30 s. Logged under tag `LiveFitMusic` (route, each headless step, timeouts).
- **Session attach (B3):** `MediaSessionManager.addOnActiveSessionsChangedListener` (registered once notification access exists, retried on the 1 s poll) attaches YTM's controller as soon as its session appears; the 1 s poll remains as a fallback.
- **Queue window (glasses music screen):** the service reads `MediaController.getQueue()` (on device YouTube Music reports queue title "Up next", 25 items, played and upcoming) and `PlaybackState.activeQueueItemId`, re-evaluated on `onQueueChanged` / metadata / playback callbacks and the 1 s attach poll, and publishes a `QueueWindow` flow (`MusicService.queue`). Window of **N** items (Settings → YouTube Music, 5–50, default **25**): all history before the current item that fits (at most N − 1), the current item, then up to N − M − 1 upcoming items (fewer when the queue is shorter). No or unknown active id → the first N items with no current marker; empty queue → empty window, except that a loaded song (non-blank title) is then listed alone as the current item (queue id −1; selecting it does nothing) (B3). Pure function `QueueWindowing.window` (JVM-tested). The hub pushes the window as `QueueFrame` on `lf_queue` when it changes and on (re)connect.
- `PlayQueueItem(queueId)` goes through the hub router like other music commands → `transportControls.skipToQueueItem(queueId)`; toast "Playing selected song".
- **Workout start behaviour** (Settings → Music): *Don't touch* / *Resume last played* (**default**) / *Play saved search* (e.g. "workout mix"). On stop: pause music (default on). Pause workout does not pause music by default.

### 5.6 History — `:services:history`
- Room tables: `session(id, type, detectedType, startMs, endMs, activeMs, avgHr, maxHr, steps, distanceKm, kcal, provenance, source, status: Active|Stopping|Complete|Incomplete, endReason)`, `sample(sessionId, tMs, hr, steps, distanceKm, kcal, speedKmh, provenance)`.
- Every sample has **provenance**: `Live(sourceId)` (e.g. `galaxy-watch/health-services`) or `Fake`. A session is `Live` only if all its samples are Live; otherwise it is labelled **Demo** in the UI.
- Activity tab, two levels (generic list screen): **level 1** (source `workout-days`) has one row per local calendar day, newest first — Today / Yesterday / "EEE d MMM" — with the day's workout count, total active time, kcal, steps, distance, and HR (average of the sessions' avg HR weighted by active time, ignoring sessions without HR; max = the day's max) plus "Demo n · Incomplete n" when present. Sessions are grouped by the local date of their **start** (a session crossing midnight stays on its start day); pure `DailyActivity.group(sessions, zoneId)`. **Level 2** (source `workouts`, filter `{"date":"yyyy-MM-dd"}`, header = day name) lists that day's workouts with the existing rows and Demo/Incomplete badges → detail page (summary tiles + HR chart). Settings → Data → "Clear history".

### 5.7 Confirmation — `:services:confirm`
- Holds at most one pending confirmation; new requests replace older ones of the same kind. Timeout 15 s → No. Pure Kotlin, clock injected.

---

## 6. UX (approved in brainstorming; mock-ups exist in the repo)

### 6.1 Phone (Compose, white, iconographic)
- Palette: white surfaces, mint `#14C3A2` / sky `#3D8BFF` accents, pastel icon chips; inspired by Hi Rokid, Samsung Health, AIVELA.
- **Floating pill footer** (Home, Activity, Music, Settings) on **every page except the live Workout screen**; current tab highlighted.
- **Home:** gradient header + settings button; device bubbles with status dots (glasses, watch, music); Start hero (opens type picker Walk/Run/Cycle/Auto; becomes "in progress" card); metric tiles; now-playing card.
- **Workout:** type chip + big timer; HR ring coloured by zone; calories; steps/distance/speed tiles; mini music bar; stop / pause-resume / mic controls; toast; **Summary** on stop with Done.
- **Music:** artwork, title/artist, like, progress, previous/play/next, volume.
- **Settings:** General (Languages, Units); **Linked services** (Rokid glasses, Galaxy Watch, YouTube Music; V2 adds Health Connect); Glasses display; Workout (GPS outdoors); Voice (language); Data (clear history); Advanced (Developer tools); About.
  - *Rokid glasses:* status, battery, pair/unpair/re-pair (companion association), re-authorize Hi Rokid, reconnect, glasses permissions, link to Glasses display, HUD preview.
  - *Galaxy Watch:* reachable or not, battery, watch app installed, sensor permissions, re-link, and an optional tip: turn off auto-open for the watch's Media controls (for example Settings → Apps → Media controls; the exact menu varies by One UI Watch version) so LiveFit's workout screen stays in front.
  - *YouTube Music:* notification access status, workout-start behaviour, saved search, **glasses music screen: songs listed (N, slider 5–50, default 25; persisted with the other settings, applies within ~1 s)**.
  - *Glasses display:* preview, size 30–100 % (default 40 %), position 3×3 (default bottom-centre), per-item toggles; **edits are a draft until Apply (header) or Back (auto-apply)**, then toast "Sent to glasses" / "Saved · applies when glasses connect".
- **Generic list screen** ("function screen") with source id + optional JSON filter: search, status filter, A–Z sort, grouped sections, confirm-then-run actions with optional blocking progress overlay. Sources: languages, permissions, workout-days, workouts (+ V2 playlists); a row action may navigate to any route (e.g. a filtered list).
- **Languages:** on-device speech packs; downloaded vs available; tap → download with progress overlay. Only Google's own confirmation dialog is shown (no second LiveFit dialog); the row shows pack size when known.
- **First-run setup wizard (new in V1):** icon cards — (1) Welcome + permissions (microphone, nearby devices, notifications, background), (2) Link Rokid glasses (Hi Rokid authorization + companion pairing), (3) Link watch (app installed, sensor permissions; shows the same optional Media-controls auto-open tip, never blocks setup), (4) YouTube Music notification access, (5) Voice language: choose (default en-IN) and **download pack** (required for voice; skippable but voice stays off), (6) Done → Home. An account step can be inserted before (1) in V3.
- Glasses icon (outlined) replaces the eye icon for glasses everywhere.

### 6.2 Watch (Wear Compose, black)
- **Ready:** phone + glasses status dots (online = frames arriving), big mint Start, type pill (tap to cycle).
- **Live pager (3 pages, swipe):**
  1. HR in an edge ring coloured by **effort** (green Z0–2, amber Z3, orange Z4, red Z5), faint 60 s HR trend line behind the number, "Z2 · Fat burn" label, type, timer, pause; when paused: resume + stop.
  2. Pills: calories (big), steps, distance, speed.
  3. Music: title/artist, previous / play-pause / next, like, and **volume**: a curved arc along the screen edge showing the phone's media volume (from `StateFrame.music.volume`); drag the arc or **turn the rotating bezel** (5 % per detent, only while this page is shown) → `SetVolume(level)`, throttled to ≤ 10 commands/s.
- **Summary** with Done. **Confirmation** overlay (Yes/No buttons). "Phone offline" badge in offline mode.
- **Brought to the front by the phone:** music starting with a workout puts Samsung's media controls on top, so after a successful hub start (Starting → Active) requested from the glasses, phone or voice, the hub opens `MainActivity` with `RemoteActivityHelper` (`livefit://workout`, works with the watch screen off; a watch app cannot start its own activity from the background). Not for a start tapped on the watch or a session adopted from it. The hub does the same for every new confirmation (§4.6), except a takeover prompt for a start tapped on the watch.
- **Watch-side raise (best effort):** the phone's `RemoteActivityHelper` launch goes through Galaxy Wearable's JobScheduler and can lag by minutes, so the watch also reacts itself when a hub `ExerciseOp.Start` arrives while it records, and on each new confirmation in a `StateFrame` (`FrontLaunchPolicy`: only while `MainActivity` is not resumed, once per confirmation id, at most one raise per 10 s). It (1) tries a direct `startActivity` (Android blocks it silently unless a background-start exemption applies) and (2) posts a one-shot silent `IMPORTANCE_HIGH` notification (channel `workout_front`, 15 s timeout, cancelled when `MainActivity` resumes) with a full-screen intent — only if `NotificationManager.canUseFullScreenIntent()` (Android 14+: `USE_FULL_SCREEN_INTENT` is default-granted only to calling/alarm apps on Play installs; otherwise skipped silently). The full-screen intent launches only while the screen is off/ambient; with the screen on it is a tappable heads-up. The phone path stays. Users can also turn off Media-controls auto-open (§6.1).

### 6.3 Glasses HUD (green monochrome)
- Rules: black = transparent; outlines not fills; three brightness tiers (100 / 60 / 35 %); tabular digits; thin strokes ≥ 2 px.
- **Size and position from Settings apply to every LiveFit screen** (Ready, Workout, Summary, overlays). Default **40 %, bottom-centre**.
- **Ready:** battery rings for watch / phone / glasses, "LiveFit ready", "Tap to talk · 'start workout'".
- **Workout (full):** status row (REC, **battery rings** around watch/phone/glasses icons — arc = battery %, offline = dotted ring + slash; music note), **big:** workout type (+ "A" badge for Auto) + timer, heart rate + calories; **single HR trend chart** (last ~2 min) over dotted zone lines with "Z1" label ("–" below zone 1); **small:** steps (footprints icon), distance, speed; dim now-playing line. Every item toggleable from Settings.
- **Glance mode:** timer + heart rate only. (No touchpad gesture toggles it since swipes switch pages, see Input; the mode is kept across page switches.)
- **Clock:** current local time from the glasses' own clock ("HH:mm", or "h:mm" when the device uses 12-hour time), updated on each minute boundary. Shown in the status row on Ready and Workout (full, left after REC; hidden with the status row); screens without a status row (Glance, Summary, Saving, Connecting) show it alone in the top-right corner.
- **Overlays** inside the HUD block: listening ring (pulsing), toast ("✓ Next song"), "❚❚ PAUSED", **Confirmation** (✓ Yes / ✕ No; highlight = brighter/thicker; swipe moves highlight, tap confirms, double-tap = No; initial highlight = what the user just requested; mic auto-opens ~6 s for a spoken answer).
- **Music screen** (second page, reached by a forward swipe from the workout HUD or the Ready screen): top = "MUSIC" + position "3/25" (the highlighted song) + clock, then the current song (▶/❚❚ play-state glyph, title bold 100 %, artist 60 %, "playing"/"paused"). Below a hairline: the queue window (§5.5), 7 rows visible, scrolled to keep the highlight in view; played songs 35 %, upcoming 60 %, current song 100 % bold with a ▶ mark; the highlighted row has a bright outline (no fill), always shown while there is a queue (on the current song unless moved). Bottom: a dim hint line — "swipe: songs · long swipe: pages · tap: play/pause" (highlight on the current song), "… · tap: play song" (highlight moved), "tap: talk · long swipe: pages" (empty queue). "No queue from YouTube Music" / "Nothing playing" when empty. Overlays (confirmation, toasts, listening) draw on top as on the HUD.
- **Summary:** type "DONE", timer, avg HR, kcal, distance.
- **Input.** Touchpad key sequences, verified on device with `getevent` ("ROKID,PSOC-TP-R"); every gesture starts with key 83 (KEY_DASHBOARD = KEYCODE_NOTIFICATION, finger touch):
  - tap = 83, then ENTER ~0.5 s later; double-tap = 83, 83, then BACK; long press = KEY_PROG1 (system "Hi Rokid", not used); vertical swipes are not sensed.
  - **short swipe** forward = 83, one RIGHT, then ~30–50 ms later one DOWN "twin" (back: LEFT then UP); **long swipe** = 83, two RIGHT ~50–90 ms apart, then DOWN (back: two LEFT, then UP). Measured: 26/26 short swipes gave 1 step; 8/10 long swipes gave 2 steps, 2 gave 1 (never short → long).
  - **One gesture = one swipe** (`SwipeClassifier`): RIGHT/LEFT keys are counted per gesture; the gesture closes on its DOWN/UP twin, on the next touch (83), or **150 ms** after its last RIGHT/LEFT; a twin arriving within 400 ms after a timer-closed gesture is swallowed. Result = direction + **long when ≥ 2 steps**. Without key 83 (adb `input keyevent`), a lone RIGHT/LEFT is a short swipe (after 150 ms) and a lone DOWN/UP a short forward/back swipe at once. Swipe keys are consumed (down and up) and classified on key-down.
  - Priority: a pending (cross-device) confirmation overrides everything: any swipe moves the ✓/✕ highlight, tap confirms, double-tap = No. Next the glasses-local close prompt (below), then page navigation.
  - **Pages** are an ordered list (workout, music; more can be added): next/previous page, **no wrap** (forward on the last / back on the first does nothing); double-tap never switches pages.
  - Workout page (also Ready/Summary): **short or long swipe = next/previous page**; tap = talk.
  - Music page: the highlight is visible at once on the current song; **short swipe** moves it down/up the list (stops at the ends; stays on its song when the window shifts, else falls back to the current song); **long swipe = next/previous page**; **tap** = `PlayPause` when the highlight is on the current song, else play the highlighted song (`PlayQueueItem`); empty queue → tap = talk. **6 s** without input puts the highlight back on the current song.
  - **Double-tap** (any page, any mode) = close the app: two KEYCODE_NOTIFICATION key-downs within **400 ms**, or one BACK, is one double-tap (keys within 600 ms after it are its tail, so firmware sending both fires once; a lone key 83 does nothing; a swipe key after an 83 breaks the pair, so a quick swipe then a touch is not a double-tap). Key 83 is consumed (down and up) so the system does not background us itself. While a workout is Starting/Active/Paused/Syncing the glasses first show a local confirm **"Close LiveFit? / The workout keeps recording."** (✓ Close preselected / ✕ Stay; same visuals and swipe/tap rules as a confirmation; double-tap or **8 s** = stay); a hub confirmation arriving replaces it. Idle/Ready/Saving/Summary: close immediately. Close = `moveTaskToBack` (the workout keeps recording on phone/watch).
  - All key events are logged at debug level (`LiveFitGlasses`: action, keyCode, scanCode, repeat) to verify touchpad codes on device.

---

## 7. Error handling

| Situation | Behaviour |
|---|---|
| Workout ended by another app / system | All devices show ended immediately; Summary and history complete only after all deltas are stored (§4.9); otherwise `Incomplete`. |
| Watch ↔ phone link drops mid-workout | After the liveness timeout (§4.3) the watch records locally (§4.4); phone "Watch offline"; glasses watch ring dotted + slash; gap replayed on reconnect. |
| Glasses disconnect | Workout continues; reconnect with back-off; HUD resumes. |
| Phone restarts / app killed mid-workout | Watch continues offline; next watch message wakes the phone; hub adopts the session from the watch buffer. |
| HR unavailable | HUD "--", watch "acquiring"; other metrics continue. |
| Another app tracking | `TakeOverWorkout` confirmation; 15 s silence = No. |
| Conflicting simultaneous commands | Arrival order; duplicate ids ignored; result broadcast. |
| Voice not understood | "Didn't catch that"; no state change. |
| Voice pack missing | Voice disabled with link to Languages; **no online fallback**. |
| YTM not running / no notification access | Music controls show "Connect YouTube Music" → Linked services. |
| Rokid authorization lost | Transparent AuthActivity → reflection fallback → notification. |
| Battery ≤ 15 % on any device | Ring at full brightness; one warning toast per device per workout. |
| Protocol mismatch | Coordinated upgrades (§4.7): any `protocolVersion` difference → the outdated device shows "Update LiveFit on your <device>" and its commands are ignored until updated. |

---

## 8. Testing

| Level | Scope | Location |
|---|---|---|
| Unit (pure Kotlin) | Workout state machine (pause excluded, auto-detect, gap replay from events + samples, duplicate commands, Syncing rejects workout commands), exercise-control results (permission missing, other app tracking, timeout, late ok stops only the abandoned session, WrongSession rejection, abandoned-start reconciliation on reconnect, ended by other app), completion rule (ended report before outstanding deltas → stays Stopping until all seqs through final are stored; final seq received with a gap (100 stored, 99 missing) stays Stopping and becomes Incomplete at 24 h; lost buffer → Incomplete), delta ack only after durable store + retry/dedup, liveness (paused session with 5 s heartbeat never goes offline), parsers per language incl. ASR-style variants, yes/no parser, protocol round-trips + version tolerance, confirmation (first answer wins, timeout), provenance rule, ack + buffer pruning. Existing: 27 tests. | `:core:*`, `:services:*` |
| Contract | Each Live service passes the same suite as its Fake. | per service |
| Android instrumented | Room history, settings store, setup wizard, list screen, Apply/auto-apply. | phone, watch |
| On-device scripted | adb scripts: start/pause/stop from each device and verify all three; latency watch-sample → glasses render (log, target ≤ 1 s); glasses push-to-talk with recorded phrases; Samsung Health takeover with confirm on each device; watch Bluetooth off mid-workout → on → no gap. | `tools/device-tests/` |
| Acceptance | Owner's real walk/run against §1.3. | owner |

---

## 9. Risks and mitigations

| Risk | Mitigation |
|---|---|
| CXR-L background authorization (quirks 2.1.1–2.1.2) | Companion-device BAL exemption + transparent AuthActivity; reflection fallback; notification fallback. Spike this first in the plan. |
| Wear OS kills the watch app | `health` FGS + Ongoing Activity; offline buffer guarantees no loss. |
| Data Layer latency spikes | Push-on-change, coalesce 100 ms; measure in device tests. |
| YTM changes custom action names | Match by action id and name keywords; Like falls back to hidden if absent. |
| Glasses low RAM (Android Go) | HUD Compose kept simple; no images; audio buffer ≤ 6 s. |
| Battery on glasses (continuous HUD) | Push-on-change (no 1 Hz redraw when idle); glance mode. |

## 10. Decisions log (from brainstorming)
- Rokid SDK only (CXR-L/CXR-S), never raw RFCOMM.
- Phone = hub; watch = sensor + client; glasses = HUD + client.
- Approach A for offline: watch buffers temporarily, deletes after phone ack.
- Hub service starts when glasses or a wearable is nearby.
- Music workout-start default: Resume; stop pauses music.
- No online speech recognition; pack downloaded in setup.
- Take over another app's workout only after a confirmation; any device can answer; timeout = No.
- Glasses confirm by touchpad and voice.
- HUD settings apply to all LiveFit glasses screens; draft + Apply / auto-apply on back.
- Footer persistent except live Workout screen.
- Review round 1 (Codex): 12 s liveness vs 5 s heartbeat; SessionDelta events + DeltaAck after durable store + SessionClaim/Syncing adoption; explicit hub → watch ExerciseRequest/Result/StateReport; coordinated-upgrade version policy; watch volume via edge arc + rotating bezel (`SetVolume`).
- Review round 2 (Codex): session-scoped exercise ops + WrongSession + abandoned-start reconciliation; single completion rule (§4.9) with `Incomplete` status.
