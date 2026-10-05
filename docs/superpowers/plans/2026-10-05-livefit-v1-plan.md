# Rokid LiveFit V1 (Live core) Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Replace every Fake in the LiveFit mock-ups with Live services so a real workout on the Galaxy Watch shows on the Rokid glasses within 1 s and can be controlled from phone, watch and glasses, with lossless offline recovery and local history.

**Architecture:** The phone is the hub (single source of truth) running a foreground service that hosts a `ServiceGraph` of modular services. Pure-Kotlin modules (`:core:model`, `:core:services`, `:services:workout`, `:services:sync`, `:services:confirm`, `:services:voice` parser part) hold all protocol and state-machine logic and are unit-tested on the JVM; Android-specific Live implementations (Health Services, Data Layer, Rokid CXR-L, YouTube Music media session, on-device speech, Room) sit in Android library modules behind the `:core:services` interfaces. Watch and glasses are clients: they send session-scoped commands and render pushed `StateFrame`s; the watch additionally streams `SessionDelta`s that the phone stores durably before acknowledging.

**Tech Stack:** Kotlin 2.0.21, AGP 8.13.2, Gradle 8.13, JDK 17, kotlinx.coroutines 1.9.0, kotlinx.serialization-json 1.7.3, Jetpack Compose (BOM 2024.09.00), Wear Compose 1.4.0, Room 2.6.1, Robolectric 4.13, Health Services 1.1.0-alpha05, Play Services Wearable 19.0.0, Rokid CXR-L `com.rokid.cxr:client-l:1.1.2`, CXR-S `com.rokid.cxr:cxr-service-bridge:1.4`.

**Spec:** `docs/superpowers/specs/2026-10-05-livefit-v1-design.md` (read it alongside this plan). Context brief: `docs/superpowers/specs/REVIEW-BRIEF.md`.

## Global Constraints

- Build with JDK 17: prefix every Gradle command with `export JAVA_HOME=$(/usr/libexec/java_home -v 17) &&`.
- Gradle wrapper 8.13, AGP 8.13.2, Kotlin 2.0.21, compileSdk 36. Phone minSdk 29 / target 35, watch minSdk 30 / target 35, glasses minSdk 28 / target 32 (arm64-v8a only).
- Phone and watch share `applicationId = "com.debasish.livefit"` and the debug signing key; glasses `applicationId = "com.debasish.livefit.glasses"`.
- Rokid: CXR-L `com.rokid.cxr:client-l:1.1.2` (phone), CXR-S `com.rokid.cxr:cxr-service-bridge:1.4` (glasses), repo `https://maven.rokid.com/repository/maven-public/`. Never raw RFCOMM.
- `:core:model`, `:core:services`, `:services:workout`, `:services:sync`, `:services:confirm` and the parser part of `:services:voice` contain **no `android.*` imports**.
- Wire JSON: kotlinx.serialization, `classDiscriminator = "cmd"`, `ignoreUnknownKeys = true`, every top-level message carries `protocolVersion`; `PROTOCOL_VERSION = 1`. Coordinated upgrades: any mismatch → "Update LiveFit on your <device>", commands ignored.
- Glasses channels: `lf_state`, `lf_settings` (phone→glasses); `lf_cmd`, `lf_listen`, `lf_audio`, `lf_listen_end` (glasses→phone).
- Watch paths: `/lf/state`, `/lf/settings`, `/lf/cmd`, `/lf/delta`, `/lf/ack`, `/lf/claim`, `/lf/exercise_req`, `/lf/exercise_res`, `/lf/exercise_state`, `/lf/battery_req`, `/lf/battery`.
- Timing: coalesce 100 ms; heartbeat 5 s; liveness timeout 12 s; exercise result timeout 10 s; confirmation 15 s (silence = No); incomplete after 24 h; glasses reconnect back-off 2 s → 5 s → 10 s → 30 s; unacked delta resend every 5 s; VAD stop after 0.8 s silence or 6 s max; audio chunk 100 ms of 16 kHz mono PCM16; confirmation mic 6 s; watch battery report every 60 s; watch volume throttle ≤ 10 commands/s, bezel 5 % per detent; voice volume step ±10 %.
- HUD defaults: scale 0.4, position BottomCenter; settings apply to every LiveFit glasses screen.
- Voice: default locale `en-IN`; **no online speech recognition**; voice disabled until the locale's on-device pack is installed.
- Music workout-start default: Resume last played; stop pauses music; pause does not.
- Install phone APKs into the personal profile: `adb -s <phone> install -r --user 0 …`.
- Device serials (owner's setup): glasses `<glasses-serial>` (USB), watch `<watch-adb-serial>`, phone `<phone-adb-serial>` (wireless; ports change on reconnect — re-run `adb devices`).

## Review Focus

These five conditions are implied by the spec but no task's main tests target them; each line names the owning task where a pinning test is added.

1. **Two devices press Start within the same second** (watch tap + glasses voice) → exactly one session and one `ExerciseRequest`. Pinned in Task 6 (`simultaneousStartsCreateOneSession`).
2. **Watch and phone clocks differ by seconds** → elapsed/active time comes only from watch event timestamps, never mixed with the phone clock. Pinned in Task 5 (`activeTimeUsesEventTimestampsOnly`).
3. **Glasses screen sleeps or the wearer removes them mid-workout** (CXR session paused/closed) → workout keeps running; on wake the link reconnects, re-sends `HudSettingsFrame`, and does not open a second session. Pinned in Task 14 (`GlassesSessionPolicyTest`).
4. **Second touchpad tap while a recognition is still running** → ignored, no overlapping audio sessions. Pinned in Task 16 (`secondListenWhileBusyIsIgnored`).
5. **One-hour offline gap** (3 600 deltas buffered) → replay is acknowledged progressively and the session completes. Pinned in Task 7 (`longOfflineGapReplaysAndCompletes`).

---

## File structure (locked decomposition)

```
core/model/src/main/kotlin/com/debasish/livefit/model/
  Workout.kt          (modify) WorkoutPhase += Syncing; WorkoutSnapshot += sessionId; zoneLabel()
  Devices.kt          (modify) Command += SetVolume, Answer; NowPlaying += volume; DeviceKind, DeviceState, Devices
  Frames.kt           (create) StateFrame, HudSettingsFrame, CommandEnvelope, Confirmation, ConfirmationKind
  Session.kt          (create) SessionEvent, EndReason, Sample, Provenance, SessionDelta, DeltaAck, SessionClaim, SessionStatus, SessionSummary
  Exercise.kt         (create) ExerciseOp, ExerciseRequest, ExerciseError, ExerciseState, ExerciseResult, ExerciseStateReport
  Protocol.kt         (modify) PROTOCOL_VERSION, GlassesChannels, WatchPaths, Wire codec; legacy HudFrame kept until Task 26
core/services/src/main/kotlin/com/debasish/livefit/services/
  Services.kt         (modify) + Clock, WatchExerciseGateway, SessionStore, ConfirmationService, SpeechToText, HubNotices
services/workout/     SessionAssembler.kt (create), HubWorkoutService.kt (create), DefaultWorkoutService.kt (keep: demo)
services/sync/        (new pure module) StateBroadcaster.kt, LivenessMonitor.kt, Backoff.kt, CommandDeduper.kt, FileDeltaBuffer.kt, WatchSessionRecorder.kt
services/confirm/     (new pure module) DefaultConfirmationService.kt
services/voice/       CommandParser.kt (keep), YesNoParser.kt, LanguageRegistry.kt, EnergyVad.kt  (pure)
services/voice-android/ (new Android lib) AndroidOnDeviceStt.kt, LiveVoiceService.kt
services/history/     (new Android lib) Room: HistoryDatabase.kt, entities, dao, RoomSessionStore.kt
services/metrics/     FakeMetricsSource.kt (keep; pure)
services/watch-link/  DataLayerWatchLink.kt (rewrite: gateway + frames + battery), WatchMessageCodec.kt
services/glasses-link/ CxrGlassesLink.kt (rewrite), GlassesSessionPolicy.kt (pure policy), AuthActivity.kt
services/music/       FakeMusicService.kt (keep), YtmMediaSessionService.kt, WorkoutMusicPolicy.kt (pure)
phone/   LiveFitHubService.kt, CompanionPresenceService.kt, ServiceGraph.kt (rewrite), HubCommandRouter.kt, ui/... (screens), setup/...
watch/   WatchClient.kt (replaces PhoneHub), HealthServicesExercise.kt, ExerciseService.kt (rewrite), ui/WatchApp.kt (modify)
glasses/ hud/HudController.kt (rewrite), hud/HudScreen.kt (modify), voice/PushToTalk.kt, hud/ConfirmOverlay.kt
tools/   install-all.sh, device-tests/*.sh
```

Spec deviation, by design: the watch's offline "local copy" (spec §4.4 step 4) is implemented as a `SessionAssembler` over the watch's own buffered deltas instead of a separate `DefaultWorkoutService`, so phone and watch derive state with the same code.

---
## Task index

| Phase | Tasks |
|---|---|
| 0 — Pure foundations (JVM-tested) | 1 Module scaffold · 2 Protocol v1 · 3 Confirmations · 4 Yes/no + language registry · 5 SessionAssembler · 6 Hub start/ops · 7 Hub deltas/claims/completion · 8 Simulated watch gateway · 9 Sync utilities · 10 Watch delta buffer + recorder |
| 1 — Phone platform | 11 History (Room) · 12 ServiceGraph + hub service + router · 13 Watch link (phone side) · 14 Glasses link + companion presence · 15 YouTube Music · 16 Voice |
| 2 — Watch | 17 ExerciseService (Health Services, session-scoped) · 18 WatchClient + offline mode + watch UI |
| 3 — Glasses | 19 Protocol migration + liveness · 20 Push-to-talk · 21 Confirmation overlay |
| 4 — Phone UI | 22 Workout/confirm/volume on hub · 23 Activity history · 24 Settings & Linked services · 25 Setup wizard |
| 5 — Finish | 26 Cleanup, install script, device tests, acceptance |

---

## Phase 0 — Pure foundations

### Task 1: Module scaffold and test tooling

Registers the new modules so later tasks only add code. `:services:metrics` stays a pure Kotlin module (Fake source); the watch-side Health Services wrapper lives in the `:watch` app (Task 17).

**Files:**
- Modify: `settings.gradle.kts`
- Modify: `build.gradle.kts` (root)
- Create: `services/sync/build.gradle.kts`, `services/confirm/build.gradle.kts`, `services/history/build.gradle.kts`, `services/voice-android/build.gradle.kts`
- Create: `services/history/src/main/AndroidManifest.xml`, `services/voice-android/src/main/AndroidManifest.xml`
- Create: `services/sync/src/test/kotlin/com/debasish/livefit/sync/ScaffoldTest.kt`
- Create: `tools/install-all.sh`

**Interfaces:**
- Produces: Gradle modules `:services:sync`, `:services:confirm` (Kotlin JVM, depend on `:core:services`), `:services:history`, `:services:voice-android` (Android libs).

- [ ] **Step 1: Register modules** — replace the service `include(...)` block in `settings.gradle.kts`:

```kotlin
include(
    ":services:workout",
    ":services:metrics",
    ":services:glasses-link",
    ":services:watch-link",
    ":services:music",
    ":services:voice",
    ":services:voice-android",
    ":services:sync",
    ":services:confirm",
    ":services:history",
)
```

- [ ] **Step 2: Add the KSP plugin for Room** — root `build.gradle.kts` plugins block gains:

```kotlin
    id("com.google.devtools.ksp") version "2.0.21-1.0.27" apply false
```

- [ ] **Step 3: Pure module build files** — `services/sync/build.gradle.kts` and `services/confirm/build.gradle.kts` (identical):

```kotlin
plugins {
    id("org.jetbrains.kotlin.jvm")
}

kotlin { jvmToolchain(17) }

dependencies {
    api(project(":core:services"))
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-core:1.9.0")
    testImplementation(kotlin("test"))
    testImplementation("org.jetbrains.kotlinx:kotlinx-coroutines-test:1.9.0")
}

tasks.test { useJUnitPlatform() }
```

- [ ] **Step 4: Android library build files** — `services/history/build.gradle.kts`:

```kotlin
plugins {
    id("com.android.library")
    id("org.jetbrains.kotlin.android")
    id("com.google.devtools.ksp")
}

android {
    namespace = "com.debasish.livefit.services.history"
    compileSdk = 36
    defaultConfig { minSdk = 29 }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions { jvmTarget = "17" }
    sourceSets["main"].java.srcDirs("src/main/kotlin")
    sourceSets["test"].java.srcDirs("src/test/kotlin")
    testOptions { unitTests.isIncludeAndroidResources = true }
}

dependencies {
    api(project(":core:services"))
    implementation("androidx.room:room-runtime:2.6.1")
    implementation("androidx.room:room-ktx:2.6.1")
    ksp("androidx.room:room-compiler:2.6.1")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.9.0")
    testImplementation("junit:junit:4.13.2")
    testImplementation("org.robolectric:robolectric:4.13")
    testImplementation("androidx.test:core:1.6.1")
    testImplementation("org.jetbrains.kotlinx:kotlinx-coroutines-test:1.9.0")
}
```

`services/voice-android/build.gradle.kts`:

```kotlin
plugins {
    id("com.android.library")
    id("org.jetbrains.kotlin.android")
}

android {
    namespace = "com.debasish.livefit.services.voice.android"
    compileSdk = 36
    defaultConfig { minSdk = 29 }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions { jvmTarget = "17" }
    sourceSets["main"].java.srcDirs("src/main/kotlin")
    sourceSets["test"].java.srcDirs("src/test/kotlin")
}

dependencies {
    api(project(":services:voice"))
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.9.0")
    testImplementation(kotlin("test"))
    testImplementation("org.jetbrains.kotlinx:kotlinx-coroutines-test:1.9.0")
}
```

- [ ] **Step 5: Empty manifests** — each of the two new `AndroidManifest.xml` files:

```xml
<?xml version="1.0" encoding="utf-8"?>
<manifest xmlns:android="http://schemas.android.com/apk/res/android" />
```

- [ ] **Step 6: Smoke test** — `services/sync/src/test/kotlin/com/debasish/livefit/sync/ScaffoldTest.kt`:

```kotlin
package com.debasish.livefit.sync

import kotlin.test.Test
import kotlin.test.assertTrue

class ScaffoldTest {
    @Test fun moduleBuilds() = assertTrue(true)
}
```

Run: `export JAVA_HOME=$(/usr/libexec/java_home -v 17) && ./gradlew :services:sync:test :services:confirm:build :services:history:assembleDebug :services:voice-android:assembleDebug`
Expected: BUILD SUCCESSFUL.

- [ ] **Step 7: Install script** — `tools/install-all.sh` (coordinated upgrades: always install all three):

```bash
#!/usr/bin/env bash
# Builds and installs phone, watch and glasses APKs from the same commit (protocol versions must match).
set -euo pipefail
cd "$(dirname "$0")/.."
export JAVA_HOME=$(/usr/libexec/java_home -v 17)
./gradlew :phone:assembleDebug :watch:assembleDebug :glasses:assembleDebug -q

PHONE=${PHONE:-$(adb devices -l | awk '/model:SM_S93/{print $1; exit}')}
WATCH=${WATCH:-$(adb devices -l | awk '/model:SM_R9/{print $1; exit}')}
GLASSES=${GLASSES:-$(adb devices -l | awk '/model:RG_glasses/{print $1; exit}')}

for pair in "phone:$PHONE" "watch:$WATCH" "glasses:$GLASSES"; do
  name=${pair%%:*}; serial=${pair#*:}
  if [ -z "$serial" ]; then echo "SKIP $name: not connected"; continue; fi
  extra=""; [ "$name" = "phone" ] && extra="--user 0"
  adb -s "$serial" install -r $extra "$name/build/outputs/apk/debug/$name-debug.apk" >/dev/null && echo "OK   $name ($serial)"
done
```

Run: `chmod +x tools/install-all.sh && bash -n tools/install-all.sh`
Expected: no output (syntax OK).

- [ ] **Step 8: Commit**

```bash
git add settings.gradle.kts build.gradle.kts services/sync services/confirm services/history services/voice-android tools/install-all.sh
git commit -m "build: scaffold sync, confirm, history, voice-android modules and install script"
```

---

### Task 2: Protocol v1 types and wire codec

**Files:**
- Modify: `core/model/src/main/kotlin/com/debasish/livefit/model/Devices.kt` (full replacement below)
- Modify: `core/model/src/main/kotlin/com/debasish/livefit/model/Workout.kt`
- Create: `core/model/src/main/kotlin/com/debasish/livefit/model/Frames.kt`
- Create: `core/model/src/main/kotlin/com/debasish/livefit/model/Session.kt`
- Create: `core/model/src/main/kotlin/com/debasish/livefit/model/Exercise.kt`
- Modify: `core/model/src/main/kotlin/com/debasish/livefit/model/Protocol.kt`
- Test: `core/model/src/test/kotlin/com/debasish/livefit/model/WireTest.kt`

**Interfaces:**
- Produces (used by every later task):
  - `const val PROTOCOL_VERSION = 1`
  - `object Wire { val json: Json; inline fun <reified T> encode(value: T): String; inline fun <reified T> decode(text: String): T; fun versionOf(text: String): Int? }`
  - `object GlassesChannels { STATE, SETTINGS, COMMAND, LISTEN, AUDIO, LISTEN_END }`, `object WatchPaths { STATE, SETTINGS, COMMAND, DELTA, ACK, CLAIM, EXERCISE_REQ, EXERCISE_RES, EXERCISE_STATE, BATTERY_REQ, BATTERY }`
  - `StateFrame`, `HudSettingsFrame`, `CommandEnvelope(id, origin: DeviceKind, command)`, `Confirmation`, `ConfirmationKind`, `Devices`, `DeviceState`, `DeviceKind`
  - `SessionEvent` (`Started(tMs,type)`, `Paused(tMs)`, `Resumed(tMs)`, `TypeDetected(tMs,type)`, `Stopped(tMs,reason)`), `EndReason`, `Sample`, `Provenance` (`Live(sourceId)`, `Fake`), `SessionDelta`, `DeltaAck`, `SessionClaim`, `SessionStatus`, `SessionSummary`
  - `ExerciseOp` (`Start(type, force, gps)`, `Pause`, `Resume`, `Stop`), `ExerciseRequest`, `ExerciseError`, `ExerciseState`, `ExerciseResult`, `ExerciseStateReport`
  - `Command.SetVolume(level: Float)`, `Command.Answer(confirmationId: String, yes: Boolean)`, `NowPlaying.volume`, `WorkoutPhase.Syncing`, `WorkoutSnapshot.sessionId`, `fun zoneLabel(zone: Int?): String`

- [ ] **Step 1: Write the failing test** — `core/model/src/test/kotlin/com/debasish/livefit/model/WireTest.kt`:

```kotlin
package com.debasish.livefit.model

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class WireTest {
    private inline fun <reified T> roundTrip(value: T) = assertEquals(value, Wire.decode<T>(Wire.encode(value)))

    @Test fun stateFrameRoundTripsWithVersion() {
        val frame = StateFrame(
            workout = WorkoutSnapshot(phase = WorkoutPhase.Syncing, type = WorkoutType.Run, sessionId = "s1", elapsedMs = 61_000),
            music = NowPlaying("Song", "Artist", isPlaying = true, volume = 0.4f),
            devices = Devices(watch = DeviceState(LinkState.Connected, 81), glasses = DeviceState(LinkState.Connecting, 100)),
            confirmation = Confirmation("c1", ConfirmationKind.TakeOverWorkout, "Take over?", "Samsung Health is tracking", expiresAtMs = 99),
            toast = "Next song", sentAtMs = 5,
        )
        roundTrip(frame)
        assertTrue(Wire.encode(frame).contains("\"protocolVersion\":1"), "version must be encoded even though it is a default")
    }

    @Test fun commandEnvelopesRoundTrip() {
        val commands = listOf(
            Command.StartWorkout(WorkoutType.Cycle), Command.SetVolume(0.75f), Command.Answer("c1", yes = true),
            Command.PauseWorkout, Command.Volume(up = false),
        )
        for (c in commands) roundTrip(CommandEnvelope(id = "id-$c", origin = DeviceKind.Watch, command = c))
    }

    @Test fun sessionMessagesRoundTrip() {
        roundTrip(
            SessionDelta(
                sessionId = "s1", seq = 7,
                events = listOf(SessionEvent.Started(1, WorkoutType.Walk), SessionEvent.Paused(2), SessionEvent.Resumed(3),
                    SessionEvent.TypeDetected(4, WorkoutType.Run), SessionEvent.Stopped(5, EndReason.OtherApp)),
                samples = listOf(Sample(tMs = 1, hr = 120, stepsTotal = 10, distanceKmTotal = 0.01, kcalTotal = 1.5, speedKmh = 5.0)),
                provenance = Provenance.Live("galaxy-watch/health-services"), final = true,
            ),
        )
        roundTrip(DeltaAck(sessionId = "s1", seq = 7))
        roundTrip(SessionClaim(sessionId = "s1", type = WorkoutType.Walk, startMs = 1, phase = WorkoutPhase.Paused, activeMs = 60_000, lastSeq = 42))
    }

    @Test fun exerciseMessagesRoundTrip() {
        for (op in listOf(ExerciseOp.Start(WorkoutType.Run, force = true, gps = true), ExerciseOp.Pause, ExerciseOp.Resume, ExerciseOp.Stop)) {
            roundTrip(ExerciseRequest(requestId = "r", sessionId = "s", op = op))
        }
        val errors = listOf(
            ExerciseError.PermissionMissing(listOf("android.permission.health.READ_HEART_RATE")),
            ExerciseError.OtherAppTracking("RUNNING_TREADMILL"), ExerciseError.SensorUnavailable,
            ExerciseError.WrongSession("other"), ExerciseError.NoSuchSession, ExerciseError.Internal("boom"),
        )
        for (e in errors) roundTrip(ExerciseResult(requestId = "r", sessionId = "s", ok = false, error = e, state = ExerciseState.Idle, activeSessionId = "x"))
        roundTrip(ExerciseStateReport(sessionId = "s", state = ExerciseState.Ended, endedBy = EndReason.OtherApp))
    }

    @Test fun hudSettingsFrameRoundTrips() =
        roundTrip(HudSettingsFrame(settings = HudSettings(scale = 0.5f, position = HudPosition.TopRight, items = setOf(HudItem.HeartRate))))

    @Test fun versionOfReadsAnyMessage() {
        assertEquals(1, Wire.versionOf(Wire.encode(DeltaAck(sessionId = "s", seq = 1))))
        assertEquals(null, Wire.versionOf("not json"))
        assertEquals(null, Wire.versionOf("{\"seq\":1}"))
    }

    @Test fun zoneLabelUsesDashBelowZoneOne() {
        assertEquals("–", zoneLabel(null))
        assertEquals("–", zoneLabel(0))
        assertEquals("Z3", zoneLabel(3))
    }
}
```

- [ ] **Step 2: Run test to verify it fails**

Run: `export JAVA_HOME=$(/usr/libexec/java_home -v 17) && ./gradlew :core:model:test`
Expected: FAIL — compilation errors (`StateFrame`, `Wire`, `zoneLabel` … unresolved).

- [ ] **Step 3: Replace `Devices.kt`**

```kotlin
package com.debasish.livefit.model

import kotlinx.serialization.Serializable

@Serializable
enum class LinkState { Disconnected, Connecting, Connected }

@Serializable
data class DeviceStatus(
    val name: String,
    val link: LinkState = LinkState.Disconnected,
    val batteryPct: Int? = null,
    val detail: String? = null,
)

@Serializable
data class NowPlaying(
    val title: String,
    val artist: String,
    val isPlaying: Boolean,
    val liked: Boolean = false,
    val positionMs: Long = 0,
    val durationMs: Long = 0,
    /** Phone media volume 0..1 (shown on the watch volume arc). */
    val volume: Float = 0.5f,
)

@Serializable
enum class VoiceState { Idle, Listening, Processing }

@Serializable
enum class DeviceKind { Phone, Watch, Glasses }

@Serializable
data class DeviceState(val link: LinkState = LinkState.Disconnected, val batteryPct: Int? = null)

@Serializable
data class Devices(
    val phone: DeviceState = DeviceState(LinkState.Connected),
    val watch: DeviceState = DeviceState(),
    val glasses: DeviceState = DeviceState(),
)

/** Commands any input (voice, phone UI, watch, glasses touchpad) can issue. Always sent inside a [CommandEnvelope]. */
@Serializable
sealed interface Command {
    @Serializable data class StartWorkout(val type: WorkoutType) : Command
    @Serializable data object PauseWorkout : Command
    @Serializable data object ResumeWorkout : Command
    @Serializable data object StopWorkout : Command
    @Serializable data object DismissSummary : Command
    /** Touch toggle (phone/watch button). Voice uses the explicit [PlayMusic] / [PauseMusic]. */
    @Serializable data object PlayPause : Command
    @Serializable data object PlayMusic : Command
    @Serializable data object PauseMusic : Command
    @Serializable data object NextTrack : Command
    @Serializable data object PreviousTrack : Command
    @Serializable data object LikeTrack : Command
    /** Voice: ±10 %. */
    @Serializable data class Volume(val up: Boolean) : Command
    /** Watch arc / bezel and phone slider: absolute 0..1. */
    @Serializable data class SetVolume(val level: Float) : Command
    @Serializable data class Answer(val confirmationId: String, val yes: Boolean) : Command
}
```

- [ ] **Step 4: Update `Workout.kt`** — change the enum, the snapshot, and add `zoneLabel`:

```kotlin
@Serializable
enum class WorkoutPhase { Idle, Starting, Active, Paused, Syncing, Stopping, Summary }
```

In `data class WorkoutSnapshot(` add as the first parameter:

```kotlin
    /** Set once the hub has created (or adopted) the session. */
    val sessionId: String? = null,
```

Append at the end of the file:

```kotlin
/** "Z1".."Z5"; "–" below zone 1 or when unknown (approved HUD wording). */
fun zoneLabel(zone: Int?): String = if (zone == null || zone < 1) "–" else "Z$zone"
```

- [ ] **Step 5: Create `Frames.kt`**

```kotlin
package com.debasish.livefit.model

import kotlinx.serialization.Serializable

@Serializable
enum class ConfirmationKind { TakeOverWorkout, StopWorkoutByVoice }

/** Shown on all three devices at once; the first Answer from any device wins (spec §4.6). */
@Serializable
data class Confirmation(
    val id: String,
    val kind: ConfirmationKind,
    val title: String,
    val message: String,
    val yesLabel: String = "Yes",
    val noLabel: String = "No",
    /** Which choice the glasses highlight first. */
    val defaultYes: Boolean = true,
    val expiresAtMs: Long,
)

/** Phone → watch and glasses on every change (coalesced 100 ms) and every 5 s heartbeat. */
@Serializable
data class StateFrame(
    val protocolVersion: Int = PROTOCOL_VERSION,
    val workout: WorkoutSnapshot,
    val music: NowPlaying? = null,
    val devices: Devices = Devices(),
    val voice: VoiceState = VoiceState.Idle,
    val confirmation: Confirmation? = null,
    val toast: String? = null,
    /** Set when the hub detected a protocol mismatch with this device. */
    val outdated: DeviceKind? = null,
    val sentAtMs: Long = 0,
)

/** Phone → glasses on change and on every (re)connect. */
@Serializable
data class HudSettingsFrame(val protocolVersion: Int = PROTOCOL_VERSION, val settings: HudSettings)

/** Every command travels with a unique id so resends are applied once (spec §4.5). */
@Serializable
data class CommandEnvelope(
    val protocolVersion: Int = PROTOCOL_VERSION,
    val id: String,
    val origin: DeviceKind,
    val command: Command,
)
```

- [ ] **Step 6: Create `Session.kt`**

```kotlin
package com.debasish.livefit.model

import kotlinx.serialization.Serializable

@Serializable
enum class EndReason { User, OtherApp, System, Error }

/** Workout events recorded by the watch; they make pause intervals and active time reconstructable. */
@Serializable
sealed interface SessionEvent {
    val tMs: Long

    @Serializable data class Started(override val tMs: Long, val type: WorkoutType) : SessionEvent
    @Serializable data class Paused(override val tMs: Long) : SessionEvent
    @Serializable data class Resumed(override val tMs: Long) : SessionEvent
    @Serializable data class TypeDetected(override val tMs: Long, val type: WorkoutType) : SessionEvent
    @Serializable data class Stopped(override val tMs: Long, val reason: EndReason) : SessionEvent
}

/** One sensor reading; totals are cumulative since session start. Timestamps use the watch clock. */
@Serializable
data class Sample(
    val tMs: Long,
    val hr: Int? = null,
    val stepsTotal: Int = 0,
    val distanceKmTotal: Double = 0.0,
    val kcalTotal: Double = 0.0,
    val speedKmh: Double? = null,
)

@Serializable
sealed interface Provenance {
    @Serializable data class Live(val sourceId: String) : Provenance
    @Serializable data object Fake : Provenance
}

/** Watch → phone. seq starts at 0 and increases by 1 per delta within a session. */
@Serializable
data class SessionDelta(
    val protocolVersion: Int = PROTOCOL_VERSION,
    val sessionId: String,
    val seq: Long,
    val events: List<SessionEvent> = emptyList(),
    val samples: List<Sample> = emptyList(),
    val provenance: Provenance,
    val final: Boolean = false,
)

/** Phone → watch: highest contiguous seq durably stored on the phone. */
@Serializable
data class DeltaAck(val protocolVersion: Int = PROTOCOL_VERSION, val sessionId: String, val seq: Long)

/** Watch → phone on reconnect when it holds a session. */
@Serializable
data class SessionClaim(
    val protocolVersion: Int = PROTOCOL_VERSION,
    val sessionId: String,
    val type: WorkoutType,
    val startMs: Long,
    val phase: WorkoutPhase,
    val activeMs: Long,
    val lastSeq: Long,
)

@Serializable
enum class SessionStatus { Active, Stopping, Complete, Incomplete }

/** What history stores per session (spec §5.6). */
@Serializable
data class SessionSummary(
    val id: String,
    val type: WorkoutType,
    val detectedType: WorkoutType? = null,
    val startMs: Long,
    val endMs: Long? = null,
    val activeMs: Long,
    val avgHr: Int? = null,
    val maxHr: Int? = null,
    val steps: Int = 0,
    val distanceKm: Double = 0.0,
    val kcal: Int = 0,
    val provenance: Provenance,
    val status: SessionStatus,
    val endReason: EndReason? = null,
)
```

- [ ] **Step 7: Create `Exercise.kt`**

```kotlin
package com.debasish.livefit.model

import kotlinx.serialization.Serializable

/** Hub → watch exercise control; every op names its session (spec §4.8). */
@Serializable
sealed interface ExerciseOp {
    /** [gps] comes from the phone setting "Use GPS outdoors" (spec §5.1). */
    @Serializable data class Start(val type: WorkoutType, val force: Boolean = false, val gps: Boolean = false) : ExerciseOp
    @Serializable data object Pause : ExerciseOp
    @Serializable data object Resume : ExerciseOp
    @Serializable data object Stop : ExerciseOp
}

@Serializable
data class ExerciseRequest(
    val protocolVersion: Int = PROTOCOL_VERSION,
    val requestId: String,
    val sessionId: String,
    val op: ExerciseOp,
)

@Serializable
sealed interface ExerciseError {
    @Serializable data class PermissionMissing(val permissions: List<String>) : ExerciseError
    @Serializable data class OtherAppTracking(val appExerciseType: String) : ExerciseError
    @Serializable data object SensorUnavailable : ExerciseError
    @Serializable data class WrongSession(val activeSessionId: String?) : ExerciseError
    @Serializable data object NoSuchSession : ExerciseError
    @Serializable data class Internal(val message: String) : ExerciseError
}

@Serializable
enum class ExerciseState { Idle, Preparing, Active, Paused, Ended }

@Serializable
data class ExerciseResult(
    val protocolVersion: Int = PROTOCOL_VERSION,
    val requestId: String,
    val sessionId: String,
    val ok: Boolean,
    val error: ExerciseError? = null,
    val state: ExerciseState,
    val activeSessionId: String? = null,
)

/** Unsolicited: the real Health Services state changed (e.g. another app ended our workout). */
@Serializable
data class ExerciseStateReport(
    val protocolVersion: Int = PROTOCOL_VERSION,
    val sessionId: String,
    val state: ExerciseState,
    val endedBy: EndReason? = null,
)
```

- [ ] **Step 8: Add the codec to `Protocol.kt`** — append below the existing (legacy) `Protocol` object; keep `HudFrame` and `Protocol` until Task 26:

```kotlin
const val PROTOCOL_VERSION = 1

/** CXR custom-command names (spec §4.2). */
object GlassesChannels {
    const val STATE = "lf_state"
    const val SETTINGS = "lf_settings"
    const val COMMAND = "lf_cmd"
    const val LISTEN = "lf_listen"
    const val AUDIO = "lf_audio"
    const val LISTEN_END = "lf_listen_end"
}

/** Wearable Data Layer message paths (spec §4.2). */
object WatchPaths {
    const val STATE = "/lf/state"
    const val SETTINGS = "/lf/settings"
    const val COMMAND = "/lf/cmd"
    const val DELTA = "/lf/delta"
    const val ACK = "/lf/ack"
    const val CLAIM = "/lf/claim"
    const val EXERCISE_REQ = "/lf/exercise_req"
    const val EXERCISE_RES = "/lf/exercise_res"
    const val EXERCISE_STATE = "/lf/exercise_state"
    const val BATTERY_REQ = "/lf/battery_req"
    const val BATTERY = "/lf/battery"
}

/**
 * V1 wire codec. Defaults ARE encoded so protocolVersion always travels; discriminator is "cmd"
 * because several messages have their own `type` property.
 */
object Wire {
    val json = Json { ignoreUnknownKeys = true; encodeDefaults = true; classDiscriminator = "cmd" }

    inline fun <reified T> encode(value: T): String = json.encodeToString(value)
    inline fun <reified T> decode(text: String): T = json.decodeFromString(text)

    fun versionOf(text: String): Int? = runCatching {
        json.parseToJsonElement(text).jsonObject["protocolVersion"]?.jsonPrimitive?.int
    }.getOrNull()
}
```

Add imports at the top of `Protocol.kt`:

```kotlin
import kotlinx.serialization.encodeToString
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
```

- [ ] **Step 9: Run tests to verify they pass**

Run: `export JAVA_HOME=$(/usr/libexec/java_home -v 17) && ./gradlew :core:model:test`
Expected: PASS (WireTest 7 tests + existing ProtocolTest 4).

- [ ] **Step 10: Check the apps still compile** (the new enum value and `Command` cases must not break exhaustive `when`s)

Run: `export JAVA_HOME=$(/usr/libexec/java_home -v 17) && ./gradlew :phone:assembleDebug :watch:assembleDebug :glasses:assembleDebug -q`
Expected: BUILD SUCCESSFUL. If `ServiceGraph.dispatch`/`describe` fail as non-exhaustive, add:

```kotlin
            is Command.SetVolume -> music.setVolume(command.level)
            is Command.Answer -> Unit // wired in Task 12
```
and in `describe`:
```kotlin
            is Command.SetVolume -> "Volume ${(command.level * 100).toInt()}%"
            is Command.Answer -> if (command.yes) "Confirmed" else "Cancelled"
```

- [ ] **Step 11: Commit**

```bash
git add core/model phone/src/main/java/com/debasish/livefit/phone/ServiceGraph.kt
git commit -m "feat(model): protocol v1 frames, session deltas, exercise control and Wire codec"
```

---

### Task 3: Confirmation service

**Files:**
- Modify: `core/services/src/main/kotlin/com/debasish/livefit/services/Services.kt` (append)
- Create: `services/confirm/src/main/kotlin/com/debasish/livefit/confirm/DefaultConfirmationService.kt`
- Test: `services/confirm/src/test/kotlin/com/debasish/livefit/confirm/DefaultConfirmationServiceTest.kt`

**Interfaces:**
- Produces:
  - `fun interface Clock { fun nowMs(): Long }` (in `com.debasish.livefit.services`)
  - `enum class ConfirmationOutcome { Yes, No, Timeout, Superseded }`
  - `interface ConfirmationService { val pending: StateFlow<Confirmation?>; suspend fun ask(kind: ConfirmationKind, title: String, message: String, defaultYes: Boolean = true): ConfirmationOutcome; fun answer(confirmationId: String, yes: Boolean): Boolean }`
  - `class DefaultConfirmationService(clock: Clock, newId: () -> String = { UUID.randomUUID().toString() }, timeoutMs: Long = 15_000) : ConfirmationService`

- [ ] **Step 1: Append the contracts to `Services.kt`**

```kotlin
/** Injected time source so state machines are testable with virtual time. */
fun interface Clock { fun nowMs(): Long }

enum class ConfirmationOutcome { Yes, No, Timeout, Superseded }

/** At most one pending confirmation; first answer from any device wins; silence = No after 15 s. */
interface ConfirmationService {
    val pending: StateFlow<com.debasish.livefit.model.Confirmation?>
    suspend fun ask(kind: com.debasish.livefit.model.ConfirmationKind, title: String, message: String, defaultYes: Boolean = true): ConfirmationOutcome
    /** Returns true only for the first answer to the currently pending confirmation. */
    fun answer(confirmationId: String, yes: Boolean): Boolean
}
```

- [ ] **Step 2: Write the failing test** — `DefaultConfirmationServiceTest.kt`:

```kotlin
package com.debasish.livefit.confirm

import com.debasish.livefit.model.ConfirmationKind
import com.debasish.livefit.services.ConfirmationOutcome
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.async
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
class DefaultConfirmationServiceTest {
    private var n = 0
    private fun TestScope.service() = DefaultConfirmationService(clock = { testScheduler.currentTime }, newId = { "c${n++}" })

    @Test fun firstAnswerWinsAndSecondIsIgnored() = runTest {
        val svc = service()
        val outcome = async { svc.ask(ConfirmationKind.TakeOverWorkout, "Take over?", "SH is tracking") }
        runCurrent()
        val id = svc.pending.value!!.id
        assertTrue(svc.answer(id, yes = true))      // e.g. glasses
        assertFalse(svc.answer(id, yes = false))    // watch, a moment later
        assertEquals(ConfirmationOutcome.Yes, outcome.await())
        assertNull(svc.pending.value)
    }

    @Test fun answerForAnotherIdIsIgnored() = runTest {
        val svc = service()
        val outcome = async { svc.ask(ConfirmationKind.TakeOverWorkout, "t", "m") }
        runCurrent()
        assertFalse(svc.answer("nope", yes = true))
        svc.answer(svc.pending.value!!.id, yes = false)
        assertEquals(ConfirmationOutcome.No, outcome.await())
    }

    @Test fun silenceTimesOutAfter15Seconds() = runTest {
        val svc = service()
        val outcome = async { svc.ask(ConfirmationKind.StopWorkoutByVoice, "End workout?", "") }
        runCurrent()
        assertEquals(15_000, svc.pending.value!!.expiresAtMs)
        advanceTimeBy(14_999); runCurrent()
        assertTrue(svc.pending.value != null)
        advanceTimeBy(2); runCurrent()
        assertEquals(ConfirmationOutcome.Timeout, outcome.await())
        assertNull(svc.pending.value)
    }

    @Test fun newAskSupersedesPendingOne() = runTest {
        val svc = service()
        val first = async { svc.ask(ConfirmationKind.TakeOverWorkout, "a", "") }
        runCurrent()
        val second = async { svc.ask(ConfirmationKind.StopWorkoutByVoice, "b", "") }
        runCurrent()
        assertEquals(ConfirmationOutcome.Superseded, first.await())
        assertEquals("b", svc.pending.value!!.title)
        svc.answer(svc.pending.value!!.id, yes = true)
        assertEquals(ConfirmationOutcome.Yes, second.await())
    }
}
```

- [ ] **Step 3: Run test to verify it fails**

Run: `export JAVA_HOME=$(/usr/libexec/java_home -v 17) && ./gradlew :services:confirm:test`
Expected: FAIL — `DefaultConfirmationService` unresolved.

- [ ] **Step 4: Implement** — `DefaultConfirmationService.kt`:

```kotlin
package com.debasish.livefit.confirm

import com.debasish.livefit.model.Confirmation
import com.debasish.livefit.model.ConfirmationKind
import com.debasish.livefit.services.Clock
import com.debasish.livefit.services.ConfirmationOutcome
import com.debasish.livefit.services.ConfirmationService
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.withTimeoutOrNull
import java.util.UUID

class DefaultConfirmationService(
    private val clock: Clock,
    private val newId: () -> String = { UUID.randomUUID().toString() },
    private val timeoutMs: Long = 15_000,
) : ConfirmationService {

    private val _pending = MutableStateFlow<Confirmation?>(null)
    override val pending: StateFlow<Confirmation?> = _pending
    private var waiter: CompletableDeferred<ConfirmationOutcome>? = null

    override suspend fun ask(kind: ConfirmationKind, title: String, message: String, defaultYes: Boolean): ConfirmationOutcome {
        waiter?.complete(ConfirmationOutcome.Superseded)
        val id = newId()
        val deferred = CompletableDeferred<ConfirmationOutcome>()
        waiter = deferred
        _pending.value = Confirmation(id, kind, title, message, defaultYes = defaultYes, expiresAtMs = clock.nowMs() + timeoutMs)
        val outcome = withTimeoutOrNull(timeoutMs) { deferred.await() } ?: ConfirmationOutcome.Timeout
        if (_pending.value?.id == id) _pending.value = null
        if (waiter === deferred) waiter = null
        return outcome
    }

    override fun answer(confirmationId: String, yes: Boolean): Boolean {
        val current = _pending.value ?: return false
        if (current.id != confirmationId) return false
        _pending.value = null
        return waiter?.complete(if (yes) ConfirmationOutcome.Yes else ConfirmationOutcome.No) ?: false
    }
}
```

- [ ] **Step 5: Run tests to verify they pass**

Run: `export JAVA_HOME=$(/usr/libexec/java_home -v 17) && ./gradlew :services:confirm:test`
Expected: PASS (4 tests).

- [ ] **Step 6: Commit**

```bash
git add core/services services/confirm
git commit -m "feat(confirm): cross-device confirmation service with first-answer-wins and timeout"
```

---

### Task 4: Yes/no parser and language registry

**Files:**
- Create: `services/voice/src/main/kotlin/com/debasish/livefit/services/voice/YesNoParser.kt`
- Create: `services/voice/src/main/kotlin/com/debasish/livefit/services/voice/LanguageRegistry.kt`
- Test: `services/voice/src/test/kotlin/com/debasish/livefit/services/voice/YesNoParserTest.kt`
- Test: `services/voice/src/test/kotlin/com/debasish/livefit/services/voice/LanguageRegistryTest.kt`

**Interfaces:**
- Consumes: `CommandParser.parse(utterance: String): Command?` (existing)
- Produces:
  - `object YesNoParser { fun parse(utterance: String): Boolean? }`
  - `data class LanguagePack(val locale: String, val displayName: String, val parseCommand: (String) -> Command?, val parseYesNo: (String) -> Boolean?)`
  - `object LanguageRegistry { const val DEFAULT_LOCALE = "en-IN"; val packs: Map<String, LanguagePack>; fun forLocale(locale: String): LanguagePack? }`

- [ ] **Step 1: Write the failing tests** — `YesNoParserTest.kt`:

```kotlin
package com.debasish.livefit.services.voice

import kotlin.test.Test
import kotlin.test.assertEquals

class YesNoParserTest {
    private fun assertAll(expected: Boolean?, vararg utterances: String) {
        for (u in utterances) assertEquals(expected, YesNoParser.parse(u), "utterance: \"$u\"")
    }

    @Test fun yes() = assertAll(true, "yes", "Yes.", "yeah", "yep", "ok", "okay", "sure", "confirm", "take over", "do it", "yes please", "go ahead")
    @Test fun no() = assertAll(false, "no", "No!", "nope", "cancel", "stop", "don't", "dont", "leave it", "not now", "no thanks")
    @Test fun negationWins() = assertAll(false, "don't take over", "no, don't do it", "ok no")
    @Test fun unclear() = assertAll(null, "", "maybe", "what", "next song")
}
```

`LanguageRegistryTest.kt`:

```kotlin
package com.debasish.livefit.services.voice

import com.debasish.livefit.model.Command
import com.debasish.livefit.model.WorkoutType
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull

class LanguageRegistryTest {
    @Test fun defaultLocaleIsEnglishIndia() {
        assertEquals("en-IN", LanguageRegistry.DEFAULT_LOCALE)
        assertNotNull(LanguageRegistry.forLocale("en-IN"))
    }

    @Test fun englishPacksParseCommandsAndYesNo() {
        for (locale in listOf("en-IN", "en-US", "en-GB")) {
            val pack = LanguageRegistry.forLocale(locale)!!
            assertEquals(Command.StartWorkout(WorkoutType.Walk), pack.parseCommand("start workout"))
            assertEquals(true, pack.parseYesNo("yes"))
        }
    }

    @Test fun unknownLocaleHasNoPack() = assertNull(LanguageRegistry.forLocale("hi-IN"))
}
```

- [ ] **Step 2: Run tests to verify they fail**

Run: `export JAVA_HOME=$(/usr/libexec/java_home -v 17) && ./gradlew :services:voice:test`
Expected: FAIL — unresolved `YesNoParser`, `LanguageRegistry`.

- [ ] **Step 3: Implement `YesNoParser.kt`**

```kotlin
package com.debasish.livefit.services.voice

/** English yes/no lexicon for confirmations (spec §5.4). Negation wins over affirmation. */
object YesNoParser {
    private val no = listOf("no", "nope", "nah", "cancel", "stop", "don t", "dont", "do not", "leave it", "not now")
    private val yes = listOf("yes", "yeah", "yep", "yup", "ok", "okay", "sure", "confirm", "take over", "do it", "go ahead")

    fun parse(utterance: String): Boolean? {
        val t = utterance.lowercase().replace(Regex("[^a-z ]"), " ").replace(Regex("\\s+"), " ").trim()
        if (t.isEmpty()) return null
        fun has(words: List<String>) = words.any { Regex("\\b$it\\b").containsMatchIn(t) }
        return when {
            has(no) -> false
            has(yes) -> true
            else -> null
        }
    }
}
```

- [ ] **Step 4: Implement `LanguageRegistry.kt`**

```kotlin
package com.debasish.livefit.services.voice

import com.debasish.livefit.model.Command

/** Everything voice needs for one language. Add a language by registering a pack (spec §3.1 principle 4). */
data class LanguagePack(
    val locale: String,
    val displayName: String,
    val parseCommand: (String) -> Command?,
    val parseYesNo: (String) -> Boolean?,
)

object LanguageRegistry {
    const val DEFAULT_LOCALE = "en-IN"

    private fun english(locale: String, name: String) =
        LanguagePack(locale, name, CommandParser::parse, YesNoParser::parse)

    val packs: Map<String, LanguagePack> = listOf(
        english("en-IN", "English (India)"),
        english("en-US", "English (US)"),
        english("en-GB", "English (UK)"),
    ).associateBy { it.locale }

    fun forLocale(locale: String): LanguagePack? = packs[locale]
}
```

- [ ] **Step 5: Run tests to verify they pass**

Run: `export JAVA_HOME=$(/usr/libexec/java_home -v 17) && ./gradlew :services:voice:test`
Expected: PASS (existing 18 + 4 + 3).

- [ ] **Step 6: Commit**

```bash
git add services/voice
git commit -m "feat(voice): yes/no parser and per-locale language registry"
```

---

### Task 5: SessionAssembler (session state from deltas)

**Files:**
- Create: `services/workout/src/main/kotlin/com/debasish/livefit/services/workout/SessionAssembler.kt`
- Test: `services/workout/src/test/kotlin/com/debasish/livefit/services/workout/SessionAssemblerTest.kt`

**Interfaces:**
- Consumes: `SessionDelta`, `SessionEvent`, `Sample`, `Provenance`, `WorkoutSnapshot`, `SessionSummary`, `SessionStatus`, `HeartZones` (Task 2)
- Produces: `class SessionAssembler(val sessionId: String)` with
  - `fun add(delta: SessionDelta): Boolean` (false if duplicate seq)
  - `val contiguousSeq: Long` (−1 when empty; seq starts at 0)
  - `val finalSeq: Long?`, `val isComplete: Boolean`, `val deltaCount: Int`, `val hasSamples: Boolean`
  - `fun phase(): WorkoutPhase` (Starting / Active / Paused / Stopping)
  - `fun activeMs(): Long`, `fun snapshot(): WorkoutSnapshot`, `fun hrHistory(limit: Int = 120): List<Int>`
  - `fun provenance(): Provenance`, `fun summary(status: SessionStatus, endReasonOverride: EndReason? = null): SessionSummary`

- [ ] **Step 1: Write the failing test** — `SessionAssemblerTest.kt`:

```kotlin
package com.debasish.livefit.services.workout

import com.debasish.livefit.model.EndReason
import com.debasish.livefit.model.Provenance
import com.debasish.livefit.model.Sample
import com.debasish.livefit.model.SessionDelta
import com.debasish.livefit.model.SessionEvent
import com.debasish.livefit.model.SessionStatus
import com.debasish.livefit.model.WorkoutPhase
import com.debasish.livefit.model.WorkoutType
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class SessionAssemblerTest {
    private val live = Provenance.Live("galaxy-watch/health-services")
    private fun delta(seq: Long, events: List<SessionEvent> = emptyList(), samples: List<Sample> = emptyList(), final: Boolean = false, provenance: Provenance = live) =
        SessionDelta(sessionId = "s", seq = seq, events = events, samples = samples, provenance = provenance, final = final)

    @Test fun phaseFollowsEvents() {
        val a = SessionAssembler("s")
        assertEquals(WorkoutPhase.Starting, a.phase())
        a.add(delta(0, listOf(SessionEvent.Started(1_000, WorkoutType.Run))))
        assertEquals(WorkoutPhase.Active, a.phase())
        a.add(delta(1, listOf(SessionEvent.Paused(5_000))))
        assertEquals(WorkoutPhase.Paused, a.phase())
        a.add(delta(2, listOf(SessionEvent.Stopped(6_000, EndReason.User)), final = true))
        assertEquals(WorkoutPhase.Stopping, a.phase())
    }

    @Test fun activeTimeExcludesPauses() {
        val a = SessionAssembler("s")
        a.add(delta(0, listOf(SessionEvent.Started(0, WorkoutType.Walk))))
        a.add(delta(1, listOf(SessionEvent.Paused(10_000))))
        a.add(delta(2, listOf(SessionEvent.Resumed(40_000))))
        a.add(delta(3, listOf(SessionEvent.Stopped(45_000, EndReason.User))))
        assertEquals(15_000, a.activeMs())
    }

    /** Review Focus #2: watch timestamps only — the phone clock is never involved. */
    @Test fun activeTimeUsesEventTimestampsOnly() {
        val watchEpoch = 1_000_000_000L // watch clock far from the phone's
        val a = SessionAssembler("s")
        a.add(delta(0, listOf(SessionEvent.Started(watchEpoch, WorkoutType.Walk)), samples = listOf(Sample(watchEpoch))))
        a.add(delta(1, samples = listOf(Sample(watchEpoch + 30_000, hr = 100))))
        // still running: active time runs to the latest watch timestamp seen
        assertEquals(30_000, a.activeMs())
        assertEquals(30_000, a.snapshot().elapsedMs)
    }

    @Test fun duplicatesAreIgnoredAndOrderDoesNotMatter() {
        val a = SessionAssembler("s")
        assertTrue(a.add(delta(1, samples = listOf(Sample(2_000, hr = 110, stepsTotal = 5)))))
        assertTrue(a.add(delta(0, listOf(SessionEvent.Started(0, WorkoutType.Walk)), samples = listOf(Sample(1_000, hr = 100)))))
        assertFalse(a.add(delta(1)))
        assertEquals(1, a.contiguousSeq)
        assertEquals(110, a.snapshot().metrics.heartRate)
        assertEquals(5, a.snapshot().metrics.steps)
    }

    @Test fun completeOnlyWhenEverySeqThroughFinalIsPresent() {
        val a = SessionAssembler("s")
        a.add(delta(0, listOf(SessionEvent.Started(0, WorkoutType.Walk))))
        a.add(delta(2, listOf(SessionEvent.Stopped(3_000, EndReason.User)), final = true))
        assertEquals(2L, a.finalSeq)
        assertFalse(a.isComplete, "seq 1 is missing")
        a.add(delta(1))
        assertTrue(a.isComplete)
    }

    @Test fun totalsAndHeartRateStats() {
        val a = SessionAssembler("s")
        a.add(delta(0, listOf(SessionEvent.Started(0, WorkoutType.Run)), samples = listOf(
            Sample(1_000, hr = 100, stepsTotal = 10, distanceKmTotal = 0.01, kcalTotal = 1.0, speedKmh = 8.0),
            Sample(2_000, hr = 140, stepsTotal = 20, distanceKmTotal = 0.02, kcalTotal = 2.4, speedKmh = 9.0),
        )))
        val s = a.summary(SessionStatus.Active)
        assertEquals(120, s.avgHr)
        assertEquals(140, s.maxHr)
        assertEquals(20, s.steps)
        assertEquals(2, s.kcal)
        assertEquals(listOf(100, 140), a.hrHistory())
    }

    @Test fun provenanceIsFakeIfAnyDeltaIsFake() {
        val a = SessionAssembler("s")
        a.add(delta(0, listOf(SessionEvent.Started(0, WorkoutType.Walk))))
        assertEquals(live, a.provenance())
        a.add(delta(1, provenance = Provenance.Fake))
        assertEquals(Provenance.Fake, a.provenance())
    }

    @Test fun detectedTypeAndEndReasonInSummary() {
        val a = SessionAssembler("s")
        a.add(delta(0, listOf(SessionEvent.Started(0, WorkoutType.Auto), SessionEvent.TypeDetected(5_000, WorkoutType.Run))))
        a.add(delta(1, listOf(SessionEvent.Stopped(9_000, EndReason.OtherApp)), final = true))
        val s = a.summary(SessionStatus.Complete)
        assertEquals(WorkoutType.Auto, s.type)
        assertEquals(WorkoutType.Run, s.detectedType)
        assertEquals(EndReason.OtherApp, s.endReason)
        assertEquals(0L, s.startMs)
        assertEquals(9_000L, s.endMs)
    }
}
```

- [ ] **Step 2: Run test to verify it fails**

Run: `export JAVA_HOME=$(/usr/libexec/java_home -v 17) && ./gradlew :services:workout:test --tests '*SessionAssemblerTest*'`
Expected: FAIL — `SessionAssembler` unresolved.

- [ ] **Step 3: Implement** — `SessionAssembler.kt`:

```kotlin
package com.debasish.livefit.services.workout

import com.debasish.livefit.model.EndReason
import com.debasish.livefit.model.Metrics
import com.debasish.livefit.model.Provenance
import com.debasish.livefit.model.SessionDelta
import com.debasish.livefit.model.SessionEvent
import com.debasish.livefit.model.SessionStatus
import com.debasish.livefit.model.SessionSummary
import com.debasish.livefit.model.WorkoutPhase
import com.debasish.livefit.model.WorkoutSnapshot
import com.debasish.livefit.model.WorkoutType
import java.util.TreeMap

/**
 * Derives a session's state purely from its deltas (events + cumulative samples). Used by the
 * phone hub (authoritative) and by the watch over its own buffer while offline. All times are
 * watch-clock timestamps from the deltas; no wall clock is read here.
 */
class SessionAssembler(val sessionId: String) {
    private val deltas = TreeMap<Long, SessionDelta>()

    var finalSeq: Long? = null
        private set

    val deltaCount: Int get() = deltas.size
    val hasSamples: Boolean get() = deltas.values.any { it.samples.isNotEmpty() }

    val contiguousSeq: Long
        get() {
            var s = -1L
            while (deltas.containsKey(s + 1)) s++
            return s
        }

    val isComplete: Boolean get() = finalSeq?.let { contiguousSeq >= it } ?: false

    fun add(delta: SessionDelta): Boolean {
        require(delta.sessionId == sessionId) { "delta for ${delta.sessionId} given to $sessionId" }
        if (deltas.containsKey(delta.seq)) return false
        deltas[delta.seq] = delta
        if (delta.final) finalSeq = delta.seq
        return true
    }

    private fun events() = deltas.values.flatMap { it.events }.sortedBy { it.tMs }
    private fun samples() = deltas.values.flatMap { it.samples }.sortedBy { it.tMs }

    private fun latestTimestamp(): Long? =
        (events().map { it.tMs } + samples().map { it.tMs }).maxOrNull()

    fun phase(): WorkoutPhase = when (events().lastOrNull { it !is SessionEvent.TypeDetected }) {
        null -> WorkoutPhase.Starting
        is SessionEvent.Started, is SessionEvent.Resumed -> WorkoutPhase.Active
        is SessionEvent.Paused -> WorkoutPhase.Paused
        is SessionEvent.Stopped -> WorkoutPhase.Stopping
        is SessionEvent.TypeDetected -> WorkoutPhase.Active
    }

    fun activeMs(): Long {
        var total = 0L
        var runningSince: Long? = null
        for (e in events()) {
            when (e) {
                is SessionEvent.Started, is SessionEvent.Resumed -> if (runningSince == null) runningSince = e.tMs
                is SessionEvent.Paused -> runningSince?.let { total += e.tMs - it; runningSince = null }
                is SessionEvent.Stopped -> { runningSince?.let { total += e.tMs - it }; return total }
                is SessionEvent.TypeDetected -> Unit
            }
        }
        val since = runningSince ?: return total
        return total + ((latestTimestamp() ?: since) - since).coerceAtLeast(0)
    }

    private fun type(): WorkoutType =
        events().filterIsInstance<SessionEvent.Started>().firstOrNull()?.type ?: WorkoutType.Walk

    private fun detectedType(): WorkoutType? =
        events().filterIsInstance<SessionEvent.TypeDetected>().lastOrNull()?.type

    private fun heartRates() = samples().mapNotNull { it.hr }

    fun hrHistory(limit: Int = 120): List<Int> = heartRates().takeLast(limit)

    fun snapshot(): WorkoutSnapshot {
        val last = samples().lastOrNull()
        val hrs = heartRates()
        return WorkoutSnapshot(
            sessionId = sessionId,
            phase = phase(),
            type = type(),
            detectedType = detectedType(),
            elapsedMs = activeMs(),
            metrics = Metrics(
                heartRate = samples().lastOrNull { it.hr != null }?.hr,
                calories = last?.kcalTotal?.toInt() ?: 0,
                steps = last?.stepsTotal ?: 0,
                distanceKm = last?.distanceKmTotal ?: 0.0,
                speedKmh = last?.speedKmh ?: 0.0,
            ),
            avgHeartRate = if (hrs.isEmpty()) null else hrs.average().toInt(),
            maxHeartRate = hrs.maxOrNull(),
        )
    }

    fun provenance(): Provenance {
        val all = deltas.values.map { it.provenance }.distinct()
        return if (all.size == 1 && all[0] is Provenance.Live) all[0] else Provenance.Fake
    }

    fun summary(status: SessionStatus, endReasonOverride: EndReason? = null): SessionSummary {
        val snap = snapshot()
        val ev = events()
        val stopped = ev.filterIsInstance<SessionEvent.Stopped>().lastOrNull()
        return SessionSummary(
            id = sessionId,
            type = snap.type,
            detectedType = snap.detectedType,
            startMs = ev.filterIsInstance<SessionEvent.Started>().firstOrNull()?.tMs ?: (samples().firstOrNull()?.tMs ?: 0),
            endMs = stopped?.tMs,
            activeMs = snap.elapsedMs,
            avgHr = snap.avgHeartRate,
            maxHr = snap.maxHeartRate,
            steps = snap.metrics.steps,
            distanceKm = snap.metrics.distanceKm,
            kcal = snap.metrics.calories,
            provenance = provenance(),
            status = status,
            endReason = endReasonOverride ?: stopped?.reason,
        )
    }

    /** All stored deltas in seq order (used to rebuild after a phone restart). */
    fun deltasInOrder(): List<SessionDelta> = deltas.values.toList()
}
```

- [ ] **Step 4: Run tests to verify they pass**

Run: `export JAVA_HOME=$(/usr/libexec/java_home -v 17) && ./gradlew :services:workout:test`
Expected: PASS (8 new + 5 existing).

- [ ] **Step 5: Commit**

```bash
git add services/workout
git commit -m "feat(workout): SessionAssembler derives session state from deltas"
```

---
### Task 6: Hub workout service — start, session-scoped ops, timeouts

The phone's authoritative `WorkoutService`. This task covers starting (incl. takeover confirmation, permission errors, timeouts and abandoned starts) and session-scoped pause/resume/stop. Task 7 adds claims, completion and recovery to the same class.

**Files:**
- Modify: `core/services/src/main/kotlin/com/debasish/livefit/services/Services.kt` (append contracts)
- Modify: `services/workout/build.gradle.kts` (test dependency on `:services:confirm`)
- Create: `services/workout/src/main/kotlin/com/debasish/livefit/services/workout/InMemorySessionStore.kt`
- Create: `services/workout/src/main/kotlin/com/debasish/livefit/services/workout/HubWorkoutService.kt`
- Test: `services/workout/src/test/kotlin/com/debasish/livefit/services/workout/FakeWatchGateway.kt`
- Test: `services/workout/src/test/kotlin/com/debasish/livefit/services/workout/HubWorkoutStartTest.kt`

**Interfaces:**
- Consumes: `SessionAssembler` (Task 5), `ConfirmationService`, `Clock`, `ConfirmationOutcome` (Task 3), protocol types (Task 2).
- Produces:
  - `interface WatchExerciseGateway { suspend fun send(request: ExerciseRequest); suspend fun ack(ack: DeltaAck); val results: Flow<ExerciseResult>; val stateReports: Flow<ExerciseStateReport>; val deltas: Flow<SessionDelta>; val claims: Flow<SessionClaim> }`
  - `interface SessionStore { suspend fun storeDelta(delta: SessionDelta): Long; suspend fun deltas(sessionId: String): List<SessionDelta>; suspend fun openSessionIds(): List<String>; suspend fun finalize(summary: SessionSummary); suspend fun discard(sessionId: String) }` — `storeDelta` is durable and idempotent and returns the highest contiguous seq stored for that session.
  - `class InMemorySessionStore : SessionStore` (+ `val summaries: Map<String, SessionSummary>`)
  - `class HubWorkoutService(scope, gateway, store, confirm, clock, newId = UUID, resultTimeoutMs = 10_000, incompleteAfterMs = 24h, incompleteCheckMs = 60_000, gpsFor: (WorkoutType) -> Boolean = { false }) : WorkoutService` with extra `val notices: SharedFlow<String>`, `val hrHistory: StateFlow<List<Int>>`, `val finished: SharedFlow<SessionSummary>`.
  - Constant `HubWorkoutService.SYNCING_NOTICE = "Syncing watch data…"`.
  - Threading rule: `scope` must be single-threaded (`Dispatchers.Main.immediate` on the phone, the test dispatcher in tests); the class has no locks.

- [ ] **Step 1: Append contracts to `Services.kt`**

```kotlin
/** Phone side of hub → watch exercise control and watch → phone session data (spec §4.4, §4.8). */
interface WatchExerciseGateway {
    suspend fun send(request: com.debasish.livefit.model.ExerciseRequest)
    suspend fun ack(ack: com.debasish.livefit.model.DeltaAck)
    val results: Flow<com.debasish.livefit.model.ExerciseResult>
    val stateReports: Flow<com.debasish.livefit.model.ExerciseStateReport>
    val deltas: Flow<com.debasish.livefit.model.SessionDelta>
    val claims: Flow<com.debasish.livefit.model.SessionClaim>
}

/** Durable session storage on the phone. */
interface SessionStore {
    /** Stores durably (idempotent by sessionId+seq); returns the highest contiguous seq now stored. */
    suspend fun storeDelta(delta: com.debasish.livefit.model.SessionDelta): Long
    suspend fun deltas(sessionId: String): List<com.debasish.livefit.model.SessionDelta>
    /** Sessions with stored deltas but no finalized summary, oldest first. */
    suspend fun openSessionIds(): List<String>
    suspend fun finalize(summary: com.debasish.livefit.model.SessionSummary)
    suspend fun discard(sessionId: String)
}
```

- [ ] **Step 2: Test dependency** — in `services/workout/build.gradle.kts` `dependencies { }` add:

```kotlin
    testImplementation(project(":services:confirm"))
```

- [ ] **Step 3: Create `InMemorySessionStore.kt`** (used by tests and by demo bindings)

```kotlin
package com.debasish.livefit.services.workout

import com.debasish.livefit.model.SessionDelta
import com.debasish.livefit.model.SessionSummary
import com.debasish.livefit.services.SessionStore
import java.util.TreeMap

class InMemorySessionStore : SessionStore {
    private val deltas = LinkedHashMap<String, TreeMap<Long, SessionDelta>>()
    private val finalized = LinkedHashMap<String, SessionSummary>()
    val summaries: Map<String, SessionSummary> get() = finalized
    /** Order of storeDelta calls, for tests that check "store before ack". */
    val storeLog = mutableListOf<Pair<String, Long>>()

    override suspend fun storeDelta(delta: SessionDelta): Long {
        val m = deltas.getOrPut(delta.sessionId) { TreeMap() }
        m.putIfAbsent(delta.seq, delta)
        storeLog += delta.sessionId to delta.seq
        var s = -1L
        while (m.containsKey(s + 1)) s++
        return s
    }

    override suspend fun deltas(sessionId: String): List<SessionDelta> = deltas[sessionId]?.values?.toList() ?: emptyList()
    override suspend fun openSessionIds(): List<String> = deltas.keys.filter { it !in finalized }
    override suspend fun finalize(summary: SessionSummary) { finalized[summary.id] = summary }
    override suspend fun discard(sessionId: String) { deltas.remove(sessionId); finalized.remove(sessionId) }
}
```

- [ ] **Step 4: Create the fake gateway** — `services/workout/src/test/.../FakeWatchGateway.kt`:

```kotlin
package com.debasish.livefit.services.workout

import com.debasish.livefit.model.DeltaAck
import com.debasish.livefit.model.ExerciseError
import com.debasish.livefit.model.ExerciseRequest
import com.debasish.livefit.model.ExerciseResult
import com.debasish.livefit.model.ExerciseState
import com.debasish.livefit.model.ExerciseStateReport
import com.debasish.livefit.model.SessionClaim
import com.debasish.livefit.model.SessionDelta
import com.debasish.livefit.services.WatchExerciseGateway
import kotlinx.coroutines.flow.MutableSharedFlow

class FakeWatchGateway : WatchExerciseGateway {
    val sent = mutableListOf<ExerciseRequest>()
    val acks = mutableListOf<DeltaAck>()
    override val results = MutableSharedFlow<ExerciseResult>(extraBufferCapacity = 64)
    override val stateReports = MutableSharedFlow<ExerciseStateReport>(extraBufferCapacity = 64)
    override val deltas = MutableSharedFlow<SessionDelta>(extraBufferCapacity = 8_192)
    override val claims = MutableSharedFlow<SessionClaim>(extraBufferCapacity = 64)

    override suspend fun send(request: ExerciseRequest) { sent += request }
    override suspend fun ack(ack: DeltaAck) { acks += ack }

    fun reply(index: Int = sent.lastIndex, ok: Boolean = true, error: ExerciseError? = null, state: ExerciseState = ExerciseState.Active) {
        val r = sent[index]
        results.tryEmit(ExerciseResult(requestId = r.requestId, sessionId = r.sessionId, ok = ok, error = error, state = state))
    }
}
```

- [ ] **Step 5: Write the failing test** — `HubWorkoutStartTest.kt`:

```kotlin
package com.debasish.livefit.services.workout

import com.debasish.livefit.confirm.DefaultConfirmationService
import com.debasish.livefit.model.ExerciseError
import com.debasish.livefit.model.ExerciseOp
import com.debasish.livefit.model.Provenance
import com.debasish.livefit.model.SessionDelta
import com.debasish.livefit.model.SessionEvent
import com.debasish.livefit.model.WorkoutPhase
import com.debasish.livefit.model.WorkoutType
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

@OptIn(ExperimentalCoroutinesApi::class)
class HubWorkoutStartTest {
    private class Rig(scope: TestScope) {
        var n = 0
        val gateway = FakeWatchGateway()
        val store = InMemorySessionStore()
        val clock = com.debasish.livefit.services.Clock { scope.testScheduler.currentTime }
        val confirm = DefaultConfirmationService(clock, newId = { "c${n++}" })
        val hub = HubWorkoutService(scope.backgroundScope, gateway, store, confirm, clock, newId = { "id${n++}" })
        val notices = mutableListOf<String>()
        init { scope.backgroundScope.launch { hub.notices.toList(notices) } }
    }

    private fun started(sessionId: String) = SessionDelta(sessionId = sessionId, seq = 0,
        events = listOf(SessionEvent.Started(1_000, WorkoutType.Run)), provenance = Provenance.Fake)

    @Test fun startSendsSessionScopedRequestAndGoesActiveOnFirstDelta() = runTest {
        val r = Rig(this); runCurrent()
        r.hub.start(WorkoutType.Run); runCurrent()
        assertEquals(WorkoutPhase.Starting, r.hub.snapshot.value.phase)
        val req = r.gateway.sent.single()
        assertEquals(ExerciseOp.Start(WorkoutType.Run, force = false), req.op)
        assertEquals(r.hub.snapshot.value.sessionId, req.sessionId)
        r.gateway.reply(); r.gateway.deltas.emit(started(req.sessionId)); runCurrent()
        assertEquals(WorkoutPhase.Active, r.hub.snapshot.value.phase)
    }

    /** Review Focus #1. */
    @Test fun simultaneousStartsCreateOneSession() = runTest {
        val r = Rig(this); runCurrent()
        r.hub.start(WorkoutType.Walk)
        r.hub.start(WorkoutType.Run)
        runCurrent()
        assertEquals(1, r.gateway.sent.size)
        assertEquals(WorkoutType.Walk, r.hub.snapshot.value.type)
    }

    @Test fun noResultWithin10sReturnsToIdleAndLateOkStopsOnlyThatSession() = runTest {
        val r = Rig(this); runCurrent()
        r.hub.start(WorkoutType.Walk); runCurrent()
        val a = r.gateway.sent.single().sessionId
        advanceTimeBy(10_001); runCurrent()
        assertEquals(WorkoutPhase.Idle, r.hub.snapshot.value.phase)
        assertTrue("Watch didn't respond" in r.notices)

        r.hub.start(WorkoutType.Run); runCurrent()
        val b = r.gateway.sent.last().sessionId
        r.gateway.reply(index = 0); runCurrent()          // late ok for A
        val stop = r.gateway.sent.last()
        assertEquals(ExerciseOp.Stop, stop.op)
        assertEquals(a, stop.sessionId)
        assertEquals(b, r.hub.snapshot.value.sessionId)  // B untouched
        assertEquals(WorkoutPhase.Starting, r.hub.snapshot.value.phase)
    }

    @Test fun otherAppTrackingAsksAndForceStartsOnYes() = runTest {
        val r = Rig(this); runCurrent()
        r.hub.start(WorkoutType.Walk); runCurrent()
        r.gateway.reply(ok = false, error = ExerciseError.OtherAppTracking("RUNNING_TREADMILL")); runCurrent()
        val c = r.confirm.pending.value!!
        r.confirm.answer(c.id, yes = true); runCurrent()
        val forced = r.gateway.sent.last()
        assertEquals(ExerciseOp.Start(WorkoutType.Walk, force = true), forced.op)
        assertEquals(r.gateway.sent.first().sessionId, forced.sessionId)
    }

    @Test fun otherAppTrackingDeclinedOrSilentReturnsToIdle() = runTest {
        val r = Rig(this); runCurrent()
        r.hub.start(WorkoutType.Walk); runCurrent()
        r.gateway.reply(ok = false, error = ExerciseError.OtherAppTracking("WALKING")); runCurrent()
        advanceTimeBy(15_001); runCurrent()
        assertEquals(WorkoutPhase.Idle, r.hub.snapshot.value.phase)
        assertTrue("Cancelled" in r.notices)
        assertEquals(1, r.gateway.sent.size)
    }

    @Test fun permissionMissingReturnsToIdleWithNotice() = runTest {
        val r = Rig(this); runCurrent()
        r.hub.start(WorkoutType.Walk); runCurrent()
        r.gateway.reply(ok = false, error = ExerciseError.PermissionMissing(listOf("android.permission.health.READ_HEART_RATE"))); runCurrent()
        assertEquals(WorkoutPhase.Idle, r.hub.snapshot.value.phase)
        assertTrue(r.notices.any { it.contains("READ_HEART_RATE") })
    }

    @Test fun pauseAndStopAreScopedAndPhaseComesFromEvents() = runTest {
        val r = Rig(this); runCurrent()
        r.hub.start(WorkoutType.Run); runCurrent()
        val id = r.gateway.sent.single().sessionId
        r.gateway.reply(); r.gateway.deltas.emit(started(id)); runCurrent()
        r.hub.pause(); runCurrent()
        val pause = r.gateway.sent.last()
        assertEquals(ExerciseOp.Pause, pause.op); assertEquals(id, pause.sessionId)
        r.gateway.reply(state = com.debasish.livefit.model.ExerciseState.Paused); runCurrent()
        assertEquals(WorkoutPhase.Active, r.hub.snapshot.value.phase, "phase changes only when the watch's Paused event arrives")
        r.gateway.deltas.emit(SessionDelta(sessionId = id, seq = 1, events = listOf(SessionEvent.Paused(5_000)), provenance = Provenance.Fake)); runCurrent()
        assertEquals(WorkoutPhase.Paused, r.hub.snapshot.value.phase)
        r.hub.stop(); runCurrent()
        assertEquals(ExerciseOp.Stop, r.gateway.sent.last().op)
        assertEquals(id, r.gateway.sent.last().sessionId)
    }

    @Test fun stopWhileStartingAbandonsAndStopsThatSession() = runTest {
        val r = Rig(this); runCurrent()
        r.hub.start(WorkoutType.Walk); runCurrent()
        val id = r.gateway.sent.single().sessionId
        r.hub.stop(); runCurrent()
        assertEquals(WorkoutPhase.Idle, r.hub.snapshot.value.phase)
        assertEquals(ExerciseOp.Stop, r.gateway.sent.last().op)
        assertEquals(id, r.gateway.sent.last().sessionId)
    }
}
```

- [ ] **Step 6: Run test to verify it fails**

Run: `export JAVA_HOME=$(/usr/libexec/java_home -v 17) && ./gradlew :services:workout:test --tests '*HubWorkoutStartTest*'`
Expected: FAIL — `HubWorkoutService` unresolved.

- [ ] **Step 7: Implement** — `HubWorkoutService.kt` (start/ops/delta path; Task 7 replaces this file with the complete version):

```kotlin
package com.debasish.livefit.services.workout

import com.debasish.livefit.model.ConfirmationKind
import com.debasish.livefit.model.DeltaAck
import com.debasish.livefit.model.ExerciseError
import com.debasish.livefit.model.ExerciseOp
import com.debasish.livefit.model.ExerciseRequest
import com.debasish.livefit.model.ExerciseResult
import com.debasish.livefit.model.SessionDelta
import com.debasish.livefit.model.SessionSummary
import com.debasish.livefit.model.WorkoutPhase
import com.debasish.livefit.model.WorkoutSnapshot
import com.debasish.livefit.model.WorkoutType
import com.debasish.livefit.services.Clock
import com.debasish.livefit.services.ConfirmationOutcome
import com.debasish.livefit.services.ConfirmationService
import com.debasish.livefit.services.SessionStore
import com.debasish.livefit.services.WatchExerciseGateway
import com.debasish.livefit.services.WorkoutService
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
import java.util.UUID

/** Phone hub: decides workout state; the watch executes and reports (spec §4.4, §4.8). Single-threaded scope. */
class HubWorkoutService(
    private val scope: CoroutineScope,
    private val gateway: WatchExerciseGateway,
    private val store: SessionStore,
    private val confirm: ConfirmationService,
    private val clock: Clock,
    private val newId: () -> String = { UUID.randomUUID().toString() },
    private val resultTimeoutMs: Long = 10_000,
    private val incompleteAfterMs: Long = 24 * 60 * 60 * 1000L,
    private val incompleteCheckMs: Long = 60_000,
    /** Phone setting "Use GPS outdoors" per workout type (spec §5.1). */
    private val gpsFor: (WorkoutType) -> Boolean = { false },
) : WorkoutService {

    private val _snapshot = MutableStateFlow(WorkoutSnapshot())
    override val snapshot: StateFlow<WorkoutSnapshot> = _snapshot
    private val _notices = MutableSharedFlow<String>(extraBufferCapacity = 16)
    val notices: SharedFlow<String> = _notices
    private val _hrHistory = MutableStateFlow<List<Int>>(emptyList())
    val hrHistory: StateFlow<List<Int>> = _hrHistory
    private val _finished = MutableSharedFlow<SessionSummary>(extraBufferCapacity = 4)
    val finished: SharedFlow<SessionSummary> = _finished

    private var currentId: String? = null
    private var current: SessionAssembler? = null
    private val pendingResults = HashMap<String, CompletableDeferred<ExerciseResult>>()
    private val abandoned = HashSet<String>()

    init {
        scope.launch { gateway.results.collect { onResult(it) } }
        scope.launch { gateway.deltas.collect { onDelta(it) } }
    }

    override fun start(type: WorkoutType) {
        when (_snapshot.value.phase) {
            WorkoutPhase.Idle, WorkoutPhase.Summary -> Unit
            WorkoutPhase.Syncing -> { notice(SYNCING_NOTICE); return }
            else -> return // duplicate start from another device while starting/running
        }
        val id = newId()
        beginSession(id)
        _snapshot.value = WorkoutSnapshot(sessionId = id, phase = WorkoutPhase.Starting, type = type)
        scope.launch { runStart(id, type, force = false) }
    }

    override fun pause() = scopedOp(ExerciseOp.Pause, setOf(WorkoutPhase.Active), "pause")
    override fun resume() = scopedOp(ExerciseOp.Resume, setOf(WorkoutPhase.Paused), "resume")

    override fun stop() {
        if (_snapshot.value.phase == WorkoutPhase.Starting) {
            val id = currentId ?: return
            scope.launch { abandon(id); sendStop(id) }
            return
        }
        scopedOp(ExerciseOp.Stop, setOf(WorkoutPhase.Active, WorkoutPhase.Paused), "stop")
    }

    override fun dismissSummary() {
        if (_snapshot.value.phase == WorkoutPhase.Summary) resetIdle()
    }

    private fun beginSession(id: String) {
        currentId = id
        current = SessionAssembler(id)
        _hrHistory.value = emptyList()
    }

    private suspend fun runStart(id: String, type: WorkoutType, force: Boolean) {
        val r = request(id, ExerciseOp.Start(type, force, gpsFor(type)))
        if (currentId != id) return
        if (r == null) { abandon(id); notice("Watch didn't respond"); return }
        if (r.ok) return
        when (val e = r.error) {
            is ExerciseError.OtherAppTracking -> {
                val outcome = confirm.ask(
                    ConfirmationKind.TakeOverWorkout,
                    title = "Take over workout?",
                    message = "Another app is tracking a workout on your watch. Take over?",
                    defaultYes = true,
                )
                if (currentId != id) return
                if (outcome == ConfirmationOutcome.Yes) runStart(id, type, force = true)
                else { resetIdle(); notice(if (outcome == ConfirmationOutcome.No) "Kept the other workout" else "Cancelled") }
            }
            is ExerciseError.PermissionMissing -> { resetIdle(); notice("Watch needs permission: " + e.permissions.joinToString { it.substringAfterLast('.') }) }
            is ExerciseError.WrongSession -> { resetIdle(); notice("Watch is busy with another workout") }
            ExerciseError.SensorUnavailable -> { resetIdle(); notice("Watch sensors unavailable") }
            else -> { resetIdle(); notice("Couldn't start workout") }
        }
    }

    private fun scopedOp(op: ExerciseOp, allowed: Set<WorkoutPhase>, verb: String) {
        val phase = _snapshot.value.phase
        if (phase == WorkoutPhase.Syncing) { notice(SYNCING_NOTICE); return }
        if (phase !in allowed) return
        val id = currentId ?: return
        scope.launch {
            val r = request(id, op)
            when {
                r == null -> notice("Watch didn't respond")
                !r.ok -> notice("Couldn't $verb workout")
            }
        }
    }

    private suspend fun request(sessionId: String, op: ExerciseOp): ExerciseResult? {
        val requestId = newId()
        val waiter = CompletableDeferred<ExerciseResult>()
        pendingResults[requestId] = waiter
        gateway.send(ExerciseRequest(requestId = requestId, sessionId = sessionId, op = op))
        val r = withTimeoutOrNull(resultTimeoutMs) { waiter.await() }
        pendingResults.remove(requestId)
        return r
    }

    private suspend fun onResult(r: ExerciseResult) {
        val waiter = pendingResults.remove(r.requestId)
        if (waiter != null) { waiter.complete(r); return }
        if (r.ok && r.sessionId in abandoned) sendStop(r.sessionId) // late ok: stop only that session
    }

    private suspend fun sendStop(sessionId: String) =
        gateway.send(ExerciseRequest(requestId = newId(), sessionId = sessionId, op = ExerciseOp.Stop))

    private suspend fun abandon(id: String) {
        abandoned += id
        store.discard(id)
        if (currentId == id) resetIdle()
    }

    private suspend fun onDelta(d: SessionDelta) {
        if (d.sessionId in abandoned) { gateway.ack(DeltaAck(sessionId = d.sessionId, seq = d.seq)); return }
        if (d.sessionId != currentId) return // adoption handled in Task 7
        val a = current ?: return
        val storedSeq = store.storeDelta(d) // durable first
        a.add(d)
        gateway.ack(DeltaAck(sessionId = d.sessionId, seq = storedSeq))
        publish()
    }

    private fun publish() {
        val a = current ?: return
        val snap = a.snapshot()
        _snapshot.value = if (a.deltaCount == 0) snap.copy(phase = WorkoutPhase.Starting, type = _snapshot.value.type) else snap
        _hrHistory.value = a.hrHistory()
    }

    private fun resetIdle() {
        currentId = null
        current = null
        _snapshot.value = WorkoutSnapshot()
        _hrHistory.value = emptyList()
    }

    private fun notice(text: String) { _notices.tryEmit(text) }

    companion object { const val SYNCING_NOTICE = "Syncing watch data…" }
}
```

- [ ] **Step 8: Run tests to verify they pass**

Run: `export JAVA_HOME=$(/usr/libexec/java_home -v 17) && ./gradlew :services:workout:test`
Expected: PASS (HubWorkoutStartTest 8 + previous).

- [ ] **Step 9: Commit**

```bash
git add core/services services/workout
git commit -m "feat(workout): hub workout service with session-scoped exercise control"
```

---

### Task 7: Hub workout service — deltas, claims, completion and recovery

Completes `HubWorkoutService` per spec §4.4 (adoption, Syncing), §4.8 (state reports, abandoned-start reconciliation) and §4.9 (single completion rule, Incomplete after 24 h incl. gaps, lost watch buffer).

**Files:**
- Modify: `services/workout/src/main/kotlin/com/debasish/livefit/services/workout/HubWorkoutService.kt` (full replacement)
- Test: `services/workout/src/test/kotlin/com/debasish/livefit/services/workout/HubWorkoutRecoveryTest.kt`

**Interfaces:**
- Consumes: everything from Task 6.
- Produces: same public API as Task 6 (no new public members). Behavioural contract:
  - Unknown session in a delta or claim → adopted (phone's empty session discarded; phone's session with samples finalised as Complete/Incomplete).
  - Claim with `lastSeq` beyond stored → phase `Syncing`; workout commands rejected with `SYNCING_NOTICE`.
  - `ExerciseStateReport(Ended)` → phase `Stopping` immediately; `Summary` only when `SessionAssembler.isComplete`.
  - `ExerciseStateReport(sessionId != current, state = Idle)` while current is `Stopping` and incomplete → finalised `Incomplete` (watch has no buffer left).
  - Completion unmet 24 h after the end event → `Incomplete`.
  - On construction, the newest open session in the store is restored.

- [ ] **Step 1: Write the failing test** — `HubWorkoutRecoveryTest.kt`:

```kotlin
package com.debasish.livefit.services.workout

import com.debasish.livefit.confirm.DefaultConfirmationService
import com.debasish.livefit.model.EndReason
import com.debasish.livefit.model.ExerciseOp
import com.debasish.livefit.model.ExerciseState
import com.debasish.livefit.model.ExerciseStateReport
import com.debasish.livefit.model.Provenance
import com.debasish.livefit.model.Sample
import com.debasish.livefit.model.SessionClaim
import com.debasish.livefit.model.SessionDelta
import com.debasish.livefit.model.SessionEvent
import com.debasish.livefit.model.SessionStatus
import com.debasish.livefit.model.WorkoutPhase
import com.debasish.livefit.model.WorkoutType
import com.debasish.livefit.services.Clock
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

@OptIn(ExperimentalCoroutinesApi::class)
class HubWorkoutRecoveryTest {
    private val live = Provenance.Live("galaxy-watch/health-services")

    private class Rig(scope: TestScope, val store: InMemorySessionStore = InMemorySessionStore()) {
        var n = 0
        val gateway = FakeWatchGateway()
        val clock = Clock { scope.testScheduler.currentTime }
        val confirm = DefaultConfirmationService(clock, newId = { "c${n++}" })
        val hub = HubWorkoutService(scope.backgroundScope, gateway, store, confirm, clock, newId = { "id${n++}" })
        val notices = mutableListOf<String>()
        init { scope.backgroundScope.launch { hub.notices.toList(notices) } }
    }

    private fun d(id: String, seq: Long, events: List<SessionEvent> = emptyList(), samples: List<Sample> = emptyList(), final: Boolean = false) =
        SessionDelta(sessionId = id, seq = seq, events = events, samples = samples, provenance = live, final = final)

    /** Starts a session through the normal path and returns its id. */
    private suspend fun TestScope.active(r: Rig): String {
        runCurrent()
        r.hub.start(WorkoutType.Walk); runCurrent()
        val id = r.gateway.sent.single().sessionId
        r.gateway.reply()
        r.gateway.deltas.emit(d(id, 0, listOf(SessionEvent.Started(0, WorkoutType.Walk)))); runCurrent()
        return id
    }

    @Test fun deltaIsStoredBeforeAckAndAckIsContiguous() = runTest {
        val r = Rig(this)
        val id = active(r)
        r.gateway.deltas.emit(d(id, 2)); runCurrent()
        assertEquals(0, r.gateway.acks.last().seq, "gap at 1: ack stays at 0")
        r.gateway.deltas.emit(d(id, 1)); runCurrent()
        assertEquals(2, r.gateway.acks.last().seq)
        assertEquals(id, r.gateway.acks.last().sessionId)
        assertEquals(listOf(id to 0L, id to 2L, id to 1L), r.store.storeLog)
    }

    @Test fun claimForUnknownSessionAdoptsAndSyncsRejectingWorkoutCommands() = runTest {
        val r = Rig(this); runCurrent()
        r.gateway.claims.emit(SessionClaim(sessionId = "w1", type = WorkoutType.Run, startMs = 0, phase = WorkoutPhase.Paused, activeMs = 30_000, lastSeq = 1)); runCurrent()
        assertEquals(WorkoutPhase.Syncing, r.hub.snapshot.value.phase)
        assertEquals("w1", r.hub.snapshot.value.sessionId)
        r.hub.pause(); r.hub.start(WorkoutType.Walk); runCurrent()
        assertTrue(r.gateway.sent.isEmpty())
        assertTrue(HubWorkoutService.SYNCING_NOTICE in r.notices)
        r.gateway.deltas.emit(d("w1", 0, listOf(SessionEvent.Started(0, WorkoutType.Run))))
        r.gateway.deltas.emit(d("w1", 1, listOf(SessionEvent.Paused(30_000)))); runCurrent()
        assertEquals(WorkoutPhase.Paused, r.hub.snapshot.value.phase)
        assertEquals(30_000, r.hub.snapshot.value.elapsedMs)
    }

    @Test fun claimForAbandonedSessionStopsOnlyIt() = runTest {
        val r = Rig(this); runCurrent()
        r.hub.start(WorkoutType.Walk); runCurrent()
        val a = r.gateway.sent.single().sessionId
        advanceTimeBy(10_001); runCurrent()
        r.gateway.claims.emit(SessionClaim(sessionId = a, type = WorkoutType.Walk, startMs = 0, phase = WorkoutPhase.Active, activeMs = 1, lastSeq = 0)); runCurrent()
        assertEquals(ExerciseOp.Stop, r.gateway.sent.last().op)
        assertEquals(a, r.gateway.sent.last().sessionId)
        assertEquals(WorkoutPhase.Idle, r.hub.snapshot.value.phase)
    }

    @Test fun endedReportShowsStoppingAndCompletesOnlyWhenAllDeltasStored() = runTest {
        val r = Rig(this)
        val id = active(r)
        r.gateway.stateReports.emit(ExerciseStateReport(sessionId = id, state = ExerciseState.Ended, endedBy = EndReason.OtherApp)); runCurrent()
        assertEquals(WorkoutPhase.Stopping, r.hub.snapshot.value.phase)
        assertTrue(r.notices.any { it.startsWith("Workout ended by") })
        r.gateway.deltas.emit(d(id, 3, listOf(SessionEvent.Stopped(9_000, EndReason.OtherApp)), final = true)); runCurrent()
        assertEquals(WorkoutPhase.Stopping, r.hub.snapshot.value.phase, "seqs 1 and 2 missing")
        assertNull(r.store.summaries[id])
        r.gateway.deltas.emit(d(id, 1)); r.gateway.deltas.emit(d(id, 2)); runCurrent()
        assertEquals(WorkoutPhase.Summary, r.hub.snapshot.value.phase)
        assertEquals(SessionStatus.Complete, r.store.summaries[id]!!.status)
        assertEquals(EndReason.OtherApp, r.store.summaries[id]!!.endReason)
    }

    @Test fun finalWithGapBecomesIncompleteAfter24Hours() = runTest {
        val r = Rig(this)
        val id = active(r)
        r.gateway.deltas.emit(d(id, 100, listOf(SessionEvent.Stopped(9_000, EndReason.User)), final = true)); runCurrent()
        assertEquals(WorkoutPhase.Stopping, r.hub.snapshot.value.phase)
        advanceTimeBy(24 * 60 * 60 * 1000L + 60_001); runCurrent()
        assertEquals(WorkoutPhase.Summary, r.hub.snapshot.value.phase)
        assertEquals(SessionStatus.Incomplete, r.store.summaries[id]!!.status)
    }

    @Test fun watchWithoutBufferMakesStoppingSessionIncomplete() = runTest {
        val r = Rig(this)
        val id = active(r)
        r.gateway.stateReports.emit(ExerciseStateReport(sessionId = id, state = ExerciseState.Ended, endedBy = EndReason.System)); runCurrent()
        r.gateway.stateReports.emit(ExerciseStateReport(sessionId = "", state = ExerciseState.Idle)); runCurrent()
        assertEquals(SessionStatus.Incomplete, r.store.summaries[id]!!.status)
    }

    /** Review Focus #5. */
    @Test fun longOfflineGapReplaysAndCompletes() = runTest {
        val r = Rig(this); runCurrent()
        r.gateway.claims.emit(SessionClaim(sessionId = "w", type = WorkoutType.Walk, startMs = 0, phase = WorkoutPhase.Stopping, activeMs = 3_599_000, lastSeq = 3_599)); runCurrent()
        r.gateway.deltas.emit(d("w", 0, listOf(SessionEvent.Started(0, WorkoutType.Walk))))
        for (seq in 1L until 3_599L) r.gateway.deltas.emit(d("w", seq, samples = listOf(Sample(seq * 1_000, hr = 100 + (seq % 20).toInt()))))
        r.gateway.deltas.emit(d("w", 3_599, listOf(SessionEvent.Stopped(3_599_000, EndReason.User)), final = true))
        runCurrent()
        assertEquals(3_599, r.gateway.acks.last().seq)
        assertEquals(WorkoutPhase.Summary, r.hub.snapshot.value.phase)
        assertEquals(3_599_000, r.store.summaries["w"]!!.activeMs)
    }

    @Test fun restoresOpenSessionAfterPhoneRestart() = runTest {
        val store = InMemorySessionStore()
        store.storeDelta(d("old", 0, listOf(SessionEvent.Started(0, WorkoutType.Cycle)), samples = listOf(Sample(5_000, hr = 120))))
        val r = Rig(this, store); runCurrent()
        assertEquals("old", r.hub.snapshot.value.sessionId)
        assertEquals(WorkoutType.Cycle, r.hub.snapshot.value.type)
        assertEquals(WorkoutPhase.Active, r.hub.snapshot.value.phase)
    }

    @Test fun phoneSessionWithoutSamplesIsDiscardedWhenWatchClaimsAnother() = runTest {
        val r = Rig(this); runCurrent()
        r.hub.start(WorkoutType.Walk); runCurrent()
        val phoneId = r.gateway.sent.single().sessionId
        r.gateway.reply(); runCurrent()
        r.gateway.claims.emit(SessionClaim(sessionId = "watch", type = WorkoutType.Run, startMs = 0, phase = WorkoutPhase.Active, activeMs = 0, lastSeq = 0)); runCurrent()
        assertEquals("watch", r.hub.snapshot.value.sessionId)
        assertNull(r.store.summaries[phoneId])
        assertTrue(phoneId !in r.store.openSessionIds())
    }
}
```

- [ ] **Step 2: Run test to verify it fails**

Run: `export JAVA_HOME=$(/usr/libexec/java_home -v 17) && ./gradlew :services:workout:test --tests '*HubWorkoutRecoveryTest*'`
Expected: FAIL — claims ignored, no Syncing/Stopping/Incomplete behaviour, restore missing.

- [ ] **Step 3: Replace `HubWorkoutService.kt` with the complete version**

```kotlin
package com.debasish.livefit.services.workout

import com.debasish.livefit.model.ConfirmationKind
import com.debasish.livefit.model.DeltaAck
import com.debasish.livefit.model.EndReason
import com.debasish.livefit.model.ExerciseError
import com.debasish.livefit.model.ExerciseOp
import com.debasish.livefit.model.ExerciseRequest
import com.debasish.livefit.model.ExerciseResult
import com.debasish.livefit.model.ExerciseState
import com.debasish.livefit.model.ExerciseStateReport
import com.debasish.livefit.model.SessionClaim
import com.debasish.livefit.model.SessionDelta
import com.debasish.livefit.model.SessionStatus
import com.debasish.livefit.model.SessionSummary
import com.debasish.livefit.model.WorkoutPhase
import com.debasish.livefit.model.WorkoutSnapshot
import com.debasish.livefit.model.WorkoutType
import com.debasish.livefit.services.Clock
import com.debasish.livefit.services.ConfirmationOutcome
import com.debasish.livefit.services.ConfirmationService
import com.debasish.livefit.services.SessionStore
import com.debasish.livefit.services.WatchExerciseGateway
import com.debasish.livefit.services.WorkoutService
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
import java.util.UUID

/**
 * Phone hub: owns workout state; the watch executes on Health Services and reports.
 * Spec: §4.4 deltas/acks/adoption, §4.8 exercise control, §4.9 completion rule.
 * The [scope] must be single-threaded (no locks inside).
 */
class HubWorkoutService(
    private val scope: CoroutineScope,
    private val gateway: WatchExerciseGateway,
    private val store: SessionStore,
    private val confirm: ConfirmationService,
    private val clock: Clock,
    private val newId: () -> String = { UUID.randomUUID().toString() },
    private val resultTimeoutMs: Long = 10_000,
    private val incompleteAfterMs: Long = 24 * 60 * 60 * 1000L,
    private val incompleteCheckMs: Long = 60_000,
    /** Phone setting "Use GPS outdoors" per workout type (spec §5.1). */
    private val gpsFor: (WorkoutType) -> Boolean = { false },
) : WorkoutService {

    private val _snapshot = MutableStateFlow(WorkoutSnapshot())
    override val snapshot: StateFlow<WorkoutSnapshot> = _snapshot
    private val _notices = MutableSharedFlow<String>(extraBufferCapacity = 16)
    val notices: SharedFlow<String> = _notices
    private val _hrHistory = MutableStateFlow<List<Int>>(emptyList())
    val hrHistory: StateFlow<List<Int>> = _hrHistory
    private val _finished = MutableSharedFlow<SessionSummary>(extraBufferCapacity = 4)
    val finished: SharedFlow<SessionSummary> = _finished

    private var currentId: String? = null
    private var current: SessionAssembler? = null
    private var claim: SessionClaim? = null
    private var syncUntilSeq: Long? = null
    private var endedByReport = false
    private var endReason: EndReason? = null
    private var endedAtMs: Long? = null
    private var finishedCurrent = false
    private val pendingResults = HashMap<String, CompletableDeferred<ExerciseResult>>()
    private val abandoned = HashSet<String>()

    init {
        scope.launch { gateway.results.collect { onResult(it) } }
        scope.launch { gateway.deltas.collect { onDelta(it) } }
        scope.launch { gateway.claims.collect { onClaim(it) } }
        scope.launch { gateway.stateReports.collect { onStateReport(it) } }
        scope.launch { restore() }
        scope.launch { while (true) { delay(incompleteCheckMs); checkIncomplete() } }
    }

    // ---- Commands -----------------------------------------------------------------------

    override fun start(type: WorkoutType) {
        when (_snapshot.value.phase) {
            WorkoutPhase.Idle, WorkoutPhase.Summary -> Unit
            WorkoutPhase.Syncing -> { notice(SYNCING_NOTICE); return }
            else -> return // duplicate start from another device while starting/running
        }
        val id = newId()
        beginSession(id)
        _snapshot.value = WorkoutSnapshot(sessionId = id, phase = WorkoutPhase.Starting, type = type)
        scope.launch { runStart(id, type, force = false) }
    }

    override fun pause() = scopedOp(ExerciseOp.Pause, setOf(WorkoutPhase.Active), "pause")
    override fun resume() = scopedOp(ExerciseOp.Resume, setOf(WorkoutPhase.Paused), "resume")

    override fun stop() {
        if (_snapshot.value.phase == WorkoutPhase.Starting) {
            val id = currentId ?: return
            scope.launch { abandon(id); sendStop(id) }
            return
        }
        scopedOp(ExerciseOp.Stop, setOf(WorkoutPhase.Active, WorkoutPhase.Paused), "stop")
    }

    override fun dismissSummary() {
        if (_snapshot.value.phase == WorkoutPhase.Summary) resetIdle()
    }

    // ---- Start / ops ----------------------------------------------------------------------

    private fun beginSession(id: String) {
        currentId = id
        current = SessionAssembler(id)
        claim = null
        syncUntilSeq = null
        endedByReport = false
        endReason = null
        endedAtMs = null
        finishedCurrent = false
        _hrHistory.value = emptyList()
    }

    private suspend fun runStart(id: String, type: WorkoutType, force: Boolean) {
        val r = request(id, ExerciseOp.Start(type, force, gpsFor(type)))
        if (currentId != id) return
        if (r == null) { abandon(id); notice("Watch didn't respond"); return }
        if (r.ok) return
        when (val e = r.error) {
            is ExerciseError.OtherAppTracking -> {
                val outcome = confirm.ask(
                    ConfirmationKind.TakeOverWorkout,
                    title = "Take over workout?",
                    message = "Another app is tracking a workout on your watch. Take over?",
                    defaultYes = true,
                )
                if (currentId != id) return
                if (outcome == ConfirmationOutcome.Yes) runStart(id, type, force = true)
                else { resetIdle(); notice(if (outcome == ConfirmationOutcome.No) "Kept the other workout" else "Cancelled") }
            }
            is ExerciseError.PermissionMissing -> { resetIdle(); notice("Watch needs permission: " + e.permissions.joinToString { it.substringAfterLast('.') }) }
            is ExerciseError.WrongSession -> { resetIdle(); notice("Watch is busy with another workout") }
            ExerciseError.SensorUnavailable -> { resetIdle(); notice("Watch sensors unavailable") }
            else -> { resetIdle(); notice("Couldn't start workout") }
        }
    }

    private fun scopedOp(op: ExerciseOp, allowed: Set<WorkoutPhase>, verb: String) {
        val phase = _snapshot.value.phase
        if (phase == WorkoutPhase.Syncing) { notice(SYNCING_NOTICE); return }
        if (phase !in allowed) return
        val id = currentId ?: return
        scope.launch {
            val r = request(id, op)
            when {
                r == null -> notice("Watch didn't respond")
                !r.ok -> notice("Couldn't $verb workout")
            }
        }
    }

    private suspend fun request(sessionId: String, op: ExerciseOp): ExerciseResult? {
        val requestId = newId()
        val waiter = CompletableDeferred<ExerciseResult>()
        pendingResults[requestId] = waiter
        gateway.send(ExerciseRequest(requestId = requestId, sessionId = sessionId, op = op))
        val r = withTimeoutOrNull(resultTimeoutMs) { waiter.await() }
        pendingResults.remove(requestId)
        return r
    }

    private suspend fun onResult(r: ExerciseResult) {
        val waiter = pendingResults.remove(r.requestId)
        if (waiter != null) { waiter.complete(r); return }
        if (r.ok && r.sessionId in abandoned) sendStop(r.sessionId) // late ok: stop only that session
    }

    private suspend fun sendStop(sessionId: String) =
        gateway.send(ExerciseRequest(requestId = newId(), sessionId = sessionId, op = ExerciseOp.Stop))

    private suspend fun abandon(id: String) {
        abandoned += id
        store.discard(id)
        if (currentId == id) resetIdle()
    }

    // ---- Watch data -----------------------------------------------------------------------

    private suspend fun onDelta(d: SessionDelta) {
        if (d.sessionId in abandoned) { gateway.ack(DeltaAck(sessionId = d.sessionId, seq = d.seq)); return }
        if (d.sessionId != currentId || finishedCurrent) adopt(d.sessionId)
        val a = current ?: return
        val storedSeq = store.storeDelta(d) // durable first, then ack
        a.add(d)
        gateway.ack(DeltaAck(sessionId = d.sessionId, seq = storedSeq))
        publish()
    }

    private suspend fun onClaim(c: SessionClaim) {
        if (c.sessionId in abandoned) { sendStop(c.sessionId); return }
        if (c.sessionId != currentId || finishedCurrent) adopt(c.sessionId)
        val a = current ?: return
        claim = c
        if (a.contiguousSeq < c.lastSeq) syncUntilSeq = c.lastSeq
        publish()
    }

    private suspend fun onStateReport(r: ExerciseStateReport) {
        val a = current
        if (r.state == ExerciseState.Idle && r.sessionId != currentId && a != null && !finishedCurrent && isEnded(a) && !a.isComplete) {
            finalize(a, SessionStatus.Incomplete) // watch holds no buffer for our session: data is gone
            notice("Workout saved as incomplete")
            return
        }
        if (r.sessionId != currentId || finishedCurrent) return
        if (r.state == ExerciseState.Ended) {
            endedByReport = true
            endReason = r.endedBy
            if (endedAtMs == null) endedAtMs = clock.nowMs()
            if (r.endedBy != null && r.endedBy != EndReason.User) notice("Workout ended by ${describe(r.endedBy)}")
            publish()
        }
    }

    /** Takes over [id] as the current session (spec §4.4 step 5 and conflict rule step 7). */
    private suspend fun adopt(id: String) {
        val previous = current
        if (previous != null && !finishedCurrent && previous.sessionId != id) {
            if (previous.hasSamples) {
                val summary = previous.summary(if (previous.isComplete) SessionStatus.Complete else SessionStatus.Incomplete, endReason)
                store.finalize(summary)
                _finished.emit(summary)
            } else {
                store.discard(previous.sessionId)
            }
        }
        beginSession(id)
        val a = current!!
        store.deltas(id).forEach { a.add(it) }
    }

    private suspend fun restore() {
        if (currentId != null) return
        val id = store.openSessionIds().lastOrNull() ?: return
        val a = SessionAssembler(id)
        store.deltas(id).forEach { a.add(it) }
        if (a.deltaCount == 0 || currentId != null) return
        beginSession(id)
        current = a
        publish()
    }

    // ---- Publishing & completion -------------------------------------------------------------

    private fun isEnded(a: SessionAssembler) = endedByReport || a.phase() == WorkoutPhase.Stopping

    private suspend fun publish() {
        val a = current ?: return
        if (finishedCurrent) return
        syncUntilSeq?.let { if (a.contiguousSeq >= it) syncUntilSeq = null }
        val ended = isEnded(a)
        if (ended && endedAtMs == null) endedAtMs = clock.nowMs()
        if (a.isComplete && syncUntilSeq == null) { finalize(a, SessionStatus.Complete); return }
        var snap = a.snapshot()
        if (a.deltaCount == 0) snap = snap.copy(type = claim?.type ?: _snapshot.value.type, phase = WorkoutPhase.Starting)
        snap = when {
            syncUntilSeq != null -> snap.copy(phase = WorkoutPhase.Syncing)
            ended -> snap.copy(phase = WorkoutPhase.Stopping)
            else -> snap
        }
        _snapshot.value = snap
        _hrHistory.value = a.hrHistory()
    }

    private suspend fun finalize(a: SessionAssembler, status: SessionStatus) {
        val summary = a.summary(status, endReason)
        store.finalize(summary)
        finishedCurrent = true
        _snapshot.value = a.snapshot().copy(phase = WorkoutPhase.Summary)
        _hrHistory.value = a.hrHistory()
        _finished.emit(summary)
    }

    private suspend fun checkIncomplete() {
        val a = current ?: return
        val ended = endedAtMs ?: return
        if (finishedCurrent || a.isComplete) return
        if (clock.nowMs() - ended >= incompleteAfterMs) {
            finalize(a, SessionStatus.Incomplete)
            notice("Workout saved as incomplete")
        }
    }

    private fun resetIdle() {
        currentId = null
        current = null
        claim = null
        syncUntilSeq = null
        finishedCurrent = false
        _snapshot.value = WorkoutSnapshot()
        _hrHistory.value = emptyList()
    }

    private fun describe(reason: EndReason) = when (reason) {
        EndReason.OtherApp -> "another app"
        EndReason.System -> "the watch"
        EndReason.Error -> "an error"
        EndReason.User -> "you"
    }

    private fun notice(text: String) { _notices.tryEmit(text) }

    companion object { const val SYNCING_NOTICE = "Syncing watch data…" }
}
```

- [ ] **Step 4: Run all workout tests**

Run: `export JAVA_HOME=$(/usr/libexec/java_home -v 17) && ./gradlew :services:workout:test`
Expected: PASS — HubWorkoutRecoveryTest 9, HubWorkoutStartTest 8, SessionAssemblerTest 8, DefaultWorkoutServiceTest 5.

- [ ] **Step 5: Commit**

```bash
git add services/workout
git commit -m "feat(workout): hub adoption, syncing, completion rule and incomplete sessions"
```

---

### Task 8: Simulated watch gateway (demo bindings)

Lets the phone run end-to-end without a watch (demo / UI work) through the same hub code. Replaces the phone's use of `FakeMetricsSource` + `DefaultWorkoutService`.

**Files:**
- Create: `services/workout/src/main/kotlin/com/debasish/livefit/services/workout/SimulatedWatchGateway.kt`
- Test: `services/workout/src/test/kotlin/com/debasish/livefit/services/workout/SimulatedWatchGatewayTest.kt`

**Interfaces:**
- Consumes: `WatchExerciseGateway` (Task 6).
- Produces: `class SimulatedWatchGateway(scope: CoroutineScope, clock: Clock, tickMs: Long = 1_000) : WatchExerciseGateway` — answers every request `ok`, emits `Started`/`Paused`/`Resumed`/`Stopped` events and one `Fake`-provenance sample per tick; Stop emits the final delta. Ignores acks.

- [ ] **Step 1: Write the failing test**

```kotlin
package com.debasish.livefit.services.workout

import com.debasish.livefit.confirm.DefaultConfirmationService
import com.debasish.livefit.model.Provenance
import com.debasish.livefit.model.SessionStatus
import com.debasish.livefit.model.WorkoutPhase
import com.debasish.livefit.model.WorkoutType
import com.debasish.livefit.services.Clock
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

@OptIn(ExperimentalCoroutinesApi::class)
class SimulatedWatchGatewayTest {
    @Test fun fullDemoWorkoutCompletesAsFake() = runTest {
        val clock = Clock { testScheduler.currentTime }
        val store = InMemorySessionStore()
        val gateway = SimulatedWatchGateway(backgroundScope, clock)
        val hub = HubWorkoutService(backgroundScope, gateway, store, DefaultConfirmationService(clock), clock)
        runCurrent()
        hub.start(WorkoutType.Run); runCurrent()
        advanceTimeBy(30_000); runCurrent()
        assertEquals(WorkoutPhase.Active, hub.snapshot.value.phase)
        assertTrue(hub.snapshot.value.metrics.heartRate!! > 60)
        assertTrue(hub.snapshot.value.elapsedMs in 28_000..31_000)
        hub.pause(); runCurrent(); advanceTimeBy(10_000); runCurrent()
        assertEquals(WorkoutPhase.Paused, hub.snapshot.value.phase)
        hub.stop(); runCurrent(); advanceTimeBy(2_000); runCurrent()
        assertEquals(WorkoutPhase.Summary, hub.snapshot.value.phase)
        val summary = store.summaries.values.single()
        assertEquals(SessionStatus.Complete, summary.status)
        assertEquals(Provenance.Fake, summary.provenance)
    }
}
```

- [ ] **Step 2: Run test to verify it fails**

Run: `export JAVA_HOME=$(/usr/libexec/java_home -v 17) && ./gradlew :services:workout:test --tests '*SimulatedWatchGatewayTest*'`
Expected: FAIL — `SimulatedWatchGateway` unresolved.

- [ ] **Step 3: Implement** — `SimulatedWatchGateway.kt`:

```kotlin
package com.debasish.livefit.services.workout

import com.debasish.livefit.model.DeltaAck
import com.debasish.livefit.model.EndReason
import com.debasish.livefit.model.ExerciseOp
import com.debasish.livefit.model.ExerciseRequest
import com.debasish.livefit.model.ExerciseResult
import com.debasish.livefit.model.ExerciseState
import com.debasish.livefit.model.ExerciseStateReport
import com.debasish.livefit.model.Provenance
import com.debasish.livefit.model.Sample
import com.debasish.livefit.model.SessionClaim
import com.debasish.livefit.model.SessionDelta
import com.debasish.livefit.model.SessionEvent
import com.debasish.livefit.model.WorkoutType
import com.debasish.livefit.services.Clock
import com.debasish.livefit.services.WatchExerciseGateway
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.launch
import kotlin.math.roundToInt
import kotlin.math.sin
import kotlin.random.Random

/** Demo watch: same protocol as the real one, Fake provenance, plausible HR/cadence curves. */
class SimulatedWatchGateway(
    private val scope: CoroutineScope,
    private val clock: Clock,
    private val tickMs: Long = 1_000,
) : WatchExerciseGateway {
    override val results = MutableSharedFlow<ExerciseResult>(extraBufferCapacity = 16)
    override val stateReports = MutableSharedFlow<ExerciseStateReport>(extraBufferCapacity = 16)
    override val deltas = MutableSharedFlow<SessionDelta>(extraBufferCapacity = 256)
    override val claims = emptyFlow<SessionClaim>()

    private var sessionId: String? = null
    private var seq = 0L
    private var paused = false
    private var ticker: Job? = null
    private var steps = 0.0; private var km = 0.0; private var kcal = 0.0; private var t = 0

    override suspend fun ack(ack: DeltaAck) = Unit

    override suspend fun send(request: ExerciseRequest) {
        when (val op = request.op) {
            is ExerciseOp.Start -> {
                sessionId = request.sessionId; seq = 0; paused = false; steps = 0.0; km = 0.0; kcal = 0.0; t = 0
                reply(request, ExerciseState.Active)
                emit(events = listOf(SessionEvent.Started(clock.nowMs(), op.type)))
                ticker?.cancel()
                ticker = scope.launch { while (true) { delay(tickMs); if (!paused) tick(op.type) } }
            }
            ExerciseOp.Pause -> { paused = true; reply(request, ExerciseState.Paused); emit(events = listOf(SessionEvent.Paused(clock.nowMs()))) }
            ExerciseOp.Resume -> { paused = false; reply(request, ExerciseState.Active); emit(events = listOf(SessionEvent.Resumed(clock.nowMs()))) }
            ExerciseOp.Stop -> {
                ticker?.cancel()
                reply(request, ExerciseState.Ended)
                if (request.sessionId == sessionId) emit(events = listOf(SessionEvent.Stopped(clock.nowMs(), EndReason.User)), final = true)
                sessionId = null
            }
        }
    }

    private suspend fun reply(r: ExerciseRequest, state: ExerciseState) =
        results.emit(ExerciseResult(requestId = r.requestId, sessionId = r.sessionId, ok = true, state = state, activeSessionId = sessionId))

    private suspend fun tick(type: WorkoutType) {
        val (rest, peak, cadence, speed) = when (type) {
            WorkoutType.Run -> listOf(95.0, 158.0, 165.0, 10.2)
            WorkoutType.Cycle -> listOf(90.0, 145.0, 0.0, 21.0)
            else -> listOf(85.0, 118.0, 112.0, 5.4)
        }
        val warm = minOf(1.0, t / 90.0)
        val hr = (rest + (peak - rest) * warm + 4 * sin(t / 9.0) + Random.nextDouble(-2.0, 2.0)).roundToInt()
        val v = (speed * (0.85 + 0.15 * warm)).coerceAtLeast(0.0)
        steps += cadence / 60.0 * tickMs / 1000.0
        km += v * tickMs / 3_600_000.0
        kcal += hr * 0.09 * tickMs / 60_000.0
        t++
        emit(samples = listOf(Sample(clock.nowMs(), hr, steps.toInt(), km, kcal, v)))
    }

    private suspend fun emit(events: List<SessionEvent> = emptyList(), samples: List<Sample> = emptyList(), final: Boolean = false) {
        val id = sessionId ?: return
        deltas.emit(SessionDelta(sessionId = id, seq = seq++, events = events, samples = samples, provenance = Provenance.Fake, final = final))
    }
}
```

- [ ] **Step 4: Run tests to verify they pass**

Run: `export JAVA_HOME=$(/usr/libexec/java_home -v 17) && ./gradlew :services:workout:test`
Expected: PASS.

- [ ] **Step 5: Commit**

```bash
git add services/workout
git commit -m "feat(workout): simulated watch gateway for demo bindings"
```

---

### Task 9: Sync utilities — broadcaster, liveness, back-off, dedup

**Files:**
- Create: `services/sync/src/main/kotlin/com/debasish/livefit/sync/StateBroadcaster.kt`
- Create: `services/sync/src/main/kotlin/com/debasish/livefit/sync/LivenessMonitor.kt`
- Create: `services/sync/src/main/kotlin/com/debasish/livefit/sync/Backoff.kt`
- Create: `services/sync/src/main/kotlin/com/debasish/livefit/sync/CommandDeduper.kt`
- Delete: `services/sync/src/test/kotlin/com/debasish/livefit/sync/ScaffoldTest.kt`
- Test: `services/sync/src/test/kotlin/com/debasish/livefit/sync/SyncUtilitiesTest.kt`

**Interfaces:**
- Produces:
  - `class StateBroadcaster(scope: CoroutineScope, coalesceMs: Long = 100, heartbeatMs: Long = 5_000, send: suspend () -> Unit) { fun markDirty(); fun start(): Job }` — calls `send` ≤ `coalesceMs` after the first `markDirty`, merging bursts, and at least every `heartbeatMs`.
  - `class LivenessMonitor(clock: Clock, timeoutMs: Long = 12_000) { fun onFrame(); fun onTransportDisconnected(); fun isOnline(): Boolean }`
  - `class Backoff(stepsMs: List<Long> = listOf(2_000, 5_000, 10_000, 30_000)) { fun next(): Long; fun reset() }`
  - `class CommandDeduper(capacity: Int = 256) { fun firstTime(id: String): Boolean }`

- [ ] **Step 1: Write the failing test** — `SyncUtilitiesTest.kt`:

```kotlin
package com.debasish.livefit.sync

import com.debasish.livefit.services.Clock
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

@OptIn(ExperimentalCoroutinesApi::class)
class SyncUtilitiesTest {
    @Test fun burstOfChangesIsCoalescedInto100ms() = runTest {
        val sends = mutableListOf<Long>()
        val b = StateBroadcaster(backgroundScope, send = { sends += testScheduler.currentTime })
        b.start(); runCurrent()
        repeat(5) { b.markDirty(); advanceTimeBy(10) }
        advanceTimeBy(100); runCurrent()
        assertEquals(listOf(100L), sends)
    }

    @Test fun heartbeatEvery5sWithoutChanges() = runTest {
        val sends = mutableListOf<Long>()
        val b = StateBroadcaster(backgroundScope, send = { sends += testScheduler.currentTime })
        b.start(); runCurrent()
        advanceTimeBy(10_001); runCurrent()
        assertEquals(listOf(5_000L, 10_000L), sends)
    }

    @Test fun changeAfterHeartbeatStillFastInsideCoalesceWindow() = runTest {
        val sends = mutableListOf<Long>()
        val b = StateBroadcaster(backgroundScope, send = { sends += testScheduler.currentTime })
        b.start(); runCurrent()
        advanceTimeBy(2_000); b.markDirty(); advanceTimeBy(101); runCurrent()
        assertEquals(listOf(2_100L), sends)
    }

    @Test fun pausedSessionWithHeartbeatsNeverLooksOffline() = runTest {
        val clock = Clock { testScheduler.currentTime }
        val m = LivenessMonitor(clock)
        m.onFrame()
        repeat(10) { advanceTimeBy(5_000); assertTrue(m.isOnline()); m.onFrame() }
    }

    @Test fun offlineAfter12sOrImmediatelyOnDisconnect() = runTest {
        val clock = Clock { testScheduler.currentTime }
        val m = LivenessMonitor(clock)
        assertFalse(m.isOnline(), "no frame yet")
        m.onFrame(); advanceTimeBy(11_999); assertTrue(m.isOnline())
        advanceTimeBy(2); assertFalse(m.isOnline())
        m.onFrame(); assertTrue(m.isOnline())
        m.onTransportDisconnected(); assertFalse(m.isOnline())
    }

    @Test fun backoffSteps() {
        val b = Backoff()
        assertEquals(listOf(2_000L, 5_000L, 10_000L, 30_000L, 30_000L), List(5) { b.next() })
        b.reset()
        assertEquals(2_000L, b.next())
    }

    @Test fun deduperRemembersRecentIds() {
        val d = CommandDeduper(capacity = 2)
        assertTrue(d.firstTime("a")); assertFalse(d.firstTime("a"))
        assertTrue(d.firstTime("b")); assertTrue(d.firstTime("c"))
        assertTrue(d.firstTime("a"), "evicted after capacity")
    }
}
```

- [ ] **Step 2: Run test to verify it fails**

Run: `export JAVA_HOME=$(/usr/libexec/java_home -v 17) && ./gradlew :services:sync:test`
Expected: FAIL — unresolved classes.

- [ ] **Step 3: Implement the four classes**

`StateBroadcaster.kt`:

```kotlin
package com.debasish.livefit.sync

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull

/** Push-on-change with coalescing plus a heartbeat (spec §4.3). */
class StateBroadcaster(
    private val scope: CoroutineScope,
    private val coalesceMs: Long = 100,
    private val heartbeatMs: Long = 5_000,
    private val send: suspend () -> Unit,
) {
    private val dirty = Channel<Unit>(Channel.CONFLATED)

    fun markDirty() { dirty.trySend(Unit) }

    fun start(): Job = scope.launch {
        while (isActive) {
            val changed = withTimeoutOrNull(heartbeatMs) { dirty.receive() } != null
            if (changed) {
                delay(coalesceMs)
                dirty.tryReceive() // merge changes that arrived during the window
            }
            send()
        }
    }
}
```

`LivenessMonitor.kt`:

```kotlin
package com.debasish.livefit.sync

import com.debasish.livefit.services.Clock

/** Offline only after [timeoutMs] without a frame or on an explicit transport disconnect (spec §4.3). */
class LivenessMonitor(private val clock: Clock, private val timeoutMs: Long = 12_000) {
    private var lastFrameMs: Long? = null
    private var disconnected = true

    fun onFrame() { lastFrameMs = clock.nowMs(); disconnected = false }
    fun onTransportDisconnected() { disconnected = true }
    fun isOnline(): Boolean = !disconnected && lastFrameMs?.let { clock.nowMs() - it < timeoutMs } == true
}
```

`Backoff.kt`:

```kotlin
package com.debasish.livefit.sync

/** Reconnect delays 2 s → 5 s → 10 s → 30 s, then 30 s forever (spec §5.3). */
class Backoff(private val stepsMs: List<Long> = listOf(2_000, 5_000, 10_000, 30_000)) {
    private var i = 0
    fun next(): Long = stepsMs[minOf(i++, stepsMs.lastIndex)]
    fun reset() { i = 0 }
}
```

`CommandDeduper.kt`:

```kotlin
package com.debasish.livefit.sync

/** Applies each command id once (spec §4.5). Bounded memory. */
class CommandDeduper(private val capacity: Int = 256) {
    private val seen = LinkedHashSet<String>()

    @Synchronized
    fun firstTime(id: String): Boolean {
        if (!seen.add(id)) return false
        if (seen.size > capacity) seen.remove(seen.first())
        return true
    }
}
```

Delete `ScaffoldTest.kt`.

- [ ] **Step 4: Run tests to verify they pass**

Run: `export JAVA_HOME=$(/usr/libexec/java_home -v 17) && ./gradlew :services:sync:test`
Expected: PASS (7 tests).

- [ ] **Step 5: Commit**

```bash
git add services/sync
git commit -m "feat(sync): state broadcaster, liveness monitor, back-off and command dedup"
```

---

### Task 10: Watch delta buffer and session recorder

The watch's temporary durable buffer (spec §4.4): header + unacked deltas on disk, deleted only after the phone acks.

**Files:**
- Modify: `services/sync/build.gradle.kts` (add `api(project(":services:workout"))` — the recorder exposes a `SessionAssembler` for offline display)
- Create: `services/sync/src/main/kotlin/com/debasish/livefit/sync/FileDeltaBuffer.kt`
- Create: `services/sync/src/main/kotlin/com/debasish/livefit/sync/WatchSessionRecorder.kt`
- Test: `services/sync/src/test/kotlin/com/debasish/livefit/sync/WatchSessionRecorderTest.kt`

**Interfaces:**
- Consumes: `Wire`, `SessionDelta`, `DeltaAck`, `SessionClaim`, `SessionEvent`, `Sample`, `Provenance` (Task 2); `SessionAssembler` (Task 5).
- Produces:
  - `@Serializable data class WatchSessionHeader(val sessionId: String, val type: WorkoutType, val startMs: Long, val lastSeq: Long)`
  - `class FileDeltaBuffer(dir: File) { fun readHeader(): WatchSessionHeader?; fun writeHeader(h: WatchSessionHeader); fun put(d: SessionDelta); fun unacked(): List<SessionDelta>; fun ackUpTo(seq: Long); fun clear() }`
  - `class WatchSessionRecorder(buffer: FileDeltaBuffer, provenance: Provenance, send: suspend (SessionDelta) -> Unit)` with `val sessionId: String?`, `val assembler: SessionAssembler?`, `suspend fun begin(sessionId: String, type: WorkoutType, tMs: Long)`, `suspend fun event(e: SessionEvent, final: Boolean = false)`, `suspend fun sample(s: Sample)`, `fun onAck(ack: DeltaAck)`, `suspend fun resendUnacked()`, `fun claim(): SessionClaim?`, `val isFinalized: Boolean`.

- [ ] **Step 1: Write the failing test** — `WatchSessionRecorderTest.kt`:

```kotlin
package com.debasish.livefit.sync

import com.debasish.livefit.model.DeltaAck
import com.debasish.livefit.model.EndReason
import com.debasish.livefit.model.Provenance
import com.debasish.livefit.model.Sample
import com.debasish.livefit.model.SessionDelta
import com.debasish.livefit.model.SessionEvent
import com.debasish.livefit.model.WorkoutPhase
import com.debasish.livefit.model.WorkoutType
import kotlinx.coroutines.test.runTest
import java.io.File
import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class WatchSessionRecorderTest {
    private val live = Provenance.Live("galaxy-watch/health-services")
    private fun tmp(): File = Files.createTempDirectory("lfbuf").toFile()

    @Test fun deltasArePersistedBeforeSendAndPrunedOnAck() = runTest {
        val dir = tmp()
        val sent = mutableListOf<SessionDelta>()
        val rec = WatchSessionRecorder(FileDeltaBuffer(dir), live) { d ->
            assertTrue(File(dir, "d-${d.seq}.json").exists(), "persisted before send")
            sent += d
        }
        rec.begin("s", WorkoutType.Run, 1_000)
        rec.sample(Sample(2_000, hr = 120))
        rec.sample(Sample(3_000, hr = 125))
        assertEquals(listOf(0L, 1L, 2L), sent.map { it.seq })
        rec.onAck(DeltaAck(sessionId = "s", seq = 1))
        assertEquals(listOf(2L), FileDeltaBuffer(dir).unacked().map { it.seq })
    }

    @Test fun ackForAnotherSessionIsIgnored() = runTest {
        val dir = tmp()
        val rec = WatchSessionRecorder(FileDeltaBuffer(dir), live) {}
        rec.begin("s", WorkoutType.Walk, 0)
        rec.onAck(DeltaAck(sessionId = "other", seq = 0))
        assertEquals(1, FileDeltaBuffer(dir).unacked().size)
    }

    @Test fun finalAckClearsTheBuffer() = runTest {
        val dir = tmp()
        val rec = WatchSessionRecorder(FileDeltaBuffer(dir), live) {}
        rec.begin("s", WorkoutType.Walk, 0)
        rec.event(SessionEvent.Stopped(5_000, EndReason.User), final = true)
        assertTrue(rec.isFinalized)
        rec.onAck(DeltaAck(sessionId = "s", seq = 1))
        assertNull(FileDeltaBuffer(dir).readHeader())
        assertNull(rec.sessionId)
    }

    @Test fun survivesProcessRestartAndResendsUnacked() = runTest {
        val dir = tmp()
        WatchSessionRecorder(FileDeltaBuffer(dir), live) { error("phone unreachable") }.apply {
            begin("s", WorkoutType.Walk, 0)
            sample(Sample(1_000, hr = 100))
        }
        val resent = mutableListOf<Long>()
        val rec = WatchSessionRecorder(FileDeltaBuffer(dir), live) { resent += it.seq }
        assertEquals("s", rec.sessionId)
        rec.resendUnacked()
        assertEquals(listOf(0L, 1L), resent)
        rec.sample(Sample(2_000, hr = 101))
        assertEquals(2L, resent.last(), "seq continues after restart")
    }

    @Test fun claimDescribesTheHeldSession() = runTest {
        val rec = WatchSessionRecorder(FileDeltaBuffer(tmp()), live) {}
        rec.begin("s", WorkoutType.Run, 0)
        rec.sample(Sample(10_000, hr = 130))
        rec.event(SessionEvent.Paused(10_000))
        val c = rec.claim()!!
        assertEquals("s", c.sessionId)
        assertEquals(WorkoutType.Run, c.type)
        assertEquals(WorkoutPhase.Paused, c.phase)
        assertEquals(10_000, c.activeMs)
        assertEquals(2, c.lastSeq)
    }
}
```

- [ ] **Step 2: Run test to verify it fails**

Run: `export JAVA_HOME=$(/usr/libexec/java_home -v 17) && ./gradlew :services:sync:test --tests '*WatchSessionRecorderTest*'`
Expected: FAIL — unresolved classes.

- [ ] **Step 3: Add dependency and serialization plugin** — `services/sync/build.gradle.kts`:

```kotlin
plugins {
    id("org.jetbrains.kotlin.jvm")
    id("org.jetbrains.kotlin.plugin.serialization")
}
```
and in `dependencies`:
```kotlin
    api(project(":services:workout"))
```

- [ ] **Step 4: Implement `FileDeltaBuffer.kt`**

```kotlin
package com.debasish.livefit.sync

import com.debasish.livefit.model.SessionDelta
import com.debasish.livefit.model.Wire
import com.debasish.livefit.model.WorkoutType
import kotlinx.serialization.Serializable
import java.io.File

@Serializable
data class WatchSessionHeader(val sessionId: String, val type: WorkoutType, val startMs: Long, val lastSeq: Long)

/** One directory per watch; files are written atomically (temp + rename). */
class FileDeltaBuffer(private val dir: File) {
    init { dir.mkdirs() }

    private fun atomicWrite(name: String, text: String) {
        val tmp = File(dir, "$name.tmp")
        tmp.writeText(text)
        if (!tmp.renameTo(File(dir, name))) { File(dir, name).delete(); tmp.renameTo(File(dir, name)) }
    }

    fun readHeader(): WatchSessionHeader? =
        File(dir, HEADER).takeIf { it.exists() }?.let { runCatching { Wire.decode<WatchSessionHeader>(it.readText()) }.getOrNull() }

    fun writeHeader(h: WatchSessionHeader) = atomicWrite(HEADER, Wire.encode(h))

    fun put(d: SessionDelta) = atomicWrite("d-${d.seq}.json", Wire.encode(d))

    fun unacked(): List<SessionDelta> =
        (dir.listFiles { f -> f.name.startsWith("d-") && f.name.endsWith(".json") } ?: emptyArray())
            .mapNotNull { runCatching { Wire.decode<SessionDelta>(it.readText()) }.getOrNull() }
            .sortedBy { it.seq }

    fun ackUpTo(seq: Long) {
        dir.listFiles { f -> f.name.startsWith("d-") && f.name.endsWith(".json") }?.forEach { f ->
            val s = f.name.removePrefix("d-").removeSuffix(".json").toLongOrNull()
            if (s != null && s <= seq) f.delete()
        }
    }

    fun clear() { dir.listFiles()?.forEach { it.delete() } }

    private companion object { const val HEADER = "header.json" }
}
```

- [ ] **Step 5: Implement `WatchSessionRecorder.kt`**

```kotlin
package com.debasish.livefit.sync

import com.debasish.livefit.model.DeltaAck
import com.debasish.livefit.model.Provenance
import com.debasish.livefit.model.Sample
import com.debasish.livefit.model.SessionClaim
import com.debasish.livefit.model.SessionDelta
import com.debasish.livefit.model.SessionEvent
import com.debasish.livefit.model.WorkoutType
import com.debasish.livefit.services.workout.SessionAssembler

/**
 * Watch side of spec §4.4: every delta is persisted before it is sent; deleted only when the phone
 * acks it; the whole buffer is cleared once the final delta is acked. Keeps an in-memory
 * [SessionAssembler] of the session so the watch can show it while the phone is offline.
 */
class WatchSessionRecorder(
    private val buffer: FileDeltaBuffer,
    private val provenance: Provenance,
    private val send: suspend (SessionDelta) -> Unit,
) {
    private var header: WatchSessionHeader? = buffer.readHeader()
    private var nextSeq: Long = (header?.lastSeq ?: -1) + 1
    private var finalSeq: Long? = buffer.unacked().lastOrNull { it.final }?.seq

    var assembler: SessionAssembler? = header?.let { h -> SessionAssembler(h.sessionId).also { a -> buffer.unacked().forEach { a.add(it) } } }
        private set

    val sessionId: String? get() = header?.sessionId
    val isFinalized: Boolean get() = finalSeq != null

    suspend fun begin(sessionId: String, type: WorkoutType, tMs: Long) {
        buffer.clear()
        header = WatchSessionHeader(sessionId, type, tMs, lastSeq = -1).also { buffer.writeHeader(it) }
        nextSeq = 0
        finalSeq = null
        assembler = SessionAssembler(sessionId)
        record(listOf(SessionEvent.Started(tMs, type)), emptyList(), final = false)
    }

    suspend fun event(e: SessionEvent, final: Boolean = false) = record(listOf(e), emptyList(), final)
    suspend fun sample(s: Sample) = record(emptyList(), listOf(s), final = false)

    private suspend fun record(events: List<SessionEvent>, samples: List<Sample>, final: Boolean) {
        val h = header ?: return
        val d = SessionDelta(sessionId = h.sessionId, seq = nextSeq++, events = events, samples = samples, provenance = provenance, final = final)
        buffer.put(d)
        header = h.copy(lastSeq = d.seq).also { buffer.writeHeader(it) }
        if (final) finalSeq = d.seq
        assembler?.add(d)
        runCatching { send(d) } // unreachable phone: stays buffered, resent later
    }

    fun onAck(ack: DeltaAck) {
        if (ack.sessionId != sessionId) return
        buffer.ackUpTo(ack.seq)
        val f = finalSeq
        if (f != null && ack.seq >= f) {
            buffer.clear()
            header = null
            assembler = null
            finalSeq = null
        }
    }

    suspend fun resendUnacked() {
        for (d in buffer.unacked()) runCatching { send(d) }
    }

    fun claim(): SessionClaim? {
        val h = header ?: return null
        val a = assembler
        return SessionClaim(
            sessionId = h.sessionId,
            type = h.type,
            startMs = h.startMs,
            phase = a?.phase() ?: com.debasish.livefit.model.WorkoutPhase.Active,
            activeMs = a?.activeMs() ?: 0,
            lastSeq = h.lastSeq,
        )
    }
}
```

- [ ] **Step 6: Run tests to verify they pass**

Run: `export JAVA_HOME=$(/usr/libexec/java_home -v 17) && ./gradlew :services:sync:test`
Expected: PASS (12 tests).

- [ ] **Step 7: Commit**

```bash
git add services/sync
git commit -m "feat(sync): watch file delta buffer and session recorder"
```

---
## Phase 1 — Phone platform

### Task 11: History store (Room)

**Files:**
- Modify: `core/services/src/main/kotlin/com/debasish/livefit/services/Services.kt` (append `HistoryStore`)
- Modify: `services/history/build.gradle.kts` (add `implementation(project(":core:model"))` is transitive via `:core:services`; nothing else)
- Create: `services/history/src/main/kotlin/com/debasish/livefit/history/Entities.kt`
- Create: `services/history/src/main/kotlin/com/debasish/livefit/history/HistoryDao.kt`
- Create: `services/history/src/main/kotlin/com/debasish/livefit/history/HistoryDatabase.kt`
- Create: `services/history/src/main/kotlin/com/debasish/livefit/history/RoomSessionStore.kt`
- Test: `services/history/src/test/kotlin/com/debasish/livefit/history/RoomSessionStoreTest.kt`

**Interfaces:**
- Consumes: `SessionStore` (Task 6), `Wire`, `SessionDelta`, `SessionSummary`, `Sample`, `Provenance` (Task 2).
- Produces:
  - `interface HistoryStore : SessionStore { val sessions: Flow<List<SessionSummary>>; suspend fun samples(sessionId: String): List<Sample>; suspend fun clearAll() }`
  - `class RoomSessionStore(db: HistoryDatabase) : HistoryStore`; `HistoryDatabase.create(context: Context, inMemory: Boolean = false): HistoryDatabase`

- [ ] **Step 1: Append the interface to `Services.kt`**

```kotlin
/** Phone history: finished and open sessions plus 1 Hz samples (spec §5.6). */
interface HistoryStore : SessionStore {
    /** Finalized sessions, newest first (Complete and Incomplete; Demo = Fake provenance). */
    val sessions: Flow<List<com.debasish.livefit.model.SessionSummary>>
    suspend fun samples(sessionId: String): List<com.debasish.livefit.model.Sample>
    suspend fun clearAll()
}
```

- [ ] **Step 2: Write the failing test** — `RoomSessionStoreTest.kt` (Robolectric, JVM):

```kotlin
package com.debasish.livefit.history

import androidx.test.core.app.ApplicationProvider
import com.debasish.livefit.model.EndReason
import com.debasish.livefit.model.Provenance
import com.debasish.livefit.model.Sample
import com.debasish.livefit.model.SessionDelta
import com.debasish.livefit.model.SessionEvent
import com.debasish.livefit.model.SessionStatus
import com.debasish.livefit.model.SessionSummary
import com.debasish.livefit.model.WorkoutType
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class RoomSessionStoreTest {
    private val live = Provenance.Live("galaxy-watch/health-services")
    private fun store() = RoomSessionStore(HistoryDatabase.create(ApplicationProvider.getApplicationContext(), inMemory = true))
    private fun d(seq: Long, samples: List<Sample> = emptyList(), events: List<SessionEvent> = emptyList()) =
        SessionDelta(sessionId = "s", seq = seq, events = events, samples = samples, provenance = live)

    @Test fun storeDeltaIsIdempotentAndReturnsContiguousSeq() = runTest {
        val s = store()
        assertEquals(0L, s.storeDelta(d(0, events = listOf(SessionEvent.Started(0, WorkoutType.Run)))))
        assertEquals(0L, s.storeDelta(d(2)))
        assertEquals(0L, s.storeDelta(d(2)))
        assertEquals(2L, s.storeDelta(d(1)))
        assertEquals(listOf(0L, 1L, 2L), s.deltas("s").map { it.seq })
        assertEquals(listOf("s"), s.openSessionIds())
    }

    @Test fun samplesAreExtractedForCharts() = runTest {
        val s = store()
        s.storeDelta(d(0, samples = listOf(Sample(1_000, hr = 100), Sample(2_000, hr = 110))))
        assertEquals(listOf(100, 110), s.samples("s").map { it.hr })
    }

    @Test fun finalizeMovesSessionToHistory() = runTest {
        val s = store()
        s.storeDelta(d(0))
        s.finalize(SessionSummary(id = "s", type = WorkoutType.Run, startMs = 0, endMs = 9_000, activeMs = 9_000, provenance = live,
            status = SessionStatus.Complete, endReason = EndReason.User))
        assertTrue(s.openSessionIds().isEmpty())
        assertEquals(SessionStatus.Complete, s.sessions.first().single().status)
    }

    @Test fun discardAndClearAll() = runTest {
        val s = store()
        s.storeDelta(d(0)); s.discard("s")
        assertTrue(s.deltas("s").isEmpty())
        s.storeDelta(d(0)); s.clearAll()
        assertTrue(s.openSessionIds().isEmpty())
    }
}
```

- [ ] **Step 3: Run test to verify it fails**

Run: `export JAVA_HOME=$(/usr/libexec/java_home -v 17) && ./gradlew :services:history:testDebugUnitTest`
Expected: FAIL — unresolved `RoomSessionStore`, `HistoryDatabase`.

- [ ] **Step 4: Implement entities, DAO, database**

`Entities.kt`:

```kotlin
package com.debasish.livefit.history

import androidx.room.Entity
import androidx.room.Index

@Entity(tableName = "session", primaryKeys = ["id"])
data class SessionEntity(
    val id: String,
    /** JSON of SessionSummary once finalized; null while open. */
    val summaryJson: String?,
    val status: String,
    val startMs: Long,
    val createdAtMs: Long,
)

@Entity(tableName = "delta", primaryKeys = ["sessionId", "seq"])
data class DeltaEntity(val sessionId: String, val seq: Long, val json: String)

@Entity(tableName = "sample", primaryKeys = ["sessionId", "tMs"], indices = [Index("sessionId")])
data class SampleEntity(
    val sessionId: String,
    val tMs: Long,
    val hr: Int?,
    val steps: Int,
    val distanceKm: Double,
    val kcal: Double,
    val speedKmh: Double?,
    val provenance: String,
)
```

`HistoryDao.kt`:

```kotlin
package com.debasish.livefit.history

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Transaction
import kotlinx.coroutines.flow.Flow

@Dao
interface HistoryDao {
    @Insert(onConflict = OnConflictStrategy.IGNORE) suspend fun insertDelta(d: DeltaEntity)
    @Insert(onConflict = OnConflictStrategy.IGNORE) suspend fun insertSamples(s: List<SampleEntity>)
    @Insert(onConflict = OnConflictStrategy.IGNORE) suspend fun insertSession(s: SessionEntity)
    @Query("SELECT seq FROM delta WHERE sessionId = :id ORDER BY seq") suspend fun seqs(id: String): List<Long>
    @Query("SELECT json FROM delta WHERE sessionId = :id ORDER BY seq") suspend fun deltaJson(id: String): List<String>
    @Query("SELECT id FROM session WHERE summaryJson IS NULL ORDER BY createdAtMs") suspend fun openIds(): List<String>
    @Query("UPDATE session SET summaryJson = :json, status = :status WHERE id = :id") suspend fun finalize(id: String, json: String, status: String)
    @Query("SELECT summaryJson FROM session WHERE summaryJson IS NOT NULL ORDER BY startMs DESC") fun summaries(): Flow<List<String>>
    @Query("SELECT * FROM sample WHERE sessionId = :id ORDER BY tMs") suspend fun samples(id: String): List<SampleEntity>
    @Query("DELETE FROM delta WHERE sessionId = :id") suspend fun deleteDeltas(id: String)
    @Query("DELETE FROM sample WHERE sessionId = :id") suspend fun deleteSamples(id: String)
    @Query("DELETE FROM session WHERE id = :id") suspend fun deleteSession(id: String)
    @Query("DELETE FROM delta") suspend fun clearDeltas()
    @Query("DELETE FROM sample") suspend fun clearSamples()
    @Query("DELETE FROM session") suspend fun clearSessions()

    @Transaction
    suspend fun storeDelta(session: SessionEntity, delta: DeltaEntity, samples: List<SampleEntity>): List<Long> {
        insertSession(session)
        insertDelta(delta)
        insertSamples(samples)
        return seqs(delta.sessionId)
    }

    @Transaction
    suspend fun discard(id: String) { deleteDeltas(id); deleteSamples(id); deleteSession(id) }

    @Transaction
    suspend fun clearAll() { clearDeltas(); clearSamples(); clearSessions() }
}
```

`HistoryDatabase.kt`:

```kotlin
package com.debasish.livefit.history

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase

@Database(entities = [SessionEntity::class, DeltaEntity::class, SampleEntity::class], version = 1, exportSchema = false)
abstract class HistoryDatabase : RoomDatabase() {
    abstract fun dao(): HistoryDao

    companion object {
        fun create(context: Context, inMemory: Boolean = false): HistoryDatabase =
            (if (inMemory) Room.inMemoryDatabaseBuilder(context, HistoryDatabase::class.java).allowMainThreadQueries()
            else Room.databaseBuilder(context, HistoryDatabase::class.java, "livefit-history.db")).build()
    }
}
```

- [ ] **Step 5: Implement `RoomSessionStore.kt`**

```kotlin
package com.debasish.livefit.history

import com.debasish.livefit.model.Provenance
import com.debasish.livefit.model.Sample
import com.debasish.livefit.model.SessionDelta
import com.debasish.livefit.model.SessionStatus
import com.debasish.livefit.model.SessionSummary
import com.debasish.livefit.model.Wire
import com.debasish.livefit.services.HistoryStore
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

class RoomSessionStore(private val db: HistoryDatabase, private val now: () -> Long = System::currentTimeMillis) : HistoryStore {
    private val dao = db.dao()

    override suspend fun storeDelta(delta: SessionDelta): Long {
        val prov = Wire.encode<Provenance>(delta.provenance)
        val seqs = dao.storeDelta(
            session = SessionEntity(delta.sessionId, summaryJson = null, status = SessionStatus.Active.name,
                startMs = delta.samples.firstOrNull()?.tMs ?: delta.events.firstOrNull()?.tMs ?: 0, createdAtMs = now()),
            delta = DeltaEntity(delta.sessionId, delta.seq, Wire.encode(delta)),
            samples = delta.samples.map { SampleEntity(delta.sessionId, it.tMs, it.hr, it.stepsTotal, it.distanceKmTotal, it.kcalTotal, it.speedKmh, prov) },
        )
        var s = -1L
        for (seq in seqs) { if (seq == s + 1) s = seq else if (seq > s + 1) break }
        return s
    }

    override suspend fun deltas(sessionId: String): List<SessionDelta> = dao.deltaJson(sessionId).map { Wire.decode(it) }
    override suspend fun openSessionIds(): List<String> = dao.openIds()
    override suspend fun finalize(summary: SessionSummary) = dao.finalize(summary.id, Wire.encode(summary), summary.status.name)
    override suspend fun discard(sessionId: String) = dao.discard(sessionId)

    override val sessions: Flow<List<SessionSummary>> = dao.summaries().map { list -> list.map { Wire.decode<SessionSummary>(it) } }
    override suspend fun samples(sessionId: String): List<Sample> =
        dao.samples(sessionId).map { Sample(it.tMs, it.hr, it.steps, it.distanceKm, it.kcal, it.speedKmh) }
    override suspend fun clearAll() = dao.clearAll()
}
```

Note: `finalize` on a session that has no `session` row (adopted with zero deltas) is a no-op by design — the hub only finalizes sessions with deltas.

- [ ] **Step 6: Run tests to verify they pass**

Run: `export JAVA_HOME=$(/usr/libexec/java_home -v 17) && ./gradlew :services:history:testDebugUnitTest`
Expected: PASS (4 tests).

- [ ] **Step 7: Commit**

```bash
git add core/services services/history
git commit -m "feat(history): Room session store with durable deltas and samples"
```

---

### Task 12: Phone service graph, command router and hub foreground service

Rewires the phone around `HubWorkoutService` with demo bindings (simulated watch), adds the command router, push-on-change broadcasting and the foreground service. Later tasks flip individual bindings to Live.

**Files:**
- Modify: `core/services/src/main/kotlin/com/debasish/livefit/services/Services.kt` (replace `GlassesLinkService`, `GlassesEvent`, `WatchLinkService`, `VoiceService`)
- Create: `services/sync/src/main/kotlin/com/debasish/livefit/sync/HubCommandRouter.kt`
- Test: `services/sync/src/test/kotlin/com/debasish/livefit/sync/HubCommandRouterTest.kt`
- Modify: `services/glasses-link/src/main/kotlin/com/debasish/livefit/services/glasses/FakeGlassesLink.kt`, `CxrGlassesLink.kt` (signature only)
- Modify: `services/watch-link/src/main/kotlin/com/debasish/livefit/services/watch/FakeWatchLink.kt`, `DataLayerWatchLink.kt` (signature only)
- Modify: `services/voice/src/main/kotlin/com/debasish/livefit/services/voice/FakeVoiceService.kt`
- Rewrite: `phone/src/main/java/com/debasish/livefit/phone/ServiceGraph.kt`
- Modify: `phone/src/main/java/com/debasish/livefit/phone/LiveFitApp.kt`, `phone/build.gradle.kts`, `phone/src/main/AndroidManifest.xml`
- Create: `phone/src/main/java/com/debasish/livefit/phone/LiveFitHubService.kt`
- Modify: `phone/src/main/java/com/debasish/livefit/phone/ui/devices/DeviceScreen.kt` (preview reads `StateFrame`)
- Modify: `phone/src/main/java/com/debasish/livefit/phone/SettingsStore.kt` (new settings)

**Interfaces:**
- Consumes: `HubWorkoutService`, `SimulatedWatchGateway`, `InMemorySessionStore` (Tasks 6–8), `RoomSessionStore` (Task 11), `StateBroadcaster`, `CommandDeduper` (Task 9), `DefaultConfirmationService` (Task 3).
- Produces:
  - `interface GlassesLinkService { val status: StateFlow<DeviceStatus>; val events: Flow<GlassesEvent>; fun connect() {}; suspend fun push(frame: StateFrame); suspend fun pushSettings(frame: HudSettingsFrame) }`
  - `sealed interface GlassesEvent { data object Listen; data class Audio(val pcm: ByteArray); data object ListenEnd; data class Issue(val envelope: CommandEnvelope); data class Outdated(val version: Int?) }`
  - `interface WatchLinkService { val status: StateFlow<DeviceStatus>; val commands: Flow<CommandEnvelope>; suspend fun push(frame: StateFrame) }`
  - `interface VoiceService { val state: StateFlow<VoiceState>; fun listen(); fun startExternal(): Boolean; fun feed(pcm: ByteArray); fun endExternal() }` (Fake: `listen()` simulates; external methods no-op returning false)
  - `class HubCommandRouter(workout: WorkoutService, music: MusicService, confirm: ConfirmationService, scope: CoroutineScope, toast: (String) -> Unit, deduper: CommandDeduper = CommandDeduper())` with `fun dispatch(envelope: CommandEnvelope)`, `suspend fun dispatchVoice(command: Command)`, `val outdated: StateFlow<DeviceKind?>`
  - `class ServiceGraph` properties: `workout: HubWorkoutService`, `confirm`, `history: HistoryStore`, `glasses`, `watch`, `music`, `voice`, `settings: SettingsStore`, `router`, `toast: StateFlow<String?>`, `lastFrame: StateFlow<StateFrame?>`, `fun markDirty()`, `fun localCommand(command: Command)` (phone UI → router with a fresh id).
  - `SettingsStore` adds `val voiceLocale: StateFlow<String>` (default `"en-IN"`), `val musicOnStart: StateFlow<MusicOnStart>` (`DontTouch | Resume | PlaySearch`, default `Resume`), `val musicSearch: StateFlow<String>` (default `"workout mix"`), `val pauseMusicOnStop: StateFlow<Boolean>` (true), `val gpsOutdoors: StateFlow<Boolean>` (true) and matching setters.

- [ ] **Step 1: Write the failing router test** — `HubCommandRouterTest.kt` (separate fakes, because `WorkoutService.pause()` and `MusicService.pause()` clash in one class):

```kotlin
package com.debasish.livefit.sync

import com.debasish.livefit.model.Command
import com.debasish.livefit.model.CommandEnvelope
import com.debasish.livefit.model.Confirmation
import com.debasish.livefit.model.ConfirmationKind
import com.debasish.livefit.model.DeviceKind
import com.debasish.livefit.model.NowPlaying
import com.debasish.livefit.model.WorkoutSnapshot
import com.debasish.livefit.model.WorkoutType
import com.debasish.livefit.services.ConfirmationOutcome
import com.debasish.livefit.services.ConfirmationService
import com.debasish.livefit.services.MusicService
import com.debasish.livefit.services.WorkoutService
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

@OptIn(ExperimentalCoroutinesApi::class)
class HubCommandRouterTest {
    private val calls = mutableListOf<String>()
    private var askOutcome = ConfirmationOutcome.Yes

    private val workout = object : WorkoutService {
        override val snapshot = MutableStateFlow(WorkoutSnapshot())
        override fun start(type: WorkoutType) { calls += "start:$type" }
        override fun pause() { calls += "pauseWorkout" }
        override fun resume() { calls += "resumeWorkout" }
        override fun stop() { calls += "stopWorkout" }
        override fun dismissSummary() { calls += "dismiss" }
    }
    private val music = object : MusicService {
        override val nowPlaying = MutableStateFlow<NowPlaying?>(null)
        override val volume = MutableStateFlow(0.5f)
        override fun playPause() { calls += "playPause" }
        override fun play() { calls += "play" }
        override fun pause() { calls += "pauseMusic" }
        override fun next() { calls += "next" }
        override fun previous() { calls += "previous" }
        override fun toggleLike() { calls += "like" }
        override fun setVolume(level: Float) { calls += "vol:${"%.1f".format(level)}" }
    }
    private val confirm = object : ConfirmationService {
        override val pending = MutableStateFlow<Confirmation?>(null)
        override suspend fun ask(kind: ConfirmationKind, title: String, message: String, defaultYes: Boolean): ConfirmationOutcome { calls += "ask:$kind"; return askOutcome }
        override fun answer(confirmationId: String, yes: Boolean): Boolean { calls += "answer:$confirmationId:$yes"; return true }
    }
    private val toasts = mutableListOf<String>()
    private fun TestScope.router() = HubCommandRouter(workout, music, confirm, backgroundScope, toast = { toasts += it })
    private fun env(id: String, c: Command, origin: DeviceKind = DeviceKind.Watch, version: Int = 1) =
        CommandEnvelope(protocolVersion = version, id = id, origin = origin, command = c)

    @Test fun routesEveryCommand() = runTest {
        val r = router()
        listOf(
            Command.StartWorkout(WorkoutType.Run), Command.PauseWorkout, Command.ResumeWorkout, Command.StopWorkout, Command.DismissSummary,
            Command.PlayPause, Command.PlayMusic, Command.PauseMusic, Command.NextTrack, Command.PreviousTrack, Command.LikeTrack,
            Command.Volume(up = true), Command.SetVolume(0.2f), Command.Answer("c1", yes = false),
        ).forEachIndexed { i, c -> r.dispatch(env("id$i", c)) }
        runCurrent()
        assertEquals(listOf("start:Run", "pauseWorkout", "resumeWorkout", "stopWorkout", "dismiss", "playPause", "play", "pauseMusic",
            "next", "previous", "like", "vol:0.6", "vol:0.2", "answer:c1:false"), calls)
    }

    @Test fun duplicateIdsAreAppliedOnce() = runTest {
        val r = router()
        r.dispatch(env("same", Command.NextTrack)); r.dispatch(env("same", Command.NextTrack)); runCurrent()
        assertEquals(listOf("next"), calls)
    }

    @Test fun versionMismatchIsIgnoredAndFlagged() = runTest {
        val r = router()
        r.dispatch(env("x", Command.NextTrack, origin = DeviceKind.Glasses, version = 99)); runCurrent()
        assertTrue(calls.isEmpty())
        assertEquals(DeviceKind.Glasses, r.outdated.value)
        assertTrue(toasts.single().contains("Update LiveFit on your glasses"))
    }

    @Test fun voiceStopAsksFirst() = runTest {
        val r = router()
        askOutcome = ConfirmationOutcome.No
        r.dispatchVoice(Command.StopWorkout); runCurrent()
        assertEquals(listOf("ask:StopWorkoutByVoice"), calls)
        askOutcome = ConfirmationOutcome.Yes
        r.dispatchVoice(Command.StopWorkout); runCurrent()
        assertEquals("stopWorkout", calls.last())
    }
}
```


- [ ] **Step 2: Run test to verify it fails**

Run: `export JAVA_HOME=$(/usr/libexec/java_home -v 17) && ./gradlew :services:sync:test --tests '*HubCommandRouterTest*'`
Expected: FAIL — `HubCommandRouter` unresolved.

- [ ] **Step 3: Implement `HubCommandRouter.kt`**

```kotlin
package com.debasish.livefit.sync

import com.debasish.livefit.model.Command
import com.debasish.livefit.model.CommandEnvelope
import com.debasish.livefit.model.ConfirmationKind
import com.debasish.livefit.model.DeviceKind
import com.debasish.livefit.model.PROTOCOL_VERSION
import com.debasish.livefit.services.ConfirmationOutcome
import com.debasish.livefit.services.ConfirmationService
import com.debasish.livefit.services.MusicService
import com.debasish.livefit.services.WorkoutService
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch

/** Single entry point for commands from any device (spec §4.5, §4.7). */
class HubCommandRouter(
    private val workout: WorkoutService,
    private val music: MusicService,
    private val confirm: ConfirmationService,
    private val scope: CoroutineScope,
    private val toast: (String) -> Unit,
    private val deduper: CommandDeduper = CommandDeduper(),
) {
    private val _outdated = MutableStateFlow<DeviceKind?>(null)
    val outdated: StateFlow<DeviceKind?> = _outdated

    fun dispatch(envelope: CommandEnvelope) {
        if (envelope.protocolVersion != PROTOCOL_VERSION) {
            _outdated.value = envelope.origin
            toast("Update LiveFit on your ${envelope.origin.name.lowercase()}")
            return
        }
        if (!deduper.firstTime(envelope.id)) return
        apply(envelope.command)
    }

    /** Voice path: destructive commands are confirmed on all devices first. */
    suspend fun dispatchVoice(command: Command) {
        if (command == Command.StopWorkout) {
            val o = confirm.ask(ConfirmationKind.StopWorkoutByVoice, "End workout?", "You said stop. End the workout?", defaultYes = true)
            if (o != ConfirmationOutcome.Yes) { toast("Cancelled"); return }
        }
        apply(command)
    }

    private fun apply(command: Command) {
        when (command) {
            is Command.StartWorkout -> workout.start(command.type)
            Command.PauseWorkout -> workout.pause()
            Command.ResumeWorkout -> workout.resume()
            Command.StopWorkout -> workout.stop()
            Command.DismissSummary -> workout.dismissSummary()
            Command.PlayPause -> music.playPause()
            Command.PlayMusic -> music.play()
            Command.PauseMusic -> music.pause()
            Command.NextTrack -> music.next()
            Command.PreviousTrack -> music.previous()
            Command.LikeTrack -> music.toggleLike()
            is Command.Volume -> music.setVolume((music.volume.value + if (command.up) 0.1f else -0.1f).coerceIn(0f, 1f))
            is Command.SetVolume -> music.setVolume(command.level.coerceIn(0f, 1f))
            is Command.Answer -> scope.launch { confirm.answer(command.confirmationId, command.yes) }
        }
    }
}
```

- [ ] **Step 4: Run test to verify it passes**

Run: `export JAVA_HOME=$(/usr/libexec/java_home -v 17) && ./gradlew :services:sync:test`
Expected: PASS.

- [ ] **Step 5: Replace link and voice contracts in `Services.kt`** — replace the existing `GlassesLinkService`, `GlassesEvent`, `WatchLinkService` and `VoiceService` declarations with:

```kotlin
interface GlassesLinkService {
    val status: StateFlow<DeviceStatus>
    /** Requests from the glasses: push-to-talk audio and commands. */
    val events: Flow<GlassesEvent>
    /** Opens the link and launches the HUD app on the glasses. No-op for fakes. */
    fun connect() {}
    suspend fun push(frame: com.debasish.livefit.model.StateFrame)
    suspend fun pushSettings(frame: com.debasish.livefit.model.HudSettingsFrame)
}

sealed interface GlassesEvent {
    data object Listen : GlassesEvent
    class Audio(val pcm: ByteArray) : GlassesEvent
    data object ListenEnd : GlassesEvent
    data class Issue(val envelope: com.debasish.livefit.model.CommandEnvelope) : GlassesEvent
    data class Outdated(val version: Int?) : GlassesEvent
}

interface WatchLinkService {
    val status: StateFlow<DeviceStatus>
    /** Commands tapped on the watch. */
    val commands: Flow<com.debasish.livefit.model.CommandEnvelope>
    suspend fun push(frame: com.debasish.livefit.model.StateFrame)
}

interface VoiceService {
    val state: StateFlow<VoiceState>
    /** Push-to-talk with the phone microphone. */
    fun listen()
    /** External audio (glasses): returns false if busy or voice is unavailable. */
    fun startExternal(): Boolean
    fun feed(pcm: ByteArray)
    fun endExternal()
}
```

Remove `val commands: Flow<Command>` from the old `VoiceService` (recognised commands now go straight to the router).

- [ ] **Step 6: Update the fakes to the new contracts**

`FakeGlassesLink.kt` — replace `push` and add `pushSettings`:

```kotlin
    private val _lastFrame = MutableStateFlow<StateFrame?>(null)
    val lastFrame: StateFlow<StateFrame?> = _lastFrame

    override suspend fun push(frame: StateFrame) { _lastFrame.value = frame }
    override suspend fun pushSettings(frame: HudSettingsFrame) = Unit
```
(imports: `com.debasish.livefit.model.StateFrame`, `com.debasish.livefit.model.HudSettingsFrame`; drop `HudFrame`.)

`FakeWatchLink.kt`:

```kotlin
class FakeWatchLink : WatchLinkService {
    override val status: StateFlow<DeviceStatus> =
        MutableStateFlow(DeviceStatus("Galaxy Watch6 Classic", LinkState.Connected, batteryPct = 64, detail = "Demo data"))
    override val commands: Flow<CommandEnvelope> = emptyFlow()
    override suspend fun push(frame: StateFrame) = Unit
}
```

`FakeVoiceService.kt` — keep the scripted `listen()` but deliver through a callback; constructor becomes `FakeVoiceService(scope: CoroutineScope, onCommand: suspend (Command) -> Unit)` and the body of `listen()` calls `CommandParser.parse(...)?.let { onCommand(it) }` instead of emitting to `_commands`; add:

```kotlin
    override fun startExternal(): Boolean = false
    override fun feed(pcm: ByteArray) = Unit
    override fun endExternal() = Unit
```

`CxrGlassesLink.kt` — change `push(frame: HudFrame)` to `push(frame: StateFrame)` sending `Wire.encode(frame)` on `GlassesChannels.STATE`, add `pushSettings` sending `Wire.encode(frame)` on `GlassesChannels.SETTINGS`, and map incoming `GlassesChannels.LISTEN` → `GlassesEvent.Listen`. (Full rewrite in Task 14. The glasses app reads the new channels from Task 19; until then the live HUD shows its demo.)

`DataLayerWatchLink.kt` — change `push(frame: HudFrame)` to `push(frame: StateFrame)` sending `Wire.encode(frame)` on `WatchPaths.STATE`; change `commands` to `Flow<CommandEnvelope>` decoding `WatchPaths.COMMAND` with `Wire.decode<CommandEnvelope>`. (Full rewrite in Task 13.)

- [ ] **Step 7: New settings in `SettingsStore.kt`** — add below the HUD settings:

```kotlin
enum class MusicOnStart { DontTouch, Resume, PlaySearch }

    private fun <T> pref(key: String, default: T, read: (String) -> T?): MutableStateFlow<T> =
        MutableStateFlow(prefs.getString(key, null)?.let(read) ?: default)

    private val _voiceLocale = pref("voiceLocale", "en-IN") { it }
    val voiceLocale: StateFlow<String> = _voiceLocale
    fun setVoiceLocale(v: String) { _voiceLocale.value = v; prefs.edit().putString("voiceLocale", v).apply() }

    private val _musicOnStart = pref("musicOnStart", MusicOnStart.Resume) { runCatching { MusicOnStart.valueOf(it) }.getOrNull() }
    val musicOnStart: StateFlow<MusicOnStart> = _musicOnStart
    fun setMusicOnStart(v: MusicOnStart) { _musicOnStart.value = v; prefs.edit().putString("musicOnStart", v.name).apply() }

    private val _musicSearch = pref("musicSearch", "workout mix") { it }
    val musicSearch: StateFlow<String> = _musicSearch
    fun setMusicSearch(v: String) { _musicSearch.value = v; prefs.edit().putString("musicSearch", v).apply() }

    private val _pauseMusicOnStop = pref("pauseMusicOnStop", true) { it.toBooleanStrictOrNull() }
    val pauseMusicOnStop: StateFlow<Boolean> = _pauseMusicOnStop
    fun setPauseMusicOnStop(v: Boolean) { _pauseMusicOnStop.value = v; prefs.edit().putString("pauseMusicOnStop", v.toString()).apply() }

    private val _gpsOutdoors = pref("gpsOutdoors", true) { it.toBooleanStrictOrNull() }
    val gpsOutdoors: StateFlow<Boolean> = _gpsOutdoors
    fun setGpsOutdoors(v: Boolean) { _gpsOutdoors.value = v; prefs.edit().putString("gpsOutdoors", v.toString()).apply() }
```

(`MusicOnStart` is a top-level enum in the same file. Task 15 moves it to `:services:music` as `WorkoutMusicPolicy.MusicOnStart` — keep the name identical.)

- [ ] **Step 8: Rewrite `ServiceGraph.kt`**

```kotlin
package com.debasish.livefit.phone

import android.content.Context
import android.os.BatteryManager
import com.debasish.livefit.confirm.DefaultConfirmationService
import com.debasish.livefit.history.HistoryDatabase
import com.debasish.livefit.history.RoomSessionStore
import com.debasish.livefit.model.Command
import com.debasish.livefit.model.CommandEnvelope
import com.debasish.livefit.model.DeviceKind
import com.debasish.livefit.model.DeviceState
import com.debasish.livefit.model.Devices
import com.debasish.livefit.model.HudSettingsFrame
import com.debasish.livefit.model.LinkState
import com.debasish.livefit.model.StateFrame
import com.debasish.livefit.services.Clock
import com.debasish.livefit.services.GlassesEvent
import com.debasish.livefit.services.GlassesLinkService
import com.debasish.livefit.services.MusicService
import com.debasish.livefit.services.VoiceService
import com.debasish.livefit.services.WatchExerciseGateway
import com.debasish.livefit.services.WatchLinkService
import com.debasish.livefit.services.glasses.CxrGlassesLink
import com.debasish.livefit.services.glasses.FakeGlassesLink
import com.debasish.livefit.services.music.FakeMusicService
import com.debasish.livefit.services.voice.FakeVoiceService
import com.debasish.livefit.services.watch.FakeWatchLink
import com.debasish.livefit.services.workout.HubWorkoutService
import com.debasish.livefit.services.workout.SimulatedWatchGateway
import com.debasish.livefit.sync.HubCommandRouter
import com.debasish.livefit.sync.StateBroadcaster
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.merge
import kotlinx.coroutines.launch
import java.util.UUID

/** Which services are Live. One place to flip bindings (spec §3.1 principle 2). */
data class Bindings(val liveWatch: Boolean, val liveGlasses: Boolean, val liveMusic: Boolean, val liveVoice: Boolean)

/** The phone hub's wiring; owned by LiveFitApp, kept alive by LiveFitHubService. */
class ServiceGraph(private val app: Context, bindings: Bindings) {
    val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    val clock = Clock { System.currentTimeMillis() }
    val settings = SettingsStore(app)
    val history = RoomSessionStore(HistoryDatabase.create(app))
    val confirm = DefaultConfirmationService(clock)

    val watchGateway: WatchExerciseGateway = SimulatedWatchGateway(scope, clock) // Task 13 binds the Data Layer link when liveWatch
    val watch: WatchLinkService = FakeWatchLink()
    val glasses: GlassesLinkService = if (bindings.liveGlasses) CxrGlassesLink(app) else FakeGlassesLink()
    val music: MusicService = FakeMusicService(scope)                         // Task 15
    val workout = HubWorkoutService(scope, watchGateway, history, confirm, clock,
        gpsFor = { type -> type != com.debasish.livefit.model.WorkoutType.Walk && settings.gpsOutdoors.value })

    private val _toast = MutableStateFlow<String?>(null)
    val toast: StateFlow<String?> = _toast
    val router = HubCommandRouter(workout, music, confirm, scope, toast = ::flash)
    val voice: VoiceService = FakeVoiceService(scope) { router.dispatchVoice(it) } // Task 16

    private val _lastFrame = MutableStateFlow<StateFrame?>(null)
    val lastFrame: StateFlow<StateFrame?> = _lastFrame
    private val broadcaster = StateBroadcaster(scope, send = ::sendFrames)

    fun markDirty() = broadcaster.markDirty()

    /** Phone UI commands go through the same router (dedup id is fresh). */
    fun localCommand(command: Command) =
        router.dispatch(CommandEnvelope(id = UUID.randomUUID().toString(), origin = DeviceKind.Phone, command = command))

    fun start() {
        broadcaster.start()
        // Any state change → push (coalesced).
        scope.launch {
            merge(workout.snapshot, music.nowPlaying, voice.state, confirm.pending, toast, watch.status, glasses.status, router.outdated)
                .collect { markDirty() }
        }
        scope.launch { workout.notices.collect(::flash) }
        scope.launch { watch.commands.collect(router::dispatch) }
        scope.launch {
            glasses.events.collect { e ->
                when (e) {
                    GlassesEvent.Listen -> voice.startExternal()
                    is GlassesEvent.Audio -> voice.feed(e.pcm)
                    GlassesEvent.ListenEnd -> voice.endExternal()
                    is GlassesEvent.Issue -> router.dispatch(e.envelope)
                    is GlassesEvent.Outdated -> flash("Update LiveFit on your glasses")
                }
            }
        }
        // HUD settings: on change and whenever the glasses (re)connect.
        scope.launch {
            combine(settings.hud, glasses.status) { hud, st -> hud to st.link }
                .collect { (hud, link) -> if (link == LinkState.Connected) glasses.pushSettings(HudSettingsFrame(settings = hud)) }
        }
        glasses.connect()
    }

    private fun buildFrame() = StateFrame(
        workout = workout.snapshot.value,
        music = music.nowPlaying.value?.copy(volume = music.volume.value),
        devices = Devices(
            phone = DeviceState(LinkState.Connected, phoneBattery()),
            watch = watch.status.value.let { DeviceState(it.link, it.batteryPct) },
            glasses = glasses.status.value.let { DeviceState(it.link, it.batteryPct) },
        ),
        voice = voice.state.value,
        confirmation = confirm.pending.value,
        toast = toast.value,
        outdated = router.outdated.value,
        sentAtMs = clock.nowMs(),
    )

    private suspend fun sendFrames() {
        val frame = buildFrame()
        _lastFrame.value = frame
        runCatching { glasses.push(frame) }
        runCatching { watch.push(frame) }
    }

    private fun phoneBattery(): Int? =
        app.getSystemService(BatteryManager::class.java)?.getIntProperty(BatteryManager.BATTERY_PROPERTY_CAPACITY)

    private fun flash(text: String) {
        _toast.value = text
        scope.launch { delay(1_500); if (_toast.value == text) _toast.value = null }
    }
}
```

- [ ] **Step 9: `LiveFitApp.kt` and build config**

```kotlin
package com.debasish.livefit.phone

import android.app.Application

class LiveFitApp : Application() {
    val services: ServiceGraph by lazy {
        ServiceGraph(this, Bindings(
            liveWatch = BuildConfig.LIVE_WATCH,
            liveGlasses = BuildConfig.LIVE_GLASSES,
            liveMusic = BuildConfig.LIVE_MUSIC,
            liveVoice = BuildConfig.LIVE_VOICE,
        )).also { it.start() }
    }
}
```

In `phone/build.gradle.kts` `defaultConfig`, replace the `USE_FAKE_SERVICES`, `USE_LIVE_GLASSES`, `USE_LIVE_WATCH_LINK` fields with:

```kotlin
        buildConfigField("boolean", "LIVE_WATCH", "false")
        buildConfigField("boolean", "LIVE_GLASSES", "true")
        buildConfigField("boolean", "LIVE_MUSIC", "false")
        buildConfigField("boolean", "LIVE_VOICE", "false")
```

and add dependencies:

```kotlin
    implementation(project(":services:sync"))
    implementation(project(":services:confirm"))
    implementation(project(":services:history"))
```

Remove `implementation(project(":services:metrics"))` from the phone (the simulated gateway replaces it).

Update the `Context.services` extension at the bottom of `ServiceGraph.kt` (keep it): `val Context.services: ServiceGraph get() = (applicationContext as LiveFitApp).services`.

- [ ] **Step 10: Foreground service** — `LiveFitHubService.kt`:

```kotlin
package com.debasish.livefit.phone

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.IBinder
import androidx.core.app.ServiceCompat
import com.debasish.livefit.model.WorkoutPhase
import com.debasish.livefit.model.formatElapsed
import com.debasish.livefit.phone.ui.AppActivity
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch

/** Keeps the hub (ServiceGraph) alive while a linked device is present or a workout runs (spec §5.2). */
class LiveFitHubService : Service() {
    private var watcher: Job? = null

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        val nm = getSystemService(NotificationManager::class.java)
        nm.createNotificationChannel(NotificationChannel(CHANNEL, "LiveFit hub", NotificationManager.IMPORTANCE_LOW))
        val type = ServiceInfo.FOREGROUND_SERVICE_TYPE_CONNECTED_DEVICE
        runCatching { ServiceCompat.startForeground(this, ID, notification("LiveFit ready"), type) }
            .onFailure { stopSelf(); return } // missing BLUETOOTH_CONNECT: setup wizard grants it
        val graph = services
        watcher = graph.scope.launch {
            graph.workout.snapshot.collect { s ->
                val text = when (s.phase) {
                    WorkoutPhase.Active, WorkoutPhase.Paused ->
                        "${s.displayType.label} · ${formatElapsed(s.elapsedMs)}" + (s.metrics.heartRate?.let { " · ♥ $it" } ?: "")
                    WorkoutPhase.Syncing -> "Syncing watch data…"
                    WorkoutPhase.Stopping -> "Saving workout…"
                    else -> "LiveFit ready"
                }
                nm.notify(ID, notification(text))
            }
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int) = START_STICKY

    override fun onDestroy() { watcher?.cancel(); super.onDestroy() }

    private fun notification(text: String): Notification = Notification.Builder(this, CHANNEL)
        .setContentTitle("Rokid LiveFit")
        .setContentText(text)
        .setSmallIcon(android.R.drawable.ic_menu_compass)
        .setOngoing(true)
        .setContentIntent(PendingIntent.getActivity(this, 0, Intent(this, AppActivity::class.java), PendingIntent.FLAG_IMMUTABLE))
        .build()

    companion object {
        private const val CHANNEL = "hub"
        private const val ID = 7
        fun start(context: Context) = runCatching {
            context.startForegroundService(Intent(context, LiveFitHubService::class.java))
        }
    }
}
```

Manifest (`phone/src/main/AndroidManifest.xml`) — add permissions and the service:

```xml
    <uses-permission android:name="android.permission.FOREGROUND_SERVICE" />
    <uses-permission android:name="android.permission.FOREGROUND_SERVICE_CONNECTED_DEVICE" />
    <uses-permission android:name="android.permission.POST_NOTIFICATIONS" />
```
```xml
        <service
            android:name=".LiveFitHubService"
            android:exported="false"
            android:foregroundServiceType="connectedDevice" />
```

In `AppActivity.onCreate`, after `enableEdgeToEdge()`, add `LiveFitHubService.start(this)`.

- [ ] **Step 11: Make the phone UI compile against the new graph**
  - `DeviceScreen.kt`: replace `services.lastHud` with `services.lastFrame` and `HudFrame` with `StateFrame`; in `HudPreview` read `frame.workout`, `frame.music`.
  - Every `services.dispatch(cmd)` call in the UI → `services.localCommand(cmd)`. Every direct `services.workout.start/pause/resume/stop` or `services.music.*` call from UI → `services.localCommand(Command.…)` (same router path as other devices).
  - `services.voice.listen()` stays.
  - `HomeScreen`, `WorkoutScreen`, `MusicScreen`: `services.toast` and `services.workout.snapshot` are unchanged.
  - Delete the old `GlassesEvent.Listen -> voice.listen()` wiring from `AppActivity` if present.

Run: `export JAVA_HOME=$(/usr/libexec/java_home -v 17) && ./gradlew :phone:assembleDebug :watch:assembleDebug :glasses:assembleDebug`
Expected: BUILD SUCCESSFUL (watch/glasses still use the legacy model types, which remain until Task 26).

- [ ] **Step 12: Device check (simulated watch)**

Run: `PHONE=<phone serial> tools/install-all.sh` then open LiveFit on the phone, start a Run from Home.
Expected: Workout screen shows a ticking timer and heart rate within 2 s; the notification reads "Run · 00:05 · ♥ 1xx"; Stop shows "Saving workout…" briefly then the Summary.

- [ ] **Step 13: Commit**

```bash
git add core/services services phone
git commit -m "feat(phone): hub service graph, command router, push-on-change and foreground service"
```

---

### Task 13: Watch link — phone side (Data Layer)

**Files:**
- Create: `services/watch-link/src/main/kotlin/com/debasish/livefit/services/watch/WatchMessageCodec.kt`
- Test: `services/watch-link/src/test/kotlin/com/debasish/livefit/services/watch/WatchMessageCodecTest.kt`
- Rewrite: `services/watch-link/src/main/kotlin/com/debasish/livefit/services/watch/DataLayerWatchLink.kt`
- Modify: `services/watch-link/build.gradle.kts` (unit tests)
- Rewrite: `phone/src/main/java/com/debasish/livefit/phone/WatchListener.kt`
- Modify: `phone/src/main/AndroidManifest.xml` (listener path prefix `/lf`)
- Modify: `phone/src/main/java/com/debasish/livefit/phone/ServiceGraph.kt` (bind when `liveWatch`)
- Modify: `phone/build.gradle.kts` (`LIVE_WATCH` = `true`)

**Interfaces:**
- Consumes: `WatchExerciseGateway`, `WatchLinkService` (Tasks 6, 12), `Wire`, `WatchPaths` (Task 2).
- Produces:
  - `sealed interface WatchInbound { Delta(d), Result(r), State(r), Claim(c), Cmd(env), Battery(pct), Outdated(version) }`, `object WatchMessageCodec { fun decode(path: String, bytes: ByteArray): WatchInbound? }`
  - `class DataLayerWatchLink(context: Context, scope: CoroutineScope) : WatchLinkService, WatchExerciseGateway` with `fun onMessage(path: String, bytes: ByteArray, sourceNodeId: String)`; companion `@Volatile var instance: DataLayerWatchLink?`.

- [ ] **Step 1: Enable JVM unit tests** — add to `services/watch-link/build.gradle.kts`:

```kotlin
android { sourceSets["test"].java.srcDirs("src/test/kotlin") }
dependencies {
    testImplementation(kotlin("test"))
    testImplementation("junit:junit:4.13.2")
}
```

- [ ] **Step 2: Write the failing codec test**

```kotlin
package com.debasish.livefit.services.watch

import com.debasish.livefit.model.Command
import com.debasish.livefit.model.CommandEnvelope
import com.debasish.livefit.model.DeviceKind
import com.debasish.livefit.model.ExerciseResult
import com.debasish.livefit.model.ExerciseState
import com.debasish.livefit.model.Provenance
import com.debasish.livefit.model.SessionDelta
import com.debasish.livefit.model.WatchPaths
import com.debasish.livefit.model.Wire
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNull

class WatchMessageCodecTest {
    private fun bytes(s: String) = s.toByteArray()

    @Test fun decodesEachPath() {
        assertIs<WatchInbound.Delta>(WatchMessageCodec.decode(WatchPaths.DELTA, bytes(Wire.encode(SessionDelta(sessionId = "s", seq = 0, provenance = Provenance.Fake)))))
        assertIs<WatchInbound.Result>(WatchMessageCodec.decode(WatchPaths.EXERCISE_RES, bytes(Wire.encode(ExerciseResult(requestId = "r", sessionId = "s", ok = true, state = ExerciseState.Active)))))
        assertIs<WatchInbound.Cmd>(WatchMessageCodec.decode(WatchPaths.COMMAND, bytes(Wire.encode(CommandEnvelope(id = "1", origin = DeviceKind.Watch, command = Command.NextTrack)))))
        assertEquals(WatchInbound.Battery(83), WatchMessageCodec.decode(WatchPaths.BATTERY, bytes("83")))
    }

    @Test fun versionMismatchIsReported() {
        val json = Wire.encode(SessionDelta(protocolVersion = 2, sessionId = "s", seq = 0, provenance = Provenance.Fake))
        assertEquals(WatchInbound.Outdated(2), WatchMessageCodec.decode(WatchPaths.DELTA, bytes(json)))
    }

    @Test fun unknownPathOrGarbageIsNull() {
        assertNull(WatchMessageCodec.decode("/other", bytes("{}")))
        assertNull(WatchMessageCodec.decode(WatchPaths.DELTA, bytes("not json")))
    }
}
```

- [ ] **Step 3: Run test to verify it fails**

Run: `export JAVA_HOME=$(/usr/libexec/java_home -v 17) && ./gradlew :services:watch-link:testDebugUnitTest`
Expected: FAIL — `WatchMessageCodec` unresolved.

- [ ] **Step 4: Implement `WatchMessageCodec.kt`**

```kotlin
package com.debasish.livefit.services.watch

import com.debasish.livefit.model.CommandEnvelope
import com.debasish.livefit.model.ExerciseResult
import com.debasish.livefit.model.ExerciseStateReport
import com.debasish.livefit.model.PROTOCOL_VERSION
import com.debasish.livefit.model.SessionClaim
import com.debasish.livefit.model.SessionDelta
import com.debasish.livefit.model.WatchPaths
import com.debasish.livefit.model.Wire

sealed interface WatchInbound {
    data class Delta(val delta: SessionDelta) : WatchInbound
    data class Result(val result: ExerciseResult) : WatchInbound
    data class State(val report: ExerciseStateReport) : WatchInbound
    data class Claim(val claim: SessionClaim) : WatchInbound
    data class Cmd(val envelope: CommandEnvelope) : WatchInbound
    data class Battery(val pct: Int) : WatchInbound
    data class Outdated(val version: Int?) : WatchInbound
}

/** Pure decoding of watch → phone messages; version-checks every JSON message. */
object WatchMessageCodec {
    fun decode(path: String, bytes: ByteArray): WatchInbound? {
        val text = String(bytes)
        if (path == WatchPaths.BATTERY) return text.trim().toIntOrNull()?.let { WatchInbound.Battery(it) }
        if (path !in jsonPaths) return null
        val version = Wire.versionOf(text) ?: return null
        if (version != PROTOCOL_VERSION) return WatchInbound.Outdated(version)
        return runCatching {
            when (path) {
                WatchPaths.DELTA -> WatchInbound.Delta(Wire.decode(text))
                WatchPaths.EXERCISE_RES -> WatchInbound.Result(Wire.decode(text))
                WatchPaths.EXERCISE_STATE -> WatchInbound.State(Wire.decode(text))
                WatchPaths.CLAIM -> WatchInbound.Claim(Wire.decode(text))
                else -> WatchInbound.Cmd(Wire.decode(text))
            }
        }.getOrNull()
    }

    private val jsonPaths = setOf(WatchPaths.DELTA, WatchPaths.EXERCISE_RES, WatchPaths.EXERCISE_STATE, WatchPaths.CLAIM, WatchPaths.COMMAND)
}
```

- [ ] **Step 5: Run test to verify it passes**

Run: `export JAVA_HOME=$(/usr/libexec/java_home -v 17) && ./gradlew :services:watch-link:testDebugUnitTest`
Expected: PASS.

- [ ] **Step 6: Rewrite `DataLayerWatchLink.kt`**

```kotlin
package com.debasish.livefit.services.watch

import android.content.Context
import android.util.Log
import com.debasish.livefit.model.CommandEnvelope
import com.debasish.livefit.model.DeltaAck
import com.debasish.livefit.model.DeviceStatus
import com.debasish.livefit.model.ExerciseRequest
import com.debasish.livefit.model.ExerciseResult
import com.debasish.livefit.model.ExerciseStateReport
import com.debasish.livefit.model.LinkState
import com.debasish.livefit.model.SessionClaim
import com.debasish.livefit.model.SessionDelta
import com.debasish.livefit.model.StateFrame
import com.debasish.livefit.model.WatchPaths
import com.debasish.livefit.model.Wire
import com.debasish.livefit.services.WatchExerciseGateway
import com.debasish.livefit.services.WatchLinkService
import com.google.android.gms.wearable.Wearable
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.tasks.await

/** Phone ↔ watch over the Wearable Data Layer: gateway for the hub + frames + battery. */
class DataLayerWatchLink(context: Context, private val scope: CoroutineScope) : WatchLinkService, WatchExerciseGateway {
    private val app = context.applicationContext
    private val messages = Wearable.getMessageClient(app)
    @Volatile private var nodeId: String? = null

    private val _status = MutableStateFlow(DeviceStatus("Galaxy Watch", LinkState.Connecting))
    override val status: StateFlow<DeviceStatus> = _status
    override val commands = MutableSharedFlow<CommandEnvelope>(extraBufferCapacity = 32)
    override val results = MutableSharedFlow<ExerciseResult>(extraBufferCapacity = 32)
    override val stateReports = MutableSharedFlow<ExerciseStateReport>(extraBufferCapacity = 32)
    override val deltas = MutableSharedFlow<SessionDelta>(extraBufferCapacity = 8_192)
    override val claims = MutableSharedFlow<SessionClaim>(extraBufferCapacity = 16)
    private val _outdated = MutableSharedFlow<Int?>(extraBufferCapacity = 4)
    val outdated: SharedFlow<Int?> = _outdated

    init {
        instance = this
        scope.launch { while (true) { refreshNode(); delay(5_000) } }
        scope.launch { while (true) { requestBattery(); delay(60_000) } }
    }

    private suspend fun refreshNode() {
        val node = runCatching { Wearable.getNodeClient(app).connectedNodes.await() }.getOrNull()?.firstOrNull()
        nodeId = node?.id
        _status.update {
            if (node == null) it.copy(link = LinkState.Disconnected, detail = "Not reachable")
            else it.copy(name = node.displayName.substringBefore(" (").ifBlank { "Galaxy Watch" }, link = LinkState.Connected, detail = null)
        }
    }

    private suspend fun requestBattery() = sendRaw(WatchPaths.BATTERY_REQ, ByteArray(0))

    private suspend fun sendRaw(path: String, bytes: ByteArray) {
        val id = nodeId ?: return
        runCatching { messages.sendMessage(id, path, bytes).await() }.onFailure { Log.w(TAG, "send $path failed", it) }
    }

    override suspend fun send(request: ExerciseRequest) = sendRaw(WatchPaths.EXERCISE_REQ, Wire.encode(request).toByteArray())
    override suspend fun ack(ack: DeltaAck) = sendRaw(WatchPaths.ACK, Wire.encode(ack).toByteArray())
    override suspend fun push(frame: StateFrame) {
        if (_status.value.link == LinkState.Connected) sendRaw(WatchPaths.STATE, Wire.encode(frame).toByteArray())
    }

    /** Called from the phone's WearableListenerService for every /lf message. */
    fun onMessage(path: String, bytes: ByteArray, sourceNodeId: String) {
        nodeId = sourceNodeId
        _status.update { it.copy(link = LinkState.Connected, detail = null) }
        when (val m = WatchMessageCodec.decode(path, bytes)) {
            is WatchInbound.Delta -> deltas.tryEmit(m.delta)
            is WatchInbound.Result -> results.tryEmit(m.result)
            is WatchInbound.State -> stateReports.tryEmit(m.report)
            is WatchInbound.Claim -> claims.tryEmit(m.claim)
            is WatchInbound.Cmd -> commands.tryEmit(m.envelope)
            is WatchInbound.Battery -> _status.update { it.copy(batteryPct = m.pct) }
            is WatchInbound.Outdated -> _outdated.tryEmit(m.version)
            null -> Log.w(TAG, "ignored $path")
        }
    }

    companion object {
        const val TAG = "LiveFitWatchLink"
        @Volatile var instance: DataLayerWatchLink? = null
    }
}
```

- [ ] **Step 7: Rewrite the phone's `WatchListener.kt`**

```kotlin
package com.debasish.livefit.phone

import com.debasish.livefit.services.watch.DataLayerWatchLink
import com.google.android.gms.wearable.MessageEvent
import com.google.android.gms.wearable.WearableListenerService

/** Wakes the phone for watch messages; building the graph starts the hub (spec §5.2). */
class WatchListener : WearableListenerService() {
    override fun onMessageReceived(event: MessageEvent) {
        val graph = (application as LiveFitApp).services
        LiveFitHubService.start(this)
        val link = DataLayerWatchLink.instance ?: return
        graph.scope.launch { link.onMessage(event.path, event.data, event.sourceNodeId) }
    }
}
```
(add `import kotlinx.coroutines.launch`.) In the manifest change the listener `<data android:pathPrefix="/rf" />` to `android:pathPrefix="/lf"`.

- [ ] **Step 8: Bind the live link in `ServiceGraph`** — replace the two watch lines:

```kotlin
    private val dataLayer: DataLayerWatchLink? = if (bindings.liveWatch) DataLayerWatchLink(app, scope) else null
    val watchGateway: WatchExerciseGateway = dataLayer ?: SimulatedWatchGateway(scope, clock)
    val watch: WatchLinkService = dataLayer ?: FakeWatchLink()
```

and in `start()` add:

```kotlin
        dataLayer?.let { link -> scope.launch { link.outdated.collect { flash("Update LiveFit on your watch") } } }
```

Set `buildConfigField("boolean", "LIVE_WATCH", "true")` in `phone/build.gradle.kts`.

- [ ] **Step 9: Build**

Run: `export JAVA_HOME=$(/usr/libexec/java_home -v 17) && ./gradlew :phone:assembleDebug`
Expected: BUILD SUCCESSFUL. (End-to-end check happens after the watch side lands in Task 18.)

- [ ] **Step 10: Commit**

```bash
git add services/watch-link phone
git commit -m "feat(watch-link): Data Layer gateway with codec, acks, battery and versioned messages"
```

---

### Task 14: Glasses link — CXR-L session, background authorization, companion presence

**Files:**
- Create: `services/glasses-link/src/main/kotlin/com/debasish/livefit/services/glasses/GlassesSessionPolicy.kt`
- Test: `services/glasses-link/src/test/kotlin/com/debasish/livefit/services/glasses/GlassesSessionPolicyTest.kt`
- Modify: `services/glasses-link/build.gradle.kts` (unit tests + `:services:sync` dependency for `Backoff`)
- Rewrite: `services/glasses-link/src/main/kotlin/com/debasish/livefit/services/glasses/CxrGlassesLink.kt`
- Create: `services/glasses-link/src/main/kotlin/com/debasish/livefit/services/glasses/AuthActivity.kt`
- Modify: `services/glasses-link/src/main/AndroidManifest.xml`
- Create: `phone/src/main/java/com/debasish/livefit/phone/CompanionPresenceService.kt`
- Create: `phone/src/main/java/com/debasish/livefit/phone/CompanionLinker.kt`
- Modify: `phone/src/main/AndroidManifest.xml`

**Interfaces:**
- Consumes: `Backoff` (Task 9), `GlassesLinkService`, `GlassesEvent` (Task 12), `Wire`, `GlassesChannels`, `CommandEnvelope`.
- Produces:
  - `class GlassesSessionPolicy(backoff: Backoff = Backoff())` with `fun onEvent(e: LinkEvent): List<LinkAction>`; `sealed interface LinkEvent { Started; Paused; Resumed; Closed; ConnectFailed; DevicePresent; DeviceGone; RetryTimer }`, `sealed interface LinkAction { Connect; SendSettings; ScheduleRetry(delayMs: Long); MarkConnected; MarkConnecting; MarkDisconnected }`
  - `class CxrGlassesLink(context: Context, scope: CoroutineScope) : GlassesLinkService` with `fun authorize(activity: Activity, onDone: (Boolean) -> Unit)`, `fun onDevicePresence(present: Boolean)`
  - `class AuthActivity` (transparent; runs authorization then `finish()`), `AuthActivity.launch(context)`
  - `object CompanionLinker { fun associate(activity: Activity, kind: DeviceKind, onResult: (Boolean) -> Unit); fun observePresence(context: Context) }`
  - `class CompanionPresenceService : CompanionDeviceService` → starts `LiveFitHubService` and calls `CxrGlassesLink.onDevicePresence(true)` for the glasses association.

- [ ] **Step 1: Build file** — `services/glasses-link/build.gradle.kts` add:

```kotlin
android { sourceSets["test"].java.srcDirs("src/test/kotlin") }
dependencies {
    implementation(project(":services:sync"))
    testImplementation(kotlin("test"))
    testImplementation("junit:junit:4.13.2")
}
```

- [ ] **Step 2: Write the failing policy test** — `GlassesSessionPolicyTest.kt` (Review Focus #3):

```kotlin
package com.debasish.livefit.services.glasses

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class GlassesSessionPolicyTest {
    @Test fun startedSendsSettingsAndMarksConnected() {
        val p = GlassesSessionPolicy()
        p.onEvent(LinkEvent.DevicePresent)
        assertEquals(listOf(LinkAction.MarkConnected, LinkAction.SendSettings), p.onEvent(LinkEvent.Started))
    }

    /** Screen off / glasses removed → session paused: keep it, no second session. */
    @Test fun pausedSessionIsKeptAndResumeResendsSettings() {
        val p = GlassesSessionPolicy()
        p.onEvent(LinkEvent.DevicePresent); p.onEvent(LinkEvent.Started)
        assertEquals(listOf(LinkAction.MarkConnecting), p.onEvent(LinkEvent.Paused))
        assertEquals(listOf(LinkAction.MarkConnected, LinkAction.SendSettings), p.onEvent(LinkEvent.Resumed))
    }

    @Test fun closedWhilePresentRetriesWithBackoffAndNeverDoubleConnects() {
        val p = GlassesSessionPolicy()
        p.onEvent(LinkEvent.DevicePresent)
        assertEquals(listOf(LinkAction.MarkDisconnected, LinkAction.ScheduleRetry(2_000)), p.onEvent(LinkEvent.Closed))
        assertEquals(listOf(LinkAction.Connect), p.onEvent(LinkEvent.RetryTimer))
        assertTrue(p.onEvent(LinkEvent.RetryTimer).isEmpty(), "connect already in flight")
        assertEquals(listOf(LinkAction.MarkDisconnected, LinkAction.ScheduleRetry(5_000)), p.onEvent(LinkEvent.ConnectFailed))
        p.onEvent(LinkEvent.RetryTimer); p.onEvent(LinkEvent.Started)
        assertEquals(listOf(LinkAction.MarkDisconnected, LinkAction.ScheduleRetry(2_000)), p.onEvent(LinkEvent.Closed), "backoff reset after success")
    }

    @Test fun deviceGoneStopsRetrying() {
        val p = GlassesSessionPolicy()
        p.onEvent(LinkEvent.DevicePresent)
        p.onEvent(LinkEvent.Closed)
        assertEquals(listOf(LinkAction.MarkDisconnected), p.onEvent(LinkEvent.DeviceGone))
        assertTrue(p.onEvent(LinkEvent.RetryTimer).isEmpty())
    }

    @Test fun devicePresentConnectsOnce() {
        val p = GlassesSessionPolicy()
        assertEquals(listOf(LinkAction.MarkConnecting, LinkAction.Connect), p.onEvent(LinkEvent.DevicePresent))
        assertTrue(p.onEvent(LinkEvent.DevicePresent).isEmpty())
    }
}
```

- [ ] **Step 3: Run test to verify it fails**

Run: `export JAVA_HOME=$(/usr/libexec/java_home -v 17) && ./gradlew :services:glasses-link:testDebugUnitTest`
Expected: FAIL — unresolved policy types.

- [ ] **Step 4: Implement `GlassesSessionPolicy.kt`**

```kotlin
package com.debasish.livefit.services.glasses

import com.debasish.livefit.sync.Backoff

sealed interface LinkEvent {
    data object DevicePresent : LinkEvent
    data object DeviceGone : LinkEvent
    data object Started : LinkEvent
    data object Paused : LinkEvent
    data object Resumed : LinkEvent
    data object Closed : LinkEvent
    data object ConnectFailed : LinkEvent
    data object RetryTimer : LinkEvent
}

sealed interface LinkAction {
    data object Connect : LinkAction
    data object SendSettings : LinkAction
    data class ScheduleRetry(val delayMs: Long) : LinkAction
    data object MarkConnected : LinkAction
    data object MarkConnecting : LinkAction
    data object MarkDisconnected : LinkAction
}

/** Pure session lifecycle rules for the CXR-L link (spec §5.3, Review Focus #3). */
class GlassesSessionPolicy(private val backoff: Backoff = Backoff()) {
    private enum class S { Idle, Connecting, Open, Paused, WaitingRetry }
    private var state = S.Idle
    private var present = false

    fun onEvent(e: LinkEvent): List<LinkAction> = when (e) {
        LinkEvent.DevicePresent -> {
            present = true
            if (state == S.Idle || state == S.WaitingRetry) { state = S.Connecting; listOf(LinkAction.MarkConnecting, LinkAction.Connect) } else emptyList()
        }
        LinkEvent.DeviceGone -> { present = false; state = S.Idle; listOf(LinkAction.MarkDisconnected) }
        LinkEvent.Started -> { state = S.Open; backoff.reset(); listOf(LinkAction.MarkConnected, LinkAction.SendSettings) }
        LinkEvent.Paused -> { state = S.Paused; listOf(LinkAction.MarkConnecting) }
        LinkEvent.Resumed -> { state = S.Open; listOf(LinkAction.MarkConnected, LinkAction.SendSettings) }
        LinkEvent.Closed, LinkEvent.ConnectFailed -> {
            if (present) { state = S.WaitingRetry; listOf(LinkAction.MarkDisconnected, LinkAction.ScheduleRetry(backoff.next())) }
            else { state = S.Idle; listOf(LinkAction.MarkDisconnected) }
        }
        LinkEvent.RetryTimer -> if (present && state == S.WaitingRetry) { state = S.Connecting; listOf(LinkAction.Connect) } else emptyList()
    }

    /** Manual connect (app opened / reconnect button) is allowed only when no session is in flight. */
    fun manualConnect(): List<LinkAction> {
        present = true
        return if (state == S.Idle || state == S.WaitingRetry) { state = S.Connecting; listOf(LinkAction.MarkConnecting, LinkAction.Connect) } else emptyList()
    }
}
```

Update the test file's first test to call `p.onEvent(LinkEvent.DevicePresent)` (already present) — `manualConnect()` is exercised in Task 24.

- [ ] **Step 5: Run test to verify it passes**

Run: `export JAVA_HOME=$(/usr/libexec/java_home -v 17) && ./gradlew :services:glasses-link:testDebugUnitTest`
Expected: PASS (5 tests).

- [ ] **Step 6: Rewrite `CxrGlassesLink.kt`**

```kotlin
package com.debasish.livefit.services.glasses

import android.app.Activity
import android.content.Context
import android.util.Base64
import android.util.Log
import com.debasish.livefit.model.CommandEnvelope
import com.debasish.livefit.model.DeviceStatus
import com.debasish.livefit.model.GlassesChannels
import com.debasish.livefit.model.HudSettingsFrame
import com.debasish.livefit.model.LinkState
import com.debasish.livefit.model.PROTOCOL_VERSION
import com.debasish.livefit.model.StateFrame
import com.debasish.livefit.model.Wire
import com.debasish.livefit.services.GlassesEvent
import com.debasish.livefit.services.GlassesLinkService
import com.rokid.cxr.Caps
import com.rokid.cxr.session.AiInterceptMode
import com.rokid.cxr.session.CloseReason
import com.rokid.cxr.session.CxrSession
import com.rokid.cxr.session.CxrSessionManager
import com.rokid.cxr.session.GlassPermission
import com.rokid.cxr.session.ICustomCmdSessionCallback
import com.rokid.cxr.session.ISessionLifecycleCbk
import com.rokid.cxr.session.PausedReason
import com.rokid.cxr.session.SessionConfig
import com.rokid.cxr.session.SessionErrorCode
import com.rokid.cxr.session.SessionTimeouts
import com.rokid.cxr.session.SessionType
import com.rokid.cxr.session.TerminatingReason
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/**
 * Live glasses link over Rokid CXR-L via the Hi Rokid app (spec §2.1, §5.3).
 * Lifecycle decisions come from [GlassesSessionPolicy]; this class only performs actions.
 */
class CxrGlassesLink(context: Context, private val scope: CoroutineScope) : GlassesLinkService {
    private val app = context.applicationContext
    private val manager = CxrSessionManager.getInstance(app)
    private val policy = GlassesSessionPolicy()
    private var session: CxrSession? = null
    private var authorizedThisProcess = false
    @Volatile private var lastSettings: HudSettingsFrame? = null

    private val _status = MutableStateFlow(DeviceStatus("Rokid Glasses", LinkState.Disconnected))
    override val status: StateFlow<DeviceStatus> = _status
    private val _events = MutableSharedFlow<GlassesEvent>(extraBufferCapacity = 256)
    override val events: SharedFlow<GlassesEvent> = _events

    init { instance = this }

    /** Silent when already authorized in Hi Rokid; sets CXR-L's in-memory flags (quirk 2.1.1). */
    fun authorize(activity: Activity, onDone: (Boolean) -> Unit) {
        manager.requestAuthorization(activity, listOf(GlassPermission.MICROPHONE, GlassPermission.DEVICE_MANAGE, GlassPermission.MEDIA)) { r ->
            r.token?.takeIf { r.isSuccess }?.let { app.getSharedPreferences(PREFS, 0).edit().putString(KEY_TOKEN, it).apply() }
            authorizedThisProcess = r.isSuccess
            onDone(r.isSuccess)
        }
    }

    fun onDevicePresence(present: Boolean) = act(policy.onEvent(if (present) LinkEvent.DevicePresent else LinkEvent.DeviceGone))

    override fun connect() = act(policy.manualConnect())

    private fun act(actions: List<LinkAction>) {
        for (a in actions) when (a) {
            LinkAction.Connect -> openSession()
            LinkAction.SendSettings -> lastSettings?.let { scope.launch { pushSettings(it) } }
            is LinkAction.ScheduleRetry -> scope.launch { delay(a.delayMs); act(policy.onEvent(LinkEvent.RetryTimer)) }
            LinkAction.MarkConnected -> _status.update { it.copy(link = LinkState.Connected, detail = null) }
            LinkAction.MarkConnecting -> _status.update { it.copy(link = LinkState.Connecting) }
            LinkAction.MarkDisconnected -> _status.update { it.copy(link = LinkState.Disconnected) }
        }
    }

    private fun openSession() {
        val token = app.getSharedPreferences(PREFS, 0).getString(KEY_TOKEN, null)
        if (token == null || !authorizedThisProcess) {
            // Background authorization: companion apps may start activities (spec §5.3).
            AuthActivity.launch(app)
            _status.update { it.copy(detail = "Authorizing with Hi Rokid") }
            act(policy.onEvent(LinkEvent.ConnectFailed))
            return
        }
        preferGlobalHiRokid()
        session?.close()
        val s = manager.create(
            SessionConfig(
                sessionType = SessionType.CUSTOM_APP,
                glassesPackageName = GLASSES_PKG,
                aiInterceptMode = AiInterceptMode.ALLOW_WITH_PAUSE,
                terminatingGracePeriodMs = 5_000L,
                timeouts = SessionTimeouts(),
                viewData = "",
                viewIconData = "",
                glassesActivityName = "$GLASSES_PKG.MainActivity",
                glassesApkPath = "",
            ),
        )
        s.addLifecycleCallback(lifecycle)
        s.addCustomCmdCallback(object : ICustomCmdSessionCallback {
            override fun onCustomCmdResult(cmd: String, bytes: ByteArray?) = onGlassesMessage(cmd, bytes)
        })
        session = s
        s.connect(token)
    }

    private fun onGlassesMessage(cmd: String, bytes: ByteArray?) {
        val text = bytes?.let { runCatching { Caps.fromBytes(it).at(0).string }.getOrNull() } ?: ""
        when (cmd) {
            GlassesChannels.LISTEN -> _events.tryEmit(GlassesEvent.Listen)
            GlassesChannels.AUDIO -> runCatching { Base64.decode(text, Base64.NO_WRAP) }.getOrNull()?.let { _events.tryEmit(GlassesEvent.Audio(it)) }
            GlassesChannels.LISTEN_END -> _events.tryEmit(GlassesEvent.ListenEnd)
            GlassesChannels.COMMAND -> {
                val v = Wire.versionOf(text)
                if (v != PROTOCOL_VERSION) _events.tryEmit(GlassesEvent.Outdated(v))
                else runCatching { Wire.decode<CommandEnvelope>(text) }.getOrNull()?.let { _events.tryEmit(GlassesEvent.Issue(it)) }
            }
        }
    }

    override suspend fun push(frame: StateFrame) = send(GlassesChannels.STATE, Wire.encode(frame))

    override suspend fun pushSettings(frame: HudSettingsFrame) {
        lastSettings = frame
        send(GlassesChannels.SETTINGS, Wire.encode(frame))
    }

    private fun send(channel: String, json: String) {
        if (_status.value.link != LinkState.Connected) return
        val r = session?.sendCustomCmd(channel, Caps().apply { write(json) }, ByteArray(0)) ?: return
        if (!r.isSuccess) Log.w(TAG, "send $channel failed ${r.code}")
    }

    private val lifecycle = object : ISessionLifecycleCbk {
        override fun onSessionStarted() = act(policy.onEvent(LinkEvent.Started))
        override fun onSessionPaused(reason: PausedReason) = act(policy.onEvent(LinkEvent.Paused))
        override fun onSessionResumed() = act(policy.onEvent(LinkEvent.Resumed))
        override fun onSessionTerminating(reason: TerminatingReason, graceMs: Long) = Unit
        override fun onSessionClosed(reason: CloseReason) { session = null; act(policy.onEvent(LinkEvent.Closed)) }
        override fun onConnectResult(ok: Boolean, code: SessionErrorCode?) { if (!ok) { session = null; act(policy.onEvent(LinkEvent.ConnectFailed)) } }
    }

    /** Quirk 2.1.1: after a restart CXR-L would bind the China package. */
    private fun preferGlobalHiRokid() {
        val global = runCatching { app.packageManager.getPackageInfo("com.rokid.sprite.global.aiapp", 0) }.isSuccess
        if (!global) return
        runCatching {
            Class.forName("com.rokid.sprite.aiapp.externalapp.auth.AuthorizationHelper")
                .getDeclaredField("a").apply { isAccessible = true }.setBoolean(null, true)
        }.onFailure { Log.w(TAG, "could not force global Hi Rokid", it) }
    }

    internal fun markAuthorized() { authorizedThisProcess = true; act(policy.manualConnect()) }

    companion object {
        const val TAG = "LiveFitGlassesLink"
        const val GLASSES_PKG = "com.debasish.livefit.glasses"
        internal const val PREFS = "rokid"
        internal const val KEY_TOKEN = "token"
        @Volatile var instance: CxrGlassesLink? = null
    }
}
```

- [ ] **Step 7: `AuthActivity.kt`** (transparent, finishes immediately)

```kotlin
package com.debasish.livefit.services.glasses

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.os.Bundle

/** Runs Hi Rokid authorization in-process (CXR-L needs an Activity) and closes itself. */
class AuthActivity : Activity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val link = CxrGlassesLink.instance ?: return finish()
        link.authorize(this) { ok ->
            if (ok) link.markAuthorized()
            finish()
        }
    }

    @Deprecated("Deprecated in Java")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        CxrGlassesLink.instance?.let { link ->
            val r = runCatching { com.rokid.cxr.session.CxrSessionManager.getInstance(this).parseAuthorizationResult(resultCode, data) }.getOrNull()
            if (r?.isSuccess == true) link.markAuthorized()
        }
        finish()
    }

    companion object {
        fun launch(context: Context) = runCatching {
            context.startActivity(Intent(context, AuthActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_NO_ANIMATION))
        }
    }
}
```

`services/glasses-link/src/main/AndroidManifest.xml`:

```xml
<?xml version="1.0" encoding="utf-8"?>
<manifest xmlns:android="http://schemas.android.com/apk/res/android">
    <application>
        <activity
            android:name="com.debasish.livefit.services.glasses.AuthActivity"
            android:exported="false"
            android:excludeFromRecents="true"
            android:theme="@android:style/Theme.Translucent.NoTitleBar" />
    </application>
</manifest>
```

- [ ] **Step 8: Companion pairing and presence (phone)**

`CompanionLinker.kt`:

```kotlin
package com.debasish.livefit.phone

import android.app.Activity
import android.companion.AssociationInfo
import android.companion.AssociationRequest
import android.companion.BluetoothDeviceFilter
import android.companion.CompanionDeviceManager
import android.companion.ObservingDevicePresenceRequest
import android.content.Context
import android.content.IntentSender
import android.os.Build
import com.debasish.livefit.model.DeviceKind
import java.util.concurrent.Executor
import java.util.regex.Pattern

/** Associates the glasses / watch with LiveFit so Android wakes the hub when they're nearby (spec §5.2). */
object CompanionLinker {
    private val namePatterns = mapOf(
        DeviceKind.Glasses to Pattern.compile("(?i)(glasses|rokid|rg).*"),
        DeviceKind.Watch to Pattern.compile("(?i)(galaxy watch|watch).*"),
    )

    fun associate(activity: Activity, kind: DeviceKind, onResult: (Boolean) -> Unit) {
        val cdm = activity.getSystemService(CompanionDeviceManager::class.java)
        val request = AssociationRequest.Builder()
            .addDeviceFilter(BluetoothDeviceFilter.Builder().setNamePattern(namePatterns.getValue(kind)).build())
            .setSingleDevice(false)
            .build()
        val executor = Executor { activity.runOnUiThread(it) }
        cdm.associate(request, executor, object : CompanionDeviceManager.Callback() {
            override fun onAssociationPending(intentSender: IntentSender) {
                activity.startIntentSenderForResult(intentSender, REQUEST_CODE, null, 0, 0, 0)
            }
            override fun onAssociationCreated(info: AssociationInfo) {
                activity.getSharedPreferences("companion", 0).edit().putInt(kind.name, info.id).apply()
                observe(activity, info.id)
                onResult(true)
            }
            override fun onFailure(error: CharSequence?) = onResult(false)
        })
    }

    /** Call at app start: re-arms presence observation for saved associations. */
    fun observePresence(context: Context) {
        val prefs = context.getSharedPreferences("companion", 0)
        DeviceKind.entries.mapNotNull { k -> prefs.getInt(k.name, -1).takeIf { it >= 0 } }.forEach { observe(context, it) }
    }

    private val present = mutableSetOf<Int>()
    fun setPresent(context: Context, associationId: Int, isPresent: Boolean) { if (isPresent) present += associationId else present -= associationId }
    fun anyPresent(context: Context): Boolean = present.isNotEmpty()

    fun kindFor(context: Context, associationId: Int): DeviceKind? =
        DeviceKind.entries.firstOrNull { context.getSharedPreferences("companion", 0).getInt(it.name, -1) == associationId }

    private fun observe(context: Context, associationId: Int) {
        val cdm = context.getSystemService(CompanionDeviceManager::class.java)
        runCatching {
            if (Build.VERSION.SDK_INT >= 36) {
                cdm.startObservingDevicePresence(ObservingDevicePresenceRequest.Builder().setAssociationId(associationId).build())
            } else {
                @Suppress("DEPRECATION")
                cdm.myAssociations.firstOrNull { it.id == associationId }?.deviceMacAddress?.toString()?.let { cdm.startObservingDevicePresence(it) }
            }
        }
    }

    private const val REQUEST_CODE = 4711
}
```

`CompanionPresenceService.kt`:

```kotlin
package com.debasish.livefit.phone

import android.companion.AssociationInfo
import android.companion.CompanionDeviceService
import android.companion.DevicePresenceEvent
import android.content.Intent
import android.os.Build
import com.debasish.livefit.model.DeviceKind
import com.debasish.livefit.model.WorkoutPhase
import com.debasish.livefit.services.glasses.CxrGlassesLink

/** Android calls this when an associated glasses/watch appears or disappears. */
class CompanionPresenceService : CompanionDeviceService() {
    override fun onDevicePresenceEvent(event: DevicePresenceEvent) {
        if (Build.VERSION.SDK_INT < 36) return
        val present = event.event == DevicePresenceEvent.EVENT_BLE_APPEARED || event.event == DevicePresenceEvent.EVENT_BT_CONNECTED
        handle(event.associationId, present)
    }

    @Deprecated("API < 36")
    override fun onDeviceAppeared(info: AssociationInfo) = handle(info.id, true)

    @Deprecated("API < 36")
    override fun onDeviceDisappeared(info: AssociationInfo) = handle(info.id, false)

    private fun handle(associationId: Int, present: Boolean) {
        val graph = (application as LiveFitApp).services // builds and starts the hub graph
        CompanionLinker.setPresent(this, associationId, present)
        if (present) LiveFitHubService.start(this)
        if (CompanionLinker.kindFor(this, associationId) == DeviceKind.Glasses) CxrGlassesLink.instance?.onDevicePresence(present)
        // Spec §5.2: stop the hub when no linked device is present and no workout is active.
        val idle = graph.workout.snapshot.value.phase.let { it == WorkoutPhase.Idle || it == WorkoutPhase.Summary }
        if (!present && idle && !CompanionLinker.anyPresent(this)) stopService(Intent(this, LiveFitHubService::class.java))
    }
}
```

Manifest additions:

```xml
    <uses-feature android:name="android.software.companion_device_setup" />
    <uses-permission android:name="android.permission.REQUEST_OBSERVE_COMPANION_DEVICE_PRESENCE" />
    <uses-permission android:name="android.permission.REQUEST_COMPANION_RUN_IN_BACKGROUND" />
    <uses-permission android:name="android.permission.REQUEST_COMPANION_START_FOREGROUND_SERVICES_FROM_BACKGROUND" />
    <uses-permission android:name="android.permission.REQUEST_COMPANION_USE_DATA_IN_BACKGROUND" />
```
```xml
        <service
            android:name=".CompanionPresenceService"
            android:exported="true"
            android:permission="android.permission.BIND_COMPANION_DEVICE_SERVICE">
            <intent-filter>
                <action android:name="android.companion.CompanionDeviceService" />
            </intent-filter>
        </service>
```

In `ServiceGraph`: construct `CxrGlassesLink(app, scope)` (new constructor) and in `start()` call `CompanionLinker.observePresence(app)` before `glasses.connect()`. Remove the old authorize-and-connect block from `AppActivity` (authorization now runs through `AuthActivity`).

- [ ] **Step 9: Device spike — background authorization (highest-risk item, spec §9)**

1. `tools/install-all.sh`; open LiveFit once; Settings → Linked services → Rokid glasses → **Pair** (Task 24 adds the button; for now call `CompanionLinker.associate(this, DeviceKind.Glasses) {}` from Developer tools via a temporary button) and accept the system dialog.
2. Reboot the phone. Do **not** open LiveFit. Turn the glasses' Bluetooth off and on.
3. `adb -s $PHONE logcat -d | grep -E "LiveFitGlassesLink|AuthActivity|CompanionPresence"`

Expected: the presence service runs, `AuthActivity` flashes (no visible UI), the session starts and the glasses open Rokid LiveFit. If `AuthActivity` launch is blocked, record the log line in `docs/superpowers/specs/2026-10-05-livefit-v1-design.md` §9 and rely on the fallback notification "Open LiveFit to connect glasses" (implemented in Task 24's Linked services screen as a status row).

- [ ] **Step 10: Commit**

```bash
git add services/glasses-link phone
git commit -m "feat(glasses-link): CXR-L session policy, background authorization and companion presence"
```

---

### Task 15: YouTube Music control and workout music policy

**Files:**
- Create: `services/music/src/main/kotlin/com/debasish/livefit/services/music/WorkoutMusicPolicy.kt`
- Test: `services/music/src/test/kotlin/com/debasish/livefit/services/music/WorkoutMusicPolicyTest.kt`
- Modify: `services/music/build.gradle.kts` (convert to Android library; tests)
- Create: `services/music/src/main/kotlin/com/debasish/livefit/services/music/YtmMediaSessionService.kt`
- Create: `services/music/src/main/AndroidManifest.xml`
- Move: `phone/src/main/java/com/debasish/livefit/phone/MediaListener.kt` → `services/music/src/main/kotlin/com/debasish/livefit/services/music/MediaListener.kt` (package change; manifest entry moves to the library manifest)
- Modify: `phone/src/main/java/com/debasish/livefit/phone/ServiceGraph.kt`, `SettingsStore.kt` (use the moved `MusicOnStart`), `phone/build.gradle.kts` (`LIVE_MUSIC` = `true`)

**Interfaces:**
- Consumes: `MusicService` (core), `WorkoutPhase`.
- Produces:
  - `enum class MusicOnStart { DontTouch, Resume, PlaySearch }` (now in `com.debasish.livefit.services.music`)
  - `object WorkoutMusicPolicy { fun actionFor(from: WorkoutPhase, to: WorkoutPhase, onStart: MusicOnStart, pauseOnStop: Boolean): MusicAction }`, `enum class MusicAction { None, Resume, PlaySearch, Pause }`
  - `class YtmMediaSessionService(context: Context, scope: CoroutineScope) : MusicService` with `fun playSearch(query: String)`, `val connected: StateFlow<Boolean>` (notification access + YTM session present).
- Note: `:services:music` becomes an Android library; the glasses app (minSdk 28) and watch keep using `FakeMusicService` from it — set library `minSdk = 28`.

- [ ] **Step 1: Convert the module** — `services/music/build.gradle.kts`:

```kotlin
plugins {
    id("com.android.library")
    id("org.jetbrains.kotlin.android")
}

android {
    namespace = "com.debasish.livefit.services.music"
    compileSdk = 36
    defaultConfig { minSdk = 28 }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions { jvmTarget = "17" }
    sourceSets["main"].java.srcDirs("src/main/kotlin")
    sourceSets["test"].java.srcDirs("src/test/kotlin")
}

dependencies {
    api(project(":core:services"))
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.9.0")
    testImplementation(kotlin("test"))
    testImplementation("junit:junit:4.13.2")
}
```

- [ ] **Step 2: Write the failing policy test**

```kotlin
package com.debasish.livefit.services.music

import com.debasish.livefit.model.WorkoutPhase
import kotlin.test.Test
import kotlin.test.assertEquals

class WorkoutMusicPolicyTest {
    private fun act(from: WorkoutPhase, to: WorkoutPhase, start: MusicOnStart = MusicOnStart.Resume, pauseOnStop: Boolean = true) =
        WorkoutMusicPolicy.actionFor(from, to, start, pauseOnStop)

    @Test fun startResumesByDefault() = assertEquals(MusicAction.Resume, act(WorkoutPhase.Starting, WorkoutPhase.Active))
    @Test fun startCanPlaySearchOrDoNothing() {
        assertEquals(MusicAction.PlaySearch, act(WorkoutPhase.Starting, WorkoutPhase.Active, MusicOnStart.PlaySearch))
        assertEquals(MusicAction.None, act(WorkoutPhase.Starting, WorkoutPhase.Active, MusicOnStart.DontTouch))
    }
    @Test fun pauseDoesNotTouchMusic() = assertEquals(MusicAction.None, act(WorkoutPhase.Active, WorkoutPhase.Paused))
    @Test fun resumeFromPauseDoesNotTouchMusic() = assertEquals(MusicAction.None, act(WorkoutPhase.Paused, WorkoutPhase.Active))
    @Test fun stopPausesMusicByDefault() {
        assertEquals(MusicAction.Pause, act(WorkoutPhase.Active, WorkoutPhase.Stopping))
        assertEquals(MusicAction.None, act(WorkoutPhase.Active, WorkoutPhase.Stopping, pauseOnStop = false))
    }
    @Test fun syncingAdoptionDoesNotStartMusic() = assertEquals(MusicAction.None, act(WorkoutPhase.Syncing, WorkoutPhase.Active))
}
```

- [ ] **Step 3: Run test to verify it fails**

Run: `export JAVA_HOME=$(/usr/libexec/java_home -v 17) && ./gradlew :services:music:testDebugUnitTest`
Expected: FAIL — unresolved.

- [ ] **Step 4: Implement `WorkoutMusicPolicy.kt`**

```kotlin
package com.debasish.livefit.services.music

import com.debasish.livefit.model.WorkoutPhase

enum class MusicOnStart { DontTouch, Resume, PlaySearch }
enum class MusicAction { None, Resume, PlaySearch, Pause }

/** Settings → Music behaviour on workout transitions (spec §5.5). */
object WorkoutMusicPolicy {
    fun actionFor(from: WorkoutPhase, to: WorkoutPhase, onStart: MusicOnStart, pauseOnStop: Boolean): MusicAction = when {
        from == WorkoutPhase.Starting && to == WorkoutPhase.Active -> when (onStart) {
            MusicOnStart.DontTouch -> MusicAction.None
            MusicOnStart.Resume -> MusicAction.Resume
            MusicOnStart.PlaySearch -> MusicAction.PlaySearch
        }
        (from == WorkoutPhase.Active || from == WorkoutPhase.Paused) && to == WorkoutPhase.Stopping ->
            if (pauseOnStop) MusicAction.Pause else MusicAction.None
        else -> MusicAction.None
    }
}
```

- [ ] **Step 5: Run test to verify it passes**

Run: `export JAVA_HOME=$(/usr/libexec/java_home -v 17) && ./gradlew :services:music:testDebugUnitTest`
Expected: PASS (6 tests).

- [ ] **Step 6: Implement `YtmMediaSessionService.kt`** (generalised from the verified spike `YtmControl`)

```kotlin
package com.debasish.livefit.services.music

import android.app.SearchManager
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.media.AudioManager
import android.media.MediaMetadata
import android.media.session.MediaController
import android.media.session.MediaSessionManager
import android.media.session.PlaybackState
import android.provider.MediaStore
import com.debasish.livefit.model.NowPlaying
import com.debasish.livefit.services.MusicService
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import kotlin.math.roundToInt

/** Controls the official YouTube Music app through its media session (spec §2.3, §5.5). */
class YtmMediaSessionService(context: Context, private val scope: CoroutineScope) : MusicService {
    private val app = context.applicationContext
    private val audio = app.getSystemService(AudioManager::class.java)
    private val sessions = app.getSystemService(MediaSessionManager::class.java)
    private val listener = ComponentName(app, MediaListener::class.java)

    private val _nowPlaying = MutableStateFlow<NowPlaying?>(null)
    override val nowPlaying: StateFlow<NowPlaying?> = _nowPlaying
    private val _volume = MutableStateFlow(currentVolume())
    override val volume: StateFlow<Float> = _volume
    private val _connected = MutableStateFlow(false)
    /** Notification access granted and a YouTube Music session exists. */
    val connected: StateFlow<Boolean> = _connected

    private var controller: MediaController? = null
    private val callback = object : MediaController.Callback() {
        override fun onMetadataChanged(metadata: MediaMetadata?) = publish()
        override fun onPlaybackStateChanged(state: PlaybackState?) = publish()
        override fun onSessionDestroyed() { controller = null; publish() }
    }

    init {
        scope.launch { while (true) { attach(); _volume.value = currentVolume(); delay(1_000) } }
    }

    private fun attach() {
        val c = runCatching { sessions.getActiveSessions(listener) }.getOrNull()?.firstOrNull { it.packageName == YTM }
        if (c?.sessionToken != controller?.sessionToken) {
            controller?.unregisterCallback(callback)
            controller = c
            c?.registerCallback(callback)
        }
        publish()
    }

    private fun publish() {
        val c = controller
        _connected.value = c != null
        if (c == null) { _nowPlaying.value = null; return }
        val md = c.metadata
        val st = c.playbackState
        _nowPlaying.value = NowPlaying(
            title = md?.getString(MediaMetadata.METADATA_KEY_TITLE) ?: "",
            artist = md?.getString(MediaMetadata.METADATA_KEY_ARTIST) ?: "",
            isPlaying = st?.state == PlaybackState.STATE_PLAYING,
            liked = st?.customActions?.any { it.action == LIKE && it.name?.toString()?.contains("Unlike", true) == true } ?: false,
            positionMs = st?.position ?: 0,
            durationMs = md?.getLong(MediaMetadata.METADATA_KEY_DURATION) ?: 0,
            volume = currentVolume(),
        )
    }

    override fun playPause() { if (controller?.playbackState?.state == PlaybackState.STATE_PLAYING) pause() else play() }
    override fun play() { controller?.transportControls?.play() ?: playSearch(null) }
    override fun pause() { controller?.transportControls?.pause() }
    override fun next() { controller?.transportControls?.skipToNext() }
    override fun previous() { controller?.transportControls?.skipToPrevious() }

    override fun toggleLike() {
        val c = controller ?: return
        val like = c.playbackState?.customActions?.firstOrNull { it.action == LIKE || "${it.action} ${it.name}".contains("like", true) } ?: return
        c.transportControls.sendCustomAction(like, null)
    }

    override fun setVolume(level: Float) {
        val max = audio.getStreamMaxVolume(AudioManager.STREAM_MUSIC)
        audio.setStreamVolume(AudioManager.STREAM_MUSIC, (level.coerceIn(0f, 1f) * max).roundToInt(), 0)
        _volume.value = currentVolume()
        publish()
    }

    /** Starts YouTube Music on a search (no session yet) — `null` = just open and resume. */
    fun playSearch(query: String?) {
        val intent = Intent(MediaStore.INTENT_ACTION_MEDIA_PLAY_FROM_SEARCH).setPackage(YTM)
            .putExtra(SearchManager.QUERY, query ?: "").addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        runCatching { app.startActivity(intent) }
    }

    private fun currentVolume(): Float {
        val max = audio.getStreamMaxVolume(AudioManager.STREAM_MUSIC).coerceAtLeast(1)
        return audio.getStreamVolume(AudioManager.STREAM_MUSIC).toFloat() / max
    }

    companion object {
        const val YTM = "com.google.android.apps.youtube.music"
        const val LIKE = "thumbs_up_action"
    }
}
```

`MediaListener.kt` (moved):

```kotlin
package com.debasish.livefit.services.music

import android.service.notification.NotificationListenerService

/** Holding notification access unlocks MediaSessionManager.getActiveSessions(). */
class MediaListener : NotificationListenerService()
```

`services/music/src/main/AndroidManifest.xml`:

```xml
<?xml version="1.0" encoding="utf-8"?>
<manifest xmlns:android="http://schemas.android.com/apk/res/android">
    <queries><package android:name="com.google.android.apps.youtube.music" /></queries>
    <application>
        <service
            android:name="com.debasish.livefit.services.music.MediaListener"
            android:exported="true"
            android:permission="android.permission.BIND_NOTIFICATION_LISTENER_SERVICE">
            <intent-filter><action android:name="android.service.notification.NotificationListenerService" /></intent-filter>
        </service>
    </application>
</manifest>
```

Remove the `MediaListener` `<service>` entry from `phone/src/main/AndroidManifest.xml`, delete `phone/.../MediaListener.kt`, and update `PermissionSource` and the spike `YtmControl` imports to `com.debasish.livefit.services.music.MediaListener`.

- [ ] **Step 7: Bind and apply the policy in `ServiceGraph`**

```kotlin
    private val ytm: YtmMediaSessionService? = if (bindings.liveMusic) YtmMediaSessionService(app, scope) else null
    val music: MusicService = ytm ?: FakeMusicService(scope)
```

In `start()`:

```kotlin
        scope.launch {
            var previous = workout.snapshot.value.phase
            workout.snapshot.collect { s ->
                when (WorkoutMusicPolicy.actionFor(previous, s.phase, settings.musicOnStart.value, settings.pauseMusicOnStop.value)) {
                    MusicAction.Resume -> music.play()
                    MusicAction.PlaySearch -> ytm?.playSearch(settings.musicSearch.value) ?: music.play()
                    MusicAction.Pause -> music.pause()
                    MusicAction.None -> Unit
                }
                previous = s.phase
            }
        }
```

In `SettingsStore.kt` delete the local `enum class MusicOnStart` and import `com.debasish.livefit.services.music.MusicOnStart`. Set `LIVE_MUSIC` to `true`.

- [ ] **Step 8: Device check**

`tools/install-all.sh`; grant notification access (Settings → Permissions → Music control); play any song in YouTube Music; in LiveFit: Home now-playing card shows it; tap next, like, volume slider.
Expected: YouTube Music skips, the like icon state flips, phone media volume changes; starting a demo workout resumes playback; stopping pauses it.

- [ ] **Step 9: Commit**

```bash
git add services/music phone
git commit -m "feat(music): YouTube Music media-session control and workout music policy"
```

---

### Task 16: Voice — on-device speech, live voice service, VAD

**Files:**
- Modify: `core/services/src/main/kotlin/com/debasish/livefit/services/Services.kt` (append `SpeechToText`, `SttSession`)
- Create: `services/voice/src/main/kotlin/com/debasish/livefit/services/voice/EnergyVad.kt`
- Create: `services/voice/src/main/kotlin/com/debasish/livefit/services/voice/LiveVoiceService.kt`
- Test: `services/voice/src/test/kotlin/com/debasish/livefit/services/voice/EnergyVadTest.kt`
- Test: `services/voice/src/test/kotlin/com/debasish/livefit/services/voice/LiveVoiceServiceTest.kt`
- Create: `services/voice-android/src/main/kotlin/com/debasish/livefit/services/voice/android/AndroidOnDeviceStt.kt`
- Create: `services/voice-android/src/main/kotlin/com/debasish/livefit/services/voice/android/PhoneMic.kt`
- Move: `phone/src/main/java/com/debasish/livefit/phone/speech/SpeechPacks.kt` → `services/voice-android/src/main/kotlin/com/debasish/livefit/services/voice/android/SpeechPacks.kt` (package change only)
- Modify: `phone/src/main/java/com/debasish/livefit/phone/ServiceGraph.kt`, `phone/build.gradle.kts` (`LIVE_VOICE` = `true`, dep on `:services:voice-android`)

**Interfaces:**
- Consumes: `LanguageRegistry` (Task 4), `ConfirmationService.pending` (Task 3), `HubCommandRouter.dispatchVoice` (Task 12).
- Produces:
  - `interface SpeechToText { fun isAvailable(locale: String): Boolean; fun start(locale: String): SttSession }`
  - `interface SttSession { fun feed(pcm: ByteArray); fun end(); suspend fun awaitFinal(timeoutMs: Long): String? }`
  - `class EnergyVad(sampleRate: Int = 16_000, silenceMs: Int = 800, maxMs: Int = 6_000, minSpeechRms: Double = 300.0) { fun feed(pcm16le: ByteArray): VadDecision; fun reset() }`, `enum class VadDecision { Continue, EndOfSpeech, MaxReached }`
  - `class LiveVoiceService(scope, stt: SpeechToText, locale: () -> String, pendingConfirmationId: () -> String?, onCommand: suspend (Command) -> Unit, onAnswer: (String, Boolean) -> Unit, toast: (String) -> Unit, phoneMic: (() -> Unit)? = null) : VoiceService`
  - `class AndroidOnDeviceStt(context: Context) : SpeechToText` (no online recognizer — `createOnDeviceSpeechRecognizer` only)
  - `class PhoneMic(voice: VoiceService)` with `fun record()` (AudioRecord 16 kHz mono + `EnergyVad`, feeds `startExternal/feed/endExternal`)

- [ ] **Step 1: Append to `Services.kt`**

```kotlin
/** Platform speech-to-text, on-device only (spec §5.4). */
interface SpeechToText {
    /** True when the on-device pack for [locale] is installed. */
    fun isAvailable(locale: String): Boolean
    fun start(locale: String): SttSession
}

interface SttSession {
    /** 16 kHz mono PCM16 little-endian. */
    fun feed(pcm: ByteArray)
    fun end()
    /** Final (or last partial) text; null on error/timeout. */
    suspend fun awaitFinal(timeoutMs: Long): String?
}
```

- [ ] **Step 2: Write the failing tests**

`EnergyVadTest.kt`:

```kotlin
package com.debasish.livefit.services.voice

import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.math.sin
import kotlin.test.Test
import kotlin.test.assertEquals

class EnergyVadTest {
    /** 100 ms chunk at 16 kHz of a sine with [amp], or silence when amp = 0. */
    private fun chunk(amp: Int): ByteArray {
        val b = ByteBuffer.allocate(1_600 * 2).order(ByteOrder.LITTLE_ENDIAN)
        for (i in 0 until 1_600) b.putShort((amp * sin(i / 5.0)).toInt().toShort())
        return b.array()
    }

    @Test fun endsAfter800msOfSilenceFollowingSpeech() {
        val vad = EnergyVad()
        repeat(5) { assertEquals(VadDecision.Continue, vad.feed(chunk(4_000))) }
        repeat(7) { assertEquals(VadDecision.Continue, vad.feed(chunk(0))) }
        assertEquals(VadDecision.EndOfSpeech, vad.feed(chunk(0)))
    }

    @Test fun leadingSilenceDoesNotEndBeforeSpeech() {
        val vad = EnergyVad()
        repeat(20) { assertEquals(VadDecision.Continue, vad.feed(chunk(0))) }
    }

    @Test fun capsAtSixSeconds() {
        val vad = EnergyVad()
        repeat(59) { assertEquals(VadDecision.Continue, vad.feed(chunk(4_000))) }
        assertEquals(VadDecision.MaxReached, vad.feed(chunk(4_000)))
    }
}
```

`LiveVoiceServiceTest.kt` (Review Focus #4):

```kotlin
package com.debasish.livefit.services.voice

import com.debasish.livefit.model.Command
import com.debasish.livefit.model.VoiceState
import com.debasish.livefit.model.WorkoutType
import com.debasish.livefit.services.SpeechToText
import com.debasish.livefit.services.SttSession
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withTimeoutOrNull
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

@OptIn(ExperimentalCoroutinesApi::class)
class LiveVoiceServiceTest {
    private class FakeStt(var available: Boolean = true) : SpeechToText {
        var sessions = 0
        val result = CompletableDeferred<String?>()
        override fun isAvailable(locale: String) = available
        override fun start(locale: String): SttSession { sessions++; return object : SttSession {
            override fun feed(pcm: ByteArray) = Unit
            override fun end() = Unit
            override suspend fun awaitFinal(timeoutMs: Long) = withTimeoutOrNull(timeoutMs) { result.await() }
        } }
    }

    private val commands = mutableListOf<Command>()
    private val answers = mutableListOf<Pair<String, Boolean>>()
    private val toasts = mutableListOf<String>()
    private var pending: String? = null
    private fun TestScope.voice(stt: FakeStt) = LiveVoiceService(backgroundScope, stt, locale = { "en-IN" }, pendingConfirmationId = { pending },
        onCommand = { commands += it }, onAnswer = { id, yes -> answers += id to yes }, toast = { toasts += it })

    @Test fun recognisedCommandIsDispatched() = runTest {
        val stt = FakeStt(); val v = voice(stt)
        assertTrue(v.startExternal()); assertEquals(VoiceState.Listening, v.state.value)
        v.feed(ByteArray(3_200)); v.endExternal(); runCurrent()
        assertEquals(VoiceState.Processing, v.state.value)
        stt.result.complete("Start work out"); runCurrent()
        assertEquals(listOf<Command>(Command.StartWorkout(WorkoutType.Walk)), commands)
        assertEquals(VoiceState.Idle, v.state.value)
    }

    @Test fun pendingConfirmationUsesYesNo() = runTest {
        val stt = FakeStt(); val v = voice(stt); pending = "c1"
        v.startExternal(); v.endExternal(); stt.result.complete("yeah"); runCurrent()
        assertEquals(listOf("c1" to true), answers)
        assertTrue(commands.isEmpty())
    }

    @Test fun unrecognisedSpeechToastsAndChangesNothing() = runTest {
        val stt = FakeStt(); val v = voice(stt)
        v.startExternal(); v.endExternal(); stt.result.complete("what's the weather"); runCurrent()
        assertEquals(listOf("Didn't catch that"), toasts)
    }

    /** Review Focus #4. */
    @Test fun secondListenWhileBusyIsIgnored() = runTest {
        val stt = FakeStt(); val v = voice(stt)
        assertTrue(v.startExternal())
        assertFalse(v.startExternal())
        v.endExternal(); runCurrent()
        assertFalse(v.startExternal(), "still processing")
        assertEquals(1, stt.sessions)
    }

    @Test fun missingPackDisablesVoiceWithHint() = runTest {
        val v = voice(FakeStt(available = false))
        assertFalse(v.startExternal())
        assertEquals(listOf("Voice needs the English (India) pack"), toasts)
    }
}
```

- [ ] **Step 3: Run tests to verify they fail**

Run: `export JAVA_HOME=$(/usr/libexec/java_home -v 17) && ./gradlew :services:voice:test`
Expected: FAIL — unresolved `EnergyVad`, `LiveVoiceService`.

- [ ] **Step 4: Implement `EnergyVad.kt`**

```kotlin
package com.debasish.livefit.services.voice

import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.math.sqrt

enum class VadDecision { Continue, EndOfSpeech, MaxReached }

/** Energy VAD: end after [silenceMs] of quiet following speech, or at [maxMs] (spec §5.4). */
class EnergyVad(
    private val sampleRate: Int = 16_000,
    private val silenceMs: Int = 800,
    private val maxMs: Int = 6_000,
    private val minSpeechRms: Double = 300.0,
) {
    private var totalMs = 0.0
    private var silentMs = 0.0
    private var heardSpeech = false

    fun reset() { totalMs = 0.0; silentMs = 0.0; heardSpeech = false }

    fun feed(pcm16le: ByteArray): VadDecision {
        val samples = pcm16le.size / 2
        if (samples == 0) return VadDecision.Continue
        val buf = ByteBuffer.wrap(pcm16le).order(ByteOrder.LITTLE_ENDIAN)
        var sum = 0.0
        repeat(samples) { val s = buf.short.toDouble(); sum += s * s }
        val rms = sqrt(sum / samples)
        val ms = samples * 1000.0 / sampleRate
        totalMs += ms
        if (rms >= minSpeechRms) { heardSpeech = true; silentMs = 0.0 } else if (heardSpeech) silentMs += ms
        return when {
            totalMs >= maxMs -> VadDecision.MaxReached
            heardSpeech && silentMs >= silenceMs -> VadDecision.EndOfSpeech
            else -> VadDecision.Continue
        }
    }
}
```

- [ ] **Step 5: Implement `LiveVoiceService.kt`**

```kotlin
package com.debasish.livefit.services.voice

import com.debasish.livefit.model.Command
import com.debasish.livefit.model.VoiceState
import com.debasish.livefit.services.SpeechToText
import com.debasish.livefit.services.SttSession
import com.debasish.livefit.services.VoiceService
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch

/** Push-to-talk: audio → on-device STT → language pack parser → router or confirmation answer. */
class LiveVoiceService(
    private val scope: CoroutineScope,
    private val stt: SpeechToText,
    private val locale: () -> String,
    private val pendingConfirmationId: () -> String?,
    private val onCommand: suspend (Command) -> Unit,
    private val onAnswer: (String, Boolean) -> Unit,
    private val toast: (String) -> Unit,
    private val phoneMic: (() -> Unit)? = null,
) : VoiceService {
    private val _state = MutableStateFlow(VoiceState.Idle)
    override val state: StateFlow<VoiceState> = _state
    private var session: SttSession? = null

    override fun listen() { phoneMic?.invoke() }

    override fun startExternal(): Boolean {
        if (_state.value != VoiceState.Idle) return false
        val loc = locale()
        val pack = LanguageRegistry.forLocale(loc)
        if (pack == null || !stt.isAvailable(loc)) {
            toast("Voice needs the ${pack?.displayName ?: loc} pack")
            return false
        }
        session = stt.start(loc)
        _state.value = VoiceState.Listening
        return true
    }

    override fun feed(pcm: ByteArray) { if (_state.value == VoiceState.Listening) session?.feed(pcm) }

    override fun endExternal() {
        val s = session ?: return
        if (_state.value != VoiceState.Listening) return
        _state.value = VoiceState.Processing
        s.end()
        scope.launch {
            val text = s.awaitFinal(5_000)
            session = null
            val pack = LanguageRegistry.forLocale(locale())
            val confirmationId = pendingConfirmationId()
            when {
                text.isNullOrBlank() || pack == null -> toast("Didn't catch that")
                confirmationId != null -> pack.parseYesNo(text)?.let { onAnswer(confirmationId, it) } ?: toast("Say yes or no")
                else -> pack.parseCommand(text)?.let { onCommand(it) } ?: toast("Didn't catch that")
            }
            _state.value = VoiceState.Idle
        }
    }
}
```

- [ ] **Step 6: Run tests to verify they pass**

Run: `export JAVA_HOME=$(/usr/libexec/java_home -v 17) && ./gradlew :services:voice:test`
Expected: PASS.

- [ ] **Step 7: Android STT and phone mic** — `AndroidOnDeviceStt.kt` (pipe-fed, verified approach §2.4):

```kotlin
package com.debasish.livefit.services.voice.android

import android.content.Context
import android.content.Intent
import android.media.AudioFormat
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.ParcelFileDescriptor
import android.speech.RecognitionListener
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import com.debasish.livefit.services.SpeechToText
import com.debasish.livefit.services.SttSession
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.withTimeoutOrNull
import java.util.concurrent.Executors

/** On-device recognizer only — never the network recognizer (spec §5.4). */
class AndroidOnDeviceStt(context: Context) : SpeechToText {
    private val app = context.applicationContext
    private val main = Handler(Looper.getMainLooper())
    @Volatile private var installed: Set<String> = emptySet()

    /** Refresh the installed-pack cache (call at start and after Languages downloads). */
    suspend fun refresh() { installed = SpeechPacks.query(app).installed }

    override fun isAvailable(locale: String) =
        SpeechRecognizer.isOnDeviceRecognitionAvailable(app) && locale in installed

    override fun start(locale: String): SttSession {
        val (read, write) = ParcelFileDescriptor.createPipe()
        val out = ParcelFileDescriptor.AutoCloseOutputStream(write)
        val writer = Executors.newSingleThreadExecutor()
        val final = CompletableDeferred<String?>()
        var lastPartial: String? = null
        main.post {
            val r = SpeechRecognizer.createOnDeviceSpeechRecognizer(app)
            r.setRecognitionListener(object : RecognitionListener {
                override fun onResults(b: Bundle) { final.complete(b.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)?.firstOrNull() ?: lastPartial); r.destroy() }
                override fun onPartialResults(b: Bundle) { b.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)?.firstOrNull()?.takeIf { it.isNotBlank() }?.let { lastPartial = it } }
                override fun onError(error: Int) { final.complete(lastPartial); r.destroy() }
                override fun onReadyForSpeech(p: Bundle?) {}
                override fun onBeginningOfSpeech() {}
                override fun onRmsChanged(v: Float) {}
                override fun onBufferReceived(b: ByteArray?) {}
                override fun onEndOfSpeech() {}
                override fun onEvent(t: Int, p: Bundle?) {}
            })
            r.startListening(Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH)
                .putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
                .putExtra(RecognizerIntent.EXTRA_LANGUAGE, locale)
                .putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, true)
                .putExtra(RecognizerIntent.EXTRA_AUDIO_SOURCE, read)
                .putExtra(RecognizerIntent.EXTRA_AUDIO_SOURCE_ENCODING, AudioFormat.ENCODING_PCM_16BIT)
                .putExtra(RecognizerIntent.EXTRA_AUDIO_SOURCE_CHANNEL_COUNT, 1)
                .putExtra(RecognizerIntent.EXTRA_AUDIO_SOURCE_SAMPLING_RATE, 16_000))
        }
        return object : SttSession {
            override fun feed(pcm: ByteArray) { writer.execute { runCatching { out.write(pcm) } } }
            override fun end() { writer.execute { runCatching { out.close() }; writer.shutdown() } }
            override suspend fun awaitFinal(timeoutMs: Long) = withTimeoutOrNull(timeoutMs) { final.await() } ?: lastPartial
        }
    }
}
```

`PhoneMic.kt`:

```kotlin
package com.debasish.livefit.services.voice.android

import android.annotation.SuppressLint
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import com.debasish.livefit.services.VoiceService
import com.debasish.livefit.services.voice.EnergyVad
import com.debasish.livefit.services.voice.VadDecision
import kotlin.concurrent.thread

/** Phone mic push-to-talk: same pipeline as the glasses (16 kHz mono, VAD-terminated). */
class PhoneMic(private val voice: () -> VoiceService) {
    @SuppressLint("MissingPermission") // RECORD_AUDIO is granted in the setup wizard
    fun record() {
        val v = voice()
        if (!v.startExternal()) return
        thread(name = "phone-mic") {
            val min = AudioRecord.getMinBufferSize(16_000, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT)
            val rec = AudioRecord(MediaRecorder.AudioSource.VOICE_RECOGNITION, 16_000, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT, maxOf(min, 6_400))
            val vad = EnergyVad()
            val chunk = ByteArray(3_200) // 100 ms
            rec.startRecording()
            try {
                while (true) {
                    val n = rec.read(chunk, 0, chunk.size)
                    if (n <= 0) break
                    val bytes = chunk.copyOf(n)
                    v.feed(bytes)
                    if (vad.feed(bytes) != VadDecision.Continue) break
                }
            } finally { rec.stop(); rec.release(); v.endExternal() }
        }
    }
}
```

Move `SpeechPacks.kt` into `services/voice-android/.../android/` (package `com.debasish.livefit.services.voice.android`) and update `LanguageSource` imports. Add `implementation("androidx.core:core-ktx:1.13.1")` to `services/voice-android/build.gradle.kts` (SpeechPacks uses `ContextCompat`).

- [ ] **Step 8: Bind in `ServiceGraph`**

```kotlin
    private val stt: AndroidOnDeviceStt? = if (bindings.liveVoice) AndroidOnDeviceStt(app) else null
    private lateinit var phoneMic: PhoneMic
    val voice: VoiceService = if (stt != null) LiveVoiceService(
        scope, stt,
        locale = { settings.voiceLocale.value },
        pendingConfirmationId = { confirm.pending.value?.id },
        onCommand = { router.dispatchVoice(it) },
        onAnswer = { id, yes -> confirm.answer(id, yes) },
        toast = ::flash,
        phoneMic = { phoneMic.record() },
    ).also { v -> phoneMic = PhoneMic { v } } else FakeVoiceService(scope) { router.dispatchVoice(it) }
```

In `start()`: `stt?.let { s -> scope.launch { s.refresh() } }`. Phone build: `implementation(project(":services:voice-android"))`, `LIVE_VOICE` = `true`.

- [ ] **Step 9: Device check (phone mic)**

`tools/install-all.sh`; Workout screen → mic button → say "next song".
Expected: listening state, then toast "Next song" and YouTube Music skips. With the en-IN pack uninstalled, the mic shows "Voice needs the English (India) pack".

- [ ] **Step 10: Commit**

```bash
git add core/services services/voice services/voice-android phone
git commit -m "feat(voice): on-device STT, live voice service, energy VAD and phone push-to-talk"
```

---
## Phase 2 — Watch

### Task 17: Watch exercise controller (session-scoped) and Health Services backend

**Files:**
- Create: `services/sync/src/main/kotlin/com/debasish/livefit/sync/WatchExerciseController.kt`
- Test: `services/sync/src/test/kotlin/com/debasish/livefit/sync/WatchExerciseControllerTest.kt`
- Create: `watch/src/main/java/com/debasish/livefit/watch/HealthServicesExercise.kt`
- Create: `watch/src/main/java/com/debasish/livefit/watch/WatchRuntime.kt`
- Rewrite: `watch/src/main/java/com/debasish/livefit/watch/ExerciseService.kt`
- Rewrite: `watch/src/main/java/com/debasish/livefit/watch/PhoneCommandListener.kt`
- Modify: `watch/build.gradle.kts`, `watch/src/main/AndroidManifest.xml`
- Delete: `watch/src/main/java/com/debasish/livefit/watch/PhoneLink.kt`, `PhoneHub.kt`, `WatchGraph.kt` (replaced by `WatchRuntime`/`WatchClient`)

**Interfaces:**
- Consumes: `WatchSessionRecorder`, `FileDeltaBuffer` (Task 10), protocol types (Task 2), `Clock`.
- Produces:
  - `interface ExerciseBackend { fun missingPermissions(): List<String>; suspend fun otherAppTracking(): String?; suspend fun start(type: WorkoutType, useGps: Boolean): Boolean; suspend fun pause(); suspend fun resume(); suspend fun end(); val updates: Flow<BackendUpdate> }`
  - `sealed interface BackendUpdate { data class Reading(val sample: Sample); data class Ended(val by: EndReason) }`
  - `class WatchExerciseController(scope, backend, recorder, clock, sendResult: suspend (ExerciseResult) -> Unit, sendState: suspend (ExerciseStateReport) -> Unit)` — GPS comes from `ExerciseOp.Start.gps` with `suspend fun handle(req: ExerciseRequest)`, `suspend fun localPause()`, `suspend fun localResume()`, `suspend fun localStop()`, `val activeSessionId: String?`, `val lastError: StateFlow<ExerciseError?>`.
  - `object WatchRuntime { fun init(context: Context); val recorder: WatchSessionRecorder; val controller: WatchExerciseController; suspend fun send(path: String, bytes: ByteArray) }`

- [ ] **Step 1: Write the failing controller test**

```kotlin
package com.debasish.livefit.sync

import com.debasish.livefit.model.EndReason
import com.debasish.livefit.model.ExerciseError
import com.debasish.livefit.model.ExerciseOp
import com.debasish.livefit.model.ExerciseRequest
import com.debasish.livefit.model.ExerciseResult
import com.debasish.livefit.model.ExerciseState
import com.debasish.livefit.model.ExerciseStateReport
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
import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue

@OptIn(ExperimentalCoroutinesApi::class)
class WatchExerciseControllerTest {
    private class FakeBackend : ExerciseBackend {
        var missing = emptyList<String>()
        var other: String? = null
        val calls = mutableListOf<String>()
        override val updates = MutableSharedFlow<BackendUpdate>(extraBufferCapacity = 16)
        override fun missingPermissions() = missing
        override suspend fun otherAppTracking() = other
        override suspend fun start(type: WorkoutType, useGps: Boolean): Boolean { calls += "start:$type:$useGps"; return true }
        override suspend fun pause() { calls += "pause" }
        override suspend fun resume() { calls += "resume" }
        override suspend fun end() { calls += "end" }
    }

    private val results = mutableListOf<ExerciseResult>()
    private val states = mutableListOf<ExerciseStateReport>()
    private val sent = mutableListOf<SessionDelta>()

    private fun TestScope.rig(backend: FakeBackend = FakeBackend()): Pair<FakeBackend, WatchExerciseController> {
        val recorder = WatchSessionRecorder(FileDeltaBuffer(Files.createTempDirectory("w").toFile()), Provenance.Live("galaxy-watch/health-services")) { sent += it }
        val c = WatchExerciseController(backgroundScope, backend, recorder, Clock { testScheduler.currentTime },
            sendResult = { results += it }, sendState = { states += it })
        return backend to c
    }
    private fun req(id: String, session: String, op: ExerciseOp) = ExerciseRequest(requestId = id, sessionId = session, op = op)

    @Test fun startBeginsRecordingAndRepliesOk() = runTest {
        val (b, c) = rig(); runCurrent()
        c.handle(req("r1", "s", ExerciseOp.Start(WorkoutType.Run, gps = true)))
        assertEquals(listOf("start:Run:true"), b.calls)
        assertTrue(results.single().ok)
        assertIs<SessionEvent.Started>(sent.single().events.single())
        assertEquals("s", c.activeSessionId)
    }

    @Test fun opsForAnotherSessionAreRejected() = runTest {
        val (b, c) = rig(); runCurrent()
        c.handle(req("r1", "s", ExerciseOp.Start(WorkoutType.Walk)))
        c.handle(req("r2", "old", ExerciseOp.Stop))
        assertEquals(ExerciseError.WrongSession("s"), results.last().error)
        c.handle(req("r3", "new", ExerciseOp.Start(WorkoutType.Walk)))
        assertEquals(ExerciseError.WrongSession("s"), results.last().error)
        assertEquals(listOf("start:Walk:false"), b.calls)
    }

    @Test fun permissionMissingAndOtherAppTracking() = runTest {
        val backend = FakeBackend().apply { missing = listOf("android.permission.health.READ_HEART_RATE") }
        val (_, c) = rig(backend); runCurrent()
        c.handle(req("r1", "s", ExerciseOp.Start(WorkoutType.Walk)))
        assertIs<ExerciseError.PermissionMissing>(results.last().error)
        backend.missing = emptyList(); backend.other = "RUNNING_TREADMILL"
        c.handle(req("r2", "s", ExerciseOp.Start(WorkoutType.Walk)))
        assertEquals(ExerciseError.OtherAppTracking("RUNNING_TREADMILL"), results.last().error)
        c.handle(req("r3", "s", ExerciseOp.Start(WorkoutType.Walk, force = true)))
        assertTrue(results.last().ok)
    }

    @Test fun pauseResumeStopRecordEventsAndFinalDelta() = runTest {
        val (b, c) = rig(); runCurrent()
        c.handle(req("r1", "s", ExerciseOp.Start(WorkoutType.Walk)))
        c.handle(req("r2", "s", ExerciseOp.Pause))
        c.handle(req("r3", "s", ExerciseOp.Resume))
        c.handle(req("r4", "s", ExerciseOp.Stop))
        assertEquals(listOf("start:Walk:false", "pause", "resume", "end"), b.calls)
        val last = sent.last()
        assertTrue(last.final)
        assertEquals(SessionEvent.Stopped(0, EndReason.User), last.events.single())
    }

    @Test fun endedByAnotherAppRecordsFinalStopAndReports() = runTest {
        val (b, c) = rig(); runCurrent()
        c.handle(req("r1", "s", ExerciseOp.Start(WorkoutType.Walk)))
        b.updates.emit(BackendUpdate.Reading(Sample(1_000, hr = 100))); runCurrent()
        b.updates.emit(BackendUpdate.Ended(EndReason.OtherApp)); runCurrent()
        assertTrue(sent.last().final)
        assertEquals(ExerciseStateReport(sessionId = "s", state = ExerciseState.Ended, endedBy = EndReason.OtherApp), states.single())
        b.updates.emit(BackendUpdate.Ended(EndReason.System)); runCurrent()
        assertEquals(1, states.size, "no second stop after finalization")
    }

    @Test fun duplicateRequestIdIsAnsweredOnceWithoutRerunning() = runTest {
        val (b, c) = rig(); runCurrent()
        val r = req("r1", "s", ExerciseOp.Start(WorkoutType.Walk))
        c.handle(r); c.handle(r)
        assertEquals(1, b.calls.size)
        assertEquals(2, results.size)
        assertEquals(results[0], results[1])
    }
}
```

- [ ] **Step 2: Run test to verify it fails**

Run: `export JAVA_HOME=$(/usr/libexec/java_home -v 17) && ./gradlew :services:sync:test --tests '*WatchExerciseControllerTest*'`
Expected: FAIL — unresolved controller types.

- [ ] **Step 3: Implement `WatchExerciseController.kt`**

```kotlin
package com.debasish.livefit.sync

import com.debasish.livefit.model.EndReason
import com.debasish.livefit.model.ExerciseError
import com.debasish.livefit.model.ExerciseOp
import com.debasish.livefit.model.ExerciseRequest
import com.debasish.livefit.model.ExerciseResult
import com.debasish.livefit.model.ExerciseState
import com.debasish.livefit.model.ExerciseStateReport
import com.debasish.livefit.model.Sample
import com.debasish.livefit.model.SessionEvent
import com.debasish.livefit.model.WorkoutPhase
import com.debasish.livefit.model.WorkoutType
import com.debasish.livefit.services.Clock
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch

interface ExerciseBackend {
    fun missingPermissions(): List<String>
    /** Exercise type name if another app currently owns an exercise, else null. */
    suspend fun otherAppTracking(): String?
    suspend fun start(type: WorkoutType, useGps: Boolean): Boolean
    suspend fun pause()
    suspend fun resume()
    suspend fun end()
    val updates: Flow<BackendUpdate>
}

sealed interface BackendUpdate {
    data class Reading(val sample: Sample) : BackendUpdate
    data class Ended(val by: EndReason) : BackendUpdate
}

/** Watch executor for hub requests; scoped to one session at a time (spec §4.8). */
class WatchExerciseController(
    scope: CoroutineScope,
    private val backend: ExerciseBackend,
    private val recorder: WatchSessionRecorder,
    private val clock: Clock,
    private val sendResult: suspend (ExerciseResult) -> Unit,
    private val sendState: suspend (ExerciseStateReport) -> Unit,
) {
    private val recent = LinkedHashMap<String, ExerciseResult>()
    private val _lastError = MutableStateFlow<ExerciseError?>(null)
    val lastError: StateFlow<ExerciseError?> = _lastError

    val activeSessionId: String? get() = recorder.sessionId?.takeIf { !recorder.isFinalized }

    init {
        scope.launch {
            backend.updates.collect { u ->
                when (u) {
                    is BackendUpdate.Reading -> if (activeSessionId != null) recorder.sample(u.sample)
                    is BackendUpdate.Ended -> {
                        val id = activeSessionId ?: return@collect
                        recorder.event(SessionEvent.Stopped(clock.nowMs(), u.by), final = true)
                        sendState(ExerciseStateReport(sessionId = id, state = ExerciseState.Ended, endedBy = u.by))
                    }
                }
            }
        }
    }

    suspend fun handle(req: ExerciseRequest) {
        recent[req.requestId]?.let { sendResult(it); return }
        val result = execute(req)
        recent[req.requestId] = result
        if (recent.size > 20) recent.remove(recent.keys.first())
        _lastError.value = result.error
        sendResult(result)
    }

    private suspend fun execute(req: ExerciseRequest): ExerciseResult {
        val active = activeSessionId
        fun result(ok: Boolean, error: ExerciseError? = null, state: ExerciseState) =
            ExerciseResult(requestId = req.requestId, sessionId = req.sessionId, ok = ok, error = error, state = state, activeSessionId = activeSessionId)

        return when (val op = req.op) {
            is ExerciseOp.Start -> when {
                active == req.sessionId -> result(true, state = ExerciseState.Active)
                active != null -> result(false, ExerciseError.WrongSession(active), ExerciseState.Active)
                else -> {
                    val missing = backend.missingPermissions()
                    val other = if (op.force) null else backend.otherAppTracking()
                    when {
                        missing.isNotEmpty() -> result(false, ExerciseError.PermissionMissing(missing), ExerciseState.Idle)
                        other != null -> result(false, ExerciseError.OtherAppTracking(other), ExerciseState.Idle)
                        !backend.start(op.type, op.gps) -> result(false, ExerciseError.SensorUnavailable, ExerciseState.Idle)
                        else -> { recorder.begin(req.sessionId, op.type, clock.nowMs()); result(true, state = ExerciseState.Active) }
                    }
                }
            }
            else -> if (active != req.sessionId) result(false, ExerciseError.WrongSession(active), if (active == null) ExerciseState.Idle else ExerciseState.Active)
            else when (op) {
                ExerciseOp.Pause -> { localPause(); result(true, state = ExerciseState.Paused) }
                ExerciseOp.Resume -> { localResume(); result(true, state = ExerciseState.Active) }
                else -> { localStop(); result(true, state = ExerciseState.Ended) }
            }
        }
    }

    /** Offline controls from the watch UI use the same paths and are recorded as events. */
    suspend fun localPause() {
        if (activeSessionId == null || recorder.assembler?.phase() != WorkoutPhase.Active) return
        backend.pause(); recorder.event(SessionEvent.Paused(clock.nowMs()))
    }

    suspend fun localResume() {
        if (activeSessionId == null || recorder.assembler?.phase() != WorkoutPhase.Paused) return
        backend.resume(); recorder.event(SessionEvent.Resumed(clock.nowMs()))
    }

    suspend fun localStop() {
        if (activeSessionId == null) return
        recorder.event(SessionEvent.Stopped(clock.nowMs(), EndReason.User), final = true) // finalize first: backend Ended is then ignored
        backend.end()
    }
}
```

- [ ] **Step 4: Run tests to verify they pass**

Run: `export JAVA_HOME=$(/usr/libexec/java_home -v 17) && ./gradlew :services:sync:test`
Expected: PASS. (Note `pauseResumeStopRecordEventsAndFinalDelta` expects `end` last: `localStop` records first, then calls `backend.end()`.)

- [ ] **Step 5: Watch build** — `watch/build.gradle.kts` dependencies: replace `:services:workout`, `:services:metrics`, `:services:music` with

```kotlin
    implementation(project(":services:sync"))
    implementation(project(":services:music"))
    implementation("androidx.wear:wear-ongoing:1.0.0")
```

(`:services:sync` brings `:services:workout` and `:core:*` via `api`.)

- [ ] **Step 6: Health Services backend** — `HealthServicesExercise.kt` (from the verified spike `ExerciseService`):

```kotlin
package com.debasish.livefit.watch

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import androidx.core.content.ContextCompat
import androidx.health.services.client.ExerciseUpdateCallback
import androidx.health.services.client.HealthServices
import androidx.health.services.client.data.Availability
import androidx.health.services.client.data.DataType
import androidx.health.services.client.data.ExerciseConfig
import androidx.health.services.client.data.ExerciseEndReason
import androidx.health.services.client.data.ExerciseLapSummary
import androidx.health.services.client.data.ExerciseTrackedStatus
import androidx.health.services.client.data.ExerciseType
import androidx.health.services.client.data.ExerciseUpdate
import androidx.health.services.client.data.WarmUpConfig
import com.debasish.livefit.model.EndReason
import com.debasish.livefit.model.Sample
import com.debasish.livefit.model.WorkoutType
import com.debasish.livefit.sync.BackendUpdate
import com.debasish.livefit.sync.ExerciseBackend
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.guava.await

/** Health Services implementation of [ExerciseBackend] (spec §5.1). */
class HealthServicesExercise(context: Context) : ExerciseBackend {
    private val app = context.applicationContext
    private val client = HealthServices.getClient(app).exerciseClient
    override val updates = MutableSharedFlow<BackendUpdate>(extraBufferCapacity = 64)

    // Cumulative totals arrive in separate updates from HR; keep the latest of each.
    private var steps = 0; private var km = 0.0; private var kcal = 0.0; private var speed: Double? = null

    private val required = listOf(Manifest.permission.BODY_SENSORS, "android.permission.health.READ_HEART_RATE", Manifest.permission.ACTIVITY_RECOGNITION)

    override fun missingPermissions(): List<String> =
        required.filter { ContextCompat.checkSelfPermission(app, it) != PackageManager.PERMISSION_GRANTED }

    override suspend fun otherAppTracking(): String? {
        val info = client.getCurrentExerciseInfoAsync().await()
        return if (info.exerciseTrackedStatus == ExerciseTrackedStatus.OTHER_APP_IN_PROGRESS) info.exerciseType.name else null
    }

    private fun hsType(t: WorkoutType) = when (t) {
        WorkoutType.Run -> ExerciseType.RUNNING
        WorkoutType.Cycle -> ExerciseType.BIKING
        WorkoutType.Walk, WorkoutType.Auto -> ExerciseType.WALKING
    }

    override suspend fun start(type: WorkoutType, useGps: Boolean): Boolean = runCatching {
        steps = 0; km = 0.0; kcal = 0.0; speed = null
        val exerciseType = hsType(type)
        val supported = client.getCapabilitiesAsync().await().getExerciseTypeCapabilities(exerciseType).supportedDataTypes
        val wanted = setOf(DataType.HEART_RATE_BPM, DataType.STEPS_TOTAL, DataType.DISTANCE_TOTAL, DataType.CALORIES_TOTAL, DataType.SPEED)
        val types = wanted.filter { it in supported }.toSet()
        client.setUpdateCallback(callback)
        runCatching { client.prepareExerciseAsync(WarmUpConfig(exerciseType, setOf(DataType.HEART_RATE_BPM))).await() }
        client.startExerciseAsync(
            ExerciseConfig.builder(exerciseType).setDataTypes(types).setIsAutoPauseAndResumeEnabled(false).setIsGpsEnabled(useGps).build(),
        ).await()
        true
    }.getOrDefault(false)

    override suspend fun pause() { runCatching { client.pauseExerciseAsync().await() } }
    override suspend fun resume() { runCatching { client.resumeExerciseAsync().await() } }
    override suspend fun end() { runCatching { client.endExerciseAsync().await() } }

    private val callback = object : ExerciseUpdateCallback {
        override fun onExerciseUpdateReceived(update: ExerciseUpdate) {
            val m = update.latestMetrics
            m.getData(DataType.STEPS_TOTAL)?.total?.let { steps = it.toInt() }
            m.getData(DataType.DISTANCE_TOTAL)?.total?.let { km = it / 1000.0 }
            m.getData(DataType.CALORIES_TOTAL)?.total?.let { kcal = it }
            m.getData(DataType.SPEED).lastOrNull()?.value?.let { speed = it * 3.6 }
            val hr = m.getData(DataType.HEART_RATE_BPM).lastOrNull()?.value?.toInt()
            updates.tryEmit(BackendUpdate.Reading(Sample(System.currentTimeMillis(), hr, steps, km, kcal, speed)))
            val st = update.exerciseStateInfo
            if (st.state.isEnded) {
                val by = when (st.endReason) {
                    ExerciseEndReason.USER_END -> EndReason.User
                    ExerciseEndReason.AUTO_END_SUPERSEDED -> EndReason.OtherApp
                    else -> EndReason.System
                }
                updates.tryEmit(BackendUpdate.Ended(by))
            }
        }
        override fun onLapSummaryReceived(lapSummary: ExerciseLapSummary) {}
        override fun onRegistered() {}
        override fun onRegistrationFailed(throwable: Throwable) {}
        override fun onAvailabilityChanged(dataType: DataType<*, *>, availability: Availability) {}
    }
}
```

Units note: Health Services reports `DISTANCE_TOTAL` in metres and `SPEED` in m/s; the conversions above produce km and km/h.

- [ ] **Step 7: `WatchRuntime.kt`** — process-wide watch singletons

```kotlin
package com.debasish.livefit.watch

import android.content.Context
import android.util.Log
import com.debasish.livefit.model.Provenance
import com.debasish.livefit.model.WatchPaths
import com.debasish.livefit.model.Wire
import com.debasish.livefit.services.Clock
import com.debasish.livefit.sync.FileDeltaBuffer
import com.debasish.livefit.sync.WatchExerciseController
import com.debasish.livefit.sync.WatchSessionRecorder
import com.google.android.gms.wearable.Wearable
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.tasks.await
import java.io.File

object WatchRuntime {
    const val TAG = "LiveFitWatch"
    val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private lateinit var app: Context
    lateinit var recorder: WatchSessionRecorder; private set
    lateinit var controller: WatchExerciseController; private set
    private var initialized = false

    @Synchronized
    fun init(context: Context) {
        if (initialized) return
        app = context.applicationContext
        recorder = WatchSessionRecorder(FileDeltaBuffer(File(app.filesDir, "lf-buffer")), Provenance.Live("galaxy-watch/health-services")) { d ->
            send(WatchPaths.DELTA, Wire.encode(d).toByteArray())
        }
        controller = WatchExerciseController(
            scope, HealthServicesExercise(app), recorder, Clock { System.currentTimeMillis() },
            sendResult = { r ->
                send(WatchPaths.EXERCISE_RES, Wire.encode(r).toByteArray())
                if (r.ok && controller.activeSessionId != null) ExerciseService.start(app)
            },
            sendState = { s -> send(WatchPaths.EXERCISE_STATE, Wire.encode(s).toByteArray()) },
        )
        initialized = true
    }

    /** Sends to the phone; throws if unreachable so the recorder keeps the delta buffered. */
    suspend fun send(path: String, bytes: ByteArray) {
        val nodes = Wearable.getNodeClient(app).connectedNodes.await()
        check(nodes.isNotEmpty()) { "phone unreachable" }
        nodes.forEach { Wearable.getMessageClient(app).sendMessage(it.id, path, bytes).await() }
    }

    fun log(msg: String) = Log.i(TAG, msg)
}
```

- [ ] **Step 8: Rewrite `ExerciseService.kt`** — health foreground service + ongoing activity, stops when the session ends

```kotlin
package com.debasish.livefit.watch

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.IBinder
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import androidx.wear.ongoing.OngoingActivity
import androidx.wear.ongoing.Status
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/** Keeps tracking alive through screen-off; wrist raise returns to the app (spec §5.1). */
class ExerciseService : Service() {
    private var watcher: Job? = null

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        WatchRuntime.init(this)
        val nm = getSystemService(NotificationManager::class.java)
        nm.createNotificationChannel(NotificationChannel(CHANNEL, "Workout", NotificationManager.IMPORTANCE_LOW))
        val touch = PendingIntent.getActivity(this, 0, Intent(this, MainActivity::class.java), PendingIntent.FLAG_IMMUTABLE)
        val builder = NotificationCompat.Builder(this, CHANNEL)
            .setContentTitle("Rokid LiveFit").setContentText("Workout in progress")
            .setSmallIcon(android.R.drawable.ic_media_play).setOngoing(true).setContentIntent(touch)
        OngoingActivity.Builder(this, ID, builder)
            .setStaticIcon(android.R.drawable.ic_media_play)
            .setTouchIntent(touch)
            .setStatus(Status.Builder().addTemplate("LiveFit workout").build())
            .build().apply(this)
        ServiceCompat.startForeground(this, ID, builder.build(), ServiceInfo.FOREGROUND_SERVICE_TYPE_HEALTH)
        watcher = WatchRuntime.scope.launch {
            // Stop once the session is finalized AND its buffer is fully acked (recorder clears it).
            while (true) {
                delay(5_000)
                if (WatchRuntime.recorder.sessionId == null) { stopSelf(); break }
            }
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int) = START_STICKY
    override fun onDestroy() { watcher?.cancel(); super.onDestroy() }

    companion object {
        private const val CHANNEL = "workout"
        private const val ID = 1
        fun start(context: Context) = runCatching { context.startForegroundService(Intent(context, ExerciseService::class.java)) }
    }
}
```

- [ ] **Step 9: Rewrite `PhoneCommandListener.kt`** — routes phone → watch messages

```kotlin
package com.debasish.livefit.watch

import com.debasish.livefit.model.DeltaAck
import com.debasish.livefit.model.ExerciseRequest
import com.debasish.livefit.model.PROTOCOL_VERSION
import com.debasish.livefit.model.WatchPaths
import com.debasish.livefit.model.Wire
import com.google.android.gms.wearable.MessageEvent
import com.google.android.gms.wearable.Wearable
import com.google.android.gms.wearable.WearableListenerService
import kotlinx.coroutines.launch

/** Wakes the watch app for phone messages, even after process death (verified in spikes). */
class PhoneCommandListener : WearableListenerService() {
    override fun onMessageReceived(event: MessageEvent) {
        WatchRuntime.init(this)
        val text = String(event.data)
        when (event.path) {
            WatchPaths.BATTERY_REQ -> {
                val pct = getSystemService(android.os.BatteryManager::class.java).getIntProperty(android.os.BatteryManager.BATTERY_PROPERTY_CAPACITY)
                Wearable.getMessageClient(this).sendMessage(event.sourceNodeId, WatchPaths.BATTERY, pct.toString().toByteArray())
                return
            }
        }
        if (Wire.versionOf(text) != PROTOCOL_VERSION) { WatchClient.onOutdated(); return }
        WatchRuntime.scope.launch {
            when (event.path) {
                WatchPaths.EXERCISE_REQ -> WatchRuntime.controller.handle(Wire.decode<ExerciseRequest>(text))
                WatchPaths.ACK -> WatchRuntime.recorder.onAck(Wire.decode<DeltaAck>(text))
                WatchPaths.STATE -> WatchClient.onFrame(text)
            }
        }
    }
}
```

(`WatchClient` is created in Task 18; until then add a temporary `object WatchClient { fun onFrame(json: String) {}; fun onOutdated() {} }` in `WatchClient.kt` so the module compiles.)

Manifest: change the listener `<data android:pathPrefix="/rf" />` to `android:pathPrefix="/lf"`; keep `ExerciseService` with `foregroundServiceType="health"`; add `<uses-permission android:name="android.permission.ACCESS_FINE_LOCATION" />` (GPS outdoors).

- [ ] **Step 10: Make the watch UI compile** — in `MainActivity.kt` replace `PhoneHub.init(this)` / `WatchApp(WatchGraph.workout, WatchGraph.music, …)` with `WatchRuntime.init(this)` and a temporary `WatchApp(...)` call using `FakeMusicService` and a `DefaultWorkoutService` demo, or comment the `setContent` body to a `Text("LiveFit")` placeholder screen until Task 18 — Task 18 replaces it. Delete `PhoneLink.kt`, `PhoneHub.kt`, `WatchGraph.kt`.

Run: `export JAVA_HOME=$(/usr/libexec/java_home -v 17) && ./gradlew :watch:assembleDebug`
Expected: BUILD SUCCESSFUL.

- [ ] **Step 11: Device check (real sensors, takeover)**

1. `tools/install-all.sh`.
2. On the phone start a Walk. Expected: watch shows the workout notification icon (ongoing activity); phone Workout screen shows **real** heart rate within ~1 s.
3. Stop on the phone. Expected: "Saving workout…" then Summary; `adb -s $WATCH shell ls /data/data/com.debasish.livefit/files/lf-buffer` → empty.
4. Start a Samsung Health walk on the watch; then start on the phone. Expected: "Take over workout?" appears on the phone (watch/glasses after Tasks 18/21); answering Yes ends Samsung Health's walk and LiveFit tracks.

- [ ] **Step 12: Commit**

```bash
git add services/sync watch
git commit -m "feat(watch): session-scoped exercise controller on Health Services with ongoing activity"
```

---

### Task 18: Watch client — frames, offline mode, claims, controls, volume, confirmations

**Files:**
- Rewrite: `watch/src/main/java/com/debasish/livefit/watch/WatchClient.kt`
- Modify: `watch/src/main/java/com/debasish/livefit/watch/ui/WatchApp.kt`
- Create: `watch/src/main/java/com/debasish/livefit/watch/ui/WatchOverlays.kt` (volume arc, confirmation, offline badge, permission card)
- Modify: `watch/src/main/java/com/debasish/livefit/watch/MainActivity.kt`

**Interfaces:**
- Consumes: `WatchRuntime` (Task 17), `LivenessMonitor` (Task 9), `StateFrame`, `CommandEnvelope`, `SessionClaim` (Task 2).
- Produces:
  - `data class WatchUiState(val snapshot: WorkoutSnapshot, val music: NowPlaying?, val confirmation: Confirmation?, val phoneOnline: Boolean, val glassesOnline: Boolean, val offline: Boolean, val hrHistory: List<Int>, val needsPermissions: List<String>, val outdated: Boolean, val toast: String?)`
  - `object WatchClient { val ui: StateFlow<WatchUiState>; fun onFrame(json: String); fun onOutdated(); fun command(c: Command); fun setVolume(level: Float) }`
  - `@Composable fun WatchApp(state: WatchUiState, onCommand: (Command) -> Unit, onVolume: (Float) -> Unit, onGrantPermissions: () -> Unit)`

- [ ] **Step 1: Implement `WatchClient.kt`**

```kotlin
package com.debasish.livefit.watch

import com.debasish.livefit.model.Command
import com.debasish.livefit.model.CommandEnvelope
import com.debasish.livefit.model.Confirmation
import com.debasish.livefit.model.DeviceKind
import com.debasish.livefit.model.ExerciseError
import com.debasish.livefit.model.ExerciseState
import com.debasish.livefit.model.ExerciseStateReport
import com.debasish.livefit.model.LinkState
import com.debasish.livefit.model.NowPlaying
import com.debasish.livefit.model.StateFrame
import com.debasish.livefit.model.WatchPaths
import com.debasish.livefit.model.Wire
import com.debasish.livefit.model.WorkoutSnapshot
import com.debasish.livefit.services.Clock
import com.debasish.livefit.sync.LivenessMonitor
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import java.util.UUID

data class WatchUiState(
    val snapshot: WorkoutSnapshot = WorkoutSnapshot(),
    val music: NowPlaying? = null,
    val confirmation: Confirmation? = null,
    val phoneOnline: Boolean = false,
    val glassesOnline: Boolean = false,
    /** Phone unreachable while this watch holds an unfinished session (spec §4.4 step 4). */
    val offline: Boolean = false,
    val hrHistory: List<Int> = emptyList(),
    val needsPermissions: List<String> = emptyList(),
    val outdated: Boolean = false,
    val toast: String? = null,
)

/** Renders hub frames, falls back to the local session while the phone is offline, re-claims on reconnect. */
object WatchClient {
    private val liveness = LivenessMonitor(Clock { System.currentTimeMillis() })
    private val _ui = MutableStateFlow(WatchUiState())
    val ui: StateFlow<WatchUiState> = _ui
    private var lastFrame: StateFrame? = null
    private var wasOnline = false
    private var started = false
    private var lastVolumeSentMs = 0L

    fun start() {
        if (started) return
        started = true
        WatchRuntime.scope.launch { while (true) { tick(); delay(1_000) } }
        WatchRuntime.scope.launch { WatchRuntime.controller.lastError.collect { refresh() } }
    }

    fun onFrame(json: String) {
        val frame = runCatching { Wire.decode<StateFrame>(json) }.getOrNull() ?: return
        lastFrame = frame
        liveness.onFrame()
        if (!wasOnline) WatchRuntime.scope.launch { onReconnected() }
        wasOnline = true
        refresh()
    }

    fun onOutdated() { _ui.value = _ui.value.copy(outdated = true) }

    private suspend fun onReconnected() {
        val rec = WatchRuntime.recorder
        val claim = rec.claim()
        if (claim != null) {
            runCatching { WatchRuntime.send(WatchPaths.CLAIM, Wire.encode(claim).toByteArray()) }
            rec.resendUnacked()
        } else {
            // Tell the hub we hold nothing (lets it finalize a session whose data is gone, spec §4.9).
            runCatching { WatchRuntime.send(WatchPaths.EXERCISE_STATE, Wire.encode(ExerciseStateReport(sessionId = "", state = ExerciseState.Idle)).toByteArray()) }
        }
    }

    private suspend fun tick() {
        val online = liveness.isOnline()
        if (wasOnline && !online) wasOnline = false
        // Unacked deltas are re-sent every 5 s while online (spec §4.4 step 3).
        if (online && WatchRuntime.recorder.sessionId != null && (System.currentTimeMillis() / 1_000) % 5 == 0L) WatchRuntime.recorder.resendUnacked()
        refresh()
    }

    private fun refresh() {
        val online = liveness.isOnline()
        val local = WatchRuntime.recorder.assembler
        val offline = !online && local != null && WatchRuntime.controller.activeSessionId != null
        val f = lastFrame
        val missing = (WatchRuntime.controller.lastError.value as? ExerciseError.PermissionMissing)?.permissions ?: emptyList()
        _ui.value = if (offline) WatchUiState(
            snapshot = local!!.snapshot(), phoneOnline = false, offline = true,
            hrHistory = local.hrHistory(60), needsPermissions = missing,
        ) else WatchUiState(
            snapshot = f?.workout ?: WorkoutSnapshot(),
            music = f?.music,
            confirmation = f?.confirmation,
            phoneOnline = online,
            glassesOnline = f?.devices?.glasses?.link == LinkState.Connected,
            hrHistory = local?.hrHistory(60) ?: emptyList(),
            needsPermissions = missing,
            outdated = _ui.value.outdated,
            toast = f?.toast,
        )
    }

    fun command(c: Command) {
        WatchRuntime.scope.launch {
            if (!liveness.isOnline()) {
                // Offline: only workout controls work, applied locally and recorded as events.
                when (c) {
                    Command.PauseWorkout -> WatchRuntime.controller.localPause()
                    Command.ResumeWorkout -> WatchRuntime.controller.localResume()
                    Command.StopWorkout -> WatchRuntime.controller.localStop()
                    else -> Unit
                }
                refresh()
                return@launch
            }
            val env = CommandEnvelope(id = UUID.randomUUID().toString(), origin = DeviceKind.Watch, command = c)
            runCatching { WatchRuntime.send(WatchPaths.COMMAND, Wire.encode(env).toByteArray()) }
        }
    }

    /** Arc drag / bezel: throttled to ≤ 10 commands per second. */
    fun setVolume(level: Float) {
        val now = System.currentTimeMillis()
        if (now - lastVolumeSentMs < 100) return
        lastVolumeSentMs = now
        command(Command.SetVolume(level.coerceIn(0f, 1f)))
    }
}
```

- [ ] **Step 2: Overlays** — `WatchOverlays.kt`

```kotlin
package com.debasish.livefit.watch.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.focusable
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Check
import androidx.compose.material.icons.rounded.Close
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.input.rotary.onRotaryScrollEvent
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.wear.compose.material.Icon
import androidx.wear.compose.material.Text
import com.debasish.livefit.model.Confirmation
import kotlin.math.atan2

/**
 * Edge volume arc (bottom 120°). Drag along the edge or turn the bezel (5 % per detent).
 * [level] is the phone's real volume from the frame; [onChange] sends SetVolume.
 */
@Composable
fun VolumeArc(level: Float, onChange: (Float) -> Unit, modifier: Modifier = Modifier) {
    var local by remember(level) { mutableFloatStateOf(level) }
    val focus = remember { FocusRequester() }
    LaunchedEffect(Unit) { focus.requestFocus() }
    Canvas(
        modifier.fillMaxSize().padding(4.dp)
            .onRotaryScrollEvent { e -> local = (local + if (e.verticalScrollPixels > 0) 0.05f else -0.05f).coerceIn(0f, 1f); onChange(local); true }
            .focusRequester(focus).focusable()
            .pointerInput(Unit) {
                detectDragGestures { change, _ ->
                    val c = Offset(size.width / 2f, size.height / 2f)
                    val deg = Math.toDegrees(atan2((change.position.y - c.y).toDouble(), (change.position.x - c.x).toDouble())).toFloat()
                    // Arc spans 150° (left) → 30° (right) through 90° (bottom).
                    if (deg in 30f..150f) { local = ((150f - deg) / 120f).coerceIn(0f, 1f); onChange(local) }
                }
            },
    ) {
        val stroke = Stroke(width = 8.dp.toPx(), cap = StrokeCap.Round)
        drawArc(Color(0xFF26292D), 150f, -120f, false, style = stroke)
        drawArc(Color(0xFFFF6F9C), 150f, -120f * local, false, style = stroke)
    }
}

@Composable
fun ConfirmOverlay(c: Confirmation, onAnswer: (Boolean) -> Unit) {
    Box(Modifier.fillMaxSize().background(Color(0xEE000000)), contentAlignment = Alignment.Center) {
        Column(horizontalAlignment = Alignment.CenterHorizontally, modifier = Modifier.padding(24.dp)) {
            Text(c.title, fontSize = 16.sp, fontWeight = FontWeight.Bold, textAlign = TextAlign.Center)
            Text(c.message, fontSize = 12.sp, color = Color(0xFF9AA0A6), textAlign = TextAlign.Center, maxLines = 3)
            Spacer(Modifier.height(10.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                Box(Modifier.size(52.dp).clip(CircleShape).background(Color(0x33FF6B4F)).clickable { onAnswer(false) }, contentAlignment = Alignment.Center) {
                    Icon(Icons.Rounded.Close, contentDescription = c.noLabel, tint = Color(0xFFFF6B4F))
                }
                Box(Modifier.size(52.dp).clip(CircleShape).background(Color(0x3314C3A2)).clickable { onAnswer(true) }, contentAlignment = Alignment.Center) {
                    Icon(Icons.Rounded.Check, contentDescription = c.yesLabel, tint = Color(0xFF14C3A2))
                }
            }
        }
    }
}

@Composable
fun OfflineBadge(modifier: Modifier = Modifier) {
    Text("Phone offline", fontSize = 11.sp, color = Color(0xFFFFB627),
        modifier = modifier.clip(RoundedCornerShape(10.dp)).background(Color(0x33FFB627)).padding(horizontal = 8.dp, vertical = 2.dp))
}

@Composable
fun PermissionCard(perms: List<String>, onGrant: () -> Unit) {
    Box(Modifier.fillMaxSize().background(Color.Black), contentAlignment = Alignment.Center) {
        Column(horizontalAlignment = Alignment.CenterHorizontally, modifier = Modifier.padding(24.dp)) {
            Text("LiveFit needs sensor access", fontSize = 15.sp, fontWeight = FontWeight.Bold, textAlign = TextAlign.Center)
            Text(perms.joinToString { it.substringAfterLast('.') }, fontSize = 11.sp, color = Color(0xFF9AA0A6), textAlign = TextAlign.Center)
            Spacer(Modifier.height(10.dp))
            Text("Allow", fontSize = 15.sp, color = Color.Black, modifier = Modifier.clip(RoundedCornerShape(20.dp)).background(Color(0xFF14C3A2)).clickable(onClick = onGrant).padding(horizontal = 20.dp, vertical = 8.dp))
        }
    }
}
```

- [ ] **Step 3: Update `WatchApp.kt`** — replace the top-level `WatchApp` composable and thread state through. New top-level function (replaces the existing `WatchApp(workout, music, hrHistory, lastFrameAt)`):

```kotlin
@Composable
fun WatchApp(state: WatchUiState, onCommand: (Command) -> Unit, onVolume: (Float) -> Unit, onGrantPermissions: () -> Unit) {
    val s = state.snapshot
    MaterialTheme {
        Box(Modifier.fillMaxSize().background(Color.Black)) {
            when (s.phase) {
                WorkoutPhase.Idle -> Ready(state.phoneOnline, state.glassesOnline) { onCommand(Command.StartWorkout(it)) }
                WorkoutPhase.Summary -> Summary(s) { onCommand(Command.DismissSummary) }
                WorkoutPhase.Stopping -> Saving(s)
                else -> Live(s, state, onCommand, onVolume)
            }
            if (state.offline) OfflineBadge(Modifier.align(Alignment.TopCenter).padding(top = 18.dp))
            state.confirmation?.let { c -> ConfirmOverlay(c) { yes -> onCommand(Command.Answer(c.id, yes)) } }
            if (state.needsPermissions.isNotEmpty() && s.phase == WorkoutPhase.Idle) PermissionCard(state.needsPermissions, onGrantPermissions)
        }
    }
}

@Composable
private fun Saving(s: WorkoutSnapshot) {
    Column(Modifier.fillMaxSize(), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.Center) {
        Icon(s.displayType.icon, contentDescription = null, tint = W.Mint, modifier = Modifier.size(28.dp))
        Text("Saving workout…", fontSize = 15.sp)
        Text(formatElapsed(s.elapsedMs), fontSize = 13.sp, color = W.Dim)
    }
}
```

Then adapt the existing composables:
- `Ready(phoneOnline: Boolean, glassesOnline: Boolean, onStart: (WorkoutType) -> Unit)` — use `glassesOnline` for the glasses `StatusDot`.
- `Live(s, state, onCommand, onVolume)` — pager pages: `HeartPage(s, state.hrHistory, onCommand)`, `StatsPage(s)`, `MusicPage(state.music, onCommand, onVolume)`.
- `HeartPage`: pause/resume/stop buttons call `onCommand(Command.PauseWorkout)` / `ResumeWorkout` / `StopWorkout`; the zone label uses `zoneLabel(zone)` from `:core:model` (shows "–" below Z1): `Text(if (zone != null && zone > 0) "${zoneLabel(zone)} · ${zoneName(zone)}" else "bpm", …)`.
- `MusicPage(np: NowPlaying?, onCommand, onVolume)` — buttons send `PreviousTrack` / `PlayPause` / `NextTrack` / `LikeTrack`; wrap the page content in a `Box` and add `VolumeArc(level = np?.volume ?: 0.5f, onChange = onVolume)` as the first child so the arc sits along the bottom edge.
- `Summary(s, onDone)` unchanged.
- Remove the `StateFlow` parameters and `PhoneHub` imports.

- [ ] **Step 4: `MainActivity.kt`**

```kotlin
class MainActivity : ComponentActivity() {
    private val perms = arrayOf(Manifest.permission.BODY_SENSORS, "android.permission.health.READ_HEART_RATE",
        Manifest.permission.ACTIVITY_RECOGNITION, Manifest.permission.POST_NOTIFICATIONS, Manifest.permission.ACCESS_FINE_LOCATION)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        WatchRuntime.init(this)
        WatchClient.start()
        setContent {
            val state by WatchClient.ui.collectAsStateWithLifecycle()
            WatchApp(state, onCommand = WatchClient::command, onVolume = WatchClient::setVolume, onGrantPermissions = { requestPermissions(perms, 1) })
        }
    }
}
```

Remove the `livefit://workout/start` deep-link `<intent-filter>` from the watch manifest and the `handle(intent)` code (the hub now starts the exercise via `ExerciseRequest`).

- [ ] **Step 5: Build**

Run: `export JAVA_HOME=$(/usr/libexec/java_home -v 17) && ./gradlew :watch:assembleDebug`
Expected: BUILD SUCCESSFUL.

- [ ] **Step 6: Device check — sync, offline, volume**

1. `tools/install-all.sh`; start a Walk from the **watch**. Expected: phone and watch both show Walk with real HR; the watch's phone dot is green.
2. Pause from the phone → watch shows paused within ~1 s; resume from the watch → phone resumes.
3. Music page on the watch: turn the bezel → phone media volume changes; the arc follows the phone's level.
4. Offline: `adb -s $PHONE shell cmd bluetooth_manager disable` for 60 s, keep walking, pause and resume on the watch, then `… enable`. Expected: watch shows "Phone offline" after ~12 s and keeps counting; after re-enable the phone shows "Syncing watch data…", then the correct total active time (pause excluded) and continuous HR.
5. Stop on the watch → phone "Saving workout…" → Summary on all devices.

- [ ] **Step 7: Commit**

```bash
git add watch
git commit -m "feat(watch): hub client with offline mode, claims, confirmations and bezel volume"
```

---
## Phase 3 — Glasses

### Task 19: Glasses — protocol v1, liveness, Saving/zone states

**Files:**
- Rewrite: `glasses/src/main/java/com/debasish/livefit/glasses/hud/HudController.kt`
- Modify: `glasses/src/main/java/com/debasish/livefit/glasses/hud/HudScreen.kt`
- Modify: `glasses/src/main/java/com/debasish/livefit/glasses/MainActivity.kt`
- Modify: `glasses/build.gradle.kts` (dependencies)
- Test: `glasses/src/test/java/com/debasish/livefit/glasses/hud/HudConnectionTest.kt`

**Interfaces:**
- Consumes: `StateFrame`, `HudSettingsFrame`, `HudSettings`, `CommandEnvelope`, `GlassesChannels`, `Wire`, `zoneLabel` (Task 2); `LivenessMonitor` (Task 9).
- Produces:
  - `enum class HudConnection { Connecting, OpenPhoneApp, Live, Outdated }`
  - `fun connectionFor(hasEverReceived: Boolean, online: Boolean, outdated: Boolean, sinceStartMs: Long): HudConnection` (pure; Connecting for the first 12 s, then OpenPhoneApp while no frames)
  - `class HudController(scope: CoroutineScope, bridge: CXRServiceBridge, prefs: SharedPreferences)` with `val frame: StateFlow<StateFrame?>`, `val settings: StateFlow<HudSettings>`, `val connection: StateFlow<HudConnection>`, `val hrHistory: StateFlow<List<Int>>`, `fun send(command: Command)`, `fun sendRaw(channel: String, text: String)`
  - `@Composable fun HudScreen(frame: StateFrame?, settings: HudSettings, connection: HudConnection, mode: HudMode, glassesBattery: Int?, hrHistory: List<Int>, overlay: HudOverlay = HudOverlay.None)` (`HudOverlay` added in Task 20/21; define it now as `sealed interface HudOverlay { data object None : HudOverlay }`)

- [ ] **Step 1: Glasses dependencies** — in `glasses/build.gradle.kts` replace `:services:workout`, `:services:metrics`, `:services:music` with:

```kotlin
    implementation(project(":services:sync"))
    implementation(project(":services:voice"))
    testImplementation(kotlin("test"))
    testImplementation("junit:junit:4.13.2")
```
and add `sourceSets["test"].java.srcDirs("src/test/java")` inside `android { }` if not default.

- [ ] **Step 2: Write the failing test** — `HudConnectionTest.kt`

```kotlin
package com.debasish.livefit.glasses.hud

import kotlin.test.Test
import kotlin.test.assertEquals

class HudConnectionTest {
    @Test fun connectingForFirst12sThenOpenPhoneApp() {
        assertEquals(HudConnection.Connecting, connectionFor(hasEverReceived = false, online = false, outdated = false, sinceStartMs = 11_000))
        assertEquals(HudConnection.OpenPhoneApp, connectionFor(hasEverReceived = false, online = false, outdated = false, sinceStartMs = 12_000))
    }
    @Test fun liveWhileFramesArrive() = assertEquals(HudConnection.Live, connectionFor(true, online = true, outdated = false, sinceStartMs = 60_000))
    @Test fun lostAfterFramesShowsConnectingNotOpenApp() =
        assertEquals(HudConnection.Connecting, connectionFor(hasEverReceived = true, online = false, outdated = false, sinceStartMs = 60_000))
    @Test fun outdatedWins() = assertEquals(HudConnection.Outdated, connectionFor(true, online = true, outdated = true, sinceStartMs = 1))
}
```

- [ ] **Step 3: Run test to verify it fails**

Run: `export JAVA_HOME=$(/usr/libexec/java_home -v 17) && ./gradlew :glasses:testDebugUnitTest`
Expected: FAIL — unresolved `connectionFor`.

- [ ] **Step 4: Rewrite `HudController.kt`**

```kotlin
package com.debasish.livefit.glasses.hud

import android.content.SharedPreferences
import android.util.Log
import com.debasish.livefit.model.Command
import com.debasish.livefit.model.CommandEnvelope
import com.debasish.livefit.model.DeviceKind
import com.debasish.livefit.model.GlassesChannels
import com.debasish.livefit.model.HudSettings
import com.debasish.livefit.model.HudSettingsFrame
import com.debasish.livefit.model.PROTOCOL_VERSION
import com.debasish.livefit.model.StateFrame
import com.debasish.livefit.model.Wire
import com.debasish.livefit.services.Clock
import com.debasish.livefit.sync.LivenessMonitor
import com.rokid.cxr.CXRServiceBridge
import com.rokid.cxr.Caps
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import java.util.UUID

enum class HudConnection { Connecting, OpenPhoneApp, Live, Outdated }

/** Pure: what the HUD shows about the phone link (spec §4.3 liveness, §5.3). */
fun connectionFor(hasEverReceived: Boolean, online: Boolean, outdated: Boolean, sinceStartMs: Long): HudConnection = when {
    outdated -> HudConnection.Outdated
    online -> HudConnection.Live
    !hasEverReceived && sinceStartMs >= 12_000 -> HudConnection.OpenPhoneApp
    else -> HudConnection.Connecting
}

/** Glasses side of the CXR link: renders hub frames; sends commands and push-to-talk audio. */
class HudController(private val scope: CoroutineScope, private val bridge: CXRServiceBridge, private val prefs: SharedPreferences) {
    private val clock = Clock { System.currentTimeMillis() }
    private val liveness = LivenessMonitor(clock)
    private val startedAt = clock.nowMs()
    private var everReceived = false
    private var outdated = false

    private val _frame = MutableStateFlow<StateFrame?>(null)
    val frame: StateFlow<StateFrame?> = _frame
    private val _settings = MutableStateFlow(loadSettings())
    val settings: StateFlow<HudSettings> = _settings
    private val _connection = MutableStateFlow(HudConnection.Connecting)
    val connection: StateFlow<HudConnection> = _connection
    private val _hrHistory = MutableStateFlow<List<Int>>(emptyList())
    val hrHistory: StateFlow<List<Int>> = _hrHistory
    private var lastSampleSecond = -1L

    fun start() {
        bridge.subscribe(GlassesChannels.STATE, CXRServiceBridge.MsgCallback { _, caps, _ -> onState(caps) })
        bridge.subscribe(GlassesChannels.SETTINGS, CXRServiceBridge.MsgCallback { _, caps, _ -> onSettings(caps) })
        scope.launch { while (true) { refreshConnection(); delay(1_000) } }
    }

    private fun text(caps: Caps?) = runCatching { caps?.at(0)?.string }.getOrNull()

    private fun onState(caps: Caps?) {
        val t = text(caps) ?: return
        if (Wire.versionOf(t) != PROTOCOL_VERSION) { outdated = true; refreshConnection(); return }
        val f = runCatching { Wire.decode<StateFrame>(t) }.getOrElse { Log.w(TAG, "bad frame", it); return }
        everReceived = true
        liveness.onFrame()
        _frame.value = f
        val sec = f.workout.elapsedMs / 1_000
        val hr = f.workout.metrics.heartRate
        if (f.workout.elapsedMs == 0L) _hrHistory.value = emptyList()
        if (hr != null && sec != lastSampleSecond) { lastSampleSecond = sec; _hrHistory.value = (_hrHistory.value + hr).takeLast(HR_HISTORY) }
        refreshConnection()
    }

    private fun onSettings(caps: Caps?) {
        val t = text(caps) ?: return
        if (Wire.versionOf(t) != PROTOCOL_VERSION) return
        val s = runCatching { Wire.decode<HudSettingsFrame>(t).settings }.getOrNull() ?: return
        _settings.value = s
        prefs.edit().putString(KEY_SETTINGS, Wire.encode(s)).apply() // keep layout across restarts
    }

    private fun loadSettings(): HudSettings =
        prefs.getString(KEY_SETTINGS, null)?.let { runCatching { Wire.decode<HudSettings>(it) }.getOrNull() } ?: HudSettings()

    private fun refreshConnection() {
        _connection.value = connectionFor(everReceived, liveness.isOnline(), outdated, clock.nowMs() - startedAt)
    }

    fun send(command: Command) =
        sendRaw(GlassesChannels.COMMAND, Wire.encode(CommandEnvelope(id = UUID.randomUUID().toString(), origin = DeviceKind.Glasses, command = command)))

    fun sendRaw(channel: String, text: String) { bridge.sendMessage(channel, Caps().apply { write(text) }) }

    companion object {
        const val TAG = "LiveFitGlasses"
        private const val KEY_SETTINGS = "hudSettings"
    }
}
```

- [ ] **Step 5: Update `HudScreen.kt`**
  1. Replace the `HudScreen` signature and add the overlay type:

```kotlin
sealed interface HudOverlay { data object None : HudOverlay }

@Composable
fun HudScreen(
    frame: StateFrame?,
    settings: HudSettings,
    connection: HudConnection,
    mode: HudMode,
    glassesBattery: Int?,
    hrHistory: List<Int>,
    overlay: HudOverlay = HudOverlay.None,
) {
    val phase = frame?.workout?.phase ?: WorkoutPhase.Idle
    val inWorkout = phase == WorkoutPhase.Starting || phase == WorkoutPhase.Active || phase == WorkoutPhase.Paused || phase == WorkoutPhase.Syncing
    Box(Modifier.fillMaxSize().background(Color.Black).padding(10.dp)) {
        Scaled(settings.scale.coerceIn(0.3f, 1f), settings.position.alignment) {
            Box(Modifier.fillMaxSize().padding(horizontal = 18.dp, vertical = 16.dp)) {
                when {
                    connection == HudConnection.Outdated -> Message("Update LiveFit", "on your glasses")
                    frame == null && connection == HudConnection.OpenPhoneApp -> WaitingForPhone()
                    frame == null -> Message("Connecting…", "to your phone")
                    inWorkout && mode == HudMode.Full -> Full(frame, settings, glassesBattery, hrHistory)
                    inWorkout -> Glance(frame)
                    phase == WorkoutPhase.Stopping -> Message("Saving workout…", formatElapsed(frame.workout.elapsedMs))
                    phase == WorkoutPhase.Summary -> SummaryCard(frame)
                    else -> Ready(frame, glassesBattery)
                }
                if (frame != null) {
                    val band = Modifier.align(Alignment.Center).offset(y = (-40).dp)
                    when {
                        frame.voice != VoiceState.Idle -> Listening(band, frame.voice)
                        frame.toast != null -> Toast(frame.toast!!, band)
                        phase == WorkoutPhase.Syncing -> Toast("Syncing watch…", band)
                        phase == WorkoutPhase.Paused -> PausedBadge(band)
                    }
                }
                if (connection == HudConnection.Connecting && frame != null) Label("phone reconnecting…", 22.sp, Hud.TERTIARY)
            }
        }
    }
}

@Composable
private fun Message(title: String, subtitle: String) {
    Column(Modifier.fillMaxSize(), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.Center) {
        Label(title, 32.sp, Hud.SECONDARY, FontWeight.Bold)
        Label(subtitle, 26.sp, Hud.TERTIARY)
    }
}
```

  2. `Full(frame: HudFrame, …)` → `Full(frame: StateFrame, settings: HudSettings, battery: Int?, hrHistory: List<Int>)`; inside use `val show = settings.items`.
  3. In `Full`, `Ready`: `frame.watchBattery` → `frame.devices.watch.batteryPct`; `frame.phoneBattery` → `frame.devices.phone.batteryPct`; `frame.watch == LinkState.Connected` → `frame.devices.watch.link == LinkState.Connected`; `frame.phone == LinkState.Connected` → `frame.devices.phone.link == LinkState.Connected`.
  4. Every remaining `HudFrame` parameter type → `StateFrame`.
  5. Heart trend label: `Label("  Z${zone ?: "-"}", 30.sp, Hud.SECONDARY, FontWeight.Bold)` → `Label("  ${zoneLabel(zone)}", 30.sp, Hud.SECONDARY, FontWeight.Bold)`.
  6. Delete the private `ZoneBar` composable (unused since the single-chart change).
  7. Imports: `com.debasish.livefit.model.StateFrame`, `com.debasish.livefit.model.zoneLabel`; remove `HudFrame`.

- [ ] **Step 6: `MainActivity.kt`** — construct the new controller and pass the new parameters

```kotlin
        controller = HudController(lifecycleScope, bridge, getSharedPreferences("hud", 0)).also { it.start() }
        val batteryManager = getSystemService(BatteryManager::class.java)
        setContent {
            var battery by remember { mutableStateOf(batteryManager.getIntProperty(BatteryManager.BATTERY_PROPERTY_CAPACITY)) }
            LaunchedEffect(Unit) { while (true) { delay(30_000); battery = batteryManager.getIntProperty(BatteryManager.BATTERY_PROPERTY_CAPACITY) } }
            val frame by controller.frame.collectAsStateWithLifecycle()
            val settings by controller.settings.collectAsStateWithLifecycle()
            val connection by controller.connection.collectAsStateWithLifecycle()
            val history by controller.hrHistory.collectAsStateWithLifecycle()
            HudScreen(frame, settings, connection, mode, battery, history)
        }
```

`onKeyUp` ENTER/DPAD_CENTER: temporarily `controller.sendRaw(GlassesChannels.LISTEN, "{}")` (Task 20 replaces it with real push-to-talk). Remove the demo services (the HUD now always reflects the hub; "Connecting…"/"Open LiveFit on your phone" when there is none).

- [ ] **Step 7: Run tests and build**

Run: `export JAVA_HOME=$(/usr/libexec/java_home -v 17) && ./gradlew :glasses:testDebugUnitTest :glasses:assembleDebug`
Expected: PASS and BUILD SUCCESSFUL.

- [ ] **Step 8: Device check**

`tools/install-all.sh`; open LiveFit on the phone (glasses open automatically). Expected: glasses show "LiveFit ready" with battery rings; start a workout on the watch → HUD shows live HR; change HUD position on the phone → moves; stop → "Saving workout…" → summary; close the phone app's session (force-stop LiveFit on the phone) → after 12 s the HUD shows "phone reconnecting…".

- [ ] **Step 9: Commit**

```bash
git add glasses
git commit -m "feat(glasses): protocol v1 HUD with liveness, saving state and persisted layout"
```

---

### Task 20: Glasses push-to-talk

**Files:**
- Create: `glasses/src/main/java/com/debasish/livefit/glasses/voice/PushToTalk.kt`
- Modify: `glasses/src/main/java/com/debasish/livefit/glasses/MainActivity.kt`
- Modify: `glasses/src/main/java/com/debasish/livefit/glasses/hud/HudScreen.kt` (local listening overlay)
- Test: `glasses/src/test/java/com/debasish/livefit/glasses/voice/ChunkerTest.kt`

**Interfaces:**
- Consumes: `EnergyVad`, `VadDecision` (Task 16), `HudController.sendRaw` (Task 19), `GlassesChannels.LISTEN/AUDIO/LISTEN_END`.
- Produces:
  - `object AudioChunks { fun encode(pcm: ByteArray): String; fun split(pcm: ByteArray, chunkBytes: Int = 3_200): List<ByteArray> }` (Base64 NO_WRAP via `java.util.Base64` so it is JVM-testable)
  - `class PushToTalk(sendRaw: (String, String) -> Unit)` with `val recording: StateFlow<Boolean>`, `fun toggle(maxMs: Int = 6_000)`, `fun start(maxMs: Int = 6_000)`, `fun stop()`
  - `HudOverlay.LocalListening` (shown immediately on tap, before the phone's frame reports `VoiceState.Listening`)

- [ ] **Step 1: Write the failing test**

```kotlin
package com.debasish.livefit.glasses.voice

import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals

class ChunkerTest {
    @Test fun splitsIntoHundredMillisecondChunks() {
        val pcm = ByteArray(8_000) { it.toByte() }
        val chunks = AudioChunks.split(pcm)
        assertEquals(listOf(3_200, 3_200, 1_600), chunks.map { it.size })
    }

    @Test fun base64RoundTrips() {
        val pcm = ByteArray(3_200) { (it * 7).toByte() }
        assertContentEquals(pcm, java.util.Base64.getDecoder().decode(AudioChunks.encode(pcm)))
    }
}
```

- [ ] **Step 2: Run test to verify it fails**

Run: `export JAVA_HOME=$(/usr/libexec/java_home -v 17) && ./gradlew :glasses:testDebugUnitTest --tests '*ChunkerTest*'`
Expected: FAIL — `AudioChunks` unresolved.

- [ ] **Step 3: Implement `PushToTalk.kt`**

```kotlin
package com.debasish.livefit.glasses.voice

import android.annotation.SuppressLint
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import com.debasish.livefit.model.GlassesChannels
import com.debasish.livefit.services.voice.EnergyVad
import com.debasish.livefit.services.voice.VadDecision
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlin.concurrent.thread

object AudioChunks {
    fun encode(pcm: ByteArray): String = java.util.Base64.getEncoder().encodeToString(pcm)
    fun split(pcm: ByteArray, chunkBytes: Int = 3_200): List<ByteArray> =
        (pcm.indices step chunkBytes).map { pcm.copyOfRange(it, minOf(it + chunkBytes, pcm.size)) }
}

/**
 * Tap-to-talk on the glasses: records with the glasses' own mic (the CXR-L stream is silent —
 * spec §2.1), stops on VAD or [maxMs], streams 100 ms chunks to the phone.
 */
class PushToTalk(private val sendRaw: (String, String) -> Unit) {
    private val _recording = MutableStateFlow(false)
    val recording: StateFlow<Boolean> = _recording
    @Volatile private var stopRequested = false

    fun toggle(maxMs: Int = 6_000) = if (_recording.value) stop() else start(maxMs)

    fun stop() { stopRequested = true }

    @SuppressLint("MissingPermission") // RECORD_AUDIO granted at install via adb / first-run prompt
    fun start(maxMs: Int = 6_000) {
        if (_recording.value) return
        _recording.value = true
        stopRequested = false
        sendRaw(GlassesChannels.LISTEN, "{}")
        thread(name = "ptt") {
            val min = AudioRecord.getMinBufferSize(16_000, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT)
            val rec = AudioRecord(MediaRecorder.AudioSource.MIC, 16_000, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT, maxOf(min, 6_400))
            val vad = EnergyVad(maxMs = maxMs)
            val chunk = ByteArray(3_200)
            try {
                rec.startRecording()
                while (!stopRequested) {
                    val n = rec.read(chunk, 0, chunk.size)
                    if (n <= 0) break
                    val bytes = chunk.copyOf(n)
                    sendRaw(GlassesChannels.AUDIO, AudioChunks.encode(bytes))
                    if (vad.feed(bytes) != VadDecision.Continue) break
                }
            } finally {
                runCatching { rec.stop() }
                rec.release()
                sendRaw(GlassesChannels.LISTEN_END, "{}")
                _recording.value = false
            }
        }
    }
}
```

The phone decodes `lf_audio` with `android.util.Base64.decode(text, NO_WRAP)` (Task 14) — standard Base64 without line breaks on both sides.

- [ ] **Step 4: Wire into `MainActivity`**

```kotlin
    private lateinit var ptt: PushToTalk
```
in `onCreate` after the controller: `ptt = PushToTalk(controller::sendRaw)`; in `setContent` collect `val listening by ptt.recording.collectAsStateWithLifecycle()` and pass `overlay = if (listening) HudOverlay.LocalListening else HudOverlay.None` to `HudScreen`; in `onKeyUp` ENTER/DPAD_CENTER → `ptt.toggle()`.

Glasses manifest already declares `RECORD_AUDIO`; grant once: `adb -s $GLASSES shell pm grant com.debasish.livefit.glasses android.permission.RECORD_AUDIO` (add this line to `tools/install-all.sh` after the glasses install).

- [ ] **Step 5: Local listening overlay** — in `HudScreen.kt`:

```kotlin
sealed interface HudOverlay {
    data object None : HudOverlay
    data object LocalListening : HudOverlay
}
```
and in the overlay `when` inside `HudScreen`, before the `frame.voice` branch:
```kotlin
                        overlay == HudOverlay.LocalListening -> Listening(band, VoiceState.Listening)
```
(move the `if (frame != null)` guard so `LocalListening` shows even before the first frame arrives.)

- [ ] **Step 6: Run tests and build**

Run: `export JAVA_HOME=$(/usr/libexec/java_home -v 17) && ./gradlew :glasses:testDebugUnitTest :glasses:assembleDebug`
Expected: PASS, BUILD SUCCESSFUL.

- [ ] **Step 7: Device check**

`tools/install-all.sh`; during a workout tap the glasses touchpad, say "next song".
Expected: listening ring appears immediately; within ~1.5 s after you stop speaking the HUD shows "✓ Next song" and YouTube Music skips. Say "stop workout" → "End workout?" confirmation on phone/watch (glasses overlay in Task 21).

- [ ] **Step 8: Commit**

```bash
git add glasses tools/install-all.sh
git commit -m "feat(glasses): tap-to-talk with on-glasses mic, VAD and streamed audio"
```

---

### Task 21: Glasses confirmation overlay (touchpad + voice)

**Files:**
- Create: `glasses/src/main/java/com/debasish/livefit/glasses/hud/ConfirmOverlay.kt`
- Create: `glasses/src/main/java/com/debasish/livefit/glasses/hud/ConfirmInput.kt`
- Test: `glasses/src/test/java/com/debasish/livefit/glasses/hud/ConfirmInputTest.kt`
- Modify: `glasses/src/main/java/com/debasish/livefit/glasses/MainActivity.kt`, `hud/HudScreen.kt`

**Interfaces:**
- Consumes: `Confirmation` in `StateFrame` (Task 2), `HudController.send` (Task 19), `PushToTalk.start` (Task 20).
- Produces:
  - `class ConfirmInput { val highlightYes: Boolean; fun onConfirmation(c: Confirmation?): Boolean /* true when a new id appeared → auto mic */; fun onSwipe(); fun onTap(): Command.Answer?; fun onBack(): Command.Answer? }`
  - `@Composable fun ConfirmOverlay(c: Confirmation, highlightYes: Boolean, listening: Boolean, modifier: Modifier)`

- [ ] **Step 1: Write the failing test**

```kotlin
package com.debasish.livefit.glasses.hud

import com.debasish.livefit.model.Command
import com.debasish.livefit.model.Confirmation
import com.debasish.livefit.model.ConfirmationKind
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class ConfirmInputTest {
    private fun c(id: String, defaultYes: Boolean = true) = Confirmation(id, ConfirmationKind.TakeOverWorkout, "Take over?", "", defaultYes = defaultYes, expiresAtMs = 0)

    @Test fun newConfirmationStartsOnDefaultAndRequestsMicOnce() {
        val i = ConfirmInput()
        assertTrue(i.onConfirmation(c("a")))
        assertTrue(i.highlightYes)
        assertFalse(i.onConfirmation(c("a")), "same id: no second auto mic")
        assertTrue(i.onConfirmation(c("b", defaultYes = false)))
        assertFalse(i.highlightYes)
    }

    @Test fun swipeTogglesAndTapAnswersHighlighted() {
        val i = ConfirmInput(); i.onConfirmation(c("a"))
        i.onSwipe()
        assertEquals(Command.Answer("a", yes = false), i.onTap())
    }

    @Test fun backMeansNo() {
        val i = ConfirmInput(); i.onConfirmation(c("a"))
        assertEquals(Command.Answer("a", yes = false), i.onBack())
    }

    @Test fun nothingPendingMeansNoAnswer() {
        val i = ConfirmInput(); i.onConfirmation(null)
        assertNull(i.onTap()); assertNull(i.onBack())
    }
}
```

- [ ] **Step 2: Run test to verify it fails**

Run: `export JAVA_HOME=$(/usr/libexec/java_home -v 17) && ./gradlew :glasses:testDebugUnitTest --tests '*ConfirmInputTest*'`
Expected: FAIL — `ConfirmInput` unresolved.

- [ ] **Step 3: Implement `ConfirmInput.kt`**

```kotlin
package com.debasish.livefit.glasses.hud

import com.debasish.livefit.model.Command
import com.debasish.livefit.model.Confirmation

/** Touchpad rules while a confirmation is shown (spec §6.3): swipe moves, tap confirms, back = No. */
class ConfirmInput {
    private var current: Confirmation? = null
    var highlightYes: Boolean = true
        private set

    /** Returns true when a new confirmation appeared (caller opens the mic for ~6 s). */
    fun onConfirmation(c: Confirmation?): Boolean {
        val isNew = c != null && c.id != current?.id
        current = c
        if (isNew) highlightYes = c!!.defaultYes
        return isNew
    }

    fun onSwipe() { if (current != null) highlightYes = !highlightYes }
    fun onTap(): Command.Answer? = current?.let { Command.Answer(it.id, highlightYes) }
    fun onBack(): Command.Answer? = current?.let { Command.Answer(it.id, yes = false) }
}
```

- [ ] **Step 4: Implement `ConfirmOverlay.kt`**

```kotlin
package com.debasish.livefit.glasses.hud

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Check
import androidx.compose.material.icons.outlined.Close
import androidx.compose.material.icons.outlined.Mic
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.debasish.livefit.model.Confirmation

/** Outlined ✓ Yes / ✕ No; highlight = brighter + thicker outline (monochrome rules, spec §6.3). */
@Composable
fun ConfirmOverlay(c: Confirmation, highlightYes: Boolean, listening: Boolean, modifier: Modifier = Modifier) {
    Column(
        modifier.background(Color.Black).border(2.dp, Hud.Green.copy(alpha = Hud.SECONDARY), RoundedCornerShape(16.dp)).padding(16.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Label(c.title, 28.sp, Hud.PRIMARY, FontWeight.Bold)
        Spacer(Modifier.height(10.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(16.dp)) {
            Choice(Icons.Outlined.Check, c.yesLabel, selected = highlightYes)
            Choice(Icons.Outlined.Close, c.noLabel, selected = !highlightYes)
        }
        if (listening) {
            Spacer(Modifier.height(8.dp))
            Row(verticalAlignment = Alignment.CenterVertically) {
                Glyph(Icons.Outlined.Mic, 22.dp, Hud.SECONDARY)
                Label(" say yes or no", 20.sp, Hud.TERTIARY)
            }
        }
    }
}

@Composable
private fun Choice(icon: ImageVector, label: String, selected: Boolean) {
    val level = if (selected) Hud.PRIMARY else Hud.TERTIARY
    Row(
        Modifier.border(if (selected) 4.dp else 2.dp, Hud.Green.copy(alpha = level), RoundedCornerShape(12.dp)).padding(horizontal = 14.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Glyph(icon, 28.dp, level)
        Label(" $label", 26.sp, level, FontWeight.Bold)
    }
}
```

- [ ] **Step 5: Wire it** — `HudOverlay` gains:

```kotlin
    data class Confirm(val confirmation: Confirmation, val highlightYes: Boolean, val listening: Boolean) : HudOverlay
```
and in `HudScreen`'s overlay `when` (first branch, highest priority):
```kotlin
                        overlay is HudOverlay.Confirm -> ConfirmOverlay(overlay.confirmation, overlay.highlightYes, overlay.listening, band)
```

`MainActivity`:

```kotlin
    private val confirmInput = ConfirmInput()
    private var highlightYes by mutableStateOf(true)
```
In `setContent`, after collecting `frame`:
```kotlin
            LaunchedEffect(frame?.confirmation?.id) {
                if (confirmInput.onConfirmation(frame?.confirmation)) ptt.start(maxMs = 6_000) // auto mic for a spoken answer
                highlightYes = confirmInput.highlightYes
            }
            val confirmation = frame?.confirmation
            val overlay = when {
                confirmation != null -> HudOverlay.Confirm(confirmation, highlightYes, listening)
                listening -> HudOverlay.LocalListening
                else -> HudOverlay.None
            }
```
`onKeyUp`:
```kotlin
        val pending = controller.frame.value?.confirmation != null
        when (keyCode) {
            KeyEvent.KEYCODE_ENTER, KeyEvent.KEYCODE_DPAD_CENTER ->
                if (pending) confirmInput.onTap()?.let { controller.send(it) } else ptt.toggle()
            KeyEvent.KEYCODE_DPAD_LEFT, KeyEvent.KEYCODE_DPAD_RIGHT, KeyEvent.KEYCODE_DPAD_UP, KeyEvent.KEYCODE_DPAD_DOWN -> {
                val now = System.currentTimeMillis()
                if (now - lastSwipe > 350) {
                    if (pending) { confirmInput.onSwipe(); highlightYes = confirmInput.highlightYes }
                    else mode = if (mode == HudMode.Full) HudMode.Glance else HudMode.Full
                }
                lastSwipe = now
            }
            else -> return super.onKeyUp(keyCode, event)
        }
        return true
```
and override back so a double-tap answers No instead of leaving the app while a confirmation is pending:
```kotlin
    @Deprecated("Deprecated in Java")
    override fun onBackPressed() {
        val answer = confirmInput.onBack()
        if (controller.frame.value?.confirmation != null && answer != null) controller.send(answer) else super.onBackPressed()
    }
```

- [ ] **Step 6: Run tests and build**

Run: `export JAVA_HOME=$(/usr/libexec/java_home -v 17) && ./gradlew :glasses:testDebugUnitTest :glasses:assembleDebug`
Expected: PASS, BUILD SUCCESSFUL.

- [ ] **Step 7: Device check — takeover confirmed from the glasses**

Start a Samsung Health walk on the watch, then start LiveFit from the phone. Expected: phone, watch and glasses all show "Take over workout?"; on the glasses swipe to ✕, swipe back to ✓, tap → all three dismiss and LiveFit starts. Repeat answering "no" by voice on the glasses → all dismiss, Samsung Health keeps tracking. Repeat with no answer → after 15 s all show "Cancelled".

- [ ] **Step 8: Commit**

```bash
git add glasses
git commit -m "feat(glasses): confirmation overlay with touchpad and auto-mic voice answers"
```

---
## Phase 4 — Phone UI

### Task 22: Workout, confirmation and volume on the hub

**Files:**
- Create: `phone/src/main/java/com/debasish/livefit/phone/ui/Throttle.kt`
- Test: `phone/src/test/java/com/debasish/livefit/phone/ui/ThrottleTest.kt`
- Create: `phone/src/main/java/com/debasish/livefit/phone/ui/components/HubConfirmationDialog.kt`
- Create: `phone/src/main/java/com/debasish/livefit/phone/ui/components/OutdatedBanner.kt`
- Modify: `phone/src/main/java/com/debasish/livefit/phone/ui/workout/WorkoutScreen.kt`
- Modify: `phone/src/main/java/com/debasish/livefit/phone/ui/music/MusicScreen.kt`
- Modify: `phone/src/main/java/com/debasish/livefit/phone/ui/home/HomeScreen.kt`
- Modify: `phone/src/main/java/com/debasish/livefit/phone/ui/AppActivity.kt`
- Modify: `phone/build.gradle.kts` (unit test deps)

**Interfaces:**
- Consumes: `ServiceGraph.localCommand`, `confirm.pending`, `router.outdated`, `workout.snapshot` (Task 12).
- Produces: `class Throttle(minIntervalMs: Long, now: () -> Long = System::currentTimeMillis) { fun allow(): Boolean }`, `@Composable fun HubConfirmationDialog(services: ServiceGraph)`, `@Composable fun OutdatedBanner(services: ServiceGraph)`.

- [ ] **Step 1: Test deps** — `phone/build.gradle.kts`: `testImplementation(kotlin("test"))`, `testImplementation("junit:junit:4.13.2")`.

- [ ] **Step 2: Write the failing test** — `ThrottleTest.kt`

```kotlin
package com.debasish.livefit.phone.ui

import kotlin.test.Test
import kotlin.test.assertEquals

class ThrottleTest {
    @Test fun allowsAtMostOnePerInterval() {
        var t = 0L
        val th = Throttle(100) { t }
        val allowed = (0 until 30).count { t = it * 10L; th.allow() }
        assertEquals(3, allowed) // t = 0, 100, 200 within 0..290 ms
    }
}
```

- [ ] **Step 3: Run test to verify it fails**

Run: `export JAVA_HOME=$(/usr/libexec/java_home -v 17) && ./gradlew :phone:testDebugUnitTest --tests '*ThrottleTest*'`
Expected: FAIL — `Throttle` unresolved.

- [ ] **Step 4: Implement `Throttle.kt`**

```kotlin
package com.debasish.livefit.phone.ui

/** Rate-limits continuous inputs (volume slider) to ≤ 10 commands/s like the watch (spec §6.2). */
class Throttle(private val minIntervalMs: Long, private val now: () -> Long = System::currentTimeMillis) {
    private var last = Long.MIN_VALUE / 2
    fun allow(): Boolean {
        val t = now()
        if (t - last < minIntervalMs) return false
        last = t
        return true
    }
}
```

Run the test: `./gradlew :phone:testDebugUnitTest --tests '*ThrottleTest*'` → PASS.

- [ ] **Step 5: Confirmation dialog** — `HubConfirmationDialog.kt`

```kotlin
package com.debasish.livefit.phone.ui.components

import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.debasish.livefit.model.Command
import com.debasish.livefit.phone.ServiceGraph
import com.debasish.livefit.phone.ui.theme.LiveFitColors

/** The phone's copy of the cross-device confirmation; first answer on any device wins (spec §4.6). */
@Composable
fun HubConfirmationDialog(services: ServiceGraph) {
    val c by services.confirm.pending.collectAsStateWithLifecycle()
    val pending = c ?: return
    AlertDialog(
        onDismissRequest = {},
        title = { Text(pending.title) },
        text = { Text(pending.message) },
        confirmButton = {
            TextButton(onClick = { services.localCommand(Command.Answer(pending.id, yes = true)) }) {
                Text(pending.yesLabel, fontWeight = FontWeight.SemiBold, color = LiveFitColors.MintDeep)
            }
        },
        dismissButton = {
            TextButton(onClick = { services.localCommand(Command.Answer(pending.id, yes = false)) }) { Text(pending.noLabel, color = LiveFitColors.InkSoft) }
        },
        containerColor = Color.White,
    )
}
```

`OutdatedBanner.kt`:

```kotlin
package com.debasish.livefit.phone.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.debasish.livefit.phone.ServiceGraph

/** Coordinated-upgrade banner (spec §4.7). */
@Composable
fun OutdatedBanner(services: ServiceGraph, modifier: Modifier = Modifier) {
    val outdated by services.router.outdated.collectAsStateWithLifecycle()
    val device = outdated ?: return
    Text("Update LiveFit on your ${device.name.lowercase()} — its commands are ignored until then",
        color = Color.White, modifier = modifier.fillMaxWidth().background(Color(0xFFE5583B)).statusBarsPadding().padding(12.dp))
}
```

In `AppActivity`'s root `Box`, after the `NavHost`: `HubConfirmationDialog(services)` and `OutdatedBanner(services, Modifier.align(Alignment.TopCenter))`.

Low-battery warning (spec §7: ≤ 15 %, one toast per device per workout) — add to `ServiceGraph.start()`:

```kotlin
        scope.launch {
            val warned = mutableSetOf<DeviceKind>()
            lastFrame.collect { f ->
                if (f == null) return@collect
                if (f.workout.phase == com.debasish.livefit.model.WorkoutPhase.Idle) { warned.clear(); return@collect }
                listOf(DeviceKind.Phone to f.devices.phone, DeviceKind.Watch to f.devices.watch, DeviceKind.Glasses to f.devices.glasses).forEach { (k, d) ->
                    val pct = d.batteryPct ?: return@forEach
                    if (pct <= 15 && warned.add(k)) flash("${k.name} battery low · $pct%")
                }
            }
        }
```

- [ ] **Step 6: Workout screen phases** — in `WorkoutScreen.kt`:
  - Route every button through the router: Stop → `services.localCommand(Command.StopWorkout)`, pause/resume → `Command.PauseWorkout` / `Command.ResumeWorkout`, mini-player play/pause → `Command.PlayPause`, next → `Command.NextTrack`, mic → `services.voice.listen()` (unchanged).
  - Replace the `Controls(...)` call with a phase switch:

```kotlin
        when (s.phase) {
            WorkoutPhase.Starting -> StatusPill(Modifier.align(Alignment.BottomCenter).navigationBarsPadding().padding(bottom = 24.dp), "Starting on your watch…")
            WorkoutPhase.Syncing -> StatusPill(Modifier.align(Alignment.BottomCenter).navigationBarsPadding().padding(bottom = 24.dp), "Syncing watch data…")
            WorkoutPhase.Stopping -> StatusPill(Modifier.align(Alignment.BottomCenter).navigationBarsPadding().padding(bottom = 24.dp), "Saving workout…")
            WorkoutPhase.Summary -> Unit
            else -> Controls(
                modifier = Modifier.align(Alignment.BottomCenter).navigationBarsPadding().padding(bottom = 16.dp),
                phase = s.phase, voice = voice,
                onPauseResume = { services.localCommand(if (s.phase == WorkoutPhase.Paused) Command.ResumeWorkout else Command.PauseWorkout) },
                onStop = { services.localCommand(Command.StopWorkout) },
                onVoice = { services.voice.listen() },
            )
        }
```

and add:

```kotlin
@Composable
private fun StatusPill(modifier: Modifier, text: String) {
    Row(modifier.clip(RoundedCornerShape(24.dp)).background(LiveFitColors.ChipSky.first).padding(horizontal = 18.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically) {
        CircularProgressIndicator(Modifier.size(18.dp), strokeWidth = 2.dp, color = LiveFitColors.ChipSky.second)
        Text("  $text", color = LiveFitColors.ChipSky.second, style = MaterialTheme.typography.titleMedium)
    }
}
```

  - Summary "Done" → `services.localCommand(Command.DismissSummary)`.

- [ ] **Step 7: Volume and controls on Music / Home**
  - `MusicScreen.kt`: `val throttle = remember { Throttle(100) }`; slider `onValueChange = { v -> if (throttle.allow()) services.localCommand(Command.SetVolume(v)) }`, `onValueChangeFinished = { services.localCommand(Command.SetVolume(volume)) }`; previous/play/next/like → `PreviousTrack` / `PlayPause` / `NextTrack` / `LikeTrack` commands.
  - `HomeScreen.kt`: `WorkoutTypeSheet(onPick = { picking = false; services.localCommand(Command.StartWorkout(it)); onWorkout() }, …)`; now-playing card buttons → `PlayPause` / `NextTrack`.

- [ ] **Step 8: Build and device check**

Run: `export JAVA_HOME=$(/usr/libexec/java_home -v 17) && ./gradlew :phone:testDebugUnitTest :phone:assembleDebug` → PASS / BUILD SUCCESSFUL. Then `tools/install-all.sh`: start from Home → "Starting on your watch…" → live; trigger the Samsung Health takeover → the phone dialog appears together with watch/glasses; answer on the phone → all dismiss; drag the Music volume slider → watch arc follows.

- [ ] **Step 9: Commit**

```bash
git add phone
git commit -m "feat(phone): hub-driven workout phases, cross-device confirmation dialog and volume"
```

---

### Task 23: Activity history (list + detail)

**Files:**
- Create: `phone/src/main/java/com/debasish/livefit/phone/ui/history/HistoryFormat.kt`
- Test: `phone/src/test/java/com/debasish/livefit/phone/ui/history/HistoryFormatTest.kt`
- Rewrite: `phone/src/main/java/com/debasish/livefit/phone/ui/list/sources/WorkoutHistorySource.kt`
- Modify: `phone/src/main/java/com/debasish/livefit/phone/ui/list/ListSource.kt` (`ListSources.create` gains `open: (String) -> Unit`)
- Create: `phone/src/main/java/com/debasish/livefit/phone/ui/history/SessionDetailScreen.kt`
- Modify: `phone/src/main/java/com/debasish/livefit/phone/ui/AppActivity.kt` (route `session/{id}`)

**Interfaces:**
- Consumes: `HistoryStore.sessions`, `HistoryStore.samples` (Task 11).
- Produces: `object HistoryFormat { fun title(s: SessionSummary): String; fun subtitle(s: SessionSummary, now: Long): String; fun badge(s: SessionSummary): String? }`; route `session/{id}`.

- [ ] **Step 1: Write the failing test**

```kotlin
package com.debasish.livefit.phone.ui.history

import com.debasish.livefit.model.Provenance
import com.debasish.livefit.model.SessionStatus
import com.debasish.livefit.model.SessionSummary
import com.debasish.livefit.model.WorkoutType
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class HistoryFormatTest {
    private fun s(status: SessionStatus = SessionStatus.Complete, prov: Provenance = Provenance.Live("galaxy-watch/health-services"), type: WorkoutType = WorkoutType.Run, detected: WorkoutType? = null) =
        SessionSummary(id = "x", type = type, detectedType = detected, startMs = 0, endMs = 1_752_000, activeMs = 1_720_000, avgHr = 151,
            distanceKm = 4.9, provenance = prov, status = status)

    @Test fun titleUsesDetectedTypeForAuto() {
        assertEquals("Run", HistoryFormat.title(s()))
        assertEquals("Auto · Cycle", HistoryFormat.title(s(type = WorkoutType.Auto, detected = WorkoutType.Cycle)))
    }

    @Test fun subtitleShowsDurationAndAvgHr() = assertEquals("28:40 · ♥ 151 avg", HistoryFormat.subtitle(s(), now = 0).substringAfter(" · "))

    @Test fun badges() {
        assertNull(HistoryFormat.badge(s()))
        assertEquals("Incomplete", HistoryFormat.badge(s(status = SessionStatus.Incomplete)))
        assertEquals("Demo", HistoryFormat.badge(s(prov = Provenance.Fake)))
    }
}
```

- [ ] **Step 2: Run test to verify it fails**

Run: `export JAVA_HOME=$(/usr/libexec/java_home -v 17) && ./gradlew :phone:testDebugUnitTest --tests '*HistoryFormatTest*'`
Expected: FAIL — unresolved.

- [ ] **Step 3: Implement `HistoryFormat.kt`**

```kotlin
package com.debasish.livefit.phone.ui.history

import com.debasish.livefit.model.Provenance
import com.debasish.livefit.model.SessionStatus
import com.debasish.livefit.model.SessionSummary
import com.debasish.livefit.model.WorkoutType
import com.debasish.livefit.model.formatElapsed
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

object HistoryFormat {
    fun title(s: SessionSummary): String =
        if (s.type == WorkoutType.Auto && s.detectedType != null) "Auto · ${s.detectedType!!.label}" else s.type.label

    fun subtitle(s: SessionSummary, now: Long): String {
        val day = if (now - s.startMs < 86_400_000L) "Today" else SimpleDateFormat("EEE d MMM", Locale.getDefault()).format(Date(s.startMs))
        return "$day · ${formatElapsed(s.activeMs)}" + (s.avgHr?.let { " · ♥ $it avg" } ?: "")
    }

    /** Spec §5.6: Demo = not all samples Live; Incomplete = completion rule unmet (§4.9). */
    fun badge(s: SessionSummary): String? = when {
        s.provenance is Provenance.Fake -> "Demo"
        s.status == SessionStatus.Incomplete -> "Incomplete"
        else -> null
    }
}
```

Run: `./gradlew :phone:testDebugUnitTest --tests '*HistoryFormatTest*'` → PASS.

- [ ] **Step 4: History-backed list source** — `WorkoutHistorySource.kt`

```kotlin
package com.debasish.livefit.phone.ui.list.sources

import com.debasish.livefit.model.WorkoutType
import com.debasish.livefit.phone.ui.components.icon
import com.debasish.livefit.phone.ui.history.HistoryFormat
import com.debasish.livefit.phone.ui.list.ActionResult
import com.debasish.livefit.phone.ui.list.ItemAction
import com.debasish.livefit.phone.ui.list.ListItem
import com.debasish.livefit.phone.ui.list.ListSource
import com.debasish.livefit.services.HistoryStore
import kotlinx.coroutines.flow.first
import org.json.JSONObject

/** Activity tab: LiveFit sessions only (owner decision), newest first. */
class WorkoutHistorySource(private val history: HistoryStore, private val open: (String) -> Unit) : ListSource {
    override val title = "Activity"
    override val searchHint = "Search workouts"

    override suspend fun load(filter: JSONObject?): List<ListItem> {
        val now = System.currentTimeMillis()
        return history.sessions.first().map { s ->
            val badge = HistoryFormat.badge(s)
            ListItem(
                id = s.id,
                title = HistoryFormat.title(s) + (badge?.let { " · $it" } ?: ""),
                subtitle = HistoryFormat.subtitle(s, now),
                icon = (s.detectedType ?: s.type).takeIf { it != WorkoutType.Auto }?.icon ?: s.type.icon,
                trailingText = "%.1f km".format(s.distanceKm),
            )
        }
    }

    override fun actionFor(item: ListItem) = ItemAction { open(item.id); ActionResult.Silent }
}
```

`ListSources.create(id, context)` → `create(id: String, context: Context, open: (String) -> Unit = {})`, with `WORKOUTS -> WorkoutHistorySource(context.services.history, open)`. `ListScreen` gains an `onOpen: (String) -> Unit` parameter passed to `create`; `AppActivity` passes `{ nav.navigate("session/$it") }`.

- [ ] **Step 5: Detail screen** — `SessionDetailScreen.kt`

```kotlin
package com.debasish.livefit.phone.ui.history

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.unit.dp
import com.debasish.livefit.model.Sample
import com.debasish.livefit.model.SessionSummary
import com.debasish.livefit.model.formatElapsed
import com.debasish.livefit.phone.ServiceGraph
import com.debasish.livefit.phone.ui.components.ScreenHeader
import com.debasish.livefit.phone.ui.components.SectionLabel
import com.debasish.livefit.phone.ui.components.SoftCard
import com.debasish.livefit.phone.ui.theme.LiveFitColors
import kotlinx.coroutines.flow.first

@Composable
fun SessionDetailScreen(services: ServiceGraph, sessionId: String, onBack: () -> Unit) {
    var summary by remember { mutableStateOf<SessionSummary?>(null) }
    var samples by remember { mutableStateOf<List<Sample>>(emptyList()) }
    LaunchedEffect(sessionId) {
        summary = services.history.sessions.first().firstOrNull { it.id == sessionId }
        samples = services.history.samples(sessionId)
    }
    val s = summary
    Column(Modifier.fillMaxSize().background(LiveFitColors.SurfaceSoft).verticalScroll(rememberScrollState())) {
        ScreenHeader(s?.let { HistoryFormat.title(it) } ?: "Workout", onBack)
        if (s == null) return@Column
        HistoryFormat.badge(s)?.let {
            Text(if (it == "Demo") "Demo · not saved to Health Connect" else "Incomplete · some data could not be recovered",
                color = LiveFitColors.ChipCoral.second, modifier = Modifier.padding(horizontal = 20.dp))
        }
        SoftCard(Modifier.padding(16.dp).fillMaxWidth()) {
            Row(Modifier.padding(16.dp).fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                Stat("Time", formatElapsed(s.activeMs)); Stat("Avg ♥", "${s.avgHr ?: "--"}"); Stat("Max ♥", "${s.maxHr ?: "--"}")
            }
            Row(Modifier.padding(start = 16.dp, end = 16.dp, top = 64.dp, bottom = 16.dp).fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                Stat("kcal", "${s.kcal}"); Stat("Steps", "%,d".format(s.steps)); Stat("km", "%.2f".format(s.distanceKm))
            }
        }
        SectionLabel("Heart rate")
        SoftCard(Modifier.padding(horizontal = 16.dp).fillMaxWidth().height(180.dp)) {
            val hrs = samples.mapNotNull { it.hr }
            Canvas(Modifier.fillMaxSize().padding(16.dp)) {
                if (hrs.size < 2) return@Canvas
                val lo = hrs.min() - 5f; val hi = hrs.max() + 5f
                val path = Path()
                hrs.forEachIndexed { i, hr ->
                    val x = size.width * i / (hrs.size - 1)
                    val y = size.height - (hr - lo) / (hi - lo) * size.height
                    if (i == 0) path.moveTo(x, y) else path.lineTo(x, y)
                }
                drawPath(path, LiveFitColors.ChipCoral.second, style = Stroke(3.dp.toPx(), cap = StrokeCap.Round))
            }
        }
    }
}

@Composable
private fun Stat(label: String, value: String) {
    Column {
        Text(value, style = MaterialTheme.typography.titleLarge)
        Text(label, style = MaterialTheme.typography.labelMedium, color = LiveFitColors.InkSoft)
    }
}
```

Route in `AppActivity`: `composable("session/{id}") { e -> SessionDetailScreen(services, e.arguments?.getString("id").orEmpty(), onBack = { nav.popBackStack() }) }`.

- [ ] **Step 6: Build and device check**

`./gradlew :phone:assembleDebug`; `tools/install-all.sh`; finish one real workout and one demo (Developer tools → bind simulated gateway is not needed: the earlier simulated sessions from Task 12 remain in history). Expected: Activity tab lists both newest first; the demo shows "· Demo"; tapping opens the detail page with the HR chart.

- [ ] **Step 7: Commit**

```bash
git add phone
git commit -m "feat(phone): history-backed Activity tab and session detail with badges"
```

---

### Task 24: Settings — Linked services, workout, voice, data; glasses icon; single download confirm

**Files:**
- Create: `phone/src/main/java/com/debasish/livefit/phone/ui/components/GlassesIcon.kt`
- Create: `phone/src/main/java/com/debasish/livefit/phone/ui/linked/LinkedGlassesScreen.kt`, `LinkedWatchScreen.kt`, `LinkedMusicScreen.kt`
- Modify: `phone/src/main/java/com/debasish/livefit/phone/ui/settings/SettingsScreen.kt`
- Modify: `phone/src/main/java/com/debasish/livefit/phone/ui/list/sources/LanguageSource.kt`
- Modify: `phone/src/main/java/com/debasish/livefit/phone/ui/home/HomeScreen.kt`, `ui/devices/DeviceScreen.kt` (icon swap; DeviceScreen is superseded by the Linked screens — delete it and its route)
- Modify: `phone/src/main/java/com/debasish/livefit/phone/ui/AppActivity.kt` (routes `linked/glasses`, `linked/watch`, `linked/music`)

**Interfaces:**
- Consumes: `CompanionLinker` (Task 14), `AuthActivity.launch`, `CxrGlassesLink.connect`, `SettingsStore` music/voice/gps settings (Tasks 12, 15), `YtmMediaSessionService.connected` (Task 15), `HistoryStore.clearAll` (Task 11).
- Produces: `val GlassesIcon: ImageVector` (phone), routes above.

- [ ] **Step 1: Glasses icon** — `GlassesIcon.kt` (same outline as the HUD icon, filled-stroke style for the phone)

```kotlin
package com.debasish.livefit.phone.ui.components

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.graphics.vector.path
import androidx.compose.ui.unit.dp

/** Outlined smart-glasses icon; replaces the eye icon for glasses everywhere (approved design). */
val GlassesIcon: ImageVector by lazy {
    ImageVector.Builder("Glasses", 24.dp, 24.dp, 24f, 24f).apply {
        path(stroke = SolidColor(Color.Black), strokeLineWidth = 2f, strokeLineCap = StrokeCap.Round) {
            moveTo(3f, 10f); lineTo(10f, 10f); lineTo(10f, 13f); curveTo(10f, 15f, 8.5f, 16f, 6.5f, 16f); curveTo(4.5f, 16f, 3f, 15f, 3f, 13f); close()
            moveTo(14f, 10f); lineTo(21f, 10f); lineTo(21f, 13f); curveTo(21f, 15f, 19.5f, 16f, 17.5f, 16f); curveTo(15.5f, 16f, 14f, 15f, 14f, 13f); close()
            moveTo(10f, 11f); curveTo(11f, 10f, 13f, 10f, 14f, 11f)
            moveTo(3f, 10f); lineTo(1.5f, 8f)
            moveTo(21f, 10f); lineTo(22.5f, 8f)
        }
    }.build()
}
```

Replace `Icons.Rounded.Visibility` used for glasses in `HomeScreen.kt` and `SettingsScreen.kt` with `GlassesIcon` (`Icon` tints the path, so the black stroke colour is overridden).

- [ ] **Step 2: Settings structure** — replace the body of `SettingsScreen` groups:

```kotlin
        SectionLabel("General")
        Group {
            ChipRow(Icons.Rounded.Language, LiveFitColors.ChipSky, "Languages", "Voice packs · voice: $voiceLocale", onLanguages)
            Divider()
            ChipRow(Icons.Rounded.Straighten, LiveFitColors.ChipAmber, "Units", "Metric", { onNavigate("units") })
        }
        SectionLabel("Linked services")
        Group {
            ChipRow(GlassesIcon, LiveFitColors.ChipMint, "Rokid glasses", glassesStatus, { onNavigate("linked/glasses") })
            Divider()
            ChipRow(Icons.Rounded.Watch, LiveFitColors.ChipViolet, "Galaxy Watch", watchStatus, { onNavigate("linked/watch") })
            Divider()
            ChipRow(Icons.Rounded.LibraryMusic, LiveFitColors.ChipRose, "YouTube Music", musicStatus, { onNavigate("linked/music") })
        }
        SectionLabel("Glasses")
        Group { ChipRow(Icons.Rounded.Dashboard, LiveFitColors.ChipSky, "Glasses display", "Size, position, metrics", { onNavigate("hud") }) }
        SectionLabel("Workout")
        Group {
            ChipRow(Icons.Rounded.MyLocation, LiveFitColors.ChipMint, "Use GPS outdoors", "Run, Cycle, Auto", onClick = { services.settings.setGpsOutdoors(!gps) },
                trailing = { Switch(gps, { services.settings.setGpsOutdoors(it) }) })
        }
        SectionLabel("Data")
        Group { ChipRow(Icons.Rounded.DeleteSweep, LiveFitColors.ChipCoral, "Clear history", "Removes all workouts on this phone", { confirmClear = true }) }
        SectionLabel("Advanced")
        Group {
            if (BuildConfig.DEBUG) { ChipRow(Icons.Rounded.Code, LiveFitColors.ChipSlate, "Developer tools", "Spike console", onDeveloper); Divider() }
            ChipRow(Icons.Rounded.Info, LiveFitColors.ChipRose, "About", "Rokid LiveFit 0.1", { onNavigate("about") })
        }
```

with, at the top of the composable (the screen now takes `services: ServiceGraph`):

```kotlin
    val voiceLocale by services.settings.voiceLocale.collectAsStateWithLifecycle()
    val gps by services.settings.gpsOutdoors.collectAsStateWithLifecycle()
    val glasses by services.glasses.status.collectAsStateWithLifecycle()
    val watch by services.watch.status.collectAsStateWithLifecycle()
    val glassesStatus = glasses.link.name + (glasses.batteryPct?.let { " · $it%" } ?: "")
    val watchStatus = watch.link.name + (watch.batteryPct?.let { " · $it%" } ?: "")
    val musicStatus = if (services.musicConnected.collectAsStateWithLifecycle().value) "Connected" else "Needs notification access"
    var confirmClear by remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()
    if (confirmClear) AlertDialog(
        onDismissRequest = { confirmClear = false },
        title = { Text("Clear history?") },
        text = { Text("All workouts stored on this phone are deleted. Health Connect copies are not affected.") },
        confirmButton = { TextButton(onClick = { confirmClear = false; scope.launch { services.history.clearAll() } }) { Text("Clear") } },
        dismissButton = { TextButton(onClick = { confirmClear = false }) { Text("Cancel") } },
    )
```

In `ServiceGraph` add `val musicConnected: StateFlow<Boolean> = ytm?.connected ?: MutableStateFlow(true)`.

- [ ] **Step 3: Linked glasses** — `LinkedGlassesScreen.kt`

```kotlin
package com.debasish.livefit.phone.ui.linked

import android.app.Activity
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Dashboard
import androidx.compose.material.icons.rounded.Key
import androidx.compose.material.icons.rounded.Link
import androidx.compose.material.icons.rounded.Refresh
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.debasish.livefit.model.DeviceKind
import com.debasish.livefit.phone.CompanionLinker
import com.debasish.livefit.phone.ServiceGraph
import com.debasish.livefit.phone.ui.components.ChipRow
import com.debasish.livefit.phone.ui.components.GlassesIcon
import com.debasish.livefit.phone.ui.components.ScreenHeader
import com.debasish.livefit.phone.ui.components.SectionLabel
import com.debasish.livefit.phone.ui.components.SoftCard
import com.debasish.livefit.phone.ui.theme.LiveFitColors
import com.debasish.livefit.services.glasses.AuthActivity

@Composable
fun LinkedGlassesScreen(services: ServiceGraph, onBack: () -> Unit, onDisplay: () -> Unit, toast: (String) -> Unit) {
    val activity = LocalContext.current as Activity
    val st by services.glasses.status.collectAsStateWithLifecycle()
    Column(Modifier.fillMaxSize().background(LiveFitColors.SurfaceSoft).verticalScroll(rememberScrollState())) {
        ScreenHeader("Rokid glasses", onBack)
        SoftCard(Modifier.padding(horizontal = 16.dp).fillMaxWidth()) {
            ChipRow(GlassesIcon, LiveFitColors.ChipMint, st.name, "${st.link.name}${st.batteryPct?.let { " · $it%" } ?: ""}${st.detail?.let { " · $it" } ?: ""}", {}, trailing = {})
        }
        SectionLabel("Link")
        SoftCard(Modifier.padding(horizontal = 16.dp).fillMaxWidth()) {
            Column {
                ChipRow(Icons.Rounded.Link, LiveFitColors.ChipSky, "Pair / re-pair", "Lets Android wake LiveFit when the glasses are near",
                    { CompanionLinker.associate(activity, DeviceKind.Glasses) { ok -> toast(if (ok) "Glasses paired" else "Pairing cancelled") } })
                HorizontalDivider(Modifier.padding(start = 70.dp), color = LiveFitColors.Line)
                ChipRow(Icons.Rounded.Key, LiveFitColors.ChipAmber, "Re-authorize in Hi Rokid", "Microphone, device and media access", { AuthActivity.launch(activity) })
                HorizontalDivider(Modifier.padding(start = 70.dp), color = LiveFitColors.Line)
                ChipRow(Icons.Rounded.Refresh, LiveFitColors.ChipViolet, "Reconnect", "Opens LiveFit on the glasses", { services.glasses.connect() })
            }
        }
        SectionLabel("Display")
        SoftCard(Modifier.padding(horizontal = 16.dp).fillMaxWidth()) {
            ChipRow(Icons.Rounded.Dashboard, LiveFitColors.ChipSky, "Glasses display", "Size, position, metrics", onDisplay)
        }
        Text("Unpair: Android Settings → Connected devices → Rokid glasses → remove LiveFit's companion access.",
            color = LiveFitColors.InkSoft, modifier = Modifier.padding(20.dp))
    }
}
```

`LinkedWatchScreen.kt` — same structure: status row (`services.watch.status`), "Pair / re-pair" → `CompanionLinker.associate(activity, DeviceKind.Watch)`, info row "Sensor permissions are granted on the watch: open Rokid LiveFit on the watch and tap Allow", and a row showing the last `ExerciseError.PermissionMissing` if the hub reported one (read from `services.workout.notices` is transient — show static guidance only).

`LinkedMusicScreen.kt`:

```kotlin
@Composable
fun LinkedMusicScreen(services: ServiceGraph, onBack: () -> Unit) {
    val context = LocalContext.current
    val connected by services.musicConnected.collectAsStateWithLifecycle()
    val onStart by services.settings.musicOnStart.collectAsStateWithLifecycle()
    val search by services.settings.musicSearch.collectAsStateWithLifecycle()
    val pauseOnStop by services.settings.pauseMusicOnStop.collectAsStateWithLifecycle()
    Column(Modifier.fillMaxSize().background(LiveFitColors.SurfaceSoft).verticalScroll(rememberScrollState())) {
        ScreenHeader("YouTube Music", onBack)
        SoftCard(Modifier.padding(horizontal = 16.dp).fillMaxWidth()) {
            ChipRow(Icons.Rounded.LibraryMusic, LiveFitColors.ChipRose, "Music control", if (connected) "Connected" else "Needs notification access",
                { context.startActivity(Intent(Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) })
        }
        SectionLabel("When a workout starts")
        SoftCard(Modifier.padding(horizontal = 16.dp).fillMaxWidth()) {
            Column {
                MusicOnStart.entries.forEach { option ->
                    val label = when (option) { MusicOnStart.DontTouch -> "Don't touch music"; MusicOnStart.Resume -> "Resume last played"; MusicOnStart.PlaySearch -> "Play saved search" }
                    ChipRow(Icons.Rounded.PlayArrow, LiveFitColors.ChipRose, label, null, { services.settings.setMusicOnStart(option) },
                        trailing = { RadioButton(selected = onStart == option, onClick = { services.settings.setMusicOnStart(option) }) })
                }
                if (onStart == MusicOnStart.PlaySearch) OutlinedTextField(search, services.settings::setMusicSearch, label = { Text("Search") },
                    modifier = Modifier.fillMaxWidth().padding(16.dp), singleLine = true)
            }
        }
        SectionLabel("When a workout stops")
        SoftCard(Modifier.padding(horizontal = 16.dp).fillMaxWidth()) {
            ChipRow(Icons.Rounded.Pause, LiveFitColors.ChipRose, "Pause music", null, { services.settings.setPauseMusicOnStop(!pauseOnStop) },
                trailing = { Switch(pauseOnStop, services.settings::setPauseMusicOnStop) })
        }
        Text("V2 adds Google sign-in here for playlists.", color = LiveFitColors.InkSoft, modifier = Modifier.padding(20.dp))
    }
}
```
(imports: Compose material3 `RadioButton`, `Switch`, `OutlinedTextField`, icons `LibraryMusic`, `PlayArrow`, `Pause`, `android.provider.Settings`, `android.content.Intent`, `MusicOnStart`.)

Routes in `AppActivity`: `linked/glasses` → `LinkedGlassesScreen(services, back, onDisplay = { nav.navigate("hud") }, toast)`, `linked/watch` → `LinkedWatchScreen(...)`, `linked/music` → `LinkedMusicScreen(...)`; Home device bubbles navigate to these routes; delete the `device/{kind}` route and `DeviceScreen.kt` (its HUD preview moves into `LinkedGlassesScreen` — copy the `HudPreview` composable there, reading `services.lastFrame`).

- [ ] **Step 4: Languages — single confirm and "use for voice"** — in `LanguageSource.kt`:
  - `actionFor` for `ActionNeeded` returns `ItemAction(confirmTitle = null, blocking = true) { … download … }` (Google shows its own size dialog; no LiveFit dialog).
  - For `Done` items return `ItemAction { onUseForVoice(item.id); ActionResult.Done }`, and show `trailingText = "Voice"` for the item whose id equals the current voice locale. Constructor: `LanguageSource(context: Context, voiceLocale: () -> String, onUseForVoice: (String) -> Unit)`; `ListSources.create` passes `{ services.settings.voiceLocale.value }` and `services.settings::setVoiceLocale`. After a successful download call `services.sttRefresh()` (add `fun sttRefresh() { stt?.let { s -> scope.launch { s.refresh() } } }` to `ServiceGraph`).

- [ ] **Step 5: Build and device check**

`./gradlew :phone:assembleDebug`; `tools/install-all.sh`. Expected: Settings shows Linked services with live status/battery; Pair opens the system companion dialog; Re-authorize flashes Hi Rokid and returns; Languages → tap an installed language → it becomes "Voice"; downloading shows only Google's dialog then the progress overlay; Clear history empties Activity.

- [ ] **Step 6: Commit**

```bash
git add phone
git commit -m "feat(phone): linked services, workout/voice/data settings, glasses icon, single download confirm"
```

---

### Task 25: First-run setup wizard

**Files:**
- Create: `phone/src/main/java/com/debasish/livefit/phone/setup/SetupFlow.kt`
- Test: `phone/src/test/java/com/debasish/livefit/phone/setup/SetupFlowTest.kt`
- Create: `phone/src/main/java/com/debasish/livefit/phone/setup/SetupScreen.kt`
- Modify: `phone/src/main/java/com/debasish/livefit/phone/SettingsStore.kt` (`setupDone`), `ui/AppActivity.kt` (start destination)

**Interfaces:**
- Produces: `enum class SetupStep { Welcome, Glasses, Watch, Music, Voice, Done }`, `class SetupFlow(start: SetupStep = Welcome) { val step: SetupStep; fun next(); fun back(); fun skip(); val voiceEnabled: Boolean; fun voicePackInstalled() }`; `SettingsStore.setupDone: StateFlow<Boolean>` + `setSetupDone()`.

- [ ] **Step 1: Write the failing test**

```kotlin
package com.debasish.livefit.phone.setup

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class SetupFlowTest {
    @Test fun walksAllSteps() {
        val f = SetupFlow()
        val seen = mutableListOf(f.step)
        repeat(5) { f.next(); seen += f.step }
        assertEquals(SetupStep.entries.toList(), seen)
    }

    @Test fun backStopsAtWelcome() {
        val f = SetupFlow(); f.back()
        assertEquals(SetupStep.Welcome, f.step)
    }

    @Test fun skippingVoiceLeavesVoiceOff() {
        val f = SetupFlow(SetupStep.Voice); f.skip()
        assertEquals(SetupStep.Done, f.step)
        assertFalse(f.voiceEnabled)
    }

    @Test fun installingPackEnablesVoice() {
        val f = SetupFlow(SetupStep.Voice); f.voicePackInstalled(); f.next()
        assertTrue(f.voiceEnabled)
    }

    @Test fun nextOnVoiceWithoutPackActsAsSkip() {
        val f = SetupFlow(SetupStep.Voice); f.next()
        assertFalse(f.voiceEnabled)
        assertEquals(SetupStep.Done, f.step)
    }
}
```

- [ ] **Step 2: Run test to verify it fails**

Run: `export JAVA_HOME=$(/usr/libexec/java_home -v 17) && ./gradlew :phone:testDebugUnitTest --tests '*SetupFlowTest*'`
Expected: FAIL — unresolved.

- [ ] **Step 3: Implement `SetupFlow.kt`**

```kotlin
package com.debasish.livefit.phone.setup

/** First-run steps (spec §6.1). Voice is required for voice features but skippable. */
enum class SetupStep { Welcome, Glasses, Watch, Music, Voice, Done }

class SetupFlow(start: SetupStep = SetupStep.Welcome) {
    var step: SetupStep = start
        private set
    var voiceEnabled: Boolean = false
        private set
    private var packInstalled = false

    fun voicePackInstalled() { packInstalled = true }

    fun next() {
        if (step == SetupStep.Voice) voiceEnabled = packInstalled
        if (step != SetupStep.Done) step = SetupStep.entries[step.ordinal + 1]
    }

    fun skip() {
        if (step == SetupStep.Voice) voiceEnabled = false
        if (step != SetupStep.Done) step = SetupStep.entries[step.ordinal + 1]
    }

    fun back() { if (step != SetupStep.Welcome) step = SetupStep.entries[step.ordinal - 1] }
}
```

Run the test → PASS.

- [ ] **Step 4: `SettingsStore`** — add:

```kotlin
    private val _setupDone = MutableStateFlow(prefs.getBoolean("setupDone", false))
    val setupDone: StateFlow<Boolean> = _setupDone
    fun setSetupDone() { _setupDone.value = true; prefs.edit().putBoolean("setupDone", true).apply() }
```

- [ ] **Step 5: `SetupScreen.kt`** — one icon card per step, Back / Skip / Next buttons:

```kotlin
package com.debasish.livefit.phone.setup

import android.Manifest
import android.app.Activity
import android.content.Intent
import android.net.Uri
import android.provider.Settings
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Favorite
import androidx.compose.material.icons.rounded.LibraryMusic
import androidx.compose.material.icons.rounded.Mic
import androidx.compose.material.icons.rounded.Shield
import androidx.compose.material.icons.rounded.Watch
import androidx.compose.material3.Button
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import com.debasish.livefit.model.DeviceKind
import com.debasish.livefit.phone.CompanionLinker
import com.debasish.livefit.phone.ServiceGraph
import com.debasish.livefit.phone.ui.components.GlassesIcon
import com.debasish.livefit.phone.ui.components.IconChip
import com.debasish.livefit.phone.ui.theme.LiveFitColors
import com.debasish.livefit.services.glasses.AuthActivity
import com.debasish.livefit.services.voice.android.SpeechPacks
import kotlinx.coroutines.launch

@Composable
fun SetupScreen(services: ServiceGraph, onFinished: () -> Unit) {
    val activity = LocalContext.current as Activity
    val flow = remember { SetupFlow() }
    var step by remember { mutableStateOf(flow.step) }
    var progress by remember { mutableStateOf<Float?>(null) }
    var downloading by remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()
    val permissions = rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) {}
    fun advance(skip: Boolean = false) { if (skip) flow.skip() else flow.next(); step = flow.step; if (step == SetupStep.Done) { services.settings.setSetupDone(); onFinished() } }

    Column(Modifier.fillMaxSize().background(LiveFitColors.HeaderGradient).statusBarsPadding().padding(24.dp), horizontalAlignment = Alignment.CenterHorizontally) {
        LinearProgressIndicator(progress = { (step.ordinal + 1) / SetupStep.entries.size.toFloat() }, modifier = Modifier.fillMaxWidth(), color = LiveFitColors.Mint)
        Spacer(Modifier.height(48.dp))
        val (icon, title, body) = when (step) {
            SetupStep.Welcome -> Triple(Icons.Rounded.Shield, "Welcome to Rokid LiveFit", "Allow microphone, nearby devices, notifications and background use so the hub can run during workouts.")
            SetupStep.Glasses -> Triple(GlassesIcon, "Link your Rokid glasses", "Authorize LiveFit in Hi Rokid, then pair so Android wakes LiveFit when the glasses are near.")
            SetupStep.Watch -> Triple(Icons.Rounded.Watch, "Link your Galaxy Watch", "Pair the watch, then open Rokid LiveFit on the watch once and tap Allow for heart-rate sensors.")
            SetupStep.Music -> Triple(Icons.Rounded.LibraryMusic, "Control YouTube Music", "Give LiveFit notification access so it can play, skip and like songs.")
            SetupStep.Voice -> Triple(Icons.Rounded.Mic, "Offline voice: English (India)", "Download the on-device voice pack. Voice stays off until it's installed — there's no online fallback.")
            SetupStep.Done -> Triple(Icons.Rounded.Favorite, "All set", "")
        }
        IconChip(icon, LiveFitColors.ChipMint, size = 96.dp, shapeRadius = 28.dp)
        Spacer(Modifier.height(20.dp))
        Text(title, style = MaterialTheme.typography.headlineMedium)
        Text(body, style = MaterialTheme.typography.bodyMedium, color = LiveFitColors.InkSoft, modifier = Modifier.padding(top = 8.dp))
        Spacer(Modifier.height(24.dp))
        when (step) {
            SetupStep.Welcome -> Button(onClick = {
                permissions.launch(arrayOf(Manifest.permission.RECORD_AUDIO, Manifest.permission.BLUETOOTH_CONNECT, Manifest.permission.POST_NOTIFICATIONS))
                activity.startActivity(Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS, Uri.parse("package:${activity.packageName}")))
            }) { Text("Grant access") }
            SetupStep.Glasses -> Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                OutlinedButton(onClick = { AuthActivity.launch(activity) }) { Text("Authorize") }
                Button(onClick = { CompanionLinker.associate(activity, DeviceKind.Glasses) {} }) { Text("Pair") }
            }
            SetupStep.Watch -> Button(onClick = { CompanionLinker.associate(activity, DeviceKind.Watch) {} }) { Text("Pair watch") }
            SetupStep.Music -> Button(onClick = { activity.startActivity(Intent(Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS)) }) { Text("Open notification access") }
            SetupStep.Voice -> Column(horizontalAlignment = Alignment.CenterHorizontally) {
                Button(enabled = !downloading, onClick = {
                    downloading = true
                    scope.launch {
                        val r = SpeechPacks.download(activity, services.settings.voiceLocale.value) { progress = it }
                        downloading = false
                        if (r is SpeechPacks.Result.Success || SpeechPacks.query(activity).installed.contains(services.settings.voiceLocale.value)) {
                            flow.voicePackInstalled(); services.sttRefresh()
                        }
                    }
                }) { Text(if (downloading) "Downloading…" else "Download voice pack") }
                progress?.let { LinearProgressIndicator(progress = { it }, modifier = Modifier.fillMaxWidth().padding(top = 12.dp), color = LiveFitColors.Mint) }
            }
            SetupStep.Done -> Unit
        }
        Spacer(Modifier.weight(1f))
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
            TextButton(onClick = { flow.back(); step = flow.step }, enabled = step != SetupStep.Welcome) { Text("Back") }
            Row {
                if (step != SetupStep.Done) TextButton(onClick = { advance(skip = true) }) { Text("Skip") }
                Button(onClick = { advance() }) { Text(if (step == SetupStep.Done) "Start" else "Next") }
            }
        }
    }
}
```

- [ ] **Step 6: Start destination** — in `AppActivity`, `startDestination = if (services.settings.setupDone.value) "home" else "setup"` and add `composable("setup") { SetupScreen(services) { nav.navigate("home") { popUpTo("setup") { inclusive = true } } } }`. Hide the footer on `setup` (`showNav = route != "workout" && route != "setup"`).

- [ ] **Step 7: Build and device check**

`./gradlew :phone:testDebugUnitTest :phone:assembleDebug`; `adb -s $PHONE shell pm clear --user 0 com.debasish.livefit`; `tools/install-all.sh`; open LiveFit. Expected: wizard runs through all six cards; skipping Voice leaves the mic showing "Voice needs the English (India) pack"; finishing lands on Home and the wizard never shows again.

- [ ] **Step 8: Commit**

```bash
git add phone
git commit -m "feat(phone): first-run setup wizard with permissions, pairing and voice pack"
```

---

## Phase 5 — Finish

### Task 26: Cleanup, latency instrumentation, device tests and acceptance

**Files:**
- Modify: `core/model/src/main/kotlin/com/debasish/livefit/model/Workout.kt` (`latestSampleMs`), `services/workout/.../SessionAssembler.kt`
- Delete: legacy `HudFrame` + `Protocol` object from `Protocol.kt`, `core/model/src/test/.../ProtocolTest.kt` (covered by `WireTest`), `services/workout/.../DefaultWorkoutService.kt` + its test, `services/metrics/` module + `MetricsSource` interface, `services/glasses-link/.../FakeGlassesLink.kt` `HudFrame` remnants
- Move: spike code `phone/src/main/java/com/debasish/livefit/phone/{SpikeActivity,RokidLink,SpeechSpike,WatchControl,YtmControl,SpikeLog,DebugReceiver}.kt` → `phone/src/debug/java/com/debasish/livefit/phone/`; their manifest entries → `phone/src/debug/AndroidManifest.xml`
- Modify: `glasses/src/main/java/com/debasish/livefit/glasses/hud/HudController.kt` (latency log)
- Create: `tools/device-tests/common.sh`, `sync.sh`, `latency.sh`, `takeover.sh`, `offline.sh`, `voice.sh`
- Create: `docs/superpowers/acceptance/2026-10-05-livefit-v1-acceptance.md`

**Interfaces:**
- Produces: `WorkoutSnapshot.latestSampleMs: Long?` (watch-clock time of the newest sample), glasses log line `LiveFitLatency sample=<watchMs> render=<glassesMs>`.

- [ ] **Step 1: Failing test for `latestSampleMs`** — add to `SessionAssemblerTest`:

```kotlin
    @Test fun snapshotCarriesLatestSampleTimeForLatencyMeasurement() {
        val a = SessionAssembler("s")
        a.add(delta(0, listOf(SessionEvent.Started(0, WorkoutType.Walk)), samples = listOf(Sample(1_000), Sample(2_500))))
        assertEquals(2_500L, a.snapshot().latestSampleMs)
    }
```

Run: `./gradlew :services:workout:test --tests '*SessionAssemblerTest*'` → FAIL (unresolved `latestSampleMs`).

- [ ] **Step 2: Implement** — `WorkoutSnapshot` gains `val latestSampleMs: Long? = null`; in `SessionAssembler.snapshot()` set `latestSampleMs = last?.tMs`. Run the test → PASS.

- [ ] **Step 3: Glasses latency log** — in `HudController.onState` after decoding:

```kotlin
        f.workout.latestSampleMs?.let { Log.i("LiveFitLatency", "sample=$it render=${System.currentTimeMillis()}") }
```

- [ ] **Step 4: Remove legacy code** — delete the items listed under *Files → Delete*; remove `":services:metrics"` from `settings.gradle.kts`; delete `interface MetricsSource` from `Services.kt`; move the spike files into `phone/src/debug/...` with a `phone/src/debug/AndroidManifest.xml` declaring `SpikeActivity` and `DebugReceiver`; the Developer tools row (debug only, Task 24) opens it with `Intent().setClassName(packageName, "com.debasish.livefit.phone.SpikeActivity")`.

Run: `export JAVA_HOME=$(/usr/libexec/java_home -v 17) && ./gradlew test :phone:testDebugUnitTest :phone:assembleDebug :phone:assembleRelease :watch:assembleDebug :glasses:assembleDebug`
Expected: all unit tests PASS; all builds SUCCESSFUL (release build proves no main-source dependency on spike code).

- [ ] **Step 5: Device-test helpers** — `tools/device-tests/common.sh`

```bash
#!/usr/bin/env bash
# Shared helpers: device serials and clock offsets (ms) relative to this Mac.
set -euo pipefail
PHONE=${PHONE:-$(adb devices -l | awk '/model:SM_S93/{print $1; exit}')}
WATCH=${WATCH:-$(adb devices -l | awk '/model:SM_R9/{print $1; exit}')}
GLASSES=${GLASSES:-$(adb devices -l | awk '/model:RG_glasses/{print $1; exit}')}

now_ms() { python3 -c 'import time; print(int(time.time()*1000))'; }
# Device epoch ms minus host epoch ms (round-trip corrected).
offset_ms() {
  local s=$1 t0 dev t1
  t0=$(now_ms); dev=$(adb -s "$s" shell 'date +%s%3N' | tr -d '\r'); t1=$(now_ms)
  echo $(( dev - (t0 + t1) / 2 ))
}
logcat_clear() { for s in "$PHONE" "$WATCH" "$GLASSES"; do adb -s "$s" logcat -c; done; }
```

`tools/device-tests/latency.sh` — computes watch-sample → glasses-render latency over a running workout:

```bash
#!/usr/bin/env bash
# Usage: start a workout, then run for ~60 s. Prints p50/p95/max end-to-end latency (target ≤ 1000 ms).
source "$(dirname "$0")/common.sh"
WOFF=$(offset_ms "$WATCH"); GOFF=$(offset_ms "$GLASSES")
adb -s "$GLASSES" logcat -c; sleep "${1:-60}"
adb -s "$GLASSES" logcat -d -s LiveFitLatency | python3 -c "
import re,sys
woff,goff=int(sys.argv[1]),int(sys.argv[2])
lat=sorted(int(m.group(2))-goff-(int(m.group(1))-woff) for m in re.finditer(r'sample=(\d+) render=(\d+)', sys.stdin.read()))
if not lat: print('no samples'); sys.exit(1)
p=lambda q: lat[min(len(lat)-1,int(q*len(lat)))]
print(f'n={len(lat)} p50={p(.5)}ms p95={p(.95)}ms max={lat[-1]}ms', 'PASS' if p(.95)<=1000 else 'CHECK (target p95 <= 1000 ms)')
" "$WOFF" "$GOFF"
```

`tools/device-tests/offline.sh`:

```bash
#!/usr/bin/env bash
# Usage: during an active workout. Drops phone Bluetooth for $1 s (default 60) and checks recovery.
source "$(dirname "$0")/common.sh"
logcat_clear
echo "Disabling phone Bluetooth for ${1:-60}s — keep moving; pause+resume on the watch once."
adb -s "$PHONE" shell cmd bluetooth_manager disable; sleep "${1:-60}"; adb -s "$PHONE" shell cmd bluetooth_manager enable
sleep 30
echo "Watch buffer files left (expect 0 after sync):"; adb -s "$WATCH" shell "run-as com.debasish.livefit ls files/lf-buffer 2>/dev/null | grep -c '^d-' || true"
echo "Check the phone now shows continuous time/HR and no 'Syncing' banner."
```

`tools/device-tests/sync.sh`, `takeover.sh`, `voice.sh` — guided manual checks (each prints the steps from the acceptance doc below, waits for Enter, and greps the relevant log tags: `LiveFitWatchLink`, `LiveFitGlassesLink`, `LiveFitWatch`, `LiveFitGlasses`). Example `sync.sh`:

```bash
#!/usr/bin/env bash
source "$(dirname "$0")/common.sh"
step() { echo; echo "▶ $1"; read -r -p "  press Enter when done… "; }
logcat_clear
step "Start a Walk from the WATCH. Phone + glasses must show it within 2 s."
step "Pause from the GLASSES by voice ('pause workout'). Watch + phone must show paused."
step "Resume from the PHONE. Watch + glasses must resume."
step "Next song from the WATCH music page. Phone + glasses show the new title."
step "Stop from the PHONE. All show 'Saving…' then Summary."
adb -s "$PHONE" logcat -d | grep -cE "LiveFitWatchLink|LiveFitGlassesLink" | xargs echo "link log lines:"
```

Make all scripts executable: `chmod +x tools/device-tests/*.sh`.

- [ ] **Step 6: Acceptance checklist** — `docs/superpowers/acceptance/2026-10-05-livefit-v1-acceptance.md`

```markdown
# LiveFit V1 acceptance (spec §1.3)

Run on: Galaxy S25 + Galaxy Watch6 Classic + Rokid Glasses, all installed via `tools/install-all.sh` from one commit.

| # | Check | How | Pass |
|---|---|---|---|
| 1 | Live watch data on glasses ≤ 1 s (p95) | 10-min walk, `tools/device-tests/latency.sh 600` | p95 ≤ 1000 ms |
| 2 | Start/pause/resume/stop from each device | `tools/device-tests/sync.sh` | all three reflect each action |
| 3 | Music play/pause/next/previous/like/volume from each device | phone Music screen, watch music page (bezel), glasses voice | YouTube Music reacts; all show state |
| 4 | Session in Activity history | after #1 | listed, no Demo/Incomplete badge, HR chart shown |
| 5 | Phone drop loses no data | `tools/device-tests/offline.sh 120` mid-workout | total active time = wall time − pauses; HR continuous; watch buffer empty |
| 6 | Takeover asks first; any device answers | `tools/device-tests/takeover.sh` | prompt on all three; first answer wins; 15 s silence = Cancelled |
| 7 | Voice offline only | airplane mode on phone, glasses tap → "next song" | works; with pack removed: "Voice needs … pack" |
| 8 | Coordinated upgrade guard | install an older glasses APK | phone shows "Update LiveFit on your glasses"; glasses commands ignored |
```

- [ ] **Step 7: Run the acceptance checks on devices** and record results (date, numbers) in the acceptance doc's Pass column.

- [ ] **Step 8: Commit**

```bash
git add -A
git commit -m "chore: remove legacy mock-up code, add latency logging, device tests and acceptance checklist"
```
