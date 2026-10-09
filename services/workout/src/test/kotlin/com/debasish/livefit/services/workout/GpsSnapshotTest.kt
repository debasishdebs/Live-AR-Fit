package com.debasish.livefit.services.workout

import com.debasish.livefit.confirm.DefaultConfirmationService
import com.debasish.livefit.model.ExerciseOp
import com.debasish.livefit.model.Provenance
import com.debasish.livefit.model.SessionDelta
import com.debasish.livefit.model.SessionEvent
import com.debasish.livefit.model.WorkoutType
import com.debasish.livefit.services.Clock
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

@OptIn(ExperimentalCoroutinesApi::class)
class GpsSnapshotTest {
    @Test fun snapshotReportsGpsFromTheStartedEvent() {
        val a = SessionAssembler("s")
        a.add(SessionDelta(sessionId = "s", seq = 0, events = listOf(SessionEvent.Started(1, WorkoutType.Walk, gps = true)), provenance = Provenance.Fake))
        assertTrue(a.gps())
        assertTrue(a.snapshot().gps)
    }

    @Test fun noStartedEventMeansNoGps() = assertFalse(SessionAssembler("s").snapshot().gps)

    @Test fun startingSnapshotCarriesTheHubsGpsChoice() = runTest {
        val gateway = FakeWatchGateway()
        val clock = Clock { testScheduler.currentTime }
        val hub = HubWorkoutService(backgroundScope, gateway, InMemorySessionStore(), DefaultConfirmationService(clock), clock, gpsFor = { true })
        runCurrent()
        hub.start(WorkoutType.Walk); runCurrent()
        assertTrue(hub.snapshot.value.gps)
        assertEquals(ExerciseOp.Start(WorkoutType.Walk, gps = true), gateway.sent.single().op)
    }
}
