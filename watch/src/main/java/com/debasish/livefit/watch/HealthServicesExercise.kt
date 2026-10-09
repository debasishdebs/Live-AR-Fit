package com.debasish.livefit.watch

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.os.SystemClock
import androidx.core.content.ContextCompat
import androidx.health.services.client.ExerciseUpdateCallback
import androidx.health.services.client.HealthServices
import androidx.health.services.client.data.Availability
import androidx.health.services.client.data.BatchingMode
import androidx.health.services.client.data.DataType
import androidx.health.services.client.data.ExerciseConfig
import androidx.health.services.client.data.ExerciseEndReason
import androidx.health.services.client.data.ExerciseLapSummary
import androidx.health.services.client.data.ExerciseState
import androidx.health.services.client.data.ExerciseTrackedStatus
import androidx.health.services.client.data.ExerciseType
import androidx.health.services.client.data.ExerciseUpdate
import androidx.health.services.client.data.LocationAccuracy
import androidx.health.services.client.data.WarmUpConfig
import com.debasish.livefit.model.EndReason
import com.debasish.livefit.model.LocationFix
import com.debasish.livefit.model.Sample
import com.debasish.livefit.model.WorkoutType
import com.debasish.livefit.sync.BackendUpdate
import com.debasish.livefit.sync.ExerciseBackend
import com.debasish.livefit.sync.batchSamples
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.guava.await
import java.time.Instant
import android.util.Log

/** Health Services implementation of [ExerciseBackend] (spec §5.1). */
class HealthServicesExercise(context: Context) : ExerciseBackend {
    private val app = context.applicationContext
    private val client = HealthServices.getClient(app).exerciseClient
    private val queue = Channel<BackendUpdate>(Channel.UNLIMITED) // never drops an Ended
    override val updates = queue.receiveAsFlow()

    // Cumulative totals arrive in separate updates from HR; keep the latest of each.
    private var steps = 0; private var km = 0.0; private var kcal = 0.0; private var speed: Double? = null
    private var sumSteps = false; private var sumDistance = false
    /** Last Active/Paused state reported; null = report the next one (after start/reattach). */
    private var lastPaused: Boolean? = null
    /** Updates of an exercise that started before our latest start() are a previous one's stragglers. */
    private var startedAfter: Instant? = null

    private val required = listOf(Manifest.permission.BODY_SENSORS, "android.permission.health.READ_HEART_RATE", Manifest.permission.ACTIVITY_RECOGNITION)

    override fun missingPermissions(): List<String> =
        required.filter { ContextCompat.checkSelfPermission(app, it) != PackageManager.PERMISSION_GRANTED }

