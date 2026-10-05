# Review brief: Rokid LiveFit specs (V1 + V2 + V3 roadmap)

## What to review
- `docs/superpowers/specs/2026-10-05-livefit-v1-design.md` (live core, the main target)
- `docs/superpowers/specs/2026-10-05-livefit-v2-design.md` (Health Connect + YouTube playlists)
- `docs/superpowers/specs/2026-10-05-livefit-v3-roadmap.md` (future themes, not approved for build)

Repo: `live-fitness-tracker/` (Gradle multi-module, branch `design/livefit-v1-v2`). Mock-up code already exists in all modules; the specs describe replacing Fakes with Live implementations.

Please check: internal consistency, contradictions between V1/V2, missing failure modes, unrealistic platform assumptions, latency feasibility, testability, scope creep, and anything ambiguous enough to be built two ways.

## Product in one paragraph
Personal-use (sideloaded APKs, no Play Store yet) fitness app on three devices:
- **Samsung Galaxy S25 phone:** hub.
- **Galaxy Watch6 Classic, Wear OS 6:** sensors + controller.
- **Rokid Glasses:** YodaOS / Android 12, 480×640 portrait green monochrome Micro-LED in the right eye, touchpad.

During a workout the glasses show live watch data (heart rate, calories, timer, workout type big; steps, distance, speed small). Workout and YouTube Music can be controlled from any of the three devices, and every change syncs to all three.

**Success:**
- ≤ 1 s latency from watch sensor to glasses (2 s hard ceiling);
- every control works from every device;
- the session is saved in history;
- no data is lost if the phone disconnects.

## Facts verified on the real devices (treat as constraints)
1. **Rokid link = CXR-L 1.1.2** (`com.rokid.cxr:client-l`) on the phone, going through the Hi Rokid app; `cxr-service-bridge:1.4` on the glasses.
   - **No client secret needed.** CXR-M would need Rokid-issued credentials the owner doesn't have. CXR-L authorizes through Hi Rokid and returns a token.
   - The phone opens our glasses APK in ~1 s (`SessionType.CUSTOM_APP`).
   - Custom messages work both ways; round trip ~30–40 ms, with occasional 500 ms spikes.
2. **CXR-L quirks:**
   - "Use the global Hi Rokid package" and the granted permissions are kept in **static memory flags**, set only by `requestAuthorization` in the current process. After a restart, `connect()` binds the China package and fails. Workaround: silent re-authorization per process, plus a reflection flag.
   - `bindService` fails when the app is in the background or the phone is locked, so a **foreground service is required**.
   - When the glasses leave our app, the session terminates (`GLASSES_APP_EXIT`).
3. **"Hi Rokid" voice can't be used:**
   - The wake event reaches us, but the Rokid assistant keeps the mic: the SDK audio is digital silence, it answers by itself and returns to the launcher.
   - `BLOCK_AI` mode is deprecated and does nothing.
   - Third-party apps can't register voice phrases, and voice can't open sideloaded apps.
   - **What works:** a plain `AudioRecord` inside our glasses app records clean 16 kHz audio. Hence **tap-to-talk**.
4. **Speech:**
   - The phone's on-device recognizer works when fed glasses PCM through a **pipe** (a regular file descriptor crashes the service).
   - Language packs can be queried and downloaded with progress (API 33/34).
   - en-IN and en-US are installed.
5. **Watch (Health Services):**
   - The phone can launch the watch app remotely, even with the screen off. A Data Layer message wakes the app after process death.
   - Wear OS 6 needs `health.READ_HEART_RATE`.
   - Heart rate arrives at ~1 Hz; watch → glasses was ~0.75 s with 1 Hz polling.
   - Phone and watch must share the applicationId and signing key.
