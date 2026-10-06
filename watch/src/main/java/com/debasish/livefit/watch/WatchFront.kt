package com.debasish.livefit.watch

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.os.Build
import androidx.core.app.NotificationCompat

/**
 * Watch-side "bring the workout screen to the front" (A3), in addition to the phone's RemoteActivityHelper path.
 * 1. A direct startActivity: allowed only when Android grants this background start an exemption; otherwise the
 *    platform blocks it silently (no exception) — harmless.
 * 2. A one-shot, silent, high-importance notification with a full-screen intent. Android launches it only while the
 *    screen is off/ambient; with the screen on it shows as a heads-up the user can tap. Skipped silently when
 *    [NotificationManager.canUseFullScreenIntent] is false (Android 14+ grants USE_FULL_SCREEN_INTENT by default only
 *    to calling/alarm apps on Play installs) or notifications are off.
 */
object WatchFront {
    private const val CHANNEL = "workout_front"
    private const val ID = 2
    private const val TIMEOUT_MS = 15_000L

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
        val open = Intent(app, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        runCatching { app.startActivity(open) }.onFailure { WatchRuntime.log("front: direct start failed ($reason): $it") }
        runCatching { postFullScreen(app, open, reason) }.onFailure { WatchRuntime.log("front: full-screen notification failed ($reason): $it") }
    }

    private fun postFullScreen(context: Context, open: Intent, reason: String) {
        val nm = context.getSystemService(NotificationManager::class.java)
        if (!nm.areNotificationsEnabled()) return
        if (Build.VERSION.SDK_INT >= 34 && !nm.canUseFullScreenIntent()) {
            WatchRuntime.log("front: full-screen intent not permitted; relying on the phone launch ($reason)")
            return
        }
        nm.createNotificationChannel(NotificationChannel(CHANNEL, "Workout screen", NotificationManager.IMPORTANCE_HIGH).apply {
            setSound(null, null)
            enableVibration(false)
            setShowBadge(false)
        })
        val pi = PendingIntent.getActivity(context, 1, open, PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
        val n = NotificationCompat.Builder(context, CHANNEL)
            .setSmallIcon(android.R.drawable.ic_media_play)
            .setContentTitle("Rokid LiveFit")
            .setContentText(if (reason == "confirmation") "Answer on the workout screen" else "Workout started")
            .setCategory(NotificationCompat.CATEGORY_WORKOUT)
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setContentIntent(pi)
            .setFullScreenIntent(pi, true)
            .setAutoCancel(true)
            .setTimeoutAfter(TIMEOUT_MS)
            .build()
        nm.notify(ID, n)
    }
}
