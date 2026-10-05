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
- After the one-time consent, playlist adds keep working beyond 7 days without re-prompting (silent re-authorization).

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
- "Sign in with Google" via Google Identity Services **`AuthorizationClient.authorize()`** requesting scope `https://www.googleapis.com/auth/youtube`. First call returns a `PendingIntent` (consent screen) which the Linked services screen launches.
- **No refresh token is stored by the app.** `AuthorizationClient` does not hand a refresh token to Android apps for this flow; instead, **every time an access token is needed** (or after it expires) the service calls `authorize()` again. After consent Google returns an **access token silently**; if it instead returns a resolution `PendingIntent` (consent revoked, scope changed, account removed), the account state becomes `NeedsConsent` and Linked services shows "Reconnect YouTube" — background commands (voice/watch/glasses) then fail with toast "Reconnect YouTube on your phone" rather than launching UI.
- Access token cached **in memory only** until its expiry; the signed-in account email is the only persisted item (plain prefs).
- Google Cloud project OAuth consent screen set to **"In production" (unverified)** so consent isn't time-limited as in testing mode. Owner accepts the "unverified app" warning once.
- Sign out: clear the cached token and account, and call `AuthorizationClient.revokeAccess` where available (else instruct the user to remove access in their Google account).
- Screen also shows: notification-access status (V1), workout-start music behaviour (V1), **Default playlist** picker (generic list screen, source `playlists`, from `playlists.list?mine=true`).

### 3.2 "Add to playlist" command
- New `Command.AddToPlaylist` (optional `playlistId`, default = Settings default).
- Entry points: phone Music screen button, watch music page button, glasses (voice only, plus confirmation highlight), voice phrases: "add to playlist", "save this song", "add this song to my playlist", "add to workout playlist" (parser + tests).
- Flow:
  1. Take `NowPlaying(title, artist, durationMs)` from the media session (no video id is exposed — verified).
  2. **Capture** the request immediately: `{trackSnapshot(title, artist, durationMs), playlistId, requestId}`. All later steps use this snapshot, never the live now-playing (the song may change meanwhile).
  3. Resolve video: cache lookup (Room `track_cache(title, artist, durationMs) → videoId, confidence`), else `search.list(part=snippet, q="<title> <artist>", type=video, videoCategoryId=10, maxResults=5)` → candidate ids → **`videos.list(part=contentDetails,snippet, id=…)`** to get each candidate's duration (search results don't include it). Score = title similarity + artist/channel similarity + duration delta (|Δ| ≤ 3 s strong, ≤ 10 s ok).
  4. High confidence → insert (step 6).
  5. Low confidence → `Confirmation(kind = AddToPlaylistMatch, "Add '<candidate title> – <channel>' to <playlist>?")` whose **payload binds `requestId`, the snapshot, the chosen `videoId` and `playlistId`**. On Yes the bound values are used even if a different song is now playing; on No / timeout nothing is added.
  6. `playlistItems.insert(playlistId, videoId)`. Toast "Added to <playlist>" on all devices. Successful matches are cached.
- **Uncertain outcome:** a network error after the insert request was sent does **not** mean nothing was added. Before retrying, check `playlistItems.list(playlistId, videoId=…)`; if present → report success, else retry once. Toast "Couldn't confirm — check YouTube Music" if the check also fails.
- **Quota** (per current YouTube Data API docs at time of writing — verify in the Cloud console): `search.list` has its **own default bucket of 100 calls/day**; `videos.list` costs 1 unit and `playlistItems.insert` 50 units from the default 10,000 units/day. So **≈100 new-song lookups/day**; cached songs need no search. On quota errors show "Daily YouTube limit reached".
- Errors: consent needed → "Reconnect YouTube" (above); other API errors → toast with reason; never silent.

### 3.3 Interfaces
```kotlin
interface PlaylistService {                // :core:services
    val account: StateFlow<AccountState>   // SignedOut | SignedIn(email) | NeedsConsent
    suspend fun playlists(): List<Playlist>
    suspend fun addCurrent(track: NowPlaying, playlistId: String): AddResult // Added | NeedsConfirm(match) | Failed(reason)
}
```
Live: `YouTubeDataPlaylistService`. Fake: in-memory.

---

## 4. Protocol additions
- `Command.AddToPlaylist(playlistId?)`, `Confirmation.kind = AddToPlaylistMatch`.
- `protocolVersion` → 2. Per the V1 version policy (V1 §4.7, **coordinated upgrades**), all three APKs are updated together; a device still on version 1 is told to update and its commands are ignored. No mixed-version compatibility is promised.

## 5. Error handling (V2)
| Situation | Behaviour |
|---|---|
| HC not installed / outdated | Linked services shows "Install/Update Health Connect" (Play Store link). Sessions queue. |
| HC permission revoked | Status "Needs permissions"; queue holds; toast once per workout end. |
| Write partially fails | Whole session retried (upsert by clientRecordId makes retries safe). |
| Ineligible (Demo) session | Never written; shown as "Demo · not saved to Health Connect" in detail page. |
| YouTube quota | "Daily YouTube limit reached"; nothing added. |
| Network error during insert | Verify with `playlistItems.list` before retry (may already be added). |
| Consent revoked / resolution required | Account state `NeedsConsent`; "Reconnect YouTube" in Linked services; background commands toast instead of opening UI. |

## 6. Testing (V2)
- Unit: provenance gate, clientRecordId scheme + idempotency, per-minute interval derivation from cumulative totals, track-match scoring (incl. duration from `videos.list`), confirmation payload binding (song changes during confirm), uncertain-insert verification, parser phrases for add-to-playlist.
- Instrumented: Health Connect writes with the `androidx.health.connect:connect-testing` fake client; encrypted token store.
- Device: real workout → verify in Health Connect app (and Samsung Health) values match LiveFit summary; add-to-playlist from each device, verify in YouTube Music.
