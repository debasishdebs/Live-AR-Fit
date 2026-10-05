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
        assertTrue("Samsung Health is still tracking" in r.notices)
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
