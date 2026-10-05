# Rokid LiveFit V2 (Health Connect + YouTube playlists) Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Save every complete, fully-live LiveFit workout to Health Connect (idempotently, with a retry queue), show Health Connect daily totals on the Activity tab, and let the user add the current YouTube Music song to a default playlist from any device or by voice.

**Architecture:** Pure-Kotlin modules hold the rules — eligibility gate and record planning (`:services:health`), track matching, add-to-playlist flow and YouTube Data API client (`:services:playlists`, OkHttp on the JVM). Android modules adapt them to platform APIs — `HealthConnectSink` + outbox worker (`:services:health-connect`) and Google `AuthorizationClient` token provider (`:services:playlists-android`). The phone hub binds them in `ServiceGraph`; V1's history store, provenance, confirmation service, router and Linked services screens are reused.

**Tech Stack:** As V1, plus `androidx.health.connect:connect-client:1.1.0`, `androidx.health.connect:connect-testing:1.0.0-alpha02` (tests), `androidx.work:work-runtime-ktx:2.9.1`, `com.google.android.gms:play-services-auth:21.2.0`, OkHttp 4.12.0 + MockWebServer 4.12.0 (tests). Use the latest stable patch of each at implementation time; API names below match these versions.

**Spec:** `docs/superpowers/specs/2026-10-05-livefit-v2-design.md` (depends on `2026-10-05-livefit-v1-design.md`). V1 plan: `docs/superpowers/plans/2026-10-05-livefit-v1-plan.md` — **V2 starts only after V1 acceptance passes.**

## Global Constraints

- All V1 Global Constraints apply (JDK 17, versions, user 0 installs, coordinated upgrades, timing constants).
- `PROTOCOL_VERSION` becomes **2** in Task 8; all three APKs are re-installed together via `tools/install-all.sh`.
- Health Connect: write only sessions with `status == Complete`, **every** sample `Provenance.Live`, and a Live source; `clientRecordId = "<sessionId>:<kind>[:<index>]"`, `clientRecordVersion = 1`; `HeartRateRecord` chunks ≤ 1,000 samples; per-minute intervals for steps / distance / active calories; retry back-off 1 min → 1 h.
- Daily totals: local midnight → now; refresh on tab open and every 15 min while visible; Activity list stays LiveFit-only.
- YouTube: scope `https://www.googleapis.com/auth/youtube`; OAuth consent screen "In production (unverified)"; **no refresh token stored** — call `AuthorizationClient.authorize()` for every token; access token cached in memory until expiry; background commands never launch UI (toast "Reconnect YouTube on your phone").
- Track match: candidates from `search.list(part=snippet, type=video, videoCategoryId=10, maxResults=5)`, durations from `videos.list(part=contentDetails,snippet)`; strong match = score ≥ 0.75 and |Δduration| ≤ 3 s; ok = score ≥ 0.55 (|Δ| ≤ 10 s) → confirmation; else "Couldn't find this song".
- Quota (verify in Cloud console): `search.list` own bucket 100 calls/day; cache hits need no search; quota error → "Daily YouTube limit reached".

## Review Focus

1. **Song changes while the add-to-playlist confirmation is open** → the captured song is added, not the new one. Pinned in Task 6 (`confirmationUsesCapturedTrackEvenIfSongChanged`).
2. **Network drops after `playlistItems.insert` was sent** → verify via `playlistItems.list` before retrying; never a duplicate. Pinned in Task 7 (`uncertainInsertIsVerifiedNotDuplicated`).
3. **Health Connect write fails half-way** (heart-rate chunk 2 of 3 throws) → whole session retried; upsert by `clientRecordId` leaves exactly one copy. Pinned in Task 2 (`partialFailureRetriesWholeSessionIdempotently`).
4. **Workout crosses midnight** → per-minute intervals stay correct and the session lands on its start day; daily totals for "today" only include today. Pinned in Task 1 (`intervalsAcrossMidnightAreContiguous`).
5. **User revokes YouTube access in their Google account** → account state becomes `NeedsConsent` on the next call; Linked services shows "Reconnect YouTube"; voice "add to playlist" toasts instead of opening UI. Pinned in Task 5 (`resolutionRequiredBecomesNeedsConsent`).

---

## File structure

```
services/health/            (new, Kotlin JVM)  HealthDataSink.kt (contracts), EligibilityGate.kt, HcRecordPlan.kt
services/health-connect/    (new, Android lib) HealthConnectSink.kt, HcOutbox.kt (Room), HcSyncWorker.kt, PermissionsRationaleActivity.kt
services/playlists/         (new, Kotlin JVM)  PlaylistService.kt (contracts), IsoDuration.kt, TrackMatcher.kt, YouTubeApi.kt, AddToPlaylistFlow.kt, TrackCache.kt
services/playlists-android/ (new, Android lib) GoogleTokenProvider.kt, PrefsTrackCache.kt, YouTubePlaylistService.kt
core/model                  Devices.kt (+AddToPlaylist), Frames.kt (+AddToPlaylistMatch), Protocol.kt (PROTOCOL_VERSION = 2)
services/voice              CommandParser.kt (+playlist phrases)
phone/                      ServiceGraph.kt, ui/linked/LinkedHealthScreen.kt, ui/linked/LinkedMusicScreen.kt (sign-in), ui/list/sources/PlaylistSource.kt, ui/history/DailyTotalsCard.kt, setup/SetupFlow.kt (+HealthConnect step)
watch/                      ui/WatchApp.kt (music page: add-to-playlist button)
```

---

### Task 1: Health contracts, eligibility gate and record planning (pure)

**Files:**
- Modify: `settings.gradle.kts` (include `:services:health`, `:services:health-connect`, `:services:playlists`, `:services:playlists-android`)
- Create: `services/health/build.gradle.kts`
- Create: `services/health/src/main/kotlin/com/debasish/livefit/health/HealthDataSink.kt`
- Create: `services/health/src/main/kotlin/com/debasish/livefit/health/EligibilityGate.kt`
- Create: `services/health/src/main/kotlin/com/debasish/livefit/health/HcRecordPlan.kt`
- Test: `services/health/src/test/kotlin/com/debasish/livefit/health/EligibilityGateTest.kt`
- Test: `services/health/src/test/kotlin/com/debasish/livefit/health/HcRecordPlanTest.kt`

**Interfaces:**
- Consumes: `SessionSummary`, `Sample`, `Provenance`, `SessionStatus` (V1).
- Produces:
  - `data class FinishedSession(val summary: SessionSummary, val samples: List<Sample>, val sampleProvenance: List<Provenance>, val pauses: List<Pair<Long, Long>> = emptyList())` (pause intervals, watch-clock ms)
  - `sealed interface SinkResult { Written; Queued(reason: String); Rejected(reason: RejectReason) }`, `enum class RejectReason { NotComplete, NotLive }`
  - `data class DailyTotals(val steps: Long, val activeKcal: Double, val restingHr: Int?)`
  - `data class SinkStatus(val link: HcLink, val queued: Int, val lastSyncMs: Long?)`, `enum class HcLink { Linked, NeedsPermissions, Unavailable, NeedsUpdate }`
  - `interface HealthDataSink { suspend fun write(session: FinishedSession): SinkResult; suspend fun dailyTotals(day: java.time.LocalDate, zone: java.time.ZoneId): DailyTotals?; val status: StateFlow<SinkStatus> }`
  - `object EligibilityGate { fun check(s: FinishedSession): RejectReason? }`
  - `data class HcRecordPlan(...)` + `object HcPlanner { fun plan(s: FinishedSession): HcRecordPlan }` with `exercise: ExercisePlan` (incl. `pauses`), `heartRateChunks: List<HrChunk>`, `minuteIntervals: List<MinuteInterval>`, `speedSamples: List<Pair<Long, Double>>`; `data class MinuteInterval(startMs, endMs, steps, distanceKm, kcal)`; ids via `HcPlanner.recordId(sessionId, kind, index)`; `HcPlanner.pauseIntervals(events: List<SessionEvent>): List<Pair<Long, Long>>`.
  - Cumulative totals are differenced from a **zero baseline at session start** (not the first sample) with a running maximum (a total that drops is never counted twice) — the same rule as V1's `SessionAssembler`, so history and Health Connect report the same totals.

- [ ] **Step 1: Module** — add the four `include(":services:…")` entries; `services/health/build.gradle.kts`:

```kotlin
plugins { id("org.jetbrains.kotlin.jvm") }
kotlin { jvmToolchain(17) }
dependencies {
    api(project(":core:services"))
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-core:1.9.0")
    testImplementation(kotlin("test"))
}
tasks.test { useJUnitPlatform() }
```

- [ ] **Step 2: Write the failing tests**

`EligibilityGateTest.kt`:

```kotlin
package com.debasish.livefit.health

import com.debasish.livefit.model.Provenance
import com.debasish.livefit.model.Sample
import com.debasish.livefit.model.SessionStatus
import com.debasish.livefit.model.SessionSummary
import com.debasish.livefit.model.WorkoutType
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class EligibilityGateTest {
    private val live = Provenance.Live("galaxy-watch/health-services")
    private fun session(status: SessionStatus = SessionStatus.Complete, prov: Provenance = live, sampleProv: List<Provenance> = listOf(live, live)) = FinishedSession(
        SessionSummary(id = "s", type = WorkoutType.Run, startMs = 0, endMs = 2_000, activeMs = 2_000, provenance = prov, status = status),
        samples = listOf(Sample(1_000, hr = 100), Sample(2_000, hr = 110)),
        sampleProvenance = sampleProv,
    )

    @Test fun completeAndFullyLiveIsEligible() = assertNull(EligibilityGate.check(session()))
    @Test fun incompleteIsRejected() = assertEquals(RejectReason.NotComplete, EligibilityGate.check(session(status = SessionStatus.Incomplete)))
    @Test fun stoppingIsRejected() = assertEquals(RejectReason.NotComplete, EligibilityGate.check(session(status = SessionStatus.Stopping)))
    @Test fun fakeSessionIsRejected() = assertEquals(RejectReason.NotLive, EligibilityGate.check(session(prov = Provenance.Fake)))
    @Test fun oneFakeSampleRejectsTheSession() = assertEquals(RejectReason.NotLive, EligibilityGate.check(session(sampleProv = listOf(live, Provenance.Fake))))
}
```

`HcRecordPlanTest.kt`:

```kotlin
package com.debasish.livefit.health

import com.debasish.livefit.model.EndReason
import com.debasish.livefit.model.Provenance
import com.debasish.livefit.model.Sample
import com.debasish.livefit.model.SessionEvent
import com.debasish.livefit.model.SessionStatus
import com.debasish.livefit.model.SessionSummary
import com.debasish.livefit.model.WorkoutType
import kotlin.test.Test
import kotlin.test.assertEquals

class HcRecordPlanTest {
    private val live = Provenance.Live("galaxy-watch/health-services")
    private fun finished(samples: List<Sample>, startMs: Long = 0, type: WorkoutType = WorkoutType.Walk, detected: WorkoutType? = null) = FinishedSession(
        SessionSummary(id = "s1", type = type, detectedType = detected, startMs = startMs, endMs = samples.last().tMs, activeMs = samples.last().tMs - startMs,
            provenance = live, status = SessionStatus.Complete),
        samples, List(samples.size) { live },
    )

    @Test fun recordIdsAreStableAndScoped() {
        assertEquals("s1:exercise", HcPlanner.recordId("s1", "exercise"))
        assertEquals("s1:hr:2", HcPlanner.recordId("s1", "hr", 2))
    }

    @Test fun autoMapsToDetectedType() =
        assertEquals("RUN", HcPlanner.plan(finished(listOf(Sample(0), Sample(60_000)), type = WorkoutType.Auto, detected = WorkoutType.Run)).exercise.type)

    @Test fun heartRateIsChunkedAt1000() {
        val samples = List(2_500) { Sample(it * 1_000L, hr = 100) }
        assertEquals(listOf(1_000, 1_000, 500), HcPlanner.plan(finished(samples)).heartRateChunks.map { it.samples.size })
    }

    @Test fun minuteIntervalsAreDeltasOfCumulativeTotals() {
        val samples = listOf(Sample(0, stepsTotal = 0, distanceKmTotal = 0.0, kcalTotal = 0.0),
            Sample(60_000, stepsTotal = 100, distanceKmTotal = 0.08, kcalTotal = 5.0),
            Sample(120_000, stepsTotal = 220, distanceKmTotal = 0.17, kcalTotal = 11.0))
        val iv = HcPlanner.plan(finished(samples)).minuteIntervals
        assertEquals(listOf(100L, 120L), iv.map { it.steps })
        assertEquals(listOf(0L to 60_000L, 60_000L to 120_000L), iv.map { it.startMs to it.endMs })
    }

    /** Review Focus #4. */
    @Test fun intervalsAcrossMidnightAreContiguous() {
        val start = 1_760_000_000_000L - 90_000 // arbitrary instant; contiguity is what matters
        val samples = List(5) { Sample(start + it * 60_000L, stepsTotal = it * 50) }
        val iv = HcPlanner.plan(finished(samples, startMs = start)).minuteIntervals
        iv.zipWithNext().forEach { (a, b) -> assertEquals(a.endMs, b.startMs) }
        assertEquals(200L, iv.sumOf { it.steps })
    }

    /** Codex P2: the first reading can arrive late and already be non-zero — it still counts. */
    @Test fun cumulativeTotalsCountFromAZeroBaseline() {
        val samples = listOf(Sample(30_000, stepsTotal = 10, distanceKmTotal = 0.01, kcalTotal = 1.0),
            Sample(120_000, stepsTotal = 100, distanceKmTotal = 0.08, kcalTotal = 6.0))
        val iv = HcPlanner.plan(finished(samples)).minuteIntervals
        assertEquals(10L, iv.first().steps)
        assertEquals(100L, iv.sumOf { it.steps })
        assertEquals(0.08, iv.sumOf { it.distanceKm }, 1e-9)
        assertEquals(6.0, iv.sumOf { it.kcal }, 1e-9)
    }

    @Test fun aTotalThatDropsIsNeverCountedTwice() {
        val samples = listOf(Sample(60_000, stepsTotal = 500), Sample(90_000, stepsTotal = 0), Sample(180_000, stepsTotal = 560))
        assertEquals(560L, HcPlanner.plan(finished(samples)).minuteIntervals.sumOf { it.steps })
    }

    /** Codex plan round 2: ends during a dip → same 100 steps the V1 history summary shows. */
    @Test fun sessionEndingDuringADipMatchesTheHistorySummary() {
        val samples = listOf(Sample(60_000, stepsTotal = 100), Sample(120_000, stepsTotal = 50))
        assertEquals(100L, HcPlanner.plan(finished(samples)).minuteIntervals.sumOf { it.steps })
    }

    @Test fun pauseIntervalsComeFromEvents() {
        val events = listOf(SessionEvent.Started(0, WorkoutType.Walk), SessionEvent.Paused(240_000), SessionEvent.Resumed(360_000),
            SessionEvent.Paused(500_000), SessionEvent.Stopped(600_000, EndReason.User))
        assertEquals(listOf(240_000L to 360_000L, 500_000L to 600_000L), HcPlanner.pauseIntervals(events))
    }

    @Test fun pausesArePlannedWithinTheSession() {
        val s = finished(listOf(Sample(0), Sample(600_000))).copy(pauses = listOf(-5_000L to 10_000L, 120_000L to 240_000L))
        assertEquals(listOf(0L to 10_000L, 120_000L to 240_000L), HcPlanner.plan(s).exercise.pauses, "clipped to the session")
    }
}
```

