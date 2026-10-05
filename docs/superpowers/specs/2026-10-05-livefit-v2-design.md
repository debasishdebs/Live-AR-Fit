# Rokid LiveFit — V2 Design (Health Connect + YouTube playlists)

- **Date:** 2026-10-05
- **Status:** Draft for review
- **Depends on:** [V1 design](2026-10-05-livefit-v1-design.md) — history store, provenance, Live music service, Linked services screen, generic list screen.
- **Next:** [V3 roadmap](2026-10-05-livefit-v3-roadmap.md)

V1 and V2 are designed together and planned together; V2 is built after V1 passes its acceptance test.

---

## 1. Intent

1. **Health Connect:** every finished, *real* LiveFit workout is saved to Health Connect so other health apps see it; the Activity tab gains a **daily totals** card.
2. **YouTube playlists:** add the currently playing song to a chosen playlist from any device or by voice.

### Success criteria
- A real V1 workout appears in Health Connect (and therefore Samsung Health / Google Fit-style readers) with matching duration, HR samples, steps, distance and calories.
- **No demo/fake data ever reaches Health Connect.**
- "Add to playlist" from phone, watch, glasses and voice adds the right song to the default playlist.
- Google sign-in survives beyond 7 days without re-prompting.

### Out of scope
Reading other apps' workouts into the Activity list (owner chose **LiveFit only**), playlist creation/editing beyond "add current song", everything in V3.

---

## 2. Health Connect — `:services:health-connect`

### 2.1 Write-back
- Trigger: session reaches `Summary` (finalised) and is **eligible** (§2.2).
- Records (all with `metadata.clientRecordId = "<sessionId>:<kind>[:<index>]"` and `clientRecordVersion` so re-writes upsert, never duplicate):
  - `ExerciseSessionRecord` — type mapping Walk → `EXERCISE_TYPE_WALKING`, Run → `RUNNING`, Cycle → `BIKING`, Auto → detected type; title "LiveFit <type>"; start/end; pauses as `ExerciseSegment`s or excluded via laps if supported.
  - `HeartRateRecord` — 1 Hz samples, chunked (≤ 1,000 samples per record).
  - `StepsRecord`, `DistanceRecord`, `ActiveCaloriesBurnedRecord` — per-minute intervals derived from cumulative totals.
  - `SpeedRecord` — sampled series when available.
- **Retry queue** (Room table `hc_outbox(sessionId, attempts, lastError, nextAttemptMs)`): if Health Connect is unavailable, permissions missing, or a write fails, the session stays queued with exponential back-off (1 min → 1 h); status visible in Linked services → Health Connect ("Last synced 2 min ago" / "3 workouts waiting").
- Samsung Health takeover case: SH writes its own partial session; LiveFit writes its own — distinct workouts, not duplicates.

### 2.2 Real-data guarantee (provenance gate)
- A session is **eligible** only if **every** sample's provenance is `Live` and the session's `source` is a Live metrics source.
- `HealthConnectWriter` refuses ineligible sessions at the API boundary (not just in UI), returning `Rejected(NotLive)`.
- The writer is **only bound in the ServiceGraph when the metrics binding is Live**; Fake builds bind a no-op writer, so demo builds cannot write even if permissions are granted.
- "Export past workouts" skips Demo sessions and shows how many were skipped.
- Unit tests: mixed Live+Fake session rejected; all-Fake rejected; all-Live accepted; re-write is idempotent (same `clientRecordId`s).

### 2.3 Daily totals card (Activity tab)
- Today (local midnight → now), aggregated by Health Connect across all apps (HC de-duplicates by data-origin priority):
  - Steps — `StepsRecord.COUNT_TOTAL`
  - Active calories — `ActiveCaloriesBurnedRecord.ACTIVE_CALORIES_TOTAL`
  - Resting heart rate — latest `RestingHeartRateRecord`
- Refresh on tab open and every 15 min while visible; card shows "Connect Health Connect" when not linked.
- The workout list below remains **LiveFit sessions only**.

### 2.4 Permissions and setup
- Write: `WRITE_EXERCISE`, `WRITE_HEART_RATE`, `WRITE_STEPS`, `WRITE_DISTANCE`, `WRITE_ACTIVE_CALORIES_BURNED`, `WRITE_SPEED`.
- Read: `READ_STEPS`, `READ_ACTIVE_CALORIES_BURNED`, `READ_RESTING_HEART_RATE`.
- Manifest must declare the **permissions-rationale / privacy-policy activity** (`ACTION_SHOW_PERMISSIONS_RATIONALE` + `VIEW_PERMISSION_USAGE` alias); without it the HC permission dialog never appears.
- Settings → Linked services → **Health Connect**: install/update status, permission rows (generic list screen, Granted / Needs your OK), "Save workouts" switch (default on once linked), "Export past workouts", last sync / queue status.
- Setup wizard (V1) gains an optional step "Save workouts to Health Connect" after the watch step.

