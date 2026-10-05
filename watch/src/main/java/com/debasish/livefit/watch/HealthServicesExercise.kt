package com.debasish.livefit.watch

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import androidx.core.content.ContextCompat
import androidx.health.services.client.ExerciseUpdateCallback
import androidx.health.services.client.HealthServices
import androidx.health.services.client.data.Availability
import androidx.health.services.client.data.DataType
import androidx.health.services.client.data.ExerciseConfig
import androidx.health.services.client.data.ExerciseEndReason
import androidx.health.services.client.data.ExerciseLapSummary
import androidx.health.services.client.data.ExerciseTrackedStatus
import androidx.health.services.client.data.ExerciseType
import androidx.health.services.client.data.ExerciseUpdate
import androidx.health.services.client.data.WarmUpConfig
import com.debasish.livefit.model.EndReason
import com.debasish.livefit.model.Sample
import com.debasish.livefit.model.WorkoutType
import com.debasish.livefit.sync.BackendUpdate
import com.debasish.livefit.sync.ExerciseBackend
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.guava.await

/** Health Services implementation of [ExerciseBackend] (spec §5.1). */
class HealthServicesExercise(context: Context) : ExerciseBackend {
    private val app = context.applicationContext
    private val client = HealthServices.getClient(app).exerciseClient
    override val updates = MutableSharedFlow<BackendUpdate>(extraBufferCapacity = 64)

    // Cumulative totals arrive in separate updates from HR; keep the latest of each.
    private var steps = 0; private var km = 0.0; private var kcal = 0.0; private var speed: Double? = null

    private val required = listOf(Manifest.permission.BODY_SENSORS, "android.permission.health.READ_HEART_RATE", Manifest.permission.ACTIVITY_RECOGNITION)

    override fun missingPermissions(): List<String> =
        required.filter { ContextCompat.checkSelfPermission(app, it) != PackageManager.PERMISSION_GRANTED }

    override suspend fun otherAppTracking(): String? {
        val info = client.getCurrentExerciseInfoAsync().await()
        return if (info.exerciseTrackedStatus == ExerciseTrackedStatus.OTHER_APP_IN_PROGRESS) info.exerciseType.name else null
    }

    private fun hsType(t: WorkoutType) = when (t) {
        WorkoutType.Run -> ExerciseType.RUNNING
        WorkoutType.Cycle -> ExerciseType.BIKING
        WorkoutType.Walk, WorkoutType.Auto -> ExerciseType.WALKING
    }

    override suspend fun start(type: WorkoutType, useGps: Boolean): Boolean = runCatching {
        steps = 0; km = 0.0; kcal = 0.0; speed = null
        val exerciseType = hsType(type)
        val supported = client.getCapabilitiesAsync().await().getExerciseTypeCapabilities(exerciseType).supportedDataTypes
        val wanted = setOf(DataType.HEART_RATE_BPM, DataType.STEPS_TOTAL, DataType.DISTANCE_TOTAL, DataType.CALORIES_TOTAL, DataType.SPEED)
        val types = wanted.filter { it in supported }.toSet()
        client.setUpdateCallback(callback)
        runCatching { client.prepareExerciseAsync(WarmUpConfig(exerciseType, setOf(DataType.HEART_RATE_BPM))).await() }
        client.startExerciseAsync(
            ExerciseConfig.builder(exerciseType).setDataTypes(types).setIsAutoPauseAndResumeEnabled(false).setIsGpsEnabled(useGps && ContextCompat.checkSelfPermission(app, Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED).build(),
        ).await()
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
        client.setUpdateCallback(callback)
        true
    }.getOrNull()

    private val callback = object : ExerciseUpdateCallback {
        override fun onExerciseUpdateReceived(update: ExerciseUpdate) {
            val m = update.latestMetrics
            m.getData(DataType.STEPS_TOTAL)?.total?.let { steps = it.toInt() }
            m.getData(DataType.DISTANCE_TOTAL)?.total?.let { km = it / 1000.0 }
            m.getData(DataType.CALORIES_TOTAL)?.total?.let { kcal = it }
            m.getData(DataType.SPEED).lastOrNull()?.value?.let { speed = it * 3.6 }
            val hr = m.getData(DataType.HEART_RATE_BPM).lastOrNull()?.value?.toInt()
            updates.tryEmit(BackendUpdate.Reading(Sample(System.currentTimeMillis(), hr, steps, km, kcal, speed)))
            val st = update.exerciseStateInfo
            if (st.state.isEnded) {
                val by = when (st.endReason) {
                    ExerciseEndReason.USER_END -> EndReason.User
                    ExerciseEndReason.AUTO_END_SUPERSEDED -> EndReason.OtherApp
                    else -> EndReason.System
                }
                updates.tryEmit(BackendUpdate.Ended(by))
            }
        }
        override fun onLapSummaryReceived(lapSummary: ExerciseLapSummary) {}
        override fun onRegistered() {}
        override fun onRegistrationFailed(throwable: Throwable) {}
        override fun onAvailabilityChanged(dataType: DataType<*, *>, availability: Availability) {}
    }
}
