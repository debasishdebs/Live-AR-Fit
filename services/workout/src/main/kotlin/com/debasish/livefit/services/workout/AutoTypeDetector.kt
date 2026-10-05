package com.debasish.livefit.services.workout

import com.debasish.livefit.model.Sample
import com.debasish.livefit.model.WorkoutType

/**
 * Classifies an Auto workout from its samples: cycling ≥ 15 km/h with < 20 steps/min, running ≥ 7.5 km/h
 * or ≥ 140 steps/min, else walking. Speed is the median over a [windowMs] window (or distance/time when the
 * watch reports no speed). A type is reported only after it has held for [stableMs], and only when it changes.
 */
class AutoTypeDetector(private val windowMs: Long = 30_000, private val stableMs: Long = 30_000) {
    private val window = ArrayDeque<Sample>()
    private var candidate: WorkoutType? = null
    private var candidateSinceMs = 0L
    private var reported: WorkoutType? = null

    /** [alreadyReported] = the type detected before a restart, so it isn't reported twice. */
    fun reset(alreadyReported: WorkoutType? = null) {
        window.clear(); candidate = null; candidateSinceMs = 0; reported = alreadyReported
    }

    /** Returns a newly detected type, or null when nothing changed. */
    fun onSample(s: Sample): WorkoutType? {
        window.addLast(s)
        while (window.first().tMs < s.tMs - windowMs) window.removeFirst()
        val first = window.first()
        val spanMs = s.tMs - first.tMs
        if (spanMs < windowMs / 2) return null // not enough data yet
        val cadence = (s.stepsTotal - first.stepsTotal) * 60_000.0 / spanMs
        val speeds = window.mapNotNull { it.speedKmh }.sorted()
        val speed = if (speeds.isNotEmpty()) speeds[speeds.size / 2] else (s.distanceKmTotal - first.distanceKmTotal) * 3_600_000.0 / spanMs
        val type = when {
            speed >= 15 && cadence < 20 -> WorkoutType.Cycle
            speed >= 7.5 || cadence >= 140 -> WorkoutType.Run
            else -> WorkoutType.Walk
        }
        if (type != candidate) { candidate = type; candidateSinceMs = s.tMs; return null }
        if (s.tMs - candidateSinceMs < stableMs || type == reported) return null
        reported = type
        return type
    }
}
