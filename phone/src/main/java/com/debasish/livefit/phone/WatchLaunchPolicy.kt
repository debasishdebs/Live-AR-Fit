package com.debasish.livefit.phone

import com.debasish.livefit.model.Confirmation
import com.debasish.livefit.model.ConfirmationKind
import com.debasish.livefit.model.DeviceKind
import com.debasish.livefit.model.WorkoutPhase

/**
 * When the hub opens LiveFit's screen on the watch (F2, F6). Music starting with a workout brings up Samsung's media
 * controls, so the watch must be told to show our workout screen and any cross-device confirmation.
 * - A successful start (Starting → Active) requested from any device opens it — also one tapped on the watch, since
 *   the media controls cover it there too (seen on device) — but not a session adopted from the watch.
 * - Every new confirmation opens it, except a takeover prompt for a start tapped on the watch.
 * Pure; fed from the hub's main thread.
 */
class WatchLaunchPolicy {
    private var requested = false
    private var requestOrigin: DeviceKind? = null
    private var sessionFromWatch = false
    private var armed = false
    private var phase = WorkoutPhase.Idle
    private var lastConfirmationId: String? = null

    /** [origin] null = voice (glasses or phone mic). Called just before the hub applies a StartWorkout. */
    fun onStartRequested(origin: DeviceKind?) {
        requested = true
        requestOrigin = origin
    }

    /** Returns true when the watch screen should be opened now. */
    fun onPhase(next: WorkoutPhase): Boolean {
        if (next == phase) return false
        val prev = phase
        phase = next
        if (next == WorkoutPhase.Starting) {
            sessionFromWatch = !requested || requestOrigin == DeviceKind.Watch
            armed = requested // watch UI too: music start brings Samsung's media controls over it
            requested = false
            return false
        }
        requested = false // a start request that didn't lead to Starting (rejected) must not arm a later session
        val launch = armed && prev == WorkoutPhase.Starting && next == WorkoutPhase.Active
        armed = false
        return launch
    }

    fun onConfirmation(c: Confirmation?): Boolean {
        if (c == null || c.id == lastConfirmationId) return false
        lastConfirmationId = c.id
        return !(c.kind == ConfirmationKind.TakeOverWorkout && sessionFromWatch && phase == WorkoutPhase.Starting)
    }
}
