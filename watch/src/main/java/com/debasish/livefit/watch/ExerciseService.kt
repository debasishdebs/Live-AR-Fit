package com.debasish.livefit.watch

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.IBinder
import android.util.Log
import androidx.core.app.ServiceCompat
import androidx.health.services.client.ExerciseUpdateCallback
import androidx.health.services.client.HealthServices
import androidx.health.services.client.data.Availability
import androidx.health.services.client.data.DataType
import androidx.health.services.client.data.ExerciseConfig
import androidx.health.services.client.data.ExerciseLapSummary
import androidx.health.services.client.data.ExerciseTrackedStatus
import androidx.health.services.client.data.ExerciseType
import androidx.health.services.client.data.ExerciseUpdate
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.guava.await
import kotlinx.coroutines.launch

/**
 * Owns the Health Services exercise and streams live metrics to the phone.
 * Spike goals: start from a remote trigger, detect/override another app's exercise.
 */
class ExerciseService : Service() {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    private val exerciseClient by lazy { HealthServices.getClient(this).exerciseClient }
    private var callbackSet = false

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_START -> {
                goForeground()
                scope.launch { start(force = intent.getBooleanExtra(EXTRA_FORCE, false)) }
            }
            ACTION_STOP -> scope.launch { stop() }
        }
        return START_NOT_STICKY
    }

    private fun goForeground() {
        val nm = getSystemService(NotificationManager::class.java)
        nm.createNotificationChannel(NotificationChannel(CHANNEL, "Workout", NotificationManager.IMPORTANCE_LOW))
        val notification = Notification.Builder(this, CHANNEL)
            .setContentTitle("LiveFit workout")
            .setSmallIcon(android.R.drawable.ic_media_play)
            .setOngoing(true)
            .build()
        ServiceCompat.startForeground(this, 1, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_HEALTH)
    }

    private suspend fun start(force: Boolean) {
        val info = exerciseClient.getCurrentExerciseInfoAsync().await()
        val tracked = when (info.exerciseTrackedStatus) {
            ExerciseTrackedStatus.OTHER_APP_IN_PROGRESS -> "OTHER_APP_IN_PROGRESS"
            ExerciseTrackedStatus.OWNED_EXERCISE_IN_PROGRESS -> "OWNED_EXERCISE_IN_PROGRESS"
            ExerciseTrackedStatus.NO_EXERCISE_IN_PROGRESS -> "NO_EXERCISE_IN_PROGRESS"
            else -> "UNKNOWN(${info.exerciseTrackedStatus})"
        }
        Log.i(TAG, "current exercise status=$tracked type=${info.exerciseType}")
        status("""{"phase":"check","tracked":"$tracked","force":$force}""")

        if (tracked == "OTHER_APP_IN_PROGRESS" && !force) {
            status("""{"phase":"blocked","reason":"other app tracking"}""")
            stopSelf()
            return
        }

        val supported = exerciseClient.getCapabilitiesAsync().await()
            .getExerciseTypeCapabilities(ExerciseType.WALKING).supportedDataTypes
        val wanted = setOf(
            DataType.HEART_RATE_BPM, DataType.STEPS_TOTAL, DataType.DISTANCE_TOTAL,
            DataType.CALORIES_TOTAL, DataType.SPEED,
        )
        val dataTypes = wanted.filter { it in supported }.toSet()
        Log.i(TAG, "supported=${supported.map { it.name }} using=${dataTypes.map { it.name }}")

        if (!callbackSet) {
            exerciseClient.setUpdateCallback(callback)
            callbackSet = true
        }
        val config = ExerciseConfig.builder(ExerciseType.WALKING)
            .setDataTypes(dataTypes)
            .setIsAutoPauseAndResumeEnabled(false)
            .setIsGpsEnabled(false)
            .build()
        runCatching { exerciseClient.startExerciseAsync(config).await() }
            .onSuccess { status("""{"phase":"started","types":"${dataTypes.joinToString { it.name }}"}""") }
            .onFailure {
                Log.e(TAG, "startExercise failed", it)
                status("""{"phase":"error","msg":"${it.message?.replace("\"", "'")}"}""")
            }
    }

    private suspend fun stop() {
        runCatching { exerciseClient.endExerciseAsync().await() }
        status("""{"phase":"stopped"}""")
        stopSelf()
    }

    private val callback = object : ExerciseUpdateCallback {
        override fun onExerciseUpdateReceived(update: ExerciseUpdate) {
            val m = update.latestMetrics
            val hr = m.getData(DataType.HEART_RATE_BPM).lastOrNull()?.value
            val speed = m.getData(DataType.SPEED).lastOrNull()?.value
            val steps = m.getData(DataType.STEPS_TOTAL)?.total
            val dist = m.getData(DataType.DISTANCE_TOTAL)?.total
            val cal = m.getData(DataType.CALORIES_TOTAL)?.total
            val json = """{"t":${System.currentTimeMillis()},"state":"${update.exerciseStateInfo.state}",""" +
                """"hr":$hr,"steps":$steps,"dist":$dist,"cal":$cal,"speed":$speed}"""
            Log.i(TAG, "metrics $json")
            scope.launch { PhoneLink.send(this@ExerciseService, PhoneLink.PATH_METRICS, json) }
        }

        override fun onLapSummaryReceived(lapSummary: ExerciseLapSummary) {}
        override fun onRegistered() { Log.i(TAG, "update callback registered") }
        override fun onRegistrationFailed(throwable: Throwable) { Log.e(TAG, "callback registration failed", throwable) }
        override fun onAvailabilityChanged(dataType: DataType<*, *>, availability: Availability) {
            Log.i(TAG, "availability ${dataType.name} -> $availability")
        }
    }

    private fun status(json: String) {
        Log.i(TAG, "status $json")
        scope.launch { PhoneLink.send(this@ExerciseService, PhoneLink.PATH_STATUS, json) }
    }

    override fun onDestroy() {
        scope.cancel()
        super.onDestroy()
    }

    companion object {
        const val TAG = PhoneLink.TAG
        const val CHANNEL = "workout"
        const val ACTION_START = "livefit.START"
        const val ACTION_STOP = "livefit.STOP"
        const val EXTRA_FORCE = "force"

        fun intent(context: Context, action: String, force: Boolean = false) =
            Intent(context, ExerciseService::class.java).setAction(action).putExtra(EXTRA_FORCE, force)
    }
}