### 2.5 Interfaces
```kotlin
interface HealthDataSink {               // :core:services
    suspend fun write(session: FinishedSession): SinkResult   // Written | Queued | Rejected(reason)
    suspend fun dailyTotals(day: LocalDate): DailyTotals?
    val status: StateFlow<SinkStatus>    // Linked | NeedsPermissions | Unavailable, queue size, lastSync
}
```
Live: `HealthConnectSink`. Fake: in-memory sink for tests/demo (never touches HC). Future iOS: HealthKit sink implementing the same interface.

---

## 3. YouTube playlists — `:services:playlists`

### 3.1 Sign-in (Settings → Linked services → YouTube Music)
- "Sign in with Google" using the Credential Manager / `AuthorizationClient` flow requesting scope `https://www.googleapis.com/auth/youtube`.
- Google Cloud project OAuth consent screen set to **"In production" (unverified)** — testing-mode refresh tokens expire after 7 days. Owner accepts the "unverified app" warning once.
- Refresh token stored with **EncryptedSharedPreferences / Android Keystore**. Sign out revokes and deletes it.
- Screen also shows: notification-access status (V1), workout-start music behaviour (V1), **Default playlist** picker (generic list screen, source `playlists`, from `playlists.list?mine=true`).

### 3.2 "Add to playlist" command
- New `Command.AddToPlaylist` (optional `playlistId`, default = Settings default).
- Entry points: phone Music screen button, watch music page button, glasses (voice only, plus confirmation highlight), voice phrases: "add to playlist", "save this song", "add this song to my playlist", "add to workout playlist" (parser + tests).
- Flow:
  1. Take `NowPlaying(title, artist, durationMs)` from the media session (no video id is exposed — verified).
  2. Resolve video: cache lookup (Room `track_cache(title, artist) → videoId, confidence`), else `search.list(q="<title> <artist>", type=video, videoCategoryId=10 Music, maxResults=5)`; score by title/artist similarity and duration delta.
  3. High confidence → `playlistItems.insert`; toast "Added to <playlist>" on all devices.
  4. Low confidence → `Confirmation(kind = AddToPlaylistMatch, "Add '<title> – <artist>'?")` on all devices (first answer wins, 15 s → No).
- **Quota:** search = 100 units, insert = 50 → ~66 adds/day on the default 10,000/day; cache avoids repeat searches. Show "Daily YouTube limit reached" on quota error.
- Errors: auth expired → re-prompt sign-in only then; network/quota → toast with reason; never silent.

### 3.3 Interfaces
```kotlin
interface PlaylistService {                // :core:services
    val account: StateFlow<AccountState>   // SignedOut | SignedIn(email) | Expired
    suspend fun playlists(): List<Playlist>
    suspend fun addCurrent(track: NowPlaying, playlistId: String): AddResult // Added | NeedsConfirm(match) | Failed(reason)
}
```
Live: `YouTubeDataPlaylistService`. Fake: in-memory.

---

## 4. Protocol additions
- `Command.AddToPlaylist(playlistId?)`, `Confirmation.kind = AddToPlaylistMatch`.
- `StateFrame.music.liked` already exists; no other wire changes. `protocolVersion` → 2; V1 clients ignore unknown commands gracefully (phone never sends commands to clients).

## 5. Error handling (V2)
| Situation | Behaviour |
|---|---|
| HC not installed / outdated | Linked services shows "Install/Update Health Connect" (Play Store link). Sessions queue. |
| HC permission revoked | Status "Needs permissions"; queue holds; toast once per workout end. |
| Write partially fails | Whole session retried (upsert by clientRecordId makes retries safe). |
| Ineligible (Demo) session | Never written; shown as "Demo · not saved to Health Connect" in detail page. |
| YouTube quota / network | Toast with reason; nothing added. |
| Refresh token revoked | Account state `Expired`; one prompt in Linked services. |

## 6. Testing (V2)
- Unit: provenance gate, clientRecordId scheme + idempotency, per-minute interval derivation from cumulative totals, track-match scoring, parser phrases for add-to-playlist.
- Instrumented: Health Connect writes with the `androidx.health.connect:connect-testing` fake client; encrypted token store.
- Device: real workout → verify in Health Connect app (and Samsung Health) values match LiveFit summary; add-to-playlist from each device, verify in YouTube Music.
