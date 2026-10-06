package com.debasish.livefit.phone

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
import android.os.Build
import android.os.IBinder
import androidx.core.app.ServiceCompat
import androidx.core.content.ContextCompat
import com.debasish.livefit.model.DeviceKind
import com.debasish.livefit.model.LinkState
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
            .onFailure { stopSelf(); return } // missing BLUETOOTH_CONNECT: setup wizard grants it, then ensureRunning() retries
        running = true
        val graph = services
        connectGlassesIfNearby(graph)
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

    /**
     * F1: after an APK update or reboot nothing else reconnects the glasses (they sit on "Open Rokid LiveFit on your phone").
     * One attempt when they are linked and nearby; opening the app (already connecting) or a running session is left alone.
     */
    private fun connectGlassesIfNearby(graph: ServiceGraph) {
        val glassesId = CompanionLinker.associationId(this, DeviceKind.Glasses)
        val status = graph.glasses.status.value
        val linkIdle = status.link == LinkState.Disconnected && status.detail == null
        val present = glassesId != null && CompanionLinker.isPresent(glassesId)
        if (shouldConnectGlasses(linkIdle, glassesId != null, present, btConnected = glassesId != null && !present && CompanionLinker.isBtConnected(this, DeviceKind.Glasses))) {
            graph.glasses.connectOnce()
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int) = START_STICKY

    override fun onDestroy() { running = false; watcher?.cancel(); super.onDestroy() }

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
        /** True while the service is in the foreground (set once startForeground succeeded). */
        @Volatile private var running = false

        fun start(context: Context) = runCatching {
            context.startForegroundService(Intent(context, LiveFitHubService::class.java))
        }

        /**
         * Starts the hub unless it already runs or would only fail its connectedDevice prerequisite again.
         * Idempotent: called on every app resume and after the setup grants (permission or pairing).
         */
        fun ensureRunning(context: Context) {
            val bluetooth = Build.VERSION.SDK_INT < 31 ||
                ContextCompat.checkSelfPermission(context, Manifest.permission.BLUETOOTH_CONNECT) == PackageManager.PERMISSION_GRANTED
            val associated = DeviceKind.entries.any { CompanionLinker.canUnpair(context, it) }
            if (shouldStart(running, canStart(Build.VERSION.SDK_INT, bluetooth, associated))) start(context)
        }

        /** Android 14+ requires a runtime prerequisite for connectedDevice: Bluetooth permission or a companion association. */
        internal fun canStart(sdk: Int, bluetoothGranted: Boolean, hasAssociation: Boolean) = sdk < 34 || bluetoothGranted || hasAssociation

        internal fun shouldStart(running: Boolean, canStart: Boolean) = !running && canStart

        /** Connect the glasses once on hub start when linked and nearby (companion presence or a connected bonded BT device). */
        internal fun shouldConnectGlasses(linkIdle: Boolean, associated: Boolean, present: Boolean, btConnected: Boolean) =
            linkIdle && associated && (present || btConnected)
    }
}
