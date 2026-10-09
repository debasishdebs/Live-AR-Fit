package com.debasish.livefit.watch

import com.debasish.livefit.model.WorkoutType
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class ExerciseSupportTest {
    @Test fun supportedTypeHasNoReason() = assertNull(ExerciseSupport.unsupportedReason(WorkoutType.Run, "RUN", setOf("RUN", "WALK")))

    @Test fun unsupportedTypeNamesTheWorkout() =
        assertEquals("This watch can't track Cycle", ExerciseSupport.unsupportedReason(WorkoutType.Cycle, "BIKE", setOf("RUN")))
}
