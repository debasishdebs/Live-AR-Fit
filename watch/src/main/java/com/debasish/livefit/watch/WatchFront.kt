package com.debasish.livefit.watch

import android.app.NotificationManager
import android.content.Context
import android.content.Intent

/**
 * Watch-side "bring the workout screen to the front" (A3), in addition to the phone's RemoteActivityHelper path.
 * A direct startActivity, allowed only when Android grants this background start an exemption (otherwise the platform
 * blocks it silently) — best effort. The reliable path is the workout's Ongoing Activity (ExerciseService), whose
 * "return to workout" chip and notification tap open LiveFit on every Wear OS 3+ watch (spec §4: no full-screen intent).
 */
object WatchFront {
    private const val ID = 2

    @Volatile var visible = false; private set
    private val policy = FrontLaunchPolicy()

    fun onHubStart(context: Context) {
        val raise = synchronized(policy) { policy.onHubStart(WatchRuntime.recorder.holdsData, visible, System.currentTimeMillis()) }
        if (raise) raise(context, "hub start")
    }

    fun onConfirmation(context: Context, id: String?) {
        val raise = synchronized(policy) { policy.onConfirmation(id, visible, System.currentTimeMillis()) }
        if (raise) raise(context, "confirmation")
    }

    fun onVisible(context: Context, isVisible: Boolean) {
        visible = isVisible
        if (isVisible) runCatching { context.getSystemService(NotificationManager::class.java).cancel(ID) }
    }

    private fun raise(context: Context, reason: String) {
        val app = context.applicationContext
        WatchRuntime.ensureExerciseService() // the Ongoing Activity chip appears with the health FGS
        val open = Intent(app, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        runCatching { app.startActivity(open) }.onFailure { WatchRuntime.log("front: direct start failed ($reason): $it") }
    }
}
