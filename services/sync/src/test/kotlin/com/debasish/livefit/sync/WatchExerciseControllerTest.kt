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
import kotlinx.coroutines.async
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import java.io.File
import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue

@OptIn(ExperimentalCoroutinesApi::class)
class WatchExerciseControllerTest {
    private class FakeBackend : ExerciseBackend {
        var missing = emptyList<String>()
        var other: String? = null
        var pauseOk = true
        var endOk = true
        /** What reattach() answers after "process death": true = our exercise still runs, false = gone, null = unknown. */
        var reattachAnswer: Boolean? = true
        /** Consumed first, one per reattach call, before [reattachAnswer]. */
        val reattachScript = ArrayDeque<Boolean?>()
        var throwOnOther = false
        var endDelayMs = 0L
        val calls = mutableListOf<String>()
        override val updates = MutableSharedFlow<BackendUpdate>(extraBufferCapacity = 16)
        override fun missingPermissions() = missing
        override suspend fun otherAppTracking(): String? { if (throwOnOther) error("hs down"); return other }
        override suspend fun start(type: WorkoutType, useGps: Boolean): Boolean { calls += "start:$type:$useGps"; return true }
        override suspend fun pause(): Boolean { calls += "pause"; return pauseOk }
        override suspend fun resume(): Boolean { calls += "resume"; return true }
        override suspend fun end(): Boolean {
            calls += "end"
            kotlinx.coroutines.delay(endDelayMs)
            if (endOk) { // Health Services delivers the last metrics together with the ENDED state
                updates.emit(BackendUpdate.Reading(Sample(500, hr = 99, stepsTotal = 42)))
                updates.emit(BackendUpdate.Ended(EndReason.User))
            }
            return endOk
        }
        override suspend fun reattach(last: Sample?): Boolean? { calls += "reattach:${last?.stepsTotal}"; return if (reattachScript.isNotEmpty()) reattachScript.removeFirst() else reattachAnswer }
    }

    private val results = mutableListOf<ExerciseResult>()
    private val states = mutableListOf<ExerciseStateReport>()
    private val sent = mutableListOf<SessionDelta>()
    private val live = Provenance.Live("galaxy-watch/health-services")

    private fun TestScope.rig(backend: FakeBackend = FakeBackend(), root: File = Files.createTempDirectory("w").toFile()): Pair<FakeBackend, WatchExerciseController> {
        val recorder = WatchSessionRecorder(File(root, "buffer"), live, send = { sent += it })
        var n = 0
        val c = WatchExerciseController(backgroundScope, backend, recorder, Clock { testScheduler.currentTime },
            sendResult = { results += it }, sendState = { states += it }, gpsPrefs = GpsPreferences(File(root, "gps.json")), newId = { "local${n++}" })
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
        assertEquals(ExerciseState.Ended, results.last().state)
    }

    /** Codex P1: readings delivered while Health Services shuts down are kept, and the final delta comes after them. */
    @Test fun stopKeepsShutdownReadingsBeforeTheFinalDelta() = runTest {
        val (_, c) = rig(); runCurrent()
        c.handle(req("r1", "s", ExerciseOp.Start(WorkoutType.Walk)))
        c.handle(req("r2", "s", ExerciseOp.Stop))
        val shutdown = sent[sent.size - 2]
        assertEquals(42, shutdown.samples.single().stepsTotal)
        assertTrue(shutdown.samples.single().tMs <= 0, "clamped to the stop time so active time doesn't grow")
        assertTrue(sent.last().final)
        assertTrue(states.isEmpty(), "a user stop is not reported as an unsolicited end")
    }

    /** Codex P1: a failed end must not finalize a workout that is still running. */
    @Test fun failedEndIsReportedAndTheSessionKeepsRunning() = runTest {
        val backend = FakeBackend().apply { endOk = false }
        val (_, c) = rig(backend); runCurrent()
        c.handle(req("r1", "s", ExerciseOp.Start(WorkoutType.Walk)))
        c.handle(req("r2", "s", ExerciseOp.Stop))
        assertFalse(results.last().ok)
        assertIs<ExerciseError.Internal>(results.last().error)
        assertFalse(sent.last().final)
        assertEquals("s", c.activeSessionId)
    }