    override fun locationGranted(): Boolean =
        ContextCompat.checkSelfPermission(app, Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED

    override suspend fun otherAppTracking(): String? {
        val info = client.getCurrentExerciseInfoAsync().await()
        return if (info.exerciseTrackedStatus == ExerciseTrackedStatus.OTHER_APP_IN_PROGRESS) info.exerciseType.name else null
    }

    private fun hsType(t: WorkoutType) = when (t) {
        WorkoutType.Run -> ExerciseType.RUNNING
        WorkoutType.Cycle -> ExerciseType.BIKING
        WorkoutType.Walk, WorkoutType.Auto -> ExerciseType.WALKING
    }

    override suspend fun unsupportedReason(type: WorkoutType): String? = runCatching {
        ExerciseSupport.unsupportedReason(type, hsType(type), client.getCapabilitiesAsync().await().supportedExerciseTypes)
    }.getOrNull() // can't tell: let start() try and report its own failure

    override suspend fun start(type: WorkoutType, useGps: Boolean): Boolean = runCatching {
        steps = 0; km = 0.0; kcal = 0.0; speed = null; lastPaused = null
        startedAfter = Instant.now().minusSeconds(1)
        val exerciseType = hsType(type)
        val caps = client.getCapabilitiesAsync().await()
        val supported = caps.getExerciseTypeCapabilities(exerciseType).supportedDataTypes
        // useGps already includes the permission check (WatchExerciseController): LOCATION only for GPS workouts.
        val wanted: Set<DataType<*, *>> = setOf<DataType<*, *>>(DataType.HEART_RATE_BPM, DataType.STEPS_TOTAL, DataType.DISTANCE_TOTAL, DataType.CALORIES_TOTAL, DataType.SPEED) +
            listOfNotNull<DataType<*, *>>(DataType.LOCATION.takeIf { useGps })
        // Some watches only offer the per-interval STEPS/DISTANCE types for an exercise: fall back to summing them.
        val fallback = listOfNotNull(
            DataType.STEPS.takeIf { DataType.STEPS_TOTAL !in supported },
            DataType.DISTANCE.takeIf { DataType.DISTANCE_TOTAL !in supported },
        )
        val types = (wanted.filter { it in supported } + fallback.filter { it in supported }).toSet()
        sumSteps = DataType.STEPS in types; sumDistance = DataType.DISTANCE in types
        Log.d(TAG, "exercise $exerciseType supported=${supported.map { it.name }} requested=${types.map { it.name }}")
        Log.i(TAG, "gps=$useGps location supported=${DataType.LOCATION in supported}")
        // B1: by default Health Services holds heart rate back in large batches while the screen is off / ambient
        // (step totals still arrive), so the phone and HUD showed a stale heart rate until the watch woke.
        val batching = batchingOverrides(caps.supportedBatchingModeOverrides)
        Log.i(TAG, "batching overrides supported=${caps.supportedBatchingModeOverrides} using=$batching")
        client.setUpdateCallback(callback)
        runCatching { client.prepareExerciseAsync(WarmUpConfig(exerciseType, setOf(DataType.HEART_RATE_BPM))).await() }
        fun config(overrides: Set<BatchingMode>) =
            ExerciseConfig.builder(exerciseType).setDataTypes(types).setBatchingModeOverrides(overrides).setIsAutoPauseAndResumeEnabled(false).setIsGpsEnabled(useGps && locationGranted()).build()
        try {
            client.startExerciseAsync(config(batching)).await()
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            // A future/incompatible advertised override must not stop the workout from recording: retry with the safe one only.
            Log.w(TAG, "start with batching overrides $batching failed, retrying with heart-rate only", e)
            val safe = setOf(BatchingMode.HEART_RATE_5_SECONDS).filter { it in caps.supportedBatchingModeOverrides }.toSet()
            client.startExerciseAsync(config(safe)).await()
        }
        true
    }.getOrDefault(false)

    override suspend fun pause() = runCatching { client.pauseExerciseAsync().await() }.isSuccess
    override suspend fun resume() = runCatching { client.resumeExerciseAsync().await() }.isSuccess
    override suspend fun end() = runCatching { client.endExerciseAsync().await() }.isSuccess

    /** New process, exercise maybe still running: Health Services keeps it, but our callback died with the old process. */
    override suspend fun reattach(last: Sample?): Boolean? = runCatching {
        val info = client.getCurrentExerciseInfoAsync().await()
        if (info.exerciseTrackedStatus != ExerciseTrackedStatus.OWNED_EXERCISE_IN_PROGRESS) return@runCatching false
        // Totals only arrive when they change; seed them so the next reading doesn't report 0 steps.
        steps = last?.stepsTotal ?: 0; km = last?.distanceKmTotal ?: 0.0; kcal = last?.kcalTotal ?: 0.0; speed = last?.speedKmh
        lastPaused = null; startedAfter = null // the first update after registering reports the actual phase
        client.setUpdateCallback(callback)
        true
    }.getOrNull()

    /**
     * Spec §2.1: Health Services may batch location while the screen is off; request every override this watch supports
     * (HEART_RATE_5_SECONDS today, and any location override a newer Health Services adds). The list is logged at start.
     */
    private fun batchingOverrides(supported: Set<BatchingMode>): Set<BatchingMode> = supported

    private val callback = object : ExerciseUpdateCallback {
        override fun onExerciseUpdateReceived(update: ExerciseUpdate) {
            if (startedAfter?.let { update.startTime?.isBefore(it) } == true) return
            val m = update.latestMetrics
            val now = System.currentTimeMillis()
            m.getData(DataType.STEPS_TOTAL)?.total?.let { steps = it.toInt() }
            if (sumSteps) steps += m.getData(DataType.STEPS).sumOf { it.value }.toInt()
            if (sumDistance) km += m.getData(DataType.DISTANCE).sumOf { it.value } / 1000.0
            m.getData(DataType.DISTANCE_TOTAL)?.total?.let { km = it / 1000.0 }
            m.getData(DataType.CALORIES_TOTAL)?.total?.let { kcal = it }
            m.getData(DataType.SPEED).lastOrNull()?.value?.let { speed = it * 3.6 }
            // Every point of a (screen-off) batch, at its own time: data point times are relative to boot.
            val boot = Instant.ofEpochMilli(now - SystemClock.elapsedRealtime())
            val hr = m.getData(DataType.HEART_RATE_BPM).map { it.getTimeInstant(boot).toEpochMilli() to it.value.toInt() }
            batchSamples(hr, now, steps, km, kcal, speed).takeIf { it.isNotEmpty() }?.let { queue.trySend(BackendUpdate.Reading(it)) } // one delta per batch
            // Location points, each at its own time (screen-off batches arrive late, spec §2.1).
            val fixes = m.getData(DataType.LOCATION).map { dp ->
                val v = dp.value
                // No accuracy = unknown (null, review #8): kept in the delta and route.bin, never routed or live (FixQuality).
                val acc = (dp.accuracy as? LocationAccuracy)?.horizontalPositionErrorMeters?.toFloat()
                    .also { if (it == null) Log.w(TAG, "location point without accuracy (unknown)") }
                LocationFix(v.latitude, v.longitude, acc, v.bearing.takeIf { it.isFinite() && it >= 0 }?.toFloat(), dp.getTimeInstant(boot).toEpochMilli().coerceAtMost(now))
            }
            if (fixes.isNotEmpty()) queue.trySend(BackendUpdate.Locations(fixes))
            val st = update.exerciseStateInfo
            val paused = when {
                st.state == ExerciseState.ACTIVE -> false
                st.state.isPaused -> true
                else -> null // starting, pausing, resuming, ending: wait for the settled state
            }
            if (paused != null && paused != lastPaused) {
                lastPaused = paused
                // When Health Services made the update, not when it reached us: a PAUSED update queued before our own
                // resume is then older than the Resumed event and ignored, instead of recording a spurious pause.
                val at = update.getUpdateDurationFromBoot()?.let { boot.plus(it).toEpochMilli() }?.coerceAtMost(now) ?: now
                val active = update.activeDurationCheckpoint?.let { cp ->
                    cp.activeDuration.toMillis() + if (paused) 0 else (at - cp.time.toEpochMilli()).coerceAtLeast(0)
                }
                queue.trySend(BackendUpdate.Phase(paused, at, active))
            }
            if (st.state.isEnded) {
                val by = when (st.endReason) {
                    ExerciseEndReason.USER_END -> EndReason.User
                    ExerciseEndReason.AUTO_END_SUPERSEDED -> EndReason.OtherApp
                    else -> EndReason.System
                }
                queue.trySend(BackendUpdate.Ended(by))
            }
        }
        override fun onLapSummaryReceived(lapSummary: ExerciseLapSummary) {}
        override fun onRegistered() {}
        override fun onRegistrationFailed(throwable: Throwable) {}
        override fun onAvailabilityChanged(dataType: DataType<*, *>, availability: Availability) {}
    }

    private companion object { const val TAG = "LiveFitExercise" }
}
