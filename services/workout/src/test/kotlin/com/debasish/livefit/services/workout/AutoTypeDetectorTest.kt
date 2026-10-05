package com.debasish.livefit.services.workout

import com.debasish.livefit.model.Sample
import com.debasish.livefit.model.WorkoutType
import kotlin.test.Test
import kotlin.test.assertEquals

class AutoTypeDetectorTest {
    /** 1 Hz samples from cumulative steps and an instantaneous speed — what Health Services delivers. */
    private fun feed(d: AutoTypeDetector, fromS: Int, toS: Int, speedKmh: Double, stepsPerMin: Int, stepsAtStart: Int = 0): List<WorkoutType> =
        (fromS until toS).mapNotNull { t ->
            d.onSample(Sample(t * 1_000L, hr = 120, stepsTotal = stepsAtStart + (t - fromS) * stepsPerMin / 60, speedKmh = speedKmh))
        }

    @Test fun runningIsDetectedOnceFromSpeedAndCadence() =
        assertEquals(listOf(WorkoutType.Run), feed(AutoTypeDetector(), 0, 120, speedKmh = 9.5, stepsPerMin = 160))

    @Test fun cyclingIsFastWithAlmostNoSteps() =
        assertEquals(listOf(WorkoutType.Cycle), feed(AutoTypeDetector(), 0, 120, speedKmh = 20.0, stepsPerMin = 0))

    @Test fun walkingIsTheDefault() =
        assertEquals(listOf(WorkoutType.Walk), feed(AutoTypeDetector(), 0, 120, speedKmh = 5.0, stepsPerMin = 110))

    @Test fun briefSprintDoesNotFlipTheType() {
        val d = AutoTypeDetector()
        assertEquals(listOf(WorkoutType.Walk), feed(d, 0, 90, speedKmh = 5.0, stepsPerMin = 110))
        assertEquals(emptyList(), feed(d, 90, 100, speedKmh = 10.0, stepsPerMin = 165, stepsAtStart = 165))
        assertEquals(emptyList(), feed(d, 100, 160, speedKmh = 5.0, stepsPerMin = 110, stepsAtStart = 192))
    }

    @Test fun sustainedChangeIsReported() {
        val d = AutoTypeDetector()
        feed(d, 0, 90, speedKmh = 5.0, stepsPerMin = 110)
        assertEquals(listOf(WorkoutType.Run), feed(d, 90, 200, speedKmh = 10.0, stepsPerMin = 165, stepsAtStart = 165))
    }
}