    @Test fun failedPauseRecordsNoPause() = runTest {
        val backend = FakeBackend().apply { pauseOk = false }
        val (_, c) = rig(backend); runCurrent()
        c.handle(req("r1", "s", ExerciseOp.Start(WorkoutType.Walk)))
        c.handle(req("r2", "s", ExerciseOp.Pause))
        assertFalse(results.last().ok)
        assertTrue(sent.none { d -> d.events.any { it is SessionEvent.Paused } })
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

    /** Codex P2: Auto is classified from the readings themselves; nothing injects TypeDetected. */
    @Test fun autoWorkoutRecordsTheDetectedTypeFromReadings() = runTest {
        val (b, c) = rig(); runCurrent()
        c.handle(req("r1", "s", ExerciseOp.Start(WorkoutType.Auto)))
        for (t in 1..90) { b.updates.emit(BackendUpdate.Reading(Sample(t * 1_000L, hr = 150, stepsTotal = t * 160 / 60, speedKmh = 9.5))); runCurrent() }
        val detected = sent.flatMap { it.events }.filterIsInstance<SessionEvent.TypeDetected>()
        assertEquals(listOf(WorkoutType.Run), detected.map { it.type })
    }

    /** Codex P1: after process death the controller reattaches to the running exercise without the watch UI. */
    @Test fun recoverReattachesAndKeepsRecording() = runTest {
        val root = Files.createTempDirectory("w").toFile()
        val (_, first) = rig(root = root); runCurrent()
        first.handle(req("r1", "s", ExerciseOp.Start(WorkoutType.Walk)))
        val (b2, c2) = rig(root = root); runCurrent() // new process, same buffer
        c2.recover()
        assertEquals(listOf("reattach:null"), b2.calls)
        b2.updates.emit(BackendUpdate.Reading(Sample(2_000, hr = 101))); runCurrent()
        assertEquals(101, sent.last().samples.single().hr)
        assertEquals("s", c2.activeSessionId)
    }

    @Test fun recoverFinalizesWhenTheExerciseIsGone() = runTest {
        val root = Files.createTempDirectory("w").toFile()
        val (_, first) = rig(root = root); runCurrent()
        first.handle(req("r1", "s", ExerciseOp.Start(WorkoutType.Walk)))
        val (b2, c2) = rig(FakeBackend().apply { reattachAnswer = false }, root); runCurrent()
        c2.recover()
        assertTrue(sent.last().final)
        assertEquals(SessionEvent.Stopped(0, EndReason.System), sent.last().events.single())
        assertEquals(ExerciseStateReport(sessionId = "s", state = ExerciseState.Ended, endedBy = EndReason.System), states.single())
        assertNull(c2.activeSessionId)
        assertTrue(b2.calls.single().startsWith("reattach"))
    }

    /** Codex P2: watch-only start while the phone is unreachable (spec §4.4). */
    @Test fun localStartChecksPermissionsAndTakeoverThenStarts() = runTest {
        val backend = FakeBackend().apply { missing = listOf("android.permission.BODY_SENSORS") }
        val (_, c) = rig(backend); runCurrent()
        assertIs<ExerciseError.PermissionMissing>(c.localStart(WorkoutType.Walk))
        assertIs<ExerciseError.PermissionMissing>(c.lastError.value)
        backend.missing = emptyList(); backend.other = "WALKING"
        assertEquals(ExerciseError.OtherAppTracking("WALKING"), c.localStart(WorkoutType.Walk))
        assertNull(c.localStart(WorkoutType.Walk, force = true))
        assertEquals("local0", c.activeSessionId)
        assertIs<SessionEvent.Started>(sent.single().events.single())
    }

    /** Codex plan round 2: phone-started Run with GPS → watch restart → offline Run still uses GPS. */
    @Test fun offlineStartReusesThePersistedGpsChoiceAfterRestart() = runTest {
        val root = Files.createTempDirectory("w").toFile()
        val (_, first) = rig(root = root); runCurrent()
        first.handle(req("r1", "a", ExerciseOp.Start(WorkoutType.Run, gps = true)))
        first.handle(req("r2", "a", ExerciseOp.Stop))
        val (b2, c2) = rig(root = root); runCurrent() // watch process restarted, phone unreachable
        assertNull(c2.localStart(WorkoutType.Run))
        assertEquals("start:Run:true", b2.calls.last())
        assertTrue(c2.localStop())
        assertNull(c2.localStart(WorkoutType.Walk))
        assertEquals("start:Walk:false", b2.calls.last(), "no GPS choice for Walk yet → off")
    }

    /** One session at a time on the phone: a phone start waits until the previous session is stored. */
    @Test fun phoneStartIsRefusedWhileAnOlderSessionAwaitsSync() = runTest {
        val (b, c) = rig(); runCurrent()
        c.handle(req("r1", "a", ExerciseOp.Start(WorkoutType.Walk)))
        c.handle(req("r2", "a", ExerciseOp.Stop)) // final delta not acked yet
        c.handle(req("r3", "b", ExerciseOp.Start(WorkoutType.Walk)))
        assertEquals(ExerciseError.Internal(WatchExerciseController.SYNCING_PREVIOUS), results.last().error)
        assertEquals(1, b.calls.count { it.startsWith("start") })
    }

    @Test fun backendExceptionBecomesInternalError() = runTest {
        val backend = FakeBackend().apply { throwOnOther = true }
        val (_, c) = rig(backend); runCurrent()
        c.handle(req("r1", "s", ExerciseOp.Start(WorkoutType.Walk)))
        assertIs<ExerciseError.Internal>(results.last().error)
        assertIs<ExerciseError.Internal>(c.localStart(WorkoutType.Walk))
        assertNull(c.activeSessionId)
    }

    @Test fun recoverKeepsRetryingUntilHealthServicesAnswers() = runTest {
        val root = Files.createTempDirectory("w").toFile()
        val (_, first) = rig(root = root); runCurrent()
        first.handle(req("r1", "s", ExerciseOp.Start(WorkoutType.Walk)))
        val (b2, c2) = rig(FakeBackend().apply { reattachScript += listOf(null, null, false) }, root); runCurrent()
        c2.recover()
        assertEquals(3, b2.calls.size)
        assertTrue(sent.last().final)
        assertEquals(ExerciseStateReport(sessionId = "s", state = ExerciseState.Ended, endedBy = EndReason.System), states.single())
        assertNull(c2.activeSessionId)
    }

    @Test fun failedEndWithExerciseGoneFinalizesAsSystem() = runTest {
        val backend = FakeBackend().apply { endOk = false; reattachAnswer = false }
        val (_, c) = rig(backend); runCurrent()
        c.handle(req("r1", "s", ExerciseOp.Start(WorkoutType.Walk)))
        assertTrue(c.localStop())
        assertTrue(sent.last().final)
        assertEquals(EndReason.System, states.single().endedBy)
    }

    @Test fun concurrentStopsShareTheOutcome() = runTest {
        val backend = FakeBackend().apply { endOk = false; endDelayMs = 100 }
        val (b, c) = rig(backend); runCurrent()
        c.handle(req("r1", "s", ExerciseOp.Start(WorkoutType.Walk)))
        val a = async { c.localStop() }
        val b2 = async { c.localStop() }
        assertEquals(listOf(false, false), listOf(a.await(), b2.await()))
        assertEquals(1, b.calls.count { it == "end" }, "second stop awaited the first")
        assertEquals("s", c.activeSessionId)
    }
}
