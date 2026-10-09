package com.debasish.livefit.watch

import com.debasish.livefit.model.WorkoutType

/** Whether this watch's Health Services can track a workout type; the reason travels back to the phone as the start error. */
object ExerciseSupport {
    fun <T> unsupportedReason(type: WorkoutType, mapped: T, supported: Set<T>): String? =
        if (mapped in supported) null else "This watch can't track ${type.label}"
}
