package com.debasish.livefit.services.workout

import com.debasish.livefit.confirm.DefaultConfirmationService
import com.debasish.livefit.model.EndReason
import com.debasish.livefit.model.ExerciseOp
import com.debasish.livefit.model.ExerciseState
import com.debasish.livefit.model.ExerciseStateReport
import com.debasish.livefit.model.ExerciseError
import com.debasish.livefit.model.Provenance
import com.debasish.livefit.model.Sample
import com.debasish.livefit.model.SessionClaim
import com.debasish.livefit.model.SessionDelta
import com.debasish.livefit.model.SessionEvent
import com.debasish.livefit.model.SessionStatus
import com.debasish.livefit.model.SessionSummary
import com.debasish.livefit.model.WorkoutPhase
import com.debasish.livefit.model.WorkoutType
import com.debasish.livefit.services.Clock
import com.debasish.livefit.services.SessionLifecycle
import com.debasish.livefit.services.SessionStore
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.yield
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

    /** [hubScope] lets a test kill one hub ("process death") and start another on the same store. */
    private class Rig(scope: TestScope, val store: InMemorySessionStore = InMemorySessionStore(), hubScope: CoroutineScope = scope.backgroundScope, idPrefix: String = "id") {
        var n = 0
        val gateway = FakeWatchGateway()
        val clock = Clock { scope.testScheduler.currentTime }
        val confirm = DefaultConfirmationService(clock, newId = { "c${n++}" })
        val hub = HubWorkoutService(hubScope, gateway, store, confirm, clock, newId = { "$idPrefix${n++}" })
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

    /** Codex P1: replaying an old session's final delta must not end or replace the current one. */
    @Test fun replayOfFinalizedSessionNeverTouchesTheCurrentWorkout() = runTest {
        val r = Rig(this)
        val a = active(r)
        val finalA = d(a, 1, listOf(SessionEvent.Stopped(9_000, EndReason.User)), final = true)
        r.gateway.deltas.emit(finalA); runCurrent()
        assertEquals(SessionStatus.Complete, r.store.summaries[a]!!.status)
        r.hub.dismissSummary()
        r.gateway.sent.clear()
        val b = active(r)
        r.gateway.deltas.emit(d(b, 1, samples = listOf(Sample(1_000, hr = 120)))); runCurrent()
        r.gateway.deltas.emit(finalA); runCurrent() // the watch never got A's final ack and re-sends it
        assertEquals(b, r.hub.snapshot.value.sessionId)
        assertEquals(WorkoutPhase.Active, r.hub.snapshot.value.phase)
        assertNull(r.store.summaries[b])
        assertEquals(a to 1L, r.gateway.acks.last().let { it.sessionId to it.seq })
        r.gateway.claims.emit(SessionClaim(sessionId = a, type = WorkoutType.Walk, startMs = 0, phase = WorkoutPhase.Stopping, activeMs = 9_000, lastSeq = 1)); runCurrent()
        assertEquals(b, r.hub.snapshot.value.sessionId, "a claim for a finalized session is acked, not adopted")
        assertEquals(a to 1L, r.gateway.acks.last().let { it.sessionId to it.seq })
    }

    /** Codex P1: the end event and its 24 h deadline survive a phone restart. */
    @Test fun restartAfterEndedReportKeepsStoppingAndTheOriginalDeadline() = runTest {
        val store = InMemorySessionStore()
        val firstLife = Job()
        val r1 = Rig(this, store, CoroutineScope(backgroundScope.coroutineContext + firstLife))
        val id = active(r1)
        r1.gateway.stateReports.emit(ExerciseStateReport(sessionId = id, state = ExerciseState.Ended, endedBy = EndReason.System)); runCurrent()
        advanceTimeBy(12 * 60 * 60 * 1000L); runCurrent()
        firstLife.cancel() // phone process dies…
        val r2 = Rig(this, store, idPrefix = "second"); runCurrent() // …and restarts
        assertEquals(id, r2.hub.snapshot.value.sessionId)
        assertEquals(WorkoutPhase.Stopping, r2.hub.snapshot.value.phase)
        advanceTimeBy(12 * 60 * 60 * 1000L + 60_001); runCurrent()
        assertEquals(SessionStatus.Incomplete, store.summaries[id]!!.status, "24 h counts from the end event, not from the restart")
        assertEquals(EndReason.System, store.summaries[id]!!.endReason)
    }

    /** Codex P1: an abandoned start stays abandoned after a phone restart. */
    @Test fun abandonedStartIsStillRejectedAfterRestart() = runTest {
        val store = InMemorySessionStore()
        val firstLife = Job()
        val r1 = Rig(this, store, CoroutineScope(backgroundScope.coroutineContext + firstLife)); runCurrent()
        r1.hub.start(WorkoutType.Walk); runCurrent()
        val a = r1.gateway.sent.single().sessionId
        advanceTimeBy(10_001); runCurrent() // no result: A abandoned
        firstLife.cancel()
        val r2 = Rig(this, store, idPrefix = "second"); runCurrent()
        r2.gateway.deltas.emit(d(a, 0, listOf(SessionEvent.Started(0, WorkoutType.Walk)))); runCurrent()
        assertEquals(WorkoutPhase.Idle, r2.hub.snapshot.value.phase)
        assertEquals(a to 0L, r2.gateway.acks.last().let { it.sessionId to it.seq })
        assertTrue(a !in store.openSessionIds())
        r2.gateway.claims.emit(SessionClaim(sessionId = a, type = WorkoutType.Walk, startMs = 0, phase = WorkoutPhase.Active, activeMs = 1, lastSeq = 0)); runCurrent()
        assertEquals(ExerciseOp.Stop, r2.gateway.sent.last().op)
        assertEquals(a, r2.gateway.sent.last().sessionId)
    }

    private class YieldingStore(private val inner: InMemorySessionStore) : SessionStore by inner {
        var finalizeCalls = 0
        override suspend fun storeDelta(delta: SessionDelta): Long { yield(); return inner.storeDelta(delta) }
        override suspend fun lifecycle(sessionId: String): SessionLifecycle? { yield(); return inner.lifecycle(sessionId) }
        override suspend fun finalize(summary: SessionSummary) { finalizeCalls++; delay(1); inner.finalize(summary) }
    }

    @Test fun concurrentDeltasWithASuspendingStoreFinalizeOnce() = runTest {
        val inner = InMemorySessionStore()
        val store = YieldingStore(inner)
        val gateway = FakeWatchGateway()
        val clock = Clock { testScheduler.currentTime }
        var n = 0
        val hub = HubWorkoutService(backgroundScope, gateway, store, DefaultConfirmationService(clock, newId = { "c${n++}" }), clock, newId = { "id${n++}" })
        val finished = mutableListOf<SessionSummary>()
        backgroundScope.launch { hub.finished.toList(finished) }
        runCurrent()
        hub.start(WorkoutType.Walk); runCurrent()
        val id = gateway.sent.single().sessionId
        gateway.reply()
        gateway.deltas.emit(d(id, 0, listOf(SessionEvent.Started(0, WorkoutType.Walk)))); runCurrent()
        val last = d(id, 1, listOf(SessionEvent.Stopped(9_000, EndReason.User)), final = true)
        gateway.deltas.emit(last); runCurrent() // delta handler is now parked inside finalize
        gateway.claims.emit(SessionClaim(sessionId = id, type = WorkoutType.Walk, startMs = 0, phase = WorkoutPhase.Stopping, activeMs = 9_000, lastSeq = 1))
        gateway.stateReports.emit(ExerciseStateReport(sessionId = id, state = ExerciseState.Ended, endedBy = EndReason.User))
        advanceTimeBy(10); runCurrent()
        assertEquals(1, finished.size)
        assertEquals(1, store.finalizeCalls)
        assertEquals(SessionStatus.Complete, inner.summaries[id]!!.status)
    }

    @Test fun takeOverDeclinedShowsSamsungHealthToast() = runTest {
        val r = Rig(this); runCurrent()
        r.hub.start(WorkoutType.Walk); runCurrent()
        r.gateway.reply(ok = false, error = ExerciseError.OtherAppTracking("WALKING")); runCurrent()
        r.confirm.answer(r.confirm.pending.value!!.id, yes = false); runCurrent()
        assertEquals(WorkoutPhase.Idle, r.hub.snapshot.value.phase)
        assertTrue("Samsung Health is still tracking" in r.notices)
    }

    @Test fun stopDuringTakeoverQuestionClearsIt() = runTest {
        val r = Rig(this); runCurrent()
        r.hub.start(WorkoutType.Walk); runCurrent()
        r.gateway.reply(ok = false, error = ExerciseError.OtherAppTracking("WALKING")); runCurrent()
        assertTrue(r.confirm.pending.value != null)
        r.hub.stop(); runCurrent()
        assertNull(r.confirm.pending.value)
        assertEquals(WorkoutPhase.Idle, r.hub.snapshot.value.phase)
    }

    @Test fun takeOverQuestionTimeoutReturnsToIdle() = runTest {
        val r = Rig(this); runCurrent()
        r.hub.start(WorkoutType.Walk); runCurrent()
        r.gateway.reply(ok = false, error = ExerciseError.OtherAppTracking("WALKING")); runCurrent()
        advanceTimeBy(15_001); runCurrent()
        assertEquals(WorkoutPhase.Idle, r.hub.snapshot.value.phase)
        assertTrue("Samsung Health is still tracking" in r.notices)
        assertNull(r.confirm.pending.value)
    }
}
