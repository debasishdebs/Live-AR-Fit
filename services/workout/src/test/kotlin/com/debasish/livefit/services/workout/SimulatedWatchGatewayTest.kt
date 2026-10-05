package com.debasish.livefit.services.workout

import com.debasish.livefit.confirm.DefaultConfirmationService
import com.debasish.livefit.model.Provenance
import com.debasish.livefit.model.SessionStatus
import com.debasish.livefit.model.WorkoutPhase
import com.debasish.livefit.model.WorkoutType
import com.debasish.livefit.services.Clock
import kotlinx.coroutines.ExperimentalCoroutinesApi
import com.debasish.livefit.model.ExerciseError
import com.debasish.livefit.model.ExerciseOp
import com.debasish.livefit.model.ExerciseRequest
import com.debasish.livefit.model.ExerciseResult
import com.debasish.livefit.model.SessionDelta
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.UnconfinedTestDispatcher
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

    @Test fun stopForAnotherSessionLeavesTheDemoRunning() = runTest {
        val clock = Clock { testScheduler.currentTime }
        val gateway = SimulatedWatchGateway(backgroundScope, clock)
        val results = mutableListOf<ExerciseResult>()
        val deltas = mutableListOf<SessionDelta>()
        backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) { gateway.results.toList(results) }
        backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) { gateway.deltas.toList(deltas) }
        gateway.send(ExerciseRequest(requestId = "r1", sessionId = "s1", op = ExerciseOp.Start(WorkoutType.Run)))
        advanceTimeBy(3_500); runCurrent()
        gateway.send(ExerciseRequest(requestId = "r2", sessionId = "other", op = ExerciseOp.Stop))
        val rejected = results.last()
        assertEquals(false, rejected.ok)
        assertEquals(ExerciseError.WrongSession("s1"), rejected.error)
        val before = deltas.size
        advanceTimeBy(3_000); runCurrent()
        assertTrue(deltas.size > before)
        assertTrue(deltas.none { it.final })
        assertTrue(deltas.all { it.sessionId == "s1" })
    }
}
