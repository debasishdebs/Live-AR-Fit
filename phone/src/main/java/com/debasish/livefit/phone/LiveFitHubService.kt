package com.debasish.livefit.phone

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.IBinder
import androidx.core.app.ServiceCompat
import com.debasish.livefit.model.WorkoutPhase
import com.debasish.livefit.model.formatElapsed
import com.debasish.livefit.phone.ui.AppActivity
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch

/** Keeps the hub (ServiceGraph) alive while a linked device is present or a workout runs (spec §5.2). */
class LiveFitHubService : Service() {
    private var watcher: Job? = null

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        val nm = getSystemService(NotificationManager::class.java)
        nm.createNotificationChannel(NotificationChannel(CHANNEL, "LiveFit hub", NotificationManager.IMPORTANCE_LOW))
        val type = ServiceInfo.FOREGROUND_SERVICE_TYPE_CONNECTED_DEVICE
        runCatching { ServiceCompat.startForeground(this, ID, notification("LiveFit ready"), type) }
            .onFailure { stopSelf(); return } // missing BLUETOOTH_CONNECT: setup wizard grants it
        val graph = services
        watcher = graph.scope.launch {
            graph.workout.snapshot.collect { s ->
                val text = when (s.phase) {
                    WorkoutPhase.Active, WorkoutPhase.Paused ->
                        "${s.displayType.label} · ${formatElapsed(s.elapsedMs)}" + (s.metrics.heartRate?.let { " · ♥ $it" } ?: "")
                    WorkoutPhase.Syncing -> "Syncing watch data…"
                    WorkoutPhase.Stopping -> "Saving workout…"
                    else -> "LiveFit ready"
                }
                nm.notify(ID, notification(text))
            }
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int) = START_STICKY

    override fun onDestroy() { watcher?.cancel(); super.onDestroy() }

    private fun notification(text: String): Notification = Notification.Builder(this, CHANNEL)
        .setContentTitle("Rokid LiveFit")
        .setContentText(text)
        .setSmallIcon(android.R.drawable.ic_menu_compass)
        .setOngoing(true)
        .setContentIntent(PendingIntent.getActivity(this, 0, Intent(this, AppActivity::class.java), PendingIntent.FLAG_IMMUTABLE))
        .build()

    companion object {
        private const val CHANNEL = "hub"
        private const val ID = 7
        fun start(context: Context) = runCatching {
            context.startForegroundService(Intent(context, LiveFitHubService::class.java))
        }
    }
}