- [ ] **Step 3: Run tests to verify they fail**

Run: `export JAVA_HOME=$(/usr/libexec/java_home -v 17) && ./gradlew :services:health:test`
Expected: FAIL — unresolved types.

- [ ] **Step 4: Implement** — `HealthDataSink.kt`:

```kotlin
package com.debasish.livefit.health

import com.debasish.livefit.model.Provenance
import com.debasish.livefit.model.Sample
import com.debasish.livefit.model.SessionSummary
import kotlinx.coroutines.flow.StateFlow
import java.time.LocalDate
import java.time.ZoneId

/** [pauses] = paused intervals (start, end) in watch-clock ms, from the session's events. */
data class FinishedSession(
    val summary: SessionSummary,
    val samples: List<Sample>,
    val sampleProvenance: List<Provenance>,
    val pauses: List<Pair<Long, Long>> = emptyList(),
)

enum class RejectReason { NotComplete, NotLive }

sealed interface SinkResult {
    data object Written : SinkResult
    data class Queued(val reason: String) : SinkResult
    data class Rejected(val reason: RejectReason) : SinkResult
}

data class DailyTotals(val steps: Long, val activeKcal: Double, val restingHr: Int?)
enum class HcLink { Linked, NeedsPermissions, Unavailable, NeedsUpdate }
data class SinkStatus(val link: HcLink, val queued: Int = 0, val lastSyncMs: Long? = null)

/** Health data destination (V2 spec §2.5). Live: Health Connect; future iOS: HealthKit. */
interface HealthDataSink {
    suspend fun write(session: FinishedSession): SinkResult
    suspend fun dailyTotals(day: LocalDate, zone: ZoneId): DailyTotals?
    val status: StateFlow<SinkStatus>
}
```

`EligibilityGate.kt`:

```kotlin
package com.debasish.livefit.health

import com.debasish.livefit.model.Provenance
import com.debasish.livefit.model.SessionStatus

/** Real-data guarantee (V2 spec §2.2): only Complete sessions whose every sample is Live. */
object EligibilityGate {
    fun check(s: FinishedSession): RejectReason? = when {
        s.summary.status != SessionStatus.Complete -> RejectReason.NotComplete
        s.summary.provenance !is Provenance.Live -> RejectReason.NotLive
        s.sampleProvenance.any { it !is Provenance.Live } -> RejectReason.NotLive
        else -> null
    }
}
```

`HcRecordPlan.kt`:

```kotlin
package com.debasish.livefit.health

import com.debasish.livefit.model.SessionEvent
import com.debasish.livefit.model.WorkoutType

data class ExercisePlan(val id: String, val type: String, val title: String, val startMs: Long, val endMs: Long, val pauses: List<Pair<Long, Long>> = emptyList())
data class HrChunk(val id: String, val samples: List<Pair<Long, Long>>) // (tMs, bpm)
data class MinuteInterval(val stepsId: String, val distanceId: String, val kcalId: String, val startMs: Long, val endMs: Long, val steps: Long, val distanceKm: Double, val kcal: Double)
data class HcRecordPlan(val exercise: ExercisePlan, val heartRateChunks: List<HrChunk>, val minuteIntervals: List<MinuteInterval>, val speedId: String, val speedSamples: List<Pair<Long, Double>>)

/** Platform-neutral plan of the Health Connect records for one session (V2 spec §2.1). */
object HcPlanner {
    fun recordId(sessionId: String, kind: String, index: Int? = null) = listOfNotNull(sessionId, kind, index?.toString()).joinToString(":")

    fun plan(s: FinishedSession): HcRecordPlan {
        val sum = s.summary
        val id = sum.id
        val shown = if (sum.type == WorkoutType.Auto) sum.detectedType ?: WorkoutType.Walk else sum.type
        val type = when (shown) { WorkoutType.Run -> "RUN"; WorkoutType.Cycle -> "BIKE"; else -> "WALK" }
        val start = sum.startMs
        val end = sum.endMs ?: s.samples.lastOrNull()?.tMs ?: start
        val hr = s.samples.mapNotNull { smp -> smp.hr?.let { smp.tMs to it.toLong() } }
        val chunks = hr.chunked(1_000).mapIndexed { i, c -> HrChunk(recordId(id, "hr", i), c) }

        // Per-minute deltas of cumulative totals: bucket k covers [start + k·60 s, start + (k+1)·60 s].
        // Totals count from 0 at session start (Health Services totals are per exercise), so the baseline is zero —
        // not the first sample, which can arrive late and already be non-zero. A running maximum ignores a total
        // that drops (e.g. the watch re-registering after process death), so nothing is counted twice.
        val sorted = s.samples.sortedBy { it.tMs }
        val intervals = mutableListOf<MinuteInterval>()
        if (end > start) {
            var steps = 0L; var km = 0.0; var kcal = 0.0
            var i = 0
            val buckets = (end - start - 1) / 60_000
            for (k in 0..buckets) {
                val bStart = start + k * 60_000
                val bEnd = minOf(bStart + 60_000, end)
                var nSteps = steps; var nKm = km; var nKcal = kcal
                while (i < sorted.size && sorted[i].tMs <= bEnd) {
                    val x = sorted[i++]
                    nSteps = maxOf(nSteps, x.stepsTotal.toLong()); nKm = maxOf(nKm, x.distanceKmTotal); nKcal = maxOf(nKcal, x.kcalTotal)
                }
                intervals += MinuteInterval(
                    recordId(id, "steps", k.toInt()), recordId(id, "distance", k.toInt()), recordId(id, "kcal", k.toInt()),
                    bStart, bEnd, nSteps - steps, nKm - km, nKcal - kcal,
                )
                steps = nSteps; km = nKm; kcal = nKcal
            }
        }
        val pauses = s.pauses.map { (a, b) -> maxOf(a, start) to minOf(b, end) }.filter { (a, b) -> b > a }

        return HcRecordPlan(
            exercise = ExercisePlan(recordId(id, "exercise"), type, "LiveFit ${shown.label}", start, end, pauses),
            heartRateChunks = chunks,
            minuteIntervals = intervals,
            speedId = recordId(id, "speed"),
            speedSamples = s.samples.mapNotNull { smp -> smp.speedKmh?.let { smp.tMs to it } },
        )
    }

    /** Paused intervals from the session's events; a pause still open at Stopped ends there. */
    fun pauseIntervals(events: List<SessionEvent>): List<Pair<Long, Long>> {
        val out = mutableListOf<Pair<Long, Long>>()
        var pausedAt: Long? = null
        for (e in events.sortedBy { it.tMs }) when (e) {
            is SessionEvent.Paused -> if (pausedAt == null) pausedAt = e.tMs
            is SessionEvent.Resumed, is SessionEvent.Stopped -> pausedAt?.let { out += it to e.tMs; pausedAt = null }
            else -> Unit
        }
        return out
    }
}
```

- [ ] **Step 5: Run tests to verify they pass**

Run: `export JAVA_HOME=$(/usr/libexec/java_home -v 17) && ./gradlew :services:health:test`
Expected: PASS (5 + 10). Intervals are contiguous by construction (bucket k ends where k+1 starts), which Health Connect requires (no overlaps).

- [ ] **Step 6: Commit**

```bash
git add settings.gradle.kts services/health
git commit -m "feat(health): sink contracts, real-data eligibility gate and HC record planning"
```

---

### Task 2: Health Connect sink with outbox and retry worker

**Files:**
- Create: `services/health-connect/build.gradle.kts`, `src/main/AndroidManifest.xml`
- Create: `services/health-connect/src/main/kotlin/com/debasish/livefit/healthconnect/HcOutbox.kt`
- Create: `services/health-connect/src/main/kotlin/com/debasish/livefit/healthconnect/HealthConnectSink.kt`
- Create: `services/health-connect/src/main/kotlin/com/debasish/livefit/healthconnect/HcSyncWorker.kt`
- Create: `services/health-connect/src/main/kotlin/com/debasish/livefit/healthconnect/PermissionsRationaleActivity.kt`
- Test: `services/health-connect/src/test/kotlin/com/debasish/livefit/healthconnect/HealthConnectSinkTest.kt`
- Test: `services/health-connect/src/test/kotlin/com/debasish/livefit/healthconnect/HcSyncWorkerTest.kt`

**Interfaces:**
- Consumes: `HealthDataSink`, `EligibilityGate`, `HcPlanner`, `FinishedSession` (Task 1).
- Produces:
  - `class HealthConnectSink(context: Context, client: () -> HealthConnectClient?, outbox: HcOutbox, now: () -> Long = System::currentTimeMillis) : HealthDataSink` with `suspend fun drainOutbox(load: suspend (String) -> FinishedSession?)`, `val permissions: Set<String>`
  - `interface HcOutbox { suspend fun enqueue(sessionId: String, error: String?); suspend fun due(nowMs: Long): List<String>; suspend fun markDone(sessionId: String); suspend fun markFailed(sessionId: String, error: String, nextAttemptMs: Long); suspend fun size(): Int; suspend fun attempts(sessionId: String): Int }` + `InMemoryHcOutbox` (tests) + `PrefsHcOutbox(context)`
  - `HcSyncWorker` (WorkManager, periodic 15 min + one-time on enqueue) + `interface HcSyncHost { suspend fun drainHealthOutbox(): Boolean }` — implemented by the phone's `Application`, which builds the sink and history from durable storage itself. No static callback: WorkManager can start a fresh process where nothing else ran.
  - Pauses are written as `ExerciseSegment`s of type `EXERCISE_SEGMENT_TYPE_PAUSE` on the `ExerciseSessionRecord`.

- [ ] **Step 1: Module build** — `services/health-connect/build.gradle.kts`:

```kotlin
plugins {
    id("com.android.library")
    id("org.jetbrains.kotlin.android")
}
android {
    namespace = "com.debasish.livefit.healthconnect"
    compileSdk = 36
    defaultConfig { minSdk = 29 }
    compileOptions { sourceCompatibility = JavaVersion.VERSION_17; targetCompatibility = JavaVersion.VERSION_17 }
    kotlinOptions { jvmTarget = "17" }
    sourceSets["main"].java.srcDirs("src/main/kotlin")
    sourceSets["test"].java.srcDirs("src/test/kotlin")
    testOptions { unitTests.isIncludeAndroidResources = true }
}
dependencies {
    api(project(":services:health"))
    implementation("androidx.health.connect:connect-client:1.1.0")
    implementation("androidx.work:work-runtime-ktx:2.9.1")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.9.0")
    testImplementation("androidx.health.connect:connect-testing:1.0.0-alpha02")
    testImplementation("junit:junit:4.13.2")
    testImplementation("org.robolectric:robolectric:4.13")
    testImplementation("androidx.test:core:1.6.1")
    testImplementation("org.jetbrains.kotlinx:kotlinx-coroutines-test:1.9.0")
    testImplementation("androidx.work:work-testing:2.9.1")
}
```

Manifest (permissions + the rationale activity HC requires before it shows its dialog):

```xml
<?xml version="1.0" encoding="utf-8"?>
<manifest xmlns:android="http://schemas.android.com/apk/res/android">
    <uses-permission android:name="android.permission.health.WRITE_EXERCISE" />
    <uses-permission android:name="android.permission.health.WRITE_HEART_RATE" />
    <uses-permission android:name="android.permission.health.WRITE_STEPS" />
    <uses-permission android:name="android.permission.health.WRITE_DISTANCE" />
    <uses-permission android:name="android.permission.health.WRITE_ACTIVE_CALORIES_BURNED" />
    <uses-permission android:name="android.permission.health.WRITE_SPEED" />
    <uses-permission android:name="android.permission.health.READ_STEPS" />
    <uses-permission android:name="android.permission.health.READ_ACTIVE_CALORIES_BURNED" />
    <uses-permission android:name="android.permission.health.READ_RESTING_HEART_RATE" />
    <queries><package android:name="com.google.android.apps.healthdata" /></queries>
    <application>
        <activity android:name="com.debasish.livefit.healthconnect.PermissionsRationaleActivity" android:exported="true">
            <intent-filter><action android:name="androidx.health.ACTION_SHOW_PERMISSIONS_RATIONALE" /></intent-filter>
        </activity>
        <activity-alias android:name="com.debasish.livefit.healthconnect.ViewPermissionUsageActivity" android:exported="true"
            android:targetActivity="com.debasish.livefit.healthconnect.PermissionsRationaleActivity"
            android:permission="android.permission.START_VIEW_PERMISSION_USAGE">
            <intent-filter>
                <action android:name="android.intent.action.VIEW_PERMISSION_USAGE" />
                <category android:name="android.intent.category.HEALTH_PERMISSIONS" />
            </intent-filter>
        </activity-alias>
    </application>
</manifest>
```

