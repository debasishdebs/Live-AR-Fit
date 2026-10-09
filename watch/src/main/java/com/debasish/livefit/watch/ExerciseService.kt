package com.debasish.livefit.watch

import android.Manifest
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.content.pm.ServiceInfo
import android.os.IBinder
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import androidx.core.content.ContextCompat
import androidx.wear.ongoing.OngoingActivity
import androidx.wear.ongoing.Status
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/** Keeps tracking alive through screen-off; wrist raise returns to the app (spec §5.1). */
class ExerciseService : Service() {
    private var watcher: Job? = null
    private var notification: Notification? = null

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        WatchRuntime.init(this)
        val nm = getSystemService(NotificationManager::class.java)
        nm.createNotificationChannel(NotificationChannel(CHANNEL, "Workout", NotificationManager.IMPORTANCE_LOW))
        val touch = PendingIntent.getActivity(this, 0, Intent(this, MainActivity::class.java), PendingIntent.FLAG_IMMUTABLE)
        val builder = NotificationCompat.Builder(this, CHANNEL)
            .setContentTitle("Rokid LiveFit").setContentText("Workout in progress")
            .setSmallIcon(android.R.drawable.ic_media_play).setOngoing(true).setContentIntent(touch)
        OngoingActivity.Builder(this, ID, builder)
            .setStaticIcon(android.R.drawable.ic_media_play)
            .setTouchIntent(touch)
            .setStatus(Status.Builder().addTemplate("LiveFit workout").build())
            .build().apply(this)
        notification = builder.build()
        promote()
        watcher = WatchRuntime.scope.launch {
            // Stop once the session is finalized AND its buffer is fully acked (recorder clears it).
            while (true) {
                delay(5_000)
                if (!WatchRuntime.recorder.holdsData) { stopSelf(); break }
            }
        }
    }

    /** Every start re-promotes: a GPS session started after the service came up needs `location` too. */
    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        promote()
        return START_STICKY
    }

    /** health|location for a GPS workout with fine location; health only otherwise — and never crash on a refusal. */
    private fun promote() {
        val n = notification ?: return
        val fine = ContextCompat.checkSelfPermission(this, Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED
        val types = WatchFgs.types(WatchRuntime.controller.activeGps, fine)
        runCatching { ServiceCompat.startForeground(this, ID, n, types) }.onFailure { e ->
            Log.w(WatchRuntime.TAG, "startForeground($types) refused; health only", e)
            runCatching { ServiceCompat.startForeground(this, ID, n, ServiceInfo.FOREGROUND_SERVICE_TYPE_HEALTH) }
        }
    }
    override fun onDestroy() { watcher?.cancel(); super.onDestroy() }

    companion object {
        private const val CHANNEL = "workout"
        private const val ID = 1
        fun start(context: Context) = runCatching { context.startForegroundService(Intent(context, ExerciseService::class.java)) }
    }
}
