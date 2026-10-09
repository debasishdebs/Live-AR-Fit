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
import android.util.Log
import androidx.core.app.ServiceCompat
import androidx.core.content.ContextCompat
import com.debasish.livefit.model.DeviceKind
import com.debasish.livefit.model.LinkState
import com.debasish.livefit.phone.ui.AppActivity
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch

/** Keeps the hub (ServiceGraph) alive while a linked device is present or a workout runs (spec §5.2). */
class LiveFitHubService : Service() {
    private var watcher: Job? = null
    private var lastText = READY

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        val nm = getSystemService(NotificationManager::class.java)
        nm.createNotificationChannel(NotificationChannel(CHANNEL, "LiveFit hub", NotificationManager.IMPORTANCE_LOW))
        val type = ServiceInfo.FOREGROUND_SERVICE_TYPE_CONNECTED_DEVICE
        runCatching { ServiceCompat.startForeground(this, ID, notification(READY), type) }
            .onFailure { stopSelf(); return } // missing BLUETOOTH_CONNECT: setup wizard grants it, then ensureRunning() retries
        running = true
        val graph = services
        connectGlassesIfNearby(graph)
        watcher = graph.scope.launch {
            HubNotification.postChanges(graph.workout.snapshot, initial = READY) { text -> lastText = text; nm.notify(ID, notification(text)) }
        }
        if (promoteOnCreate) { promoteOnCreate = false; promoteLocation() } // started from the visible Activity
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

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_PROMOTE_LOCATION) promoteLocation()
        return START_STICKY
    }

    /** Spec §2.1: `CONNECTED_DEVICE | LOCATION` while the Activity is visible and fine location is granted; refusal is harmless. */
    private fun promoteLocation() {
        val fine = ContextCompat.checkSelfPermission(this, Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED
        val types = HubLocationPolicy.fgsTypes(fine, activityVisible = true)
        if (types and ServiceInfo.FOREGROUND_SERVICE_TYPE_LOCATION == 0) { _locationCapable.value = false; return }
        runCatching { ServiceCompat.startForeground(this, ID, notification(lastText), types) }
            .onSuccess { _locationCapable.value = true }
            .onFailure { Log.w("LiveFitHub", "location FGS refused", it); _locationCapable.value = false }
    }

    override fun onDestroy() { running = false; watcher?.cancel(); _locationCapable.value = false; super.onDestroy() }

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
        private const val READY = "LiveFit ready"
        /** True while the service is in the foreground (set once startForeground succeeded). */
        @Volatile private var running = false
        private const val ACTION_PROMOTE_LOCATION = "com.debasish.livefit.PROMOTE_LOCATION"
        @Volatile private var promoteOnCreate = false
        private val _locationCapable = MutableStateFlow(false)
        /** The hub currently holds the `location` FGS type, so the phone fallback can run. */
        val locationCapable: StateFlow<Boolean> = _locationCapable

        /** Called from the visible Activity (onResume, after a location grant). */
        fun promoteLocation(context: Context) {
            if (running) runCatching { context.startService(Intent(context, LiveFitHubService::class.java).setAction(ACTION_PROMOTE_LOCATION)) }
            else promoteOnCreate = true // ensureRunning() just started it; onCreate promotes
        }

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