- [ ] **Step 2: Write the failing test** — `HealthConnectSinkTest.kt` (Robolectric + `FakeHealthConnectClient`)

```kotlin
package com.debasish.livefit.healthconnect

import androidx.health.connect.client.records.ExerciseSegment
import androidx.health.connect.client.records.ExerciseSessionRecord
import androidx.health.connect.client.records.HeartRateRecord
import androidx.health.connect.client.records.StepsRecord
import androidx.health.connect.client.request.ReadRecordsRequest
import androidx.health.connect.client.testing.FakeHealthConnectClient
import androidx.health.connect.client.time.TimeRangeFilter
import androidx.test.core.app.ApplicationProvider
import com.debasish.livefit.health.FinishedSession
import com.debasish.livefit.health.RejectReason
import com.debasish.livefit.health.SinkResult
import com.debasish.livefit.model.Provenance
import com.debasish.livefit.model.Sample
import com.debasish.livefit.model.SessionStatus
import com.debasish.livefit.model.SessionSummary
import com.debasish.livefit.model.WorkoutType
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import java.time.Instant

@RunWith(RobolectricTestRunner::class)
class HealthConnectSinkTest {
    private val live = Provenance.Live("galaxy-watch/health-services")
    private val t0 = 1_760_000_000_000L
    private fun session(prov: Provenance = live, n: Int = 180) = FinishedSession(
        SessionSummary(id = "s1", type = WorkoutType.Run, startMs = t0, endMs = t0 + n * 1_000L, activeMs = n * 1_000L, provenance = prov, status = SessionStatus.Complete),
        samples = List(n) { Sample(t0 + it * 1_000L, hr = 120, stepsTotal = it * 3, distanceKmTotal = it * 0.003, kcalTotal = it * 0.2, speedKmh = 10.0) },
        sampleProvenance = List(n) { prov },
    )
    private val all = TimeRangeFilter.between(Instant.ofEpochMilli(t0 - 1), Instant.ofEpochMilli(t0 + 10_000_000))

    @Test fun writesAllRecordsForALiveSession() = runTest {
        val fake = FakeHealthConnectClient()
        val sink = HealthConnectSink(ApplicationProvider.getApplicationContext(), { fake }, InMemoryHcOutbox(), grantedCheck = { true })
        assertEquals(SinkResult.Written, sink.write(session()))
        assertEquals(1, fake.readRecords(ReadRecordsRequest(ExerciseSessionRecord::class, all)).records.size)
        assertEquals(1, fake.readRecords(ReadRecordsRequest(HeartRateRecord::class, all)).records.size)
        assertEquals(3, fake.readRecords(ReadRecordsRequest(StepsRecord::class, all)).records.size)
    }

    @Test fun fakeSessionIsRejectedAndNothingWritten() = runTest {
        val fake = FakeHealthConnectClient()
        val sink = HealthConnectSink(ApplicationProvider.getApplicationContext(), { fake }, InMemoryHcOutbox(), grantedCheck = { true })
        assertEquals(SinkResult.Rejected(RejectReason.NotLive), sink.write(session(prov = Provenance.Fake)))
        assertEquals(0, fake.readRecords(ReadRecordsRequest(ExerciseSessionRecord::class, all)).records.size)
    }

    /** Review Focus #3. */
    @Test fun partialFailureRetriesWholeSessionIdempotently() = runTest {
        val fake = FakeHealthConnectClient()
        val outbox = InMemoryHcOutbox()
        var fail = true
        val sink = HealthConnectSink(ApplicationProvider.getApplicationContext(), { fake }, outbox, grantedCheck = { true },
            beforeInsert = { kind -> if (fail && kind == "hr") error("binder died") })
        assertEquals(true, sink.write(session()) is SinkResult.Queued)
        assertEquals(1, outbox.size())
        fail = false
        sink.drainOutbox { session() }
        sink.drainOutbox { session() } // second drain must not duplicate
        assertEquals(1, fake.readRecords(ReadRecordsRequest(ExerciseSessionRecord::class, all)).records.size)
        assertEquals(0, outbox.size())
    }

    @Test fun missingPermissionsQueues() = runTest {
        val sink = HealthConnectSink(ApplicationProvider.getApplicationContext(), { FakeHealthConnectClient() }, InMemoryHcOutbox(), grantedCheck = { false })
        assertEquals(true, sink.write(session()) is SinkResult.Queued)
    }

    /** Codex P2: a ten-minute session with a two-minute pause exports the pause. */
    @Test fun pausesAreWrittenAsPauseSegments() = runTest {
        val fake = FakeHealthConnectClient()
        val sink = HealthConnectSink(ApplicationProvider.getApplicationContext(), { fake }, InMemoryHcOutbox(), grantedCheck = { true })
        assertEquals(SinkResult.Written, sink.write(session(n = 600).copy(pauses = listOf(t0 + 240_000 to t0 + 360_000))))
        val seg = fake.readRecords(ReadRecordsRequest(ExerciseSessionRecord::class, all)).records.single().segments.single()
        assertEquals(ExerciseSegment.EXERCISE_SEGMENT_TYPE_PAUSE, seg.segmentType)
        assertEquals(Instant.ofEpochMilli(t0 + 240_000), seg.startTime)
        assertEquals(Instant.ofEpochMilli(t0 + 360_000), seg.endTime)
    }
}
```

`HcSyncWorkerTest.kt` — the worker in a "new process" (Codex P1):

```kotlin
package com.debasish.livefit.healthconnect

import android.app.Application
import androidx.health.connect.client.records.ExerciseSessionRecord
import androidx.health.connect.client.request.ReadRecordsRequest
import androidx.health.connect.client.testing.FakeHealthConnectClient
import androidx.health.connect.client.time.TimeRangeFilter
import androidx.test.core.app.ApplicationProvider
import androidx.work.ListenableWorker
import androidx.work.testing.TestListenableWorkerBuilder
import com.debasish.livefit.health.FinishedSession
import com.debasish.livefit.health.SinkResult
import com.debasish.livefit.model.Provenance
import com.debasish.livefit.model.Sample
import com.debasish.livefit.model.SessionStatus
import com.debasish.livefit.model.SessionSummary
import com.debasish.livefit.model.WorkoutType
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.time.Instant

@RunWith(RobolectricTestRunner::class)
@Config(application = HcSyncWorkerTest.HostApp::class)
class HcSyncWorkerTest {
    /** Stands in for LiveFitApp: builds the sink from durable storage only (prefs outbox, stored sessions). */
    class HostApp : Application(), HcSyncHost {
        override suspend fun drainHealthOutbox(): Boolean {
            HealthConnectSink(this, { client }, PrefsHcOutbox(this), now = { 120_000L }, grantedCheck = { true }).drainOutbox { stored }
            return true
        }
        companion object { val client = FakeHealthConnectClient(); var stored: FinishedSession? = null }
    }

    private val live = Provenance.Live("galaxy-watch/health-services")
    private val t0 = 1_760_000_000_000L
    private val session = FinishedSession(
        SessionSummary(id = "s1", type = WorkoutType.Walk, startMs = t0, endMs = t0 + 60_000, activeMs = 60_000, provenance = live, status = SessionStatus.Complete),
        samples = List(60) { Sample(t0 + it * 1_000L, hr = 110, stepsTotal = it * 2) },
        sampleProvenance = List(60) { live },
    )

    @Test fun queuedWorkDrainsAfterProcessRecreation() = runTest {
        val app = ApplicationProvider.getApplicationContext<HostApp>()
        HostApp.stored = session
        // "Process 1": permissions missing → queued in the persistent outbox, next attempt at 60 s.
        val first = HealthConnectSink(app, { HostApp.client }, PrefsHcOutbox(app), now = { 0L }, grantedCheck = { false })
        assertTrue(first.write(session) is SinkResult.Queued)
        // "Process 2": only WorkManager runs; no service graph, no in-memory callback.
        val result = TestListenableWorkerBuilder<HcSyncWorker>(app).build().doWork()
        assertEquals(ListenableWorker.Result.success(), result)
        val all = TimeRangeFilter.between(Instant.ofEpochMilli(t0 - 1), Instant.ofEpochMilli(t0 + 120_000))
        assertEquals(1, HostApp.client.readRecords(ReadRecordsRequest(ExerciseSessionRecord::class, all)).records.size)
        assertEquals(0, PrefsHcOutbox(app).size())
    }
}
```

- [ ] **Step 3: Run test to verify it fails**

Run: `export JAVA_HOME=$(/usr/libexec/java_home -v 17) && ./gradlew :services:health-connect:testDebugUnitTest`
Expected: FAIL — unresolved `HealthConnectSink`, `InMemoryHcOutbox`.

- [ ] **Step 4: Outbox** — `HcOutbox.kt`

```kotlin
package com.debasish.livefit.healthconnect

import android.content.Context
import org.json.JSONObject

interface HcOutbox {
    suspend fun enqueue(sessionId: String, error: String?)
    suspend fun due(nowMs: Long): List<String>
    suspend fun markDone(sessionId: String)
    suspend fun markFailed(sessionId: String, error: String, nextAttemptMs: Long)
    suspend fun attempts(sessionId: String): Int
    suspend fun size(): Int
}

private data class Entry(val attempts: Int, val nextAttemptMs: Long, val lastError: String?)

class InMemoryHcOutbox : HcOutbox {
    private val entries = LinkedHashMap<String, Entry>()
    override suspend fun enqueue(sessionId: String, error: String?) { entries.putIfAbsent(sessionId, Entry(0, 0, error)) }
    override suspend fun due(nowMs: Long) = entries.filterValues { it.nextAttemptMs <= nowMs }.keys.toList()
    override suspend fun markDone(sessionId: String) { entries.remove(sessionId) }
    override suspend fun markFailed(sessionId: String, error: String, nextAttemptMs: Long) {
        entries[sessionId] = Entry((entries[sessionId]?.attempts ?: 0) + 1, nextAttemptMs, error)
    }
    override suspend fun attempts(sessionId: String) = entries[sessionId]?.attempts ?: 0
    override suspend fun size() = entries.size
}

/** Persistent outbox in SharedPreferences (a handful of entries at most). */
class PrefsHcOutbox(context: Context) : HcOutbox {
    private val prefs = context.applicationContext.getSharedPreferences("hc_outbox", 0)
    private fun read(id: String) = prefs.getString(id, null)?.let { JSONObject(it) }
    private fun write(id: String, attempts: Int, next: Long, err: String?) =
        prefs.edit().putString(id, JSONObject().put("a", attempts).put("n", next).put("e", err ?: "").toString()).apply()

    override suspend fun enqueue(sessionId: String, error: String?) { if (read(sessionId) == null) write(sessionId, 0, 0, error) }
    override suspend fun due(nowMs: Long) = prefs.all.keys.filter { (read(it)?.optLong("n") ?: 0) <= nowMs }
    override suspend fun markDone(sessionId: String) { prefs.edit().remove(sessionId).apply() }
    override suspend fun markFailed(sessionId: String, error: String, nextAttemptMs: Long) = write(sessionId, attempts(sessionId) + 1, nextAttemptMs, error)
    override suspend fun attempts(sessionId: String) = read(sessionId)?.optInt("a") ?: 0
    override suspend fun size() = prefs.all.size
}
```

- [ ] **Step 5: Sink** — `HealthConnectSink.kt`

