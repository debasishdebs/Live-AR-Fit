package com.debasish.livefit.services.workout

import com.debasish.livefit.model.DeviceStatus
import com.debasish.livefit.model.Metrics
import com.debasish.livefit.model.WorkoutPhase
import com.debasish.livefit.model.WorkoutType
import com.debasish.livefit.services.MetricsSource
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals

@OptIn(ExperimentalCoroutinesApi::class)
class DefaultWorkoutServiceTest {

    private class StepSource(private val speed: Double = 5.0) : MetricsSource {
        override val id = "test"
        override val status = MutableStateFlow(DeviceStatus("test"))
        override fun start(type: WorkoutType) = flow {
            var i = 0
            while (true) { emit(Metrics(heartRate = 100 + i, steps = i * 2, speedKmh = speed)); i++; delay(1_000) }
        }
        override suspend fun stop() = Unit
    }

    private fun TestScope.service(speed: Double = 5.0) =
        DefaultWorkoutService(backgroundScope, StepSource(speed), now = { testScheduler.currentTime })

    @Test fun startGoesActiveOnFirstReading() = runTest {
        val s = service()
        s.start(WorkoutType.Walk)
        runCurrent()
        assertEquals(WorkoutPhase.Active, s.snapshot.value.phase)
    }

    @Test fun elapsedExcludesPause() = runTest {
        val s = service()
        s.start(WorkoutType.Walk); runCurrent()
        advanceTimeBy(10_000); s.pause()
        advanceTimeBy(30_000); s.resume()
        advanceTimeBy(5_000); s.stop(); runCurrent()
        assertEquals(WorkoutPhase.Summary, s.snapshot.value.phase)
        assertEquals(15_000, s.snapshot.value.elapsedMs)
    }

    @Test fun autoModeDetectsRunFromSpeed() = runTest {
        val s = service(speed = 9.0)
        s.start(WorkoutType.Auto); runCurrent()
        assertEquals(WorkoutType.Run, s.snapshot.value.displayType)
    }

    @Test fun cannotStartTwice() = runTest {
        val s = service()
        s.start(WorkoutType.Walk); runCurrent()
        s.start(WorkoutType.Run); runCurrent()
        assertEquals(WorkoutType.Walk, s.snapshot.value.type)
    }

    @Test fun dismissSummaryReturnsToIdle() = runTest {
        val s = service()
        s.start(WorkoutType.Walk); runCurrent()
        s.stop(); runCurrent()
        s.dismissSummary()
        assertEquals(WorkoutPhase.Idle, s.snapshot.value.phase)
    }
}
