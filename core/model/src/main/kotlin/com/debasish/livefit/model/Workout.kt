package com.debasish.livefit.model

import kotlinx.serialization.Serializable

@Serializable
enum class WorkoutType(val label: String) {
    Walk("Walk"),
    Run("Run"),
    Cycle("Cycle"),

    /** Type is inferred from cadence/speed; see [WorkoutSnapshot.detectedType]. */
    Auto("Auto"),
}

@Serializable
enum class WorkoutPhase { Idle, Starting, Active, Paused, Stopping, Summary }

/** Latest live readings from whichever wearable source is active. Null = not available yet. */
@Serializable
data class Metrics(
    val heartRate: Int? = null,
    val calories: Int = 0,
    val steps: Int = 0,
    val distanceKm: Double = 0.0,
    val speedKmh: Double = 0.0,
)

@Serializable
data class WorkoutSnapshot(
    val phase: WorkoutPhase = WorkoutPhase.Idle,
    val type: WorkoutType = WorkoutType.Walk,
    /** Only set when [type] is Auto. */
    val detectedType: WorkoutType? = null,
    val elapsedMs: Long = 0,
    val metrics: Metrics = Metrics(),
    val avgHeartRate: Int? = null,
    val maxHeartRate: Int? = null,
) {
    /** The type to show the user: the detected one in Auto mode, else the chosen one. */
    val displayType: WorkoutType get() = if (type == WorkoutType.Auto) detectedType ?: WorkoutType.Walk else type
}

/** Five-zone model on % of max heart rate (50/60/70/80/90). */
object HeartZones {
    fun zoneFor(heartRate: Int?, maxHeartRate: Int = 190): Int? {
        heartRate ?: return null
        val pct = heartRate * 100 / maxHeartRate
        return when {
            pct < 50 -> 0
            pct < 60 -> 1
            pct < 70 -> 2
            pct < 80 -> 3
            pct < 90 -> 4
            else -> 5
        }
    }
}

fun formatElapsed(ms: Long): String {
    val total = ms / 1000
    val h = total / 3600
    val m = (total % 3600) / 60
    val s = total % 60
    return if (h > 0) "%d:%02d:%02d".format(h, m, s) else "%02d:%02d".format(m, s)
}
