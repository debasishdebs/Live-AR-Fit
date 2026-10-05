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