```kotlin
package com.debasish.livefit.healthconnect

import android.content.Context
import androidx.health.connect.client.HealthConnectClient
import androidx.health.connect.client.permission.HealthPermission
import androidx.health.connect.client.records.ActiveCaloriesBurnedRecord
import androidx.health.connect.client.records.DistanceRecord
import androidx.health.connect.client.records.ExerciseSegment
import androidx.health.connect.client.records.ExerciseSessionRecord
import androidx.health.connect.client.records.HeartRateRecord
import androidx.health.connect.client.records.Record
import androidx.health.connect.client.records.RestingHeartRateRecord
import androidx.health.connect.client.records.SpeedRecord
import androidx.health.connect.client.records.StepsRecord
import androidx.health.connect.client.records.metadata.Device
import androidx.health.connect.client.records.metadata.Metadata
import androidx.health.connect.client.request.AggregateRequest
import androidx.health.connect.client.request.ReadRecordsRequest
import androidx.health.connect.client.time.TimeRangeFilter
import androidx.health.connect.client.units.Energy
import androidx.health.connect.client.units.Length
import androidx.health.connect.client.units.Velocity
import com.debasish.livefit.health.DailyTotals
import com.debasish.livefit.health.EligibilityGate
import com.debasish.livefit.health.FinishedSession
import com.debasish.livefit.health.HcLink
import com.debasish.livefit.health.HcPlanner
import com.debasish.livefit.health.HealthDataSink
import com.debasish.livefit.health.SinkResult
import com.debasish.livefit.health.SinkStatus
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.update
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId

/** Writes eligible sessions to Health Connect; anything that can't be written now waits in the outbox (V2 spec §2). */
class HealthConnectSink(
    context: Context,
    private val client: () -> HealthConnectClient?,
    private val outbox: HcOutbox,
    private val now: () -> Long = System::currentTimeMillis,
    private val grantedCheck: (suspend (HealthConnectClient) -> Boolean)? = null,
    private val beforeInsert: (String) -> Unit = {},
) : HealthDataSink {
    private val app = context.applicationContext

    val permissions: Set<String> = setOf(
        HealthPermission.getWritePermission(ExerciseSessionRecord::class), HealthPermission.getWritePermission(HeartRateRecord::class),
        HealthPermission.getWritePermission(StepsRecord::class), HealthPermission.getWritePermission(DistanceRecord::class),
        HealthPermission.getWritePermission(ActiveCaloriesBurnedRecord::class), HealthPermission.getWritePermission(SpeedRecord::class),
        HealthPermission.getReadPermission(StepsRecord::class), HealthPermission.getReadPermission(ActiveCaloriesBurnedRecord::class),
        HealthPermission.getReadPermission(RestingHeartRateRecord::class),
    )

    private val _status = MutableStateFlow(SinkStatus(HcLink.Unavailable))
    override val status: StateFlow<SinkStatus> = _status

    private suspend fun granted(c: HealthConnectClient) =
        grantedCheck?.invoke(c) ?: c.permissionController.getGrantedPermissions().containsAll(permissions)

    override suspend fun write(session: FinishedSession): SinkResult {
        EligibilityGate.check(session)?.let { return SinkResult.Rejected(it) }
        val c = client() ?: return queue(session.summary.id, "Health Connect unavailable", HcLink.Unavailable)
        if (!granted(c)) return queue(session.summary.id, "Permissions needed", HcLink.NeedsPermissions)
        return runCatching { insertAll(c, session) }.fold(
            onSuccess = { outbox.markDone(session.summary.id); synced(); SinkResult.Written },
            onFailure = { queue(session.summary.id, it.message ?: "write failed", HcLink.Linked) },
        )
    }

    private suspend fun queue(id: String, reason: String, link: HcLink): SinkResult {
        outbox.enqueue(id, reason)
        val attempts = outbox.attempts(id)
        outbox.markFailed(id, reason, now() + backoffMs(attempts))
        _status.update { SinkStatus(link, outbox.size(), it.lastSyncMs) }
        return SinkResult.Queued(reason)
    }

    private suspend fun synced() = _status.update { SinkStatus(HcLink.Linked, outbox.size(), now()) }

    /** 1 min → 2 → 4 … capped at 1 h. */
    private fun backoffMs(attempts: Int) = minOf(60_000L shl attempts.coerceAtMost(6), 3_600_000L)

    /** Retries every due session; [load] rebuilds a FinishedSession from history. */
    suspend fun drainOutbox(load: suspend (String) -> FinishedSession?) {
        for (id in outbox.due(now())) {
            val s = load(id) ?: run { outbox.markDone(id); null } ?: continue
            write(s)
        }
    }

    private suspend fun insertAll(c: HealthConnectClient, s: FinishedSession) {
        val plan = HcPlanner.plan(s)
        val zone = ZoneId.systemDefault().rules.getOffset(Instant.ofEpochMilli(plan.exercise.startMs))
        fun meta(id: String) = Metadata.activelyRecorded(device = Device(type = Device.TYPE_WATCH), clientRecordId = id, clientRecordVersion = 1)
        fun t(ms: Long) = Instant.ofEpochMilli(ms)

        val records = mutableListOf<Pair<String, Record>>()
        records += "exercise" to ExerciseSessionRecord(
            startTime = t(plan.exercise.startMs), startZoneOffset = zone, endTime = t(plan.exercise.endMs), endZoneOffset = zone,
            exerciseType = when (plan.exercise.type) { "RUN" -> ExerciseSessionRecord.EXERCISE_TYPE_RUNNING; "BIKE" -> ExerciseSessionRecord.EXERCISE_TYPE_BIKING; else -> ExerciseSessionRecord.EXERCISE_TYPE_WALKING },
            title = plan.exercise.title, metadata = meta(plan.exercise.id),
            // PAUSE segments are valid for every exercise type; HC excludes them from the session's active duration.
            segments = plan.exercise.pauses.map { (a, b) -> ExerciseSegment(t(a), t(b), ExerciseSegment.EXERCISE_SEGMENT_TYPE_PAUSE) },
        )
        plan.heartRateChunks.forEach { ch ->
            records += "hr" to HeartRateRecord(t(ch.samples.first().first), zone, t(ch.samples.last().first + 1), zone,
                ch.samples.map { HeartRateRecord.Sample(t(it.first), it.second) }, meta(ch.id))
        }
        plan.minuteIntervals.forEach { m ->
            if (m.steps > 0) records += "steps" to StepsRecord(t(m.startMs), zone, t(m.endMs), zone, m.steps, meta(m.stepsId))
            if (m.distanceKm > 0) records += "distance" to DistanceRecord(t(m.startMs), zone, t(m.endMs), zone, Length.kilometers(m.distanceKm), meta(m.distanceId))
            if (m.kcal > 0) records += "kcal" to ActiveCaloriesBurnedRecord(t(m.startMs), zone, t(m.endMs), zone, Energy.kilocalories(m.kcal), meta(m.kcalId))
        }
        if (plan.speedSamples.isNotEmpty()) records += "speed" to SpeedRecord(t(plan.speedSamples.first().first), zone, t(plan.speedSamples.last().first + 1), zone,
            plan.speedSamples.map { SpeedRecord.Sample(t(it.first), Velocity.kilometersPerHour(it.second)) }, meta(plan.speedId))

        // One insert call per kind keeps failures attributable; clientRecordId makes re-inserts upserts.
        records.groupBy({ it.first }, { it.second }).forEach { (kind, list) -> beforeInsert(kind); c.insertRecords(list) }
    }

    override suspend fun dailyTotals(day: LocalDate, zone: ZoneId): DailyTotals? {
        val c = client() ?: return null
        if (!granted(c)) return null
        val range = TimeRangeFilter.between(day.atStartOfDay(zone).toInstant(), Instant.ofEpochMilli(now()))
        val agg = c.aggregate(AggregateRequest(setOf(StepsRecord.COUNT_TOTAL, ActiveCaloriesBurnedRecord.ACTIVE_CALORIES_TOTAL), range))
        val rhr = c.readRecords(ReadRecordsRequest(RestingHeartRateRecord::class, range, ascendingOrder = false, pageSize = 1)).records.firstOrNull()
        return DailyTotals(agg[StepsRecord.COUNT_TOTAL] ?: 0, agg[ActiveCaloriesBurnedRecord.ACTIVE_CALORIES_TOTAL]?.inKilocalories ?: 0.0, rhr?.beatsPerMinute?.toInt())
    }

    companion object {
        fun availableClient(context: Context): HealthConnectClient? =
            if (HealthConnectClient.getSdkStatus(context) == HealthConnectClient.SDK_AVAILABLE) HealthConnectClient.getOrCreate(context) else null
    }
}
```

`PermissionsRationaleActivity.kt` — shows the privacy text and closes:

```kotlin
package com.debasish.livefit.healthconnect

import android.app.Activity
import android.os.Bundle
import android.widget.TextView

/** Required by Health Connect before it shows the permission dialog (V2 spec §2.4). */
class PermissionsRationaleActivity : Activity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(TextView(this).apply {
            setPadding(48, 96, 48, 48); textSize = 16f
            text = "Rokid LiveFit writes your finished workouts (exercise sessions, heart rate, steps, distance, active calories, speed) " +
                "to Health Connect and reads today's steps, active calories and resting heart rate for the Activity tab. " +
                "Demo or incomplete workouts are never written. Data stays on your phone."
        })
    }
}
```

`HcSyncWorker.kt`:

```kotlin
package com.debasish.livefit.healthconnect

import android.content.Context
import androidx.work.CoroutineWorker
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import java.util.concurrent.TimeUnit

/** Implemented by the phone's Application. It builds the sink and history from durable storage itself. */
interface HcSyncHost {
    /** Drains the outbox; false when Health Connect isn't bound in this build (demo). */
    suspend fun drainHealthOutbox(): Boolean
}

/**
 * Drains the outbox periodically and right after a queued write. WorkManager may start a fresh process just
 * for this, so nothing here relies on state set up by the UI or the hub service.
 */
class HcSyncWorker(ctx: Context, params: WorkerParameters) : CoroutineWorker(ctx, params) {
    override suspend fun doWork(): Result {
        val host = applicationContext as? HcSyncHost ?: return Result.failure() // wiring bug: never a silent success
        return runCatching { host.drainHealthOutbox() }.fold(onSuccess = { Result.success() }, onFailure = { Result.retry() })
    }

    companion object {
        fun schedule(context: Context) {
            val wm = WorkManager.getInstance(context)
            wm.enqueueUniquePeriodicWork("hc-sync", ExistingPeriodicWorkPolicy.KEEP, PeriodicWorkRequestBuilder<HcSyncWorker>(15, TimeUnit.MINUTES).build())
        }
        fun kick(context: Context) = WorkManager.getInstance(context).enqueue(OneTimeWorkRequestBuilder<HcSyncWorker>().setInitialDelay(60, TimeUnit.SECONDS).build())
    }
}
```

- [ ] **Step 6: Run tests to verify they pass**

Run: `export JAVA_HOME=$(/usr/libexec/java_home -v 17) && ./gradlew :services:health-connect:testDebugUnitTest`
Expected: PASS (6 tests). Note: `FakeHealthConnectClient` honours `clientRecordId` upserts; if the installed testing artifact version does not, assert record count via `clientRecordId` distinctness instead and keep the behaviour test on device (Task 4 Step 6).

- [ ] **Step 7: Commit**

```bash
git add services/health-connect
git commit -m "feat(health-connect): idempotent HC sink with pause segments, outbox, cold-start-safe worker"
```

---

### Task 3: Bind Health Connect in the hub; daily totals card

**Files:**
- Modify: `phone/build.gradle.kts` (deps `:services:health-connect`), `phone/src/main/java/com/debasish/livefit/phone/ServiceGraph.kt`
- Modify: `core/services/.../Services.kt` — none (uses `HealthDataSink` from `:services:health`)
- Create: `phone/src/main/java/com/debasish/livefit/phone/HistoryToFinished.kt`
- Create: `phone/src/main/java/com/debasish/livefit/phone/HealthSync.kt`
- Modify: `phone/src/main/java/com/debasish/livefit/phone/LiveFitApp.kt` (implements `HcSyncHost`)
- Create: `phone/src/main/java/com/debasish/livefit/phone/ui/history/DailyTotalsCard.kt`
- Modify: `phone/src/main/java/com/debasish/livefit/phone/ui/list/ListScreen.kt` (optional header slot), `AppActivity.kt` (Activity route uses the header)
- Modify: `phone/src/main/java/com/debasish/livefit/phone/SettingsStore.kt` (`saveToHealthConnect`, default true once linked)

**Interfaces:**
- Consumes: `HealthConnectSink`, `HcSyncWorker`, `PrefsHcOutbox` (Task 2); `HubWorkoutService.finished`, `HistoryStore` (V1).
- Produces: `suspend fun HistoryStore.finishedSession(id: String): FinishedSession?` (incl. pauses from events); `object HealthSync { fun sink(context: Context): HealthConnectSink; suspend fun drain(context: Context, live: Boolean): Boolean }`; `ServiceGraph.health: HealthConnectSink?`; `@Composable fun DailyTotalsCard(services: ServiceGraph)`; `ListScreen(header: (@Composable () -> Unit)? = null, …)`.

- [ ] **Step 1: `HistoryToFinished.kt`**

```kotlin
package com.debasish.livefit.phone

import com.debasish.livefit.health.FinishedSession
import com.debasish.livefit.health.HcPlanner
import com.debasish.livefit.services.HistoryStore
import kotlinx.coroutines.flow.first

/** Rebuilds what the HC sink needs from history; sample provenance and pauses come from the stored deltas. */
suspend fun HistoryStore.finishedSession(id: String): FinishedSession? {
    val summary = sessions.first().firstOrNull { it.id == id } ?: return null
    val deltas = deltas(id)
    val samples = deltas.flatMap { d -> d.samples }.sortedBy { it.tMs }
    val provenance = deltas.flatMap { d -> List(d.samples.size) { d.provenance } }
    return FinishedSession(summary, samples, provenance, pauses = HcPlanner.pauseIntervals(deltas.flatMap { it.events }))
}
```

`HealthSync.kt` — Health Connect wiring that needs no `ServiceGraph` (the graph would start the hub, Rokid authorization and the foreground service, which a background worker must not do):

```kotlin
package com.debasish.livefit.phone

import android.content.Context
import com.debasish.livefit.healthconnect.HealthConnectSink
import com.debasish.livefit.healthconnect.PrefsHcOutbox
import com.debasish.livefit.history.HistoryDatabase
import com.debasish.livefit.history.RoomSessionStore

object HealthSync {
    @Volatile private var instance: HealthConnectSink? = null

    /** One sink per process, shared by the service graph and the worker (one status flow). */
    fun sink(context: Context): HealthConnectSink = instance ?: synchronized(this) {
        instance ?: context.applicationContext.let { app -> HealthConnectSink(app, { HealthConnectSink.availableClient(app) }, PrefsHcOutbox(app)) }
            .also { instance = it }
    }

    /** Called by HcSyncWorker through LiveFitApp; builds everything from durable storage. */
    suspend fun drain(context: Context, live: Boolean): Boolean {
        if (!live) return false // demo builds never write (V2 spec §2.2)
        val history = RoomSessionStore(HistoryDatabase.shared(context))
        sink(context).drainOutbox { id -> history.finishedSession(id) }
        return true
    }
}
```

`LiveFitApp.kt` — add the host (the `services` graph stays lazy and is not touched by the worker):

```kotlin
class LiveFitApp : Application(), HcSyncHost {
    val services: ServiceGraph by lazy { /* unchanged from V1 Task 12 */ }

    override suspend fun drainHealthOutbox(): Boolean = HealthSync.drain(this, live = BuildConfig.LIVE_WATCH)
}
```
(import `com.debasish.livefit.healthconnect.HcSyncHost`.)

- [ ] **Step 2: Bind in `ServiceGraph`**