6. **Samsung Health conflict:** we can detect `OTHER_APP_IN_PROGRESS` (Samsung Health's walk was reported as `RUNNING_TREADMILL`). A forced start **ends** Samsung Health's workout (not reversible, ~7 s handover).
7. **YouTube Music:**
   - The media session supports play/pause/next/previous and custom actions `thumbs_up_action` (Like), dislike, shuffle, repeat.
   - **No playback speed.**
   - **No video ID in the metadata**, which is why V2 needs a YouTube search to find the song.
8. **Bug found by the protocol tests:** kotlinx sealed-class discriminator `"type"` clashed with `StartWorkout.type`, so the spec uses `"cmd"`.

## Key decisions and why
| Decision | Why | Alternatives rejected |
|---|---|---|
| Rokid SDK only (CXR-L/S), never raw Bluetooth RFCOMM | Owner requirement; official path; earlier apps wrongly used raw RFCOMM | Raw SPP |
| **Phone is the hub** (single source of truth); watch and glasses send Commands and render StateFrames | Sync across 3 devices without conflicts; history in one place; future ring/band sources plug in the same way | Watch-owned workout (doesn't fit other sources), dual-record + merge (conflicts) |
| **Offline approach A:** the watch always streams `SessionDelta`s (events: start/pause/resume/stop/type + samples); if the phone is unreachable it runs a local copy, keeps the header + deltas durably, and deletes them only after a `DeltaAck(sessionId, seq)` sent once the phone has stored them durably. On reconnect a `SessionClaim` puts the phone in `Syncing` (workout commands rejected) until replay completes | No data loss; pause intervals/active time reconstructable; watch-only starts possible | Phone-required (loses data) |
| Hub → watch exercise control via session-scoped `ExerciseRequest` / `ExerciseResult` / `ExerciseStateReport` (permission missing, other app tracking, timeout with abandoned-start reconciliation, `WrongSession` rejection, ended by another app) | Phone decides, watch reports what Health Services actually did; a late result can never stop a newer workout | Implicit control |
| Single completion rule: a session is `Complete` (summary, history, Health Connect) only when all deltas through the final seq are durably stored; unrecoverable → `Incomplete` | An end report can arrive before outstanding data | Finalise on end report |
| Version policy: coordinated upgrades — any `protocolVersion` mismatch → "Update LiveFit on <device>", commands ignored | Three personal-use APKs built from one commit | Mixed-version compatibility |
| Push-on-change state frames (100 ms coalescing + 5 s heartbeat) instead of a 1 Hz tick; offline only after 12 s without a frame or a transport disconnect | 1 Hz polling added up to 1 s latency; target ≤ 1 s; a paused session must not look offline | Fixed tick; 3 s timeout (contradicted the heartbeat) |
| Modular services: one module per service, Fake + Live behind interfaces, bindings in one wiring file per app | Mock-ups first, swap to live per service; testability | Separate processes / AIDL (overkill, battery) |
| Pure Kotlin for core logic (no Android imports) | Future iOS via Kotlin Multiplatform; owner wants platform decoupling | Android-coupled core |
| Phone foreground service starts when the glasses **or** a wearable is nearby (companion-device presence); otherwise the device shows "Open LiveFit on your phone" | The Rokid SDK needs a foreground service; the watch can wake the phone, the glasses can't | Always-on (battery), workout-only (friction) |
| Background Rokid authorization via a transparent activity (companion apps may launch activities from the background), with reflection and notification fallbacks | Quirk 2: authorization needs an Activity in-process | — |
| Tap-to-talk: glasses record, voice-activity detection (stop at 0.8 s silence / 6 s max), 100 ms PCM chunks streamed to the phone, on-device recognition, rule parser | Fact 3; streaming saves latency | Hi Rokid wake (impossible) |
| **No online speech recognition**; the language pack is downloaded in the first-run setup; voice is disabled without it | Owner: no Google cloud dependency; keeps a `SpeechToText` interface for a future iOS implementation | Online fallback (rejected by owner) |
| Language registry (speech + parser + yes/no lexicon keyed by locale), default en-IN | Indian-accent English now, local languages later | English-only assumptions |
| Rule-based command parser in V1 (18 tested groups); LLM intent deferred to V3 | Core happy path first | Gemini Nano / Claude fallback now |
| Takeover of another app's workout **only after a confirmation**, shown on all 3 devices; first answer wins; 15 s silence = No. Glasses answer by touchpad (swipe/tap, double-tap = No) or voice (mic opens automatically ~6 s) | The takeover is destructive and irreversible (fact 6) | Silent takeover |
| Music when a workout starts: a setting, default "Resume last played"; stopping pauses music | Owner choice | Never touch / always auto-play |
| History: Room on the phone, 1 Hz samples, **provenance** per sample (Live(source) / Fake); Activity tab shows **LiveFit sessions only** + (V2) Health Connect daily totals | Owner choice; provenance enables the V2 real-data guarantee | Merging other apps' workouts |
| V2: Health Connect write-back **only for 100% Live sessions**; the writer refuses ineligible sessions at the API boundary and isn't even bound in Fake builds; `clientRecordId` makes writes idempotent | Owner: never write demo data | UI-only guard |
| V2: YouTube sign-in under Settings → Linked services; OAuth app "In production (unverified)"; no refresh token stored — `AuthorizationClient.authorize()` is called again for silent access tokens, resolution → "Reconnect YouTube" | Testing-mode consent is time-limited; AuthorizationClient doesn't give Android apps a refresh token | Storing a refresh token |
| V2 add-to-playlist: search by title + artist (no video ID) + `videos.list` durations, cache, confirm on a weak match with the request bound to the captured song/candidate/playlist; verify before retrying an uncertain insert | Fact 7; quota per current docs: search has its own 100 calls/day bucket → ≈100 new-song lookups/day, cached songs free | — |

## Approved UX (mock-ups exist in code and were tested on devices)
- **Phone:** white, icon-led (pastel icon chips; inspired by Hi Rokid / Samsung Health / AIVELA). Floating footer on every page except the live Workout screen. Generic reusable list screen (search / filter / sort / confirm + progress overlay).
  - **Settings → Linked services:** Rokid glasses (pair / re-pair / authorize), Galaxy Watch, YouTube Music, and Health Connect (V2).
  - **Glasses display settings:** size 30–100% (default 40%), 3×3 position (default bottom-centre), per-item toggles. Edits are a draft until Apply, or auto-apply on Back.
- **Watch:** black. Pager with three pages:
  1. heart rate in a ring coloured by effort (green → amber → red), a faint 60 s trend line behind it, and the zone label;
  2. stat pills;
  3. music controls incl. volume (edge arc + rotating bezel → `SetVolume`).
  Then a summary screen.
- **Glasses HUD:**
  - **Rendering rules:** black = transparent, outlines only, brightness tiers 100 / 60 / 35%.
  - **Settings apply to every LiveFit screen.** (An earlier observation that the Rokid system menu had shifted turned out to be unrelated; resetting fixed it, and the app writes no system settings.)
  - **Status row:** battery rings around the watch / phone / glasses icons (offline = dotted ring + slash).
  - **One heart-rate chart** with dotted zone lines.
  - **Glance mode:** timer + heart rate only.
  - **Overlays:** listening, toasts, paused, confirmation.

## Known limits / constraints
- The glasses can't wake the phone. Starting from the glasses requires the phone service to already be running.
- No voice launch from the Rokid home screen (V3: Agent Store / phone-assistant path, untested).
- No playback speed with YouTube Music.
- Samsung Health takeover is irreversible.
- Rokid SDK quirks rely on reflection; fragile across SDK updates.
- Bluetooth latency spikes up to ~500 ms observed on the Rokid link.
- The glasses are a low-RAM Android Go device.
- The phone has a work profile: always install and act in user 0.
- Personal use: no Play policy work yet (V3 Theme G lists what publishing will need).

## Open risks the specs call out
1. Background Rokid authorization (spike first in the plan).
2. Wear OS killing the watch app (mitigated by the health foreground service + ongoing activity + buffer).
3. Data Layer latency.
4. YouTube Music renaming its custom actions.
5. Glasses battery and memory.

## Test baseline today
27 unit tests pass:
- protocol round-trips (incl. all commands);
- workout state machine (pause excluded, auto-detect, no double start, summary);
- parser (18 groups, ~110 phrases).

The specs add contract tests (Live vs Fake), instrumented tests, adb-scripted device tests (sync, latency, takeover, offline gap) and a real-workout acceptance test.
