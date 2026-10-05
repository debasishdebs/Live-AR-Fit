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
| Glasses | Rokid Glasses (RG-glasses), YodaOS on Android 12 / API 32, **480×640 portrait** green monochrome Micro-LED (right eye), touchpad emits DPAD/ENTER key events, double-tap = BACK. |

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

All messages are JSON (kotlinx.serialization) carrying `protocolVersion` (integer, starts at 1; policy in §4.7). Unknown fields are ignored defensively. Sealed hierarchies use discriminator key **`"cmd"`** (the default `"type"` collides with `StartWorkout.type` — bug found in spikes).

### 4.1 Messages
| Message | Direction | Content |
|---|---|---|
| `StateFrame` | phone → watch, glasses | `workout: WorkoutSnapshot`, `music: NowPlaying?` (incl. `volume` 0..1), `devices` (link state + battery for phone/watch/glasses), `voice: VoiceState`, `confirmation: Confirmation?`, `toast: String?`, `sentAtMs`. |
| `HudSettingsFrame` | phone → glasses | `HudSettings` (scale 0.3–1.0, `HudPosition` 3×3, `items: Set<HudItem>`). Sent on change and on every (re)connect. |
| `Command` | any → phone | `id: String` (UUID, dedup) + one of: `StartWorkout(type)`, `PauseWorkout`, `ResumeWorkout`, `StopWorkout`, `DismissSummary`, `PlayPause`, `PlayMusic`, `PauseMusic`, `NextTrack`, `PreviousTrack`, `LikeTrack`, `Volume(up)` (voice, ±10 %), `SetVolume(level 0..1)` (watch arc/bezel, phone slider), `Answer(confirmationId, yes)`. |
| `SessionDelta` | watch → phone | `sessionId`, `seq` (monotonic per session), `events: [SessionEvent]`, `samples: [{tMs, hr?, stepsTotal, distanceKmTotal, kcalTotal, speedKmh?}]`, `provenance`, `final: Boolean`. `SessionEvent` = `Started(tMs, type)` · `Paused(tMs)` · `Resumed(tMs)` · `TypeDetected(tMs, type)` · `Stopped(tMs, reason)`. |
| `DeltaAck` | phone → watch | `sessionId`, `seq` — highest contiguous seq **durably stored** on the phone (§4.4). |
| `SessionClaim` | watch → phone | Sent on reconnect when the watch holds an offline session: `sessionId`, `type`, `startMs`, `phase`, `activeMs`, `lastSeq`. |
| `ExerciseRequest` | phone → watch | `requestId`, `sessionId`, `op`: `Start(type, force)` · `Pause` · `Resume` · `Stop` (§4.8). **Every op names its session.** |
| `ExerciseResult` | watch → phone | `requestId`, `sessionId`, `ok`, `error?` (`PermissionMissing(perms)` · `OtherAppTracking(appType)` · `SensorUnavailable` · `WrongSession(activeSessionId)` · `NoSuchSession` · `Internal(msg)`), `state` (actual Health Services exercise state), `activeSessionId?`. |
| `ExerciseStateReport` | watch → phone | Unsolicited, whenever the real exercise state changes: `sessionId`, `state`, `endedBy?` (`User` · `OtherApp` · `System` · `Error`). |
| `ListenRequest` / `AudioChunk` / `ListenEnd` | glasses → phone | Push-to-talk: start, 100 ms PCM16 chunks (binary payload in CXR `bytes`), end (VAD or cap). |
| `BatteryReport` | watch → phone | Percentage, on request and every 60 s. |

### 4.2 Transports and channel names
- **Glasses (CXR custom cmd names):** `lf_state`, `lf_settings` (phone → glasses); `lf_cmd`, `lf_listen`, `lf_audio`, `lf_listen_end` (glasses → phone). Payload: `Caps` with one JSON string; audio uses the `bytes` argument.
- **Watch (Data Layer message paths):** `/lf/state`, `/lf/settings`, `/lf/cmd`, `/lf/delta`, `/lf/ack`, `/lf/claim`, `/lf/exercise_req`, `/lf/exercise_res`, `/lf/exercise_state`, `/lf/battery_req`, `/lf/battery`.
- **Migration:** the mock-up uses `lf_hud` / `lf_cmd` / `lf_listen` (glasses) and `/rf/*` (watch) with a combined `HudFrame`. V1 renames to the names above, splits `HudFrame` into `StateFrame` + `HudSettingsFrame`, and bumps `protocolVersion` to 1. The spike-only `rf_ping` / `rf_metrics` channels, `SpikeActivity` and `DebugReceiver` move behind a debug build type.

