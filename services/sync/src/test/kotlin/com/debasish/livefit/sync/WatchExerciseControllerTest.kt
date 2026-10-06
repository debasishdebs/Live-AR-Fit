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
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.async
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
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
        var throwOnControl = false
        var endDelayMs = 0L
        /** When set, start() suspends on it (a slow Health Services start). */
        var startGate: CompletableDeferred<Unit>? = null
        /** Health Services reports its new state while pause()/resume() are still in flight. */
        var phaseDuringControl = false
        var now: () -> Long = { 0 }
        val calls = mutableListOf<String>()
        override val updates = MutableSharedFlow<BackendUpdate>(extraBufferCapacity = 16)
        override fun missingPermissions() = missing
        override suspend fun otherAppTracking(): String? { if (throwOnOther) error("hs down"); return other }
        override suspend fun start(type: WorkoutType, useGps: Boolean): Boolean { calls += "start:$type:$useGps"; startGate?.await(); return true }
        override suspend fun pause(): Boolean {
            calls += "pause"; if (throwOnControl) error("hs")
            if (phaseDuringControl && pauseOk) { updates.emit(BackendUpdate.Phase(paused = true, atMs = now())); kotlinx.coroutines.yield() }
            return pauseOk
        }
        override suspend fun resume(): Boolean {
            calls += "resume"; if (throwOnControl) error("hs")
            if (phaseDuringControl) { updates.emit(BackendUpdate.Phase(paused = false, atMs = now())); kotlinx.coroutines.yield() }
            return true
        }
        override suspend fun end(): Boolean {
            calls += "end"
            if (throwOnControl) error("hs")
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

    private fun TestScope.rig(
        backend: FakeBackend = FakeBackend(),
        root: File = Files.createTempDirectory("w").toFile(),
        send: suspend (SessionDelta) -> Unit = { sent += it },
    ): Pair<FakeBackend, WatchExerciseController> {
        val recorder = WatchSessionRecorder(File(root, "buffer"), live, backgroundScope, send = send)
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

    @Test fun controlExceptionsAreRefusalsNotCrashes() = runTest {
        val backend = FakeBackend()
        val (_, c) = rig(backend); runCurrent()
        c.handle(req("r1", "s", ExerciseOp.Start(WorkoutType.Walk)))
        backend.throwOnControl = true
        c.handle(req("r2", "s", ExerciseOp.Pause))
        assertIs<ExerciseError.Internal>(results.last().error)
        c.handle(req("r3", "s", ExerciseOp.Stop))
        assertIs<ExerciseError.Internal>(results.last().error)
        assertEquals("s", c.activeSessionId)
    }

    /** Review #1: a stalled send must not hold back shutdown readings; the final delta follows the final reading. */
    @Test fun stalledSendDoesNotDropShutdownReadings() = runTest {
        val root = Files.createTempDirectory("w").toFile()
        val gate = CompletableDeferred<Unit>()
        val (b, c) = rig(root = root, send = { d -> if (d.samples.any { it.stepsTotal == 10 }) gate.await(); sent += d }); runCurrent()
        c.handle(req("r1", "s", ExerciseOp.Start(WorkoutType.Walk)))
        b.updates.emit(BackendUpdate.Reading(Sample(100, hr = 90, stepsTotal = 10))); runCurrent() // its send stalls
        c.handle(req("r2", "s", ExerciseOp.Stop)) // end() delivers steps=42 + Ended
        assertTrue(testScheduler.currentTime < 5_000, "Ended was seen; the stop didn't time out")
        val stored = FileDeltaBuffer(File(root, "buffer/s")).unacked()
        assertTrue(stored.last().final)
        assertEquals(42, stored[stored.size - 2].samples.single().stepsTotal, "final reading recorded before the final seq")
        gate.complete(Unit); runCurrent()
        assertEquals(stored.map { it.seq }, sent.map { it.seq }, "everything sent once the link recovers, in seq order")
    }

    /** Review #3: a hub Start and an offline Start racing must reach Health Services only once. */
    @Test fun concurrentStartsReachHealthServicesOnce() = runTest {
        val backend = FakeBackend().apply { startGate = CompletableDeferred() }
        val (b, c) = rig(backend); runCurrent()
        val hub = async { c.handle(req("r1", "s", ExerciseOp.Start(WorkoutType.Walk))) }
        val local = async { c.localStart(WorkoutType.Walk) }
        runCurrent()
        b.updates.emit(BackendUpdate.Ended(EndReason.OtherApp)); runCurrent() // stray end while starting: no recorder to close
        backend.startGate!!.complete(Unit)
        hub.await()
        assertEquals(ExerciseError.WrongSession("s"), local.await())
        assertEquals(1, b.calls.count { it.startsWith("start") })
        assertTrue(results.single().ok)
        assertEquals("s", c.activeSessionId)
        assertTrue(sent.none { it.final })
    }

    @Test fun concurrentLocalThenHubStartReachesHealthServicesOnce() = runTest {
        val backend = FakeBackend().apply { startGate = CompletableDeferred() }
        val (b, c) = rig(backend); runCurrent()
        val local = async { c.localStart(WorkoutType.Run) }
        runCurrent()
        val hub = async { c.handle(req("r1", "s", ExerciseOp.Start(WorkoutType.Walk))) }
        runCurrent()
        backend.startGate!!.complete(Unit)
        assertNull(local.await()); hub.await()
        assertEquals(ExerciseError.WrongSession("local0"), results.single().error)
        assertEquals(listOf("start:Run:false"), b.calls)
        assertEquals("local0", c.activeSessionId)
    }

    private fun phaseEvents() = sent.flatMap { it.events }.filter { it is SessionEvent.Paused || it is SessionEvent.Resumed }

    /** Review #7: Health Services paused, then the process died before the Paused event was saved. */
    @Test fun recoverRecordsAPauseTheBackendAppliedButWeDidNotSave() = runTest {
        val root = Files.createTempDirectory("w").toFile()
        val (b1, first) = rig(root = root); runCurrent()
        first.handle(req("r1", "s", ExerciseOp.Start(WorkoutType.Walk)))
        b1.updates.emit(BackendUpdate.Reading(Sample(10_000, hr = 100))); runCurrent()
        val (b2, c2) = rig(root = root); runCurrent() // new process: durable phase is Active
        c2.recover()
        // Health Services says: paused, with 12 s of active time (it paused at 12 s, we'd count to 30 s).
        b2.updates.emit(BackendUpdate.Phase(paused = true, atMs = 30_000, activeMs = 12_000)); runCurrent()
        assertEquals(listOf<SessionEvent>(SessionEvent.Paused(12_000)), phaseEvents())
        b2.updates.emit(BackendUpdate.Reading(Sample(31_000, hr = 90))); runCurrent()
        val stored = WatchSessionRecorder(File(root, "buffer"), live, backgroundScope, send = {}).assembler!!
        assertEquals(com.debasish.livefit.model.WorkoutPhase.Paused, stored.phase())
        assertEquals(12_000, stored.activeMs())
    }

    @Test fun recoverRecordsAResumeTheBackendAppliedButWeDidNotSave() = runTest {
        val root = Files.createTempDirectory("w").toFile()
        val (_, first) = rig(root = root); runCurrent()
        first.handle(req("r1", "s", ExerciseOp.Start(WorkoutType.Walk)))
        advanceTimeBy(10_000)
        first.handle(req("r2", "s", ExerciseOp.Pause)) // durable phase: Paused at 10 s
        val (b2, c2) = rig(root = root); runCurrent()
        c2.recover()
        // Health Services is active again with 25 s active at 40 s: it resumed at 25 s.
        b2.updates.emit(BackendUpdate.Phase(paused = false, atMs = 40_000, activeMs = 25_000)); runCurrent()
        assertEquals(listOf(SessionEvent.Paused(10_000), SessionEvent.Resumed(25_000)), phaseEvents())
        b2.updates.emit(BackendUpdate.Reading(Sample(40_000, hr = 90))); runCurrent()
        val stored = WatchSessionRecorder(File(root, "buffer"), live, backgroundScope, send = {}).assembler!!
        assertEquals(com.debasish.livefit.model.WorkoutPhase.Active, stored.phase())
        assertEquals(25_000, stored.activeMs())
    }

    @Test fun backendPhaseMatchingOrOlderThanOurOwnIsIgnored() = runTest {
        val backend = FakeBackend().apply { phaseDuringControl = true }
        backend.now = { testScheduler.currentTime }
        val (b, c) = rig(backend); runCurrent()
        c.handle(req("r1", "s", ExerciseOp.Start(WorkoutType.Walk)))
        advanceTimeBy(5_000)
        c.handle(req("r2", "s", ExerciseOp.Pause)) // Health Services' own Paused arrives while pause() is in flight
        b.updates.emit(BackendUpdate.Phase(paused = true, atMs = 6_000)); runCurrent() // already paused
        advanceTimeBy(5_000)
        c.handle(req("r3", "s", ExerciseOp.Resume))
        b.updates.emit(BackendUpdate.Phase(paused = true, atMs = 9_000)); runCurrent() // stale: before our resume
        assertEquals(1, phaseEvents().count { it is SessionEvent.Paused })
        assertEquals(1, phaseEvents().count { it is SessionEvent.Resumed })
        assertTrue(results.all { it.ok })
    }

    /** Review #12: granting the permission clears the PermissionMissing error the watch UI shows. */
    @Test fun permissionRecheckClearsAResolvedError() = runTest {
        val backend = FakeBackend().apply { missing = listOf("android.permission.BODY_SENSORS", "android.permission.ACTIVITY_RECOGNITION") }
        val (_, c) = rig(backend); runCurrent()
        assertIs<ExerciseError.PermissionMissing>(c.localStart(WorkoutType.Walk))
        backend.missing = listOf("android.permission.ACTIVITY_RECOGNITION")
        c.recheckPermissions()
        assertEquals(ExerciseError.PermissionMissing(listOf("android.permission.ACTIVITY_RECOGNITION")), c.lastError.value)
        backend.missing = emptyList()
        c.recheckPermissions()
        assertNull(c.lastError.value)
        assertNull(c.localStart(WorkoutType.Walk))
    }

    /** r2 I1: a session that ended here but whose data is still held is not "no session": Stop is ok, never buffer loss. */
    @Test fun opsForAnEndedSessionStillHeldAreNotReportedAsLost() = runTest {
        val (b, c) = rig(send = { error("phone unreachable") }); runCurrent() // final delta stays buffered
        c.handle(req("r1", "s", ExerciseOp.Start(WorkoutType.Walk)))
        c.handle(req("r2", "s", ExerciseOp.Stop))
        assertTrue(results.last().ok)
        c.handle(req("r3", "s", ExerciseOp.Stop)) // user taps Stop again / retries after a lost result
        assertTrue(results.last().ok, "repeated Stop of the held session is ok")
        assertEquals(ExerciseState.Ended, results.last().state)
        assertEquals(1, b.calls.count { it == "end" })
        c.handle(req("r4", "s", ExerciseOp.Pause))
        assertFalse(results.last().ok)
        assertEquals(ExerciseState.Ended, results.last().state, "not Idle: the phone must not treat it as a lost buffer")
        c.handle(req("r5", "gone", ExerciseOp.Stop))
        assertEquals(ExerciseError.WrongSession(null), results.last().error)
        assertEquals(ExerciseState.Idle, results.last().state, "a session not held at all is reported as no session")
    }

    /** r2 M2: a screen-off HR batch is recorded as one delta, not one per point. */
    @Test fun readingBatchIsRecordedAsOneDelta() = runTest {
        val (b, c) = rig(); runCurrent()
        c.handle(req("r1", "s", ExerciseOp.Start(WorkoutType.Walk)))
        b.updates.emit(BackendUpdate.Reading((1..60).map { Sample(it * 1_000L, hr = 100 + it) })); runCurrent()
        assertEquals(2, sent.size)
        assertEquals(60, sent.last().samples.size)
    }
}