```kotlin
    val health: HealthConnectSink? = if (bindings.liveWatch) HealthSync.sink(app) else null
```
(Bound only when the metrics source is Live — demo builds can't write, V2 spec §2.2.)

In `start()`:

```kotlin
        health?.let { sink ->
            HcSyncWorker.schedule(app)
            scope.launch {
                workout.finished.collect { summary ->
                    if (!settings.saveToHealthConnect.value) return@collect
                    val s = history.finishedSession(summary.id) ?: return@collect
                    if (sink.write(s) is SinkResult.Queued) HcSyncWorker.kick(app)
                }
            }
        }
```

`SettingsStore`: `saveToHealthConnect: StateFlow<Boolean>` (default `true`) + setter, same pattern as V1 settings.

- [ ] **Step 3: Daily totals card**

```kotlin
package com.debasish.livefit.phone.ui.history

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.DirectionsWalk
import androidx.compose.material.icons.rounded.Favorite
import androidx.compose.material.icons.rounded.LocalFireDepartment
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.debasish.livefit.health.DailyTotals
import com.debasish.livefit.phone.ServiceGraph
import com.debasish.livefit.phone.ui.components.IconChip
import com.debasish.livefit.phone.ui.components.SoftCard
import com.debasish.livefit.phone.ui.theme.LiveFitColors
import kotlinx.coroutines.delay
import java.time.LocalDate
import java.time.ZoneId

/** Today's totals from Health Connect across all apps (V2 spec §2.3); refresh every 15 min. */
@Composable
fun DailyTotalsCard(services: ServiceGraph, onConnect: () -> Unit) {
    val sink = services.health ?: return
    var totals by remember { mutableStateOf<DailyTotals?>(null) }
    var loaded by remember { mutableStateOf(false) }
    LaunchedEffect(Unit) {
        while (true) {
            totals = runCatching { sink.dailyTotals(LocalDate.now(), ZoneId.systemDefault()) }.getOrNull(); loaded = true
            delay(15 * 60_000L)
        }
    }
    SoftCard(Modifier.padding(16.dp).fillMaxWidth(), onClick = if (totals == null && loaded) onConnect else null) {
        val t = totals
        if (t == null) {
            Text(if (loaded) "Connect Health Connect to see today's totals" else "Loading today…", modifier = Modifier.padding(16.dp), color = LiveFitColors.MintDeep)
        } else {
            Row(Modifier.padding(16.dp).fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                Total(Icons.AutoMirrored.Rounded.DirectionsWalk, LiveFitColors.ChipMint, "%,d".format(t.steps), "steps today")
                Total(Icons.Rounded.LocalFireDepartment, LiveFitColors.ChipAmber, "${t.activeKcal.toInt()}", "active kcal")
                Total(Icons.Rounded.Favorite, LiveFitColors.ChipCoral, t.restingHr?.toString() ?: "--", "resting ♥")
            }
        }
    }
}

@Composable
private fun Total(icon: androidx.compose.ui.graphics.vector.ImageVector, colors: Pair<androidx.compose.ui.graphics.Color, androidx.compose.ui.graphics.Color>, value: String, label: String) {
    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        IconChip(icon, colors, size = 32.dp, shapeRadius = 10.dp)
        Text(value, style = MaterialTheme.typography.titleLarge)
        Text(label, style = MaterialTheme.typography.labelMedium, color = LiveFitColors.InkSoft)
    }
}
```

`ListScreen` gains `header: (@Composable () -> Unit)? = null`, rendered as the first `item { }` of the `LazyColumn`. The Activity route passes `header = { DailyTotalsCard(services) { nav.navigate("linked/health") } }`.

- [ ] **Step 4: Build**

Run: `export JAVA_HOME=$(/usr/libexec/java_home -v 17) && ./gradlew :phone:assembleDebug`
Expected: BUILD SUCCESSFUL.

- [ ] **Step 5: Commit**

```bash
git add phone
git commit -m "feat(phone): write finished sessions to Health Connect and show daily totals"
```

---

### Task 4: Linked services → Health Connect, export past workouts, setup step

**Files:**
- Create: `phone/src/main/java/com/debasish/livefit/phone/ui/linked/LinkedHealthScreen.kt`
- Modify: `phone/src/main/java/com/debasish/livefit/phone/ui/settings/SettingsScreen.kt` (row), `AppActivity.kt` (route `linked/health`)
- Modify: `phone/src/main/java/com/debasish/livefit/phone/setup/SetupFlow.kt` + test (new `HealthConnect` step after `Watch`), `setup/SetupScreen.kt`
- Test: `phone/src/test/java/com/debasish/livefit/phone/setup/SetupFlowTest.kt` (update)

**Interfaces:**
- Consumes: `HealthConnectSink.permissions`, `status`, `write`, `HistoryStore.finishedSession` (Tasks 2–3).
- Produces: `suspend fun exportPast(services: ServiceGraph): Pair<Int, Int>` (written, skipped Demo/Incomplete) in `LinkedHealthScreen.kt`.

- [ ] **Step 1: Update the setup test** — `SetupStep` becomes `Welcome, Glasses, Watch, HealthConnect, Music, Voice, Done`; change `walksAllSteps` to `repeat(6)`. Run `./gradlew :phone:testDebugUnitTest --tests '*SetupFlowTest*'` → FAIL until the enum changes; add the entry → PASS.

- [ ] **Step 2: `LinkedHealthScreen.kt`**

```kotlin
package com.debasish.livefit.phone.ui.linked

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.CloudUpload
import androidx.compose.material.icons.rounded.Favorite
import androidx.compose.material.icons.rounded.Sync
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Switch
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.health.connect.client.PermissionController
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.debasish.livefit.health.SinkResult
import com.debasish.livefit.phone.ServiceGraph
import com.debasish.livefit.phone.finishedSession
import com.debasish.livefit.phone.ui.components.ChipRow
import com.debasish.livefit.phone.ui.components.ScreenHeader
import com.debasish.livefit.phone.ui.components.SectionLabel
import com.debasish.livefit.phone.ui.components.SoftCard
import com.debasish.livefit.phone.ui.theme.LiveFitColors
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch

suspend fun exportPast(services: ServiceGraph): Pair<Int, Int> {
    val sink = services.health ?: return 0 to 0
    var written = 0; var skipped = 0
    for (s in services.history.sessions.first()) {
        val f = services.history.finishedSession(s.id) ?: continue
        when (sink.write(f)) { SinkResult.Written -> written++; is SinkResult.Rejected -> skipped++; is SinkResult.Queued -> Unit }
    }
    return written to skipped
}

@Composable
fun LinkedHealthScreen(services: ServiceGraph, onBack: () -> Unit, toast: (String) -> Unit) {
    val sink = services.health
    val status by (sink?.status ?: return).collectAsStateWithLifecycle()
    val save by services.settings.saveToHealthConnect.collectAsStateWithLifecycle()
    val scope = rememberCoroutineScope()
    val request = rememberLauncherForActivityResult(PermissionController.createRequestPermissionResultContract()) { granted ->
        toast(if (granted.containsAll(sink.permissions)) "Health Connect linked" else "Some permissions were not granted")
    }
    Column(Modifier.fillMaxSize().background(LiveFitColors.SurfaceSoft)) {
        ScreenHeader("Health Connect", onBack)
        SoftCard(Modifier.padding(horizontal = 16.dp).fillMaxWidth()) {
            Column {
                ChipRow(Icons.Rounded.Favorite, LiveFitColors.ChipCoral, "Status", "${status.link.name}${if (status.queued > 0) " · ${status.queued} waiting" else ""}",
                    { request.launch(sink.permissions) })
                HorizontalDivider(Modifier.padding(start = 70.dp), color = LiveFitColors.Line)
                ChipRow(Icons.Rounded.Sync, LiveFitColors.ChipMint, "Save workouts", "Only complete, real workouts", { services.settings.setSaveToHealthConnect(!save) },
                    trailing = { Switch(save, services.settings::setSaveToHealthConnect) })
            }
        }
        SectionLabel("Past workouts")
        SoftCard(Modifier.padding(horizontal = 16.dp).fillMaxWidth()) {
            ChipRow(Icons.Rounded.CloudUpload, LiveFitColors.ChipSky, "Export past workouts", "Demo and incomplete workouts are skipped",
                { scope.launch { val (w, s) = exportPast(services); toast("Exported $w · skipped $s") } })
        }
    }
}
```

Settings: add under Linked services `ChipRow(Icons.Rounded.Favorite, LiveFitColors.ChipCoral, "Health Connect", hcStatus, { onNavigate("linked/health") })`; route `linked/health` in `AppActivity`. Setup wizard `HealthConnect` step: icon Favorite, title "Save workouts to Health Connect", button "Connect" launching the same permission contract.

- [ ] **Step 3: Build and device check (Review Focus #3 on device)**

`./gradlew :phone:testDebugUnitTest :phone:assembleDebug`; `tools/install-all.sh`; Linked services → Health Connect → Status → grant. Finish a real 5-min walk. Expected: Health Connect app shows one "LiveFit Walk" exercise with HR, steps, distance and calories matching the LiveFit summary; Samsung Health shows it too. Then "Export past workouts" → toast "Exported 0 · skipped N" (Demo/Incomplete) and no duplicate of the walk.

- [ ] **Step 4: Commit**

```bash
git add phone
git commit -m "feat(phone): Health Connect linked service, export past workouts, setup step"
```

---

### Task 5: Playlist contracts, ISO duration, track matcher, Google token provider

**Files:**
- Create: `services/playlists/build.gradle.kts`
- Create: `services/playlists/src/main/kotlin/com/debasish/livefit/playlists/PlaylistService.kt`, `IsoDuration.kt`, `TrackMatcher.kt`, `TrackCache.kt`
- Test: `services/playlists/src/test/kotlin/com/debasish/livefit/playlists/TrackMatcherTest.kt`
- Create: `services/playlists-android/build.gradle.kts`, `src/main/AndroidManifest.xml`
- Create: `services/playlists-android/src/main/kotlin/com/debasish/livefit/playlists/android/GoogleTokenProvider.kt`
- Test: `services/playlists-android/src/test/kotlin/com/debasish/livefit/playlists/android/AccountStateTest.kt`

**Interfaces:**
- Produces:
  - `sealed interface AccountState { SignedOut; SignedIn(email: String?); NeedsConsent }`
  - `data class Playlist(val id: String, val title: String)`
  - `sealed interface AddResult { Added(playlistTitle); Declined; NotFound; Failed(reason) }`
  - `interface PlaylistService { val account: StateFlow<AccountState>; suspend fun playlists(): List<Playlist>; suspend fun addCurrent(track: NowPlaying, playlistId: String, playlistTitle: String): AddResult }`
  - `interface TokenProvider { suspend fun token(interactive: Boolean): TokenResult }`, `sealed interface TokenResult { Token(value); NeedsConsent(pendingIntent: Any?); Failed(reason) }`
  - `object IsoDuration { fun toMs(iso: String): Long? }`
  - `data class Candidate(val videoId: String, val title: String, val channel: String, val durationMs: Long?)`, `data class Match(val candidate: Candidate, val score: Double, val strong: Boolean)`, `object TrackMatcher { fun best(track: NowPlaying, candidates: List<Candidate>): Match? }`
  - `interface TrackCache { suspend fun get(key: String): String?; suspend fun put(key: String, videoId: String) }`, `fun cacheKey(track: NowPlaying): String`
  - `class GoogleTokenProvider(context: Context) : TokenProvider` + `fun accountStateFor(result: TokenResult): AccountState` (pure)

- [ ] **Step 1: Build files** — `services/playlists/build.gradle.kts` (pure; OkHttp for Task 7):

```kotlin
plugins {
    id("org.jetbrains.kotlin.jvm")
    id("org.jetbrains.kotlin.plugin.serialization")
}
kotlin { jvmToolchain(17) }
dependencies {
    api(project(":core:services"))
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-core:1.9.0")
    implementation("org.jetbrains.kotlinx:kotlinx-serialization-json:1.7.3")
    implementation("com.squareup.okhttp3:okhttp:4.12.0")
    testImplementation(kotlin("test"))
    testImplementation("org.jetbrains.kotlinx:kotlinx-coroutines-test:1.9.0")
    testImplementation("com.squareup.okhttp3:mockwebserver:4.12.0")
    testImplementation(project(":services:confirm"))
}
tasks.test { useJUnitPlatform() }
```

`services/playlists-android/build.gradle.kts`: Android library (namespace `com.debasish.livefit.playlists.android`, minSdk 29) with `api(project(":services:playlists"))`, `implementation("com.google.android.gms:play-services-auth:21.2.0")`, `implementation("org.jetbrains.kotlinx:kotlinx-coroutines-play-services:1.9.0")`, test deps `kotlin("test")`, `junit:junit:4.13.2`. Empty manifest.

- [ ] **Step 2: Write the failing matcher test**

```kotlin
package com.debasish.livefit.playlists

import com.debasish.livefit.model.NowPlaying
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class TrackMatcherTest {
    private val track = NowPlaying("Waka Waka (Esto es Africa)", "Shakira", isPlaying = true, durationMs = 202_000)

    @Test fun isoDurations() {
        assertEquals(202_000L, IsoDuration.toMs("PT3M22S"))
        assertEquals(3_723_000L, IsoDuration.toMs("PT1H2M3S"))
        assertEquals(45_000L, IsoDuration.toMs("PT45S"))
        assertNull(IsoDuration.toMs("garbage"))
    }

    @Test fun officialAudioIsAStrongMatch() {
        val m = TrackMatcher.best(track, listOf(
            Candidate("live", "Shakira - Waka Waka (Live at World Cup)", "FIFA", 260_000),
            Candidate("official", "Waka Waka (Esto es Africa)", "Shakira - Topic", 203_000),
        ))!!
        assertEquals("official", m.candidate.videoId)
        assertTrue(m.strong)
    }

    @Test fun durationOffBy8sIsOkButNotStrong() {
        val m = TrackMatcher.best(track, listOf(Candidate("x", "Waka Waka Esto es Africa", "Shakira", 210_000)))!!
        assertFalse(m.strong)
    }

    @Test fun unrelatedResultsAreNoMatch() =
        assertNull(TrackMatcher.best(track, listOf(Candidate("y", "Eye of the Tiger", "Survivor", 245_000))))

    @Test fun cacheKeyNormalises() = assertEquals(cacheKey(track), cacheKey(track.copy(title = "waka waka (ESTO ES AFRICA) ")))
}
```

- [ ] **Step 3: Run test to verify it fails**

Run: `export JAVA_HOME=$(/usr/libexec/java_home -v 17) && ./gradlew :services:playlists:test`
Expected: FAIL — unresolved.

- [ ] **Step 4: Implement** — `PlaylistService.kt`:

```kotlin
package com.debasish.livefit.playlists

import com.debasish.livefit.model.NowPlaying
import kotlinx.coroutines.flow.StateFlow

sealed interface AccountState {
    data object SignedOut : AccountState
    data class SignedIn(val email: String?) : AccountState
    /** Consent revoked / scope changed: must reconnect from the phone UI (V2 spec §3.1). */
    data object NeedsConsent : AccountState
}

data class Playlist(val id: String, val title: String)

sealed interface AddResult {
    data class Added(val playlistTitle: String) : AddResult
    data object Declined : AddResult
    data object NotFound : AddResult
    data class Failed(val reason: String) : AddResult
}

interface PlaylistService {
    val account: StateFlow<AccountState>
    suspend fun playlists(): List<Playlist>
    suspend fun addCurrent(track: NowPlaying, playlistId: String, playlistTitle: String): AddResult
}

sealed interface TokenResult {
    data class Token(val value: String) : TokenResult
    data class NeedsConsent(val pendingIntent: Any?) : TokenResult
    data class Failed(val reason: String) : TokenResult
}

/** Supplies a fresh access token each call; never stores refresh tokens (V2 spec §3.1). */
interface TokenProvider { suspend fun token(interactive: Boolean): TokenResult }
```

`IsoDuration.kt`:

```kotlin
package com.debasish.livefit.playlists

object IsoDuration {
    private val re = Regex("^PT(?:(\\d+)H)?(?:(\\d+)M)?(?:(\\d+)S)?$")
    fun toMs(iso: String): Long? {
        val m = re.matchEntire(iso.trim()) ?: return null
        val (h, mi, s) = m.destructured
        if (h.isEmpty() && mi.isEmpty() && s.isEmpty()) return null
        return ((h.toLongOrNull() ?: 0) * 3600 + (mi.toLongOrNull() ?: 0) * 60 + (s.toLongOrNull() ?: 0)) * 1000
    }
}
```

`TrackMatcher.kt`:

```kotlin
package com.debasish.livefit.playlists

import com.debasish.livefit.model.NowPlaying
import kotlin.math.abs

data class Candidate(val videoId: String, val title: String, val channel: String, val durationMs: Long?)
data class Match(val candidate: Candidate, val score: Double, val strong: Boolean)

/** Scores YouTube candidates for the playing track (V2 spec §3.2 step 3). */
object TrackMatcher {
    private fun tokens(s: String) = s.lowercase().replace(Regex("[^a-z0-9 ]"), " ").split(" ").filter { it.length > 1 && it !in noise }.toSet()
    private val noise = setOf("official", "video", "audio", "lyrics", "topic", "hd", "the", "feat", "ft")
    private fun jaccard(a: Set<String>, b: Set<String>) = if (a.isEmpty() || b.isEmpty()) 0.0 else a.intersect(b).size.toDouble() / a.union(b).size

    fun best(track: NowPlaying, candidates: List<Candidate>): Match? {
        val tt = tokens(track.title); val ta = tokens(track.artist)
        return candidates.map { c ->
            val titleSim = jaccard(tt, tokens(c.title))
            val artistSim = maxOf(jaccard(ta, tokens(c.channel)), if (ta.isNotEmpty() && tokens(c.title).containsAll(ta)) 1.0 else 0.0)
            val delta = c.durationMs?.let { abs(it - track.durationMs) }
            val durScore = when { delta == null || track.durationMs == 0L -> 0.5; delta <= 3_000 -> 1.0; delta <= 10_000 -> 0.6; else -> 0.0 }
            val score = 0.5 * titleSim + 0.3 * artistSim + 0.2 * durScore
            Match(c, score, strong = score >= 0.75 && delta != null && delta <= 3_000)
        }.filter { it.score >= 0.55 && (it.candidate.durationMs == null || abs(it.candidate.durationMs - track.durationMs) <= 10_000 || track.durationMs == 0L) }
            .maxByOrNull { it.score }
    }
}
```

`TrackCache.kt`:

```kotlin
package com.debasish.livefit.playlists

import com.debasish.livefit.model.NowPlaying

interface TrackCache {
    suspend fun get(key: String): String?
    suspend fun put(key: String, videoId: String)
}

class InMemoryTrackCache : TrackCache {
    private val m = HashMap<String, String>()
    override suspend fun get(key: String) = m[key]
    override suspend fun put(key: String, videoId: String) { m[key] = videoId }
}

fun cacheKey(t: NowPlaying): String =
    listOf(t.title, t.artist).joinToString("|") { it.lowercase().replace(Regex("[^a-z0-9]"), "") } + "|" + (t.durationMs / 1000)
```

Run `./gradlew :services:playlists:test` → PASS (5 tests).

- [ ] **Step 5: Write the failing account-state test (Review Focus #5)** — `AccountStateTest.kt`

```kotlin
package com.debasish.livefit.playlists.android

import com.debasish.livefit.playlists.AccountState
import com.debasish.livefit.playlists.TokenResult
import kotlin.test.Test
import kotlin.test.assertEquals

class AccountStateTest {
    @Test fun tokenMeansSignedIn() = assertEquals(AccountState.SignedIn("me@x"), accountStateFor(TokenResult.Token("t"), email = "me@x"))
    @Test fun resolutionRequiredBecomesNeedsConsent() = assertEquals(AccountState.NeedsConsent, accountStateFor(TokenResult.NeedsConsent(null), email = "me@x"))
    @Test fun failureWithoutAccountIsSignedOut() = assertEquals(AccountState.SignedOut, accountStateFor(TokenResult.Failed("x"), email = null))
}
```

Run `./gradlew :services:playlists-android:testDebugUnitTest` → FAIL (unresolved `accountStateFor`).

- [ ] **Step 6: `GoogleTokenProvider.kt`**

```kotlin
package com.debasish.livefit.playlists.android

import android.content.Context
import com.debasish.livefit.playlists.AccountState
import com.debasish.livefit.playlists.TokenProvider
import com.debasish.livefit.playlists.TokenResult
import com.google.android.gms.auth.api.identity.AuthorizationRequest
import com.google.android.gms.auth.api.identity.Identity
import com.google.android.gms.common.api.Scope
import kotlinx.coroutines.tasks.await

fun accountStateFor(result: TokenResult, email: String?): AccountState = when (result) {
    is TokenResult.Token -> AccountState.SignedIn(email)
    is TokenResult.NeedsConsent -> AccountState.NeedsConsent
    is TokenResult.Failed -> if (email == null) AccountState.SignedOut else AccountState.NeedsConsent
}

/**
 * Calls AuthorizationClient.authorize() for every token (no refresh token stored, V2 spec §3.1).
 * Access token cached in memory until ~5 min before its 1 h lifetime ends.
 */
class GoogleTokenProvider(context: Context) : TokenProvider {
    private val app = context.applicationContext
    private val client = Identity.getAuthorizationClient(app)
    private var cached: String? = null
    private var cachedUntilMs = 0L

    override suspend fun token(interactive: Boolean): TokenResult {
        cached?.takeIf { System.currentTimeMillis() < cachedUntilMs }?.let { return TokenResult.Token(it) }
        val req = AuthorizationRequest.builder().setRequestedScopes(listOf(Scope(SCOPE))).build()
        return runCatching { client.authorize(req).await() }.fold(
            onSuccess = { r ->
                when {
                    r.hasResolution() -> TokenResult.NeedsConsent(r.pendingIntent) // UI launches it only when interactive
                    r.accessToken != null -> { cached = r.accessToken; cachedUntilMs = System.currentTimeMillis() + 55 * 60_000; TokenResult.Token(r.accessToken!!) }
                    else -> TokenResult.Failed("no token")
                }
            },
            onFailure = { TokenResult.Failed(it.message ?: "authorization failed") },
        )
    }

    fun clear() { cached = null; cachedUntilMs = 0 }

    companion object { const val SCOPE = "https://www.googleapis.com/auth/youtube" }
}
```

Run `./gradlew :services:playlists-android:testDebugUnitTest` → PASS.

- [ ] **Step 7: Commit**

```bash
git add settings.gradle.kts services/playlists services/playlists-android
git commit -m "feat(playlists): contracts, ISO duration, track matcher and Google token provider"
```

---

### Task 6: Add-to-playlist flow (capture, match, confirm, bind)

**Files:**
- Create: `services/playlists/src/main/kotlin/com/debasish/livefit/playlists/AddToPlaylistFlow.kt`
- Test: `services/playlists/src/test/kotlin/com/debasish/livefit/playlists/AddToPlaylistFlowTest.kt`
- Modify: `core/model/src/main/kotlin/com/debasish/livefit/model/Frames.kt` (`ConfirmationKind.AddToPlaylistMatch`)

**Interfaces:**
- Consumes: `TrackMatcher`, `TrackCache`, `Candidate`, `AddResult` (Task 5), `ConfirmationService` (V1).
- Produces:
  - `interface YouTubeOps { suspend fun search(q: String): List<String>; suspend fun details(ids: List<String>): List<Candidate>; suspend fun insert(playlistId: String, videoId: String); suspend fun contains(playlistId: String, videoId: String): Boolean }`
  - `class AddToPlaylistFlow(ops: YouTubeOps, cache: TrackCache, confirm: ConfirmationService)` with `suspend fun add(track: NowPlaying, playlistId: String, playlistTitle: String): AddResult` — the track argument **is** the captured snapshot; later now-playing changes are never read.

- [ ] **Step 1: Add the confirmation kind** — `enum class ConfirmationKind { TakeOverWorkout, StopWorkoutByVoice, AddToPlaylistMatch }` (wire compatibility handled by the protocol bump in Task 8).

- [ ] **Step 2: Write the failing test**

```kotlin
package com.debasish.livefit.playlists

import com.debasish.livefit.confirm.DefaultConfirmationService
import com.debasish.livefit.model.NowPlaying
import com.debasish.livefit.services.Clock
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.async
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

@OptIn(ExperimentalCoroutinesApi::class)
class AddToPlaylistFlowTest {
    private class Ops : YouTubeOps {
        val inserted = mutableListOf<Pair<String, String>>()
        var searches = 0
        var results = mapOf<String, List<Candidate>>()
        override suspend fun search(q: String): List<String> { searches++; return results.entries.firstOrNull { q.contains(it.key, true) }?.value?.map { it.videoId } ?: emptyList() }
        override suspend fun details(ids: List<String>) = results.values.flatten().filter { it.videoId in ids }
        override suspend fun insert(playlistId: String, videoId: String) { inserted += playlistId to videoId }
        override suspend fun contains(playlistId: String, videoId: String) = (playlistId to videoId) in inserted
    }
    private val waka = NowPlaying("Waka Waka (Esto es Africa)", "Shakira", isPlaying = true, durationMs = 202_000)
    private val tiger = NowPlaying("Eye of the Tiger", "Survivor", isPlaying = true, durationMs = 245_000)

    @Test fun strongMatchIsAddedWithoutConfirmationAndCached() = runTest {
        val ops = Ops().apply { results = mapOf("Waka" to listOf(Candidate("w1", "Waka Waka (Esto es Africa)", "Shakira - Topic", 202_000))) }
        val cache = InMemoryTrackCache()
        val flow = AddToPlaylistFlow(ops, cache, DefaultConfirmationService(Clock { testScheduler.currentTime }))
        assertEquals(AddResult.Added("Workout"), flow.add(waka, "PL1", "Workout"))
        assertEquals(listOf("PL1" to "w1"), ops.inserted)
        flow.add(waka, "PL2", "Other")
        assertEquals(1, ops.searches, "second add uses the cache")
    }

    /** Review Focus #1: the song changes while the confirmation is open. */
    @Test fun confirmationUsesCapturedTrackEvenIfSongChanged() = runTest {
        val ops = Ops().apply { results = mapOf("Waka" to listOf(Candidate("w2", "Waka Waka Esto es Africa", "Shakira", 210_000)),
            "Tiger" to listOf(Candidate("t1", "Eye of the Tiger", "Survivor", 245_000))) }
        val confirm = DefaultConfirmationService(Clock { testScheduler.currentTime })
        val flow = AddToPlaylistFlow(ops, InMemoryTrackCache(), confirm)
        val result = async { flow.add(waka, "PL1", "Workout") }
        runCurrent()
        val pending = confirm.pending.value!!
        assertTrue(pending.message.contains("Waka"))
        // (YouTube Music moves on to "Eye of the Tiger" here — the flow never re-reads now-playing.)
        confirm.answer(pending.id, yes = true)
        assertEquals(AddResult.Added("Workout"), result.await())
        assertEquals(listOf("PL1" to "w2"), ops.inserted)
    }

    @Test fun declinedWeakMatchAddsNothing() = runTest {
        val ops = Ops().apply { results = mapOf("Waka" to listOf(Candidate("w2", "Waka Waka Esto es Africa", "Shakira", 210_000))) }
        val confirm = DefaultConfirmationService(Clock { testScheduler.currentTime })
        val flow = AddToPlaylistFlow(ops, InMemoryTrackCache(), confirm)
        val r = async { flow.add(waka, "PL1", "Workout") }
        runCurrent(); confirm.answer(confirm.pending.value!!.id, yes = false)
        assertEquals(AddResult.Declined, r.await())
        assertTrue(ops.inserted.isEmpty())
    }

    @Test fun noCandidateIsNotFound() = runTest {
        val flow = AddToPlaylistFlow(Ops(), InMemoryTrackCache(), DefaultConfirmationService(Clock { testScheduler.currentTime }))
        assertEquals(AddResult.NotFound, flow.add(tiger, "PL1", "Workout"))
    }
}
```

- [ ] **Step 3: Run test to verify it fails**

Run: `export JAVA_HOME=$(/usr/libexec/java_home -v 17) && ./gradlew :services:playlists:test --tests '*AddToPlaylistFlowTest*'`
Expected: FAIL — unresolved `AddToPlaylistFlow`, `YouTubeOps`.

- [ ] **Step 4: Implement `AddToPlaylistFlow.kt`**

```kotlin
package com.debasish.livefit.playlists

import com.debasish.livefit.model.ConfirmationKind
import com.debasish.livefit.model.NowPlaying
import com.debasish.livefit.services.ConfirmationOutcome
import com.debasish.livefit.services.ConfirmationService

interface YouTubeOps {
    suspend fun search(q: String): List<String>
    suspend fun details(ids: List<String>): List<Candidate>
    suspend fun insert(playlistId: String, videoId: String)
    suspend fun contains(playlistId: String, videoId: String): Boolean
}

/**
 * V2 spec §3.2: capture → resolve (cache, search + durations) → confirm weak matches → insert.
 * [track] is the snapshot captured when the user asked; nothing here reads now-playing again,
 * and the confirmation's yes is bound to (track, candidate, playlist) held in this coroutine.
 */
class AddToPlaylistFlow(private val ops: YouTubeOps, private val cache: TrackCache, private val confirm: ConfirmationService) {

    suspend fun add(track: NowPlaying, playlistId: String, playlistTitle: String): AddResult {
        val key = cacheKey(track)
        val videoId = cache.get(key) ?: run {
            val ids = ops.search("${track.title} ${track.artist}")
            if (ids.isEmpty()) return AddResult.NotFound
            val match = TrackMatcher.best(track, ops.details(ids)) ?: return AddResult.NotFound
            if (!match.strong) {
                val outcome = confirm.ask(ConfirmationKind.AddToPlaylistMatch, "Add to $playlistTitle?",
                    "Add '${match.candidate.title} – ${match.candidate.channel}' to $playlistTitle?", defaultYes = true)
                if (outcome != ConfirmationOutcome.Yes) return AddResult.Declined
            }
            match.candidate.videoId.also { cache.put(key, it) }
        }
        ops.insert(playlistId, videoId)
        return AddResult.Added(playlistTitle)
    }
}
```

Run `./gradlew :services:playlists:test` → PASS.

- [ ] **Step 5: Commit**

```bash
git add core/model services/playlists
git commit -m "feat(playlists): add-to-playlist flow with captured track and bound confirmation"
```

---

### Task 7: YouTube Data API client (search, durations, playlists, verified insert)

**Files:**
- Create: `services/playlists/src/main/kotlin/com/debasish/livefit/playlists/YouTubeApi.kt`
- Test: `services/playlists/src/test/kotlin/com/debasish/livefit/playlists/YouTubeApiTest.kt`
- Create: `services/playlists-android/src/main/kotlin/com/debasish/livefit/playlists/android/YouTubePlaylistService.kt`, `PrefsTrackCache.kt`

**Interfaces:**
- Consumes: `TokenProvider`, `YouTubeOps`, `AddToPlaylistFlow`, `Playlist`, `AccountState` (Tasks 5–6).
- Produces:
  - `class YouTubeApi(baseUrl: String = "https://www.googleapis.com/youtube/v3/", http: OkHttpClient = OkHttpClient(), onUnauthorized: () -> Unit = {}, token: suspend () -> String) : YouTubeOps` — HTTP 401 → `onUnauthorized()` (drop the cached token) and one retry with a fresh token; a second 401 → `YouTubeError.Auth` + `suspend fun myPlaylists(): List<Playlist>`; throws `YouTubeError.Quota`, `YouTubeError.Auth`, `YouTubeError.Network(uncertain: Boolean)`, `YouTubeError.Http(code)`.
  - `suspend fun YouTubeOps.insertVerified(playlistId: String, videoId: String)` — on `Network(uncertain = true)` checks `contains` before one retry.
  - `class YouTubePlaylistService(context: Context, scope: CoroutineScope, tokens: GoogleTokenProvider, confirm: ConfirmationService) : PlaylistService` with `fun connectIntent(): Any?` (last `NeedsConsent` pending intent for the Linked services button). Any `YouTubeError.Auth` sets `account = NeedsConsent` (Linked services shows "Reconnect YouTube"); a failed authorize() call (`TokenResult.Failed`, e.g. offline) is a network error, not a sign-out.

- [ ] **Step 1: Write the failing test** — `YouTubeApiTest.kt` (MockWebServer)

```kotlin
package com.debasish.livefit.playlists

import kotlinx.coroutines.test.runTest
import okhttp3.OkHttpClient
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.SocketPolicy
import java.util.concurrent.TimeUnit
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

class YouTubeApiTest {
    private val server = MockWebServer()
    private lateinit var api: YouTubeApi

    @BeforeTest fun up() {
        server.start()
        api = YouTubeApi(server.url("/").toString(), OkHttpClient.Builder().readTimeout(1, TimeUnit.SECONDS).build()) { "tok" }
    }
    @AfterTest fun down() = server.shutdown()

    @Test fun searchThenDetailsGivesDurations() = runTest {
        server.enqueue(MockResponse().setBody("""{"items":[{"id":{"videoId":"v1"}},{"id":{"videoId":"v2"}}]}"""))
        server.enqueue(MockResponse().setBody("""{"items":[{"id":"v1","snippet":{"title":"Waka Waka","channelTitle":"Shakira"},"contentDetails":{"duration":"PT3M22S"}}]}"""))
        assertEquals(listOf("v1", "v2"), api.search("waka waka shakira"))
        assertEquals(listOf(Candidate("v1", "Waka Waka", "Shakira", 202_000)), api.details(listOf("v1", "v2")))
        val searchReq = server.takeRequest()
        assertEquals("Bearer tok", searchReq.getHeader("Authorization"))
        assertEquals("10", searchReq.requestUrl!!.queryParameter("videoCategoryId"))
    }

    @Test fun quotaErrorIsTyped() = runTest {
        server.enqueue(MockResponse().setResponseCode(403).setBody("""{"error":{"errors":[{"reason":"quotaExceeded"}]}}"""))
        assertFailsWith<YouTubeError.Quota> { api.search("x") }
    }

    /** Review Focus #2: response lost after the insert reached the server. */
    @Test fun uncertainInsertIsVerifiedNotDuplicated() = runTest {
        server.enqueue(MockResponse().setSocketPolicy(SocketPolicy.NO_RESPONSE))                        // insert: no response
        server.enqueue(MockResponse().setBody("""{"items":[{"snippet":{"resourceId":{"videoId":"v1"}}}]}""")) // verify: present
        api.insertVerified("PL1", "v1")
        assertEquals(2, server.requestCount, "no second insert")
        assertEquals("POST", server.takeRequest().method)
        assertEquals("GET", server.takeRequest().method)
    }

    /** Codex P2: a rejected (revoked/expired) token is dropped and re-requested once; a second rejection is Auth. */
    @Test fun unauthorizedDropsTheTokenAndRetriesOnce() = runTest {
        var invalidated = 0
        var issued = 0
        val api2 = YouTubeApi(server.url("/").toString(), OkHttpClient(), onUnauthorized = { invalidated++ }) { "tok${issued++}" }
        server.enqueue(MockResponse().setResponseCode(401))
        server.enqueue(MockResponse().setBody("""{"items":[]}"""))
        assertEquals(emptyList<String>(), api2.search("x"))
        assertEquals(1, invalidated)
        assertEquals("Bearer tok0", server.takeRequest().getHeader("Authorization"))
        assertEquals("Bearer tok1", server.takeRequest().getHeader("Authorization"))
        server.enqueue(MockResponse().setResponseCode(401))
        server.enqueue(MockResponse().setResponseCode(401))
        assertFailsWith<YouTubeError.Auth> { api2.search("y") }
        assertEquals(3, invalidated)
    }

    @Test fun uncertainInsertRetriesOnceWhenMissing() = runTest {
        server.enqueue(MockResponse().setSocketPolicy(SocketPolicy.NO_RESPONSE))
        server.enqueue(MockResponse().setBody("""{"items":[]}"""))
        server.enqueue(MockResponse().setBody("""{"id":"item"}"""))
        api.insertVerified("PL1", "v1")
        assertEquals(3, server.requestCount)
    }
}
```

- [ ] **Step 2: Run test to verify it fails**

Run: `export JAVA_HOME=$(/usr/libexec/java_home -v 17) && ./gradlew :services:playlists:test --tests '*YouTubeApiTest*'`
Expected: FAIL — unresolved `YouTubeApi`.

- [ ] **Step 3: Implement `YouTubeApi.kt`**

```kotlin
package com.debasish.livefit.playlists

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonObject
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.io.IOException

sealed class YouTubeError(msg: String) : Exception(msg) {
    class Quota : YouTubeError("Daily YouTube limit reached")
    class Auth : YouTubeError("Reconnect YouTube on your phone")
    class Network(val uncertain: Boolean) : YouTubeError("Network error")
    class Http(val code: Int) : YouTubeError("YouTube error $code")
}

/** Minimal YouTube Data API v3 client (V2 spec §3.2). */
class YouTubeApi(
    private val baseUrl: String = "https://www.googleapis.com/youtube/v3/",
    private val http: OkHttpClient = OkHttpClient(),
    private val onUnauthorized: () -> Unit = {},
    private val token: suspend () -> String,
) : YouTubeOps {
    private val json = Json { ignoreUnknownKeys = true }
    private class Unauthorized : Exception()

    /** A 401 means the request was not executed, so retrying (even a POST) is safe. */
    private suspend fun call(path: String, query: Map<String, String>, body: JsonObject? = null): JsonObject =
        try { attempt(path, query, body) } catch (e: Unauthorized) {
            onUnauthorized() // the cached token is stale/revoked: the next token() re-authorizes silently
            try { attempt(path, query, body) } catch (e2: Unauthorized) { onUnauthorized(); throw YouTubeError.Auth() }
        }

    private suspend fun attempt(path: String, query: Map<String, String>, body: JsonObject?): JsonObject = withContext(Dispatchers.IO) {
        val url = (baseUrl + path).toHttpUrl().newBuilder().apply { query.forEach { (k, v) -> addQueryParameter(k, v) } }.build()
        val req = Request.Builder().url(url).header("Authorization", "Bearer ${token()}")
            .apply { if (body != null) post(body.toString().toRequestBody("application/json".toMediaType())) }.build()
        val resp = try { http.newCall(req).execute() } catch (e: IOException) { throw YouTubeError.Network(uncertain = body != null) }
        resp.use { r ->
            val text = r.body?.string().orEmpty()
            when {
                r.code == 401 -> throw Unauthorized()
                r.code == 403 && text.contains("quota", ignoreCase = true) -> throw YouTubeError.Quota()
                !r.isSuccessful -> throw YouTubeError.Http(r.code)
                else -> json.parseToJsonElement(text.ifBlank { "{}" }).jsonObject
            }
        }
    }

    override suspend fun search(q: String): List<String> =
        call("search", mapOf("part" to "snippet", "q" to q, "type" to "video", "videoCategoryId" to "10", "maxResults" to "5"))["items"]
            ?.jsonArray?.mapNotNull { it.jsonObject["id"]?.jsonObject?.get("videoId")?.jsonPrimitive?.content } ?: emptyList()

    override suspend fun details(ids: List<String>): List<Candidate> =
        call("videos", mapOf("part" to "contentDetails,snippet", "id" to ids.joinToString(",")))["items"]?.jsonArray?.map { it.jsonObject }?.map { v ->
            val sn = v["snippet"]!!.jsonObject
            Candidate(v["id"]!!.jsonPrimitive.content, sn["title"]!!.jsonPrimitive.content, sn["channelTitle"]?.jsonPrimitive?.content ?: "",
                v["contentDetails"]?.jsonObject?.get("duration")?.jsonPrimitive?.content?.let(IsoDuration::toMs))
        } ?: emptyList()

    override suspend fun insert(playlistId: String, videoId: String) {
        call("playlistItems", mapOf("part" to "snippet"), buildJsonObject {
            putJsonObject("snippet") { put("playlistId", playlistId); putJsonObject("resourceId") { put("kind", "youtube#video"); put("videoId", videoId) } }
        })
    }

    override suspend fun contains(playlistId: String, videoId: String): Boolean =
        call("playlistItems", mapOf("part" to "snippet", "playlistId" to playlistId, "videoId" to videoId))["items"]?.jsonArray?.isNotEmpty() == true

    suspend fun myPlaylists(): List<Playlist> =
        call("playlists", mapOf("part" to "snippet", "mine" to "true", "maxResults" to "50"))["items"]?.jsonArray?.map { it.jsonObject }?.map {
            Playlist(it["id"]!!.jsonPrimitive.content, it["snippet"]!!.jsonObject["title"]!!.jsonPrimitive.content)
        } ?: emptyList()
}

/** A lost response after POST may mean it succeeded: verify before retrying once (V2 spec §3.2). */
suspend fun YouTubeOps.insertVerified(playlistId: String, videoId: String) {
    try { insert(playlistId, videoId) } catch (e: YouTubeError.Network) {
        if (!e.uncertain) throw e
        if (contains(playlistId, videoId)) return
        insert(playlistId, videoId)
    }
}
```

In `AddToPlaylistFlow.add`, replace `ops.insert(playlistId, videoId)` with `ops.insertVerified(playlistId, videoId)`.

Run: `./gradlew :services:playlists:test` → PASS (all).

- [ ] **Step 4: Android service** — `PrefsTrackCache.kt`:

```kotlin
package com.debasish.livefit.playlists.android

import android.content.Context
import com.debasish.livefit.playlists.TrackCache

class PrefsTrackCache(context: Context) : TrackCache {
    private val prefs = context.applicationContext.getSharedPreferences("yt_track_cache", 0)
    override suspend fun get(key: String) = prefs.getString(key, null)
    override suspend fun put(key: String, videoId: String) { prefs.edit().putString(key, videoId).apply() }
}
```

`YouTubePlaylistService.kt`:

```kotlin
package com.debasish.livefit.playlists.android

import android.content.Context
import com.debasish.livefit.model.NowPlaying
import com.debasish.livefit.playlists.AccountState
import com.debasish.livefit.playlists.AddResult
import com.debasish.livefit.playlists.AddToPlaylistFlow
import com.debasish.livefit.playlists.Playlist
import com.debasish.livefit.playlists.PlaylistService
import com.debasish.livefit.playlists.TokenResult
import com.debasish.livefit.playlists.YouTubeApi
import com.debasish.livefit.playlists.YouTubeError
import com.debasish.livefit.services.ConfirmationService
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

class YouTubePlaylistService(context: Context, private val tokens: GoogleTokenProvider, confirm: ConfirmationService) : PlaylistService {
    private val prefs = context.applicationContext.getSharedPreferences("yt_account", 0)
    private val _account = MutableStateFlow<AccountState>(if (prefs.getBoolean("signedIn", false)) AccountState.SignedIn(prefs.getString("email", null)) else AccountState.SignedOut)
    override val account: StateFlow<AccountState> = _account
    @Volatile var consentIntent: Any? = null; private set

    private val api = YouTubeApi(onUnauthorized = { tokens.clear() }) { when (val t = tokens.token(interactive = false)) {
        is TokenResult.Token -> t.value
        is TokenResult.NeedsConsent -> { consentIntent = t.pendingIntent; _account.value = AccountState.NeedsConsent; throw YouTubeError.Auth() }
        is TokenResult.Failed -> throw YouTubeError.Network(uncertain = false) // authorize() itself failed (offline): not a sign-out
    } }

    /** Google rejected even a freshly authorized token (access revoked): the user must reconnect. */
    private suspend fun <T> authGuarded(block: suspend () -> T): T =
        try { block() } catch (e: YouTubeError.Auth) { _account.value = AccountState.NeedsConsent; throw e }
    private val flow = AddToPlaylistFlow(api, PrefsTrackCache(context), confirm)

    /** Called by Linked services after the consent PendingIntent returned OK. */
    suspend fun onSignedIn(email: String?) {
        tokens.clear()
        if (tokens.token(interactive = true) is TokenResult.Token) {
            prefs.edit().putBoolean("signedIn", true).putString("email", email).apply()
            _account.value = AccountState.SignedIn(email)
        }
    }

    suspend fun beginSignIn(): TokenResult = tokens.token(interactive = true).also { if (it is TokenResult.NeedsConsent) consentIntent = it.pendingIntent }

    fun signOut() { tokens.clear(); prefs.edit().clear().apply(); _account.value = AccountState.SignedOut }

    override suspend fun playlists(): List<Playlist> = runCatching { authGuarded { api.myPlaylists() } }.getOrDefault(emptyList())

    override suspend fun addCurrent(track: NowPlaying, playlistId: String, playlistTitle: String): AddResult = try {
        authGuarded { flow.add(track, playlistId, playlistTitle) }
    } catch (e: YouTubeError) { AddResult.Failed(e.message ?: "YouTube error") }
}
```

Token cache note: `tokens.clear()` drops LiveFit's in-memory copy. If the Identity library in use offers `AuthorizationClient.clearToken(ClearTokenRequest)`, call it from `GoogleTokenProvider.clear()` with the cached token, so Google's own cache can't return the rejected token again. Without it, the retry may get the same token; the second 401 then shows "Reconnect YouTube", which is still correct for a revoked grant.

Sign-out note: `AuthorizationClient.revokeAccess(...)` exists only in newer Identity releases; if available in the version used, call it in `signOut()`; otherwise show "Remove LiveFit's access in your Google account → Security → Third-party access" under the button (V2 spec §3.1).

- [ ] **Step 5: Build**

Run: `export JAVA_HOME=$(/usr/libexec/java_home -v 17) && ./gradlew :services:playlists:test :services:playlists-android:assembleDebug`
Expected: PASS / BUILD SUCCESSFUL.

- [ ] **Step 6: Commit**

```bash
git add services/playlists services/playlists-android
git commit -m "feat(playlists): YouTube Data API client with typed errors and verified insert"
```

---

### Task 8: "Add to playlist" end to end — protocol v2, parser, entry points, sign-in UI

**Files:**
- Modify: `core/model/.../Devices.kt` (`Command.AddToPlaylist`), `Protocol.kt` (`PROTOCOL_VERSION = 2`), `core/model/src/test/.../WireTest.kt` (version 2)
- Modify: `services/watch-link/src/test/.../WatchMessageCodecTest.kt` (mismatch uses version 99)
- Modify: `services/voice/src/main/kotlin/com/debasish/livefit/services/voice/CommandParser.kt` + `CommandParserTest.kt`
- Modify: `services/sync/src/main/kotlin/com/debasish/livefit/sync/HubCommandRouter.kt` + test (route `AddToPlaylist`)
- Modify: `phone/.../ServiceGraph.kt`, `SettingsStore.kt` (`defaultPlaylistId`, `defaultPlaylistTitle`), `ui/linked/LinkedMusicScreen.kt` (sign-in + default playlist), `ui/music/MusicScreen.kt` (button)
- Create: `phone/src/main/java/com/debasish/livefit/phone/ui/list/sources/PlaylistSource.kt`
- Modify: `watch/src/main/java/com/debasish/livefit/watch/ui/WatchApp.kt` (music page button)

**Interfaces:**
- Consumes: `YouTubePlaylistService`, `GoogleTokenProvider` (Tasks 5, 7), V1 router/confirmation/list screen.
- Produces: `Command.AddToPlaylist(val playlistId: String? = null)`; router callback `addToPlaylist: suspend (String?) -> Unit`; list source id `"playlists"`.

- [ ] **Step 1: Write the failing parser tests** — append to `CommandParserTest`:

```kotlin
    @Test fun addToPlaylist() = assertParses(
        Command.AddToPlaylist(),
        "add to playlist", "save this song", "add this song to my playlist", "add to workout playlist", "Save song.",
    )

    @Test fun addToLikedSongsIsStillLike() = assertParses(Command.LikeTrack, "add to liked songs")
```

Run `./gradlew :services:voice:test` → FAIL (`AddToPlaylist` unresolved).

- [ ] **Step 2: Protocol v2 and parser rule**
  - `Devices.kt`: `@Serializable data class AddToPlaylist(val playlistId: String? = null) : Command` inside `Command`.
  - `Protocol.kt`: `const val PROTOCOL_VERSION = 2`.
  - `WireTest.stateFrameRoundTripsWithVersion`: expect `"protocolVersion":2`; `versionOfReadsAnyMessage`: expect `2`; add `Command.AddToPlaylist("PL1")` to `commandEnvelopesRoundTrip`.
  - `WatchMessageCodecTest.versionMismatchIsReported`: build the delta with `protocolVersion = 99` and expect `Outdated(99)`.
  - `CommandParser.parse`: insert as the first rule in the `when`:

```kotlin
            has("playlist") || (has("save") && (music || has("this"))) -> Command.AddToPlaylist()
```

Run `./gradlew :core:model:test :services:voice:test :services:watch-link:testDebugUnitTest` → PASS.

- [ ] **Step 3: Router** — `HubCommandRouter` constructor gains `private val addToPlaylist: suspend (String?) -> Unit = {}`; in `apply`:

```kotlin
            is Command.AddToPlaylist -> scope.launch { addToPlaylist(command.playlistId) }
```

Add to `HubCommandRouterTest.routesEveryCommand` input `Command.AddToPlaylist("PL")` and expected `"add:PL"` (construct the router with `addToPlaylist = { calls += "add:$it" }`). Run `./gradlew :services:sync:test` → PASS.

- [ ] **Step 4: Bind in `ServiceGraph`**

```kotlin
    private val ytTokens = GoogleTokenProvider(app)
    val playlists: YouTubePlaylistService? = if (bindings.liveMusic) YouTubePlaylistService(app, ytTokens, confirm) else null
```
and pass to the router:
```kotlin
    val router = HubCommandRouter(workout, music, confirm, scope, toast = ::flash, addToPlaylist = ::addCurrentToPlaylist)

    /** Captures now-playing immediately (V2 spec §3.2 step 2); background callers never open UI. */
    private suspend fun addCurrentToPlaylist(requested: String?) {
        val pl = playlists ?: return flash("Playlists need YouTube sign-in")
        if (pl.account.value !is AccountState.SignedIn) return flash("Reconnect YouTube on your phone")
        val track = music.nowPlaying.value ?: return flash("Nothing playing")
        val id = requested ?: settings.defaultPlaylistId.value ?: return flash("Choose a default playlist in Settings")
        val title = settings.defaultPlaylistTitle.value ?: "playlist"
        when (val r = pl.addCurrent(track, id, title)) {
            is AddResult.Added -> flash("Added to ${r.playlistTitle}")
            AddResult.Declined -> flash("Not added")
            AddResult.NotFound -> flash("Couldn't find this song")
            is AddResult.Failed -> flash(r.reason)
        }
    }
```
(`playlists` must be declared before `router` in `ServiceGraph`; `flash` is the existing private toast helper.) `SettingsStore` gains `defaultPlaylistId` / `defaultPlaylistTitle` (`StateFlow<String?>`) with one setter `setDefaultPlaylist(id: String, title: String)`. Phone depends on `:services:playlists-android`.

- [ ] **Step 5: Linked services → YouTube Music sign-in** — in `LinkedMusicScreen` add an "Account" section:

```kotlin
    val pl = services.playlists
    val account by (pl?.account ?: MutableStateFlow(AccountState.SignedOut)).collectAsStateWithLifecycle()
    val consent = rememberLauncherForActivityResult(ActivityResultContracts.StartIntentSenderForResult()) { r ->
        if (r.resultCode == Activity.RESULT_OK) scope.launch { pl?.onSignedIn(email = null) }
    }
    fun launchConsent() = scope.launch {
        val t = pl?.beginSignIn()
        if (t is TokenResult.NeedsConsent) (t.pendingIntent as? android.app.PendingIntent)?.let { consent.launch(IntentSenderRequest.Builder(it.intentSender).build()) }
        else if (t is TokenResult.Token) pl.onSignedIn(null)
    }
```
Rows: "Google account" — `SignedOut` → "Sign in with Google" (`launchConsent()`); `SignedIn` → "Signed in" + "Sign out" (`pl.signOut()`); `NeedsConsent` → "Reconnect YouTube" (`launchConsent()`). "Default playlist" → navigates to the list screen with source `playlists`.

`PlaylistSource.kt`:

```kotlin
package com.debasish.livefit.phone.ui.list.sources

import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.QueueMusic
import com.debasish.livefit.phone.SettingsStore
import com.debasish.livefit.phone.ui.list.ActionResult
import com.debasish.livefit.phone.ui.list.ItemAction
import com.debasish.livefit.phone.ui.list.ItemStatus
import com.debasish.livefit.phone.ui.list.ListItem
import com.debasish.livefit.phone.ui.list.ListSource
import com.debasish.livefit.playlists.PlaylistService
import org.json.JSONObject

class PlaylistSource(private val service: PlaylistService?, private val settings: SettingsStore) : ListSource {
    override val title = "Default playlist"
    override val searchHint = "Search playlists"
    override suspend fun load(filter: JSONObject?) = (service?.playlists() ?: emptyList()).map {
        ListItem(it.id, it.title, icon = Icons.Rounded.QueueMusic, status = if (it.id == settings.defaultPlaylistId.value) ItemStatus.Done else ItemStatus.None)
    }
    override fun actionFor(item: ListItem) = ItemAction { settings.setDefaultPlaylist(item.id, item.title); ActionResult.Done }
}
```
Register `PLAYLISTS = "playlists"` in `ListSources.create`.

- [ ] **Step 6: Entry points**
  - Phone `MusicScreen`: an outlined "Add to playlist" button (`Icons.Rounded.PlaylistAdd`) → `services.localCommand(Command.AddToPlaylist())`.
  - Watch music page: a small `PlaylistAdd` icon next to the like heart → `onCommand(Command.AddToPlaylist())`.
  - Glasses: voice only ("add to playlist") — the confirmation overlay from V1 Task 21 already handles `AddToPlaylistMatch`.

- [ ] **Step 7: Build all and install together** (protocol 2 requires all three APKs)

Run: `export JAVA_HOME=$(/usr/libexec/java_home -v 17) && ./gradlew test :phone:testDebugUnitTest :phone:assembleDebug :watch:assembleDebug :glasses:assembleDebug && tools/install-all.sh`
Expected: all tests PASS, three OK lines.

- [ ] **Step 8: Commit**

```bash
git add core services phone watch
git commit -m "feat: add-to-playlist command on protocol v2 from phone, watch and voice"
```

---

### Task 9: V2 device verification and acceptance

**Files:**
- Create: `docs/superpowers/acceptance/2026-10-05-livefit-v2-acceptance.md`

- [ ] **Step 1: Write the checklist**

```markdown
# LiveFit V2 acceptance (V2 spec §1)

| # | Check | How | Pass |
|---|---|---|---|
| 1 | Real workout appears in Health Connect with matching numbers | 10-min walk; compare LiveFit summary with Health Connect → Data → Exercise / Heart rate / Steps | duration ±1 s; avg HR ±1; steps/distance/kcal equal |
| 2 | Demo data never reaches Health Connect | finish a session on the simulated gateway (debug build, LIVE_WATCH=false); Export past workouts | nothing written; toast counts it as skipped |
| 3 | Incomplete sessions never written | force an incomplete session (offline.sh, then `pm clear` the watch app before reconnect; wait for the "incomplete" toast or set incompleteAfterMs low in a debug build) | not in Health Connect |
| 4 | Retry queue | airplane mode is irrelevant to HC; instead revoke one HC permission, finish a workout, re-grant | status shows "1 waiting", then written within 15 min (or on "Status" tap) |
| 5 | Daily totals | open Activity | steps/active kcal/resting HR match Health Connect "today" |
| 6 | Add to playlist from phone, watch, voice | play a song; trigger from each | song in the default playlist once each; weak match asks first |
| 7 | Song change during confirmation | trigger on an obscure track, skip song before answering Yes | the original song is added |
| 8 | Consent survives > 7 days | revisit after 8 days | add works without a sign-in prompt |
| 9 | Revoked consent | remove access in Google account; voice "add to playlist" | toast "Reconnect YouTube on your phone"; Linked services shows Reconnect |
```

- [ ] **Step 2: Run the checks on devices** and fill in the Pass column with dates and numbers.

- [ ] **Step 3: Commit**

```bash
git add docs/superpowers/acceptance
git commit -m "docs: V2 acceptance checklist and results"
```
