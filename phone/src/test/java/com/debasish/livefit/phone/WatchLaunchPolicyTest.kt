package com.debasish.livefit.phone

import com.debasish.livefit.model.Confirmation
import com.debasish.livefit.model.ConfirmationKind
import com.debasish.livefit.model.DeviceKind
import com.debasish.livefit.model.WorkoutPhase
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** F2/F6: when the hub brings the LiveFit screen up on the watch (Samsung's media controls cover it otherwise). */
class WatchLaunchPolicyTest {
    private fun c(id: String, kind: ConfirmationKind) = Confirmation(id, kind, "t", "m", expiresAtMs = 0)

    private fun WatchLaunchPolicy.run(vararg phases: WorkoutPhase) = phases.map(::onPhase)

    @Test fun successfulStartFromGlassesPhoneOrVoiceOpensTheWatchScreenOnce() {
        for (origin in listOf(DeviceKind.Glasses, DeviceKind.Phone, null)) {
            val p = WatchLaunchPolicy()
            p.onStartRequested(origin)
            kotlin.test.assertEquals(listOf(false, true, false, false), p.run(WorkoutPhase.Starting, WorkoutPhase.Active, WorkoutPhase.Paused, WorkoutPhase.Active), "origin $origin")
        }
    }

    @Test fun startFromTheWatchUiDoesNotRelaunch() {
        val p = WatchLaunchPolicy()
        p.onStartRequested(DeviceKind.Watch)
        assertFalse(p.run(WorkoutPhase.Starting, WorkoutPhase.Active).any { it })
    }

    @Test fun failedStartOrAdoptedSessionDoesNotLaunch() {
        val p = WatchLaunchPolicy()
        p.onStartRequested(DeviceKind.Glasses)
        assertFalse(p.run(WorkoutPhase.Starting, WorkoutPhase.Idle).any { it }, "start failed")
        // A session adopted from the watch (offline start, reconnect) passes Starting without any start request here.
        assertFalse(p.run(WorkoutPhase.Starting, WorkoutPhase.Active).any { it }, "adopted")
    }

    @Test fun aRejectedStartRequestDoesNotArmALaterSession() {
        val p = WatchLaunchPolicy()
        p.onPhase(WorkoutPhase.Syncing)
        p.onStartRequested(DeviceKind.Phone) // rejected while syncing
        assertFalse(p.run(WorkoutPhase.Active, WorkoutPhase.Starting, WorkoutPhase.Active).any { it })
    }

    @Test fun newConfirmationsOpenTheWatchScreen() {
        val p = WatchLaunchPolicy()
        assertTrue(p.onConfirmation(c("a", ConfirmationKind.StopWorkoutByVoice)))
        assertFalse(p.onConfirmation(c("a", ConfirmationKind.StopWorkoutByVoice)), "same prompt again (heartbeat)")
        assertFalse(p.onConfirmation(null))
        assertTrue(p.onConfirmation(c("b", ConfirmationKind.TakeOverWorkout)))
    }

    @Test fun takeoverForAStartTappedOnTheWatchStaysOnTheWatchUi() {
        val p = WatchLaunchPolicy()
        p.onStartRequested(DeviceKind.Watch)
        p.onPhase(WorkoutPhase.Starting)
        assertFalse(p.onConfirmation(c("t", ConfirmationKind.TakeOverWorkout)), "the watch already shows it")
        assertTrue(p.onConfirmation(c("s", ConfirmationKind.StopWorkoutByVoice)), "voice stop always shows on the watch")
    }
}
