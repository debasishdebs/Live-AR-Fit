package com.debasish.livefit.phone

import com.debasish.livefit.model.WorkoutPhase
import com.debasish.livefit.model.WorkoutSnapshot
import com.debasish.livefit.model.formatElapsed
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.conflate
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map

/** Content of the hub's ongoing notification (spec §5.2) and when to re-post it (F7). */
object HubNotification {
    fun text(s: WorkoutSnapshot): String = when (s.phase) {
        WorkoutPhase.Active, WorkoutPhase.Paused ->
            "${s.displayType.label} · ${formatElapsed(s.elapsedMs)}" + (s.metrics.heartRate?.let { " · ♥ $it" } ?: "")
        WorkoutPhase.Syncing -> "Syncing watch data…"
        WorkoutPhase.Stopping -> "Saving workout…"
        else -> "LiveFit ready"
    }

    /**
     * Calls [post] only when the visible text differs from what is shown, and at most once per [minIntervalMs]
     * (snapshots change several times a second during a workout; each notify() used to re-post the notification).
     */
    suspend fun postChanges(snapshots: Flow<WorkoutSnapshot>, initial: String, minIntervalMs: Long = 1_000, post: (String) -> Unit) {
        var shown = initial
        snapshots.map(::text).distinctUntilChanged().conflate().collect { t ->
            if (t == shown) return@collect
            post(t)
            shown = t
            delay(minIntervalMs) // conflate keeps only the newest text meanwhile
        }
    }
}