### 4.3 Push-on-change and latency
- `StateBroadcaster` emits a `StateFrame` **whenever state changes**, coalescing changes within **100 ms**, plus a **5 s heartbeat**. (Replaces the mock-up's fixed 1 s tick, which added up to 1 s latency.) During an active workout frames flow ~1 Hz anyway (each sample changes state); the heartbeat matters when idle or paused.
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

### 4.7 Version policy — coordinated upgrades
All three APKs are built from the same commit and share one `protocolVersion`. Any mismatch (not only major) is treated as incompatible: the hub ignores commands from that device and tells it to update; the hub shows which device is outdated. `tools/install-all.sh` installs all three together. Unknown-field tolerance stays only as defensive parsing, not as a compatibility promise.

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
- GPS: enabled for Run / Cycle / Auto when Settings → Workout → "Use GPS outdoors" is on (requires location permission). Walk uses step-based distance.
- Before starting: if `OTHER_APP_IN_PROGRESS`, the watch returns `OtherAppTracking`; the phone raises `TakeOverWorkout`; the watch force-starts only on a new `Start(force=true)` (§4.8).
- Fix from spikes: status messages must be sent **before** `stopSelf()` cancels the service scope.
- Permissions: `BODY_SENSORS`, `health.READ_HEART_RATE`, `ACTIVITY_RECOGNITION`, `FOREGROUND_SERVICE_HEALTH`, `POST_NOTIFICATIONS`, optional `ACCESS_FINE_LOCATION`.

### 5.2 Phone hub service — `LiveFitHubService`
- Foreground service (`connectedDevice`). Hosts the `ServiceGraph`; UI screens are views onto it.
- **Started when either the glasses or a wearable is nearby**: `CompanionDeviceManager` associations for the glasses' and watch's Bluetooth devices; `startObservingDevicePresence` → `CompanionDeviceService.onDeviceAppeared` starts the hub. Also started by any incoming watch Data Layer message (which wakes the app) and when the app UI opens.
- Stops when no linked device is present and no workout is active.
- Notification: "LiveFit ready" → "Walk · 12:34 · ♥ 142" during a workout.
- If a device tries to start a workout and the hub cannot be reached, that device shows **"Open LiveFit on your phone"**. (In practice: the watch can always wake the phone; the glasses cannot, because they reach us only through an open CXR session.)

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
5. Result toast on all devices ("✓ Next song" / "Didn't catch that").
- **Phone mic button** uses the same pipeline with the phone microphone.
- **No online recognition.** `SpeechToText` Android implementation = platform on-device recognizer only. If the selected language pack is not installed, voice is disabled with "Voice needs the English (India) pack" → Languages. (Downloaded in the setup wizard, §6.1.)
- `SpeechToText` interface: `start(locale): Session`; `Session.feed(pcm)`, `Session.end()`; flows of partial and final text; errors. Future iOS implementation: Apple on-device recognition. Bundled engines (Whisper/Vosk) are a V3 option.
- Parser coverage (already unit-tested, 18 groups): start walk/run/cycle/auto, stop/end/finish, pause/resume workout, next/skip, previous/go back, like/love/add to liked, play/resume/unpause music, pause/stop/mute music, volume up/down; music words win over workout verbs; bare "stop" ignored. Yes/no lexicon: yes, yeah, yep, ok, okay, sure, confirm, take over, do it / no, nope, cancel, stop, don't, leave it.

### 5.5 Music — `YtmMediaSessionService`
- Requires notification-listener access (Linked services → YouTube Music).
- `play/pause/next/previous` via `TransportControls`; `like` via custom action `thumbs_up_action`; `volume` via `AudioManager.STREAM_MUSIC` — `Volume(up)` = ±10 %, `SetVolume(level)` = absolute; current level published in `NowPlaying.volume`.
- `NowPlaying` from metadata + playback state (title, artist, position, duration, isPlaying, liked if exposed).
- If no YTM session exists: `PlayMusic` launches YTM with `MEDIA_PLAY_FROM_SEARCH` using the saved query.
- **Workout start behaviour** (Settings → Music): *Don't touch* / *Resume last played* (**default**) / *Play saved search* (e.g. "workout mix"). On stop: pause music (default on). Pause workout does not pause music by default.

### 5.6 History — `:services:history`
- Room tables: `session(id, type, detectedType, startMs, endMs, activeMs, avgHr, maxHr, steps, distanceKm, kcal, provenance, source, status: Active|Stopping|Complete|Incomplete, endReason)`, `sample(sessionId, tMs, hr, steps, distanceKm, kcal, speedKmh, provenance)`.
- Every sample has **provenance**: `Live(sourceId)` (e.g. `galaxy-watch/health-services`) or `Fake`. A session is `Live` only if all its samples are Live; otherwise it is labelled **Demo** in the UI.
- Activity tab: list (generic list screen, source `workouts`) and a detail page (summary tiles + HR chart). Settings → Data → "Clear history".

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
  - *Galaxy Watch:* reachable or not, battery, watch app installed, sensor permissions, re-link.
  - *YouTube Music:* notification access status, workout-start behaviour, saved search.
  - *Glasses display:* preview, size 30–100 % (default 40 %), position 3×3 (default bottom-centre), per-item toggles; **edits are a draft until Apply (header) or Back (auto-apply)**, then toast "Sent to glasses" / "Saved · applies when glasses connect".
- **Generic list screen** ("function screen") with source id + optional JSON filter: search, status filter, A–Z sort, grouped sections, confirm-then-run actions with optional blocking progress overlay. Sources: languages, permissions, workouts (+ V2 playlists).
- **Languages:** on-device speech packs; downloaded vs available; tap → download with progress overlay. Only Google's own confirmation dialog is shown (no second LiveFit dialog); the row shows pack size when known.
- **First-run setup wizard (new in V1):** icon cards — (1) Welcome + permissions (microphone, nearby devices, notifications, background), (2) Link Rokid glasses (Hi Rokid authorization + companion pairing), (3) Link watch (app installed, sensor permissions), (4) YouTube Music notification access, (5) Voice language: choose (default en-IN) and **download pack** (required for voice; skippable but voice stays off), (6) Done → Home. An account step can be inserted before (1) in V3.
- Glasses icon (outlined) replaces the eye icon for glasses everywhere.

### 6.2 Watch (Wear Compose, black)
- **Ready:** phone + glasses status dots (online = frames arriving), big mint Start, type pill (tap to cycle).
- **Live pager (3 pages, swipe):**
  1. HR in an edge ring coloured by **effort** (green Z0–2, amber Z3, orange Z4, red Z5), faint 60 s HR trend line behind the number, "Z2 · Fat burn" label, type, timer, pause; when paused: resume + stop.
  2. Pills: calories (big), steps, distance, speed.
  3. Music: title/artist, previous / play-pause / next, like, and **volume**: a curved arc along the screen edge showing the phone's media volume (from `StateFrame.music.volume`); drag the arc or **turn the rotating bezel** (5 % per detent, only while this page is shown) → `SetVolume(level)`, throttled to ≤ 10 commands/s.
- **Summary** with Done. **Confirmation** overlay (Yes/No buttons). "Phone offline" badge in offline mode.

### 6.3 Glasses HUD (green monochrome)
- Rules: black = transparent; outlines not fills; three brightness tiers (100 / 60 / 35 %); tabular digits; thin strokes ≥ 2 px.
- **Size and position from Settings apply to every LiveFit screen** (Ready, Workout, Summary, overlays). Default **40 %, bottom-centre**.
- **Ready:** battery rings for watch / phone / glasses, "LiveFit ready", "Tap to talk · 'start workout'".
- **Workout (full):** status row (REC, **battery rings** around watch/phone/glasses icons — arc = battery %, offline = dotted ring + slash; music note), **big:** workout type (+ "A" badge for Auto) + timer, heart rate + calories; **single HR trend chart** (last ~2 min) over dotted zone lines with "Z1" label ("–" below zone 1); **small:** steps (footprints icon), distance, speed; dim now-playing line. Every item toggleable from Settings.
- **Glance mode** (touchpad swipe): timer + heart rate only.
- **Overlays** inside the HUD block: listening ring (pulsing), toast ("✓ Next song"), "❚❚ PAUSED", **Confirmation** (✓ Yes / ✕ No; highlight = brighter/thicker; swipe moves highlight, tap confirms, double-tap = No; initial highlight = what the user just requested; mic auto-opens ~6 s for a spoken answer).
- **Summary:** type "DONE", timer, avg HR, kcal, distance.
- Input: tap = talk (or confirm in a prompt), swipe = full/glance (or move highlight), double-tap = back/No.

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
