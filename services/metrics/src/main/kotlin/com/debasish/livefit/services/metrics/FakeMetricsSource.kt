package com.debasish.livefit.services.metrics

import com.debasish.livefit.model.DeviceStatus
import com.debasish.livefit.model.LinkState
import com.debasish.livefit.model.Metrics
import com.debasish.livefit.model.WorkoutType
import com.debasish.livefit.services.MetricsSource
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.flow
import kotlin.math.roundToInt
import kotlin.math.sin
import kotlin.random.Random

/** Simulated Galaxy Watch: plausible HR warm-up curve plus type-specific cadence and speed. */
class FakeMetricsSource(private val tickMs: Long = 1_000) : MetricsSource {
    override val id = "fake-watch"
    override val status: StateFlow<DeviceStatus> =
        MutableStateFlow(DeviceStatus("Galaxy Watch6 Classic", LinkState.Connected, batteryPct = 64, detail = "Demo data"))

    override fun start(type: WorkoutType): Flow<Metrics> = flow {
        val profile = Profile.of(type)
        var t = 0
        var steps = 0.0
        var km = 0.0
        var kcal = 0.0
        while (true) {
            val warmUp = minOf(1.0, t / 90.0)
            val hr = (profile.restHr + (profile.peakHr - profile.restHr) * warmUp + 4 * sin(t / 9.0) + Random.nextDouble(-2.0, 2.0)).roundToInt()
            val speed = (profile.speedKmh * (0.85 + 0.15 * warmUp) + Random.nextDouble(-0.3, 0.3)).coerceAtLeast(0.0)
            steps += profile.cadencePerMin / 60.0 * (tickMs / 1000.0)
            km += speed * tickMs / 3_600_000.0
            kcal += hr * profile.kcalPerBeat * (tickMs / 60_000.0)
            emit(Metrics(heartRate = hr, calories = kcal.toInt(), steps = steps.toInt(), distanceKm = km, speedKmh = speed))
            t++
            delay(tickMs)
        }
    }

    override suspend fun stop() = Unit

    private data class Profile(val restHr: Int, val peakHr: Int, val cadencePerMin: Int, val speedKmh: Double, val kcalPerBeat: Double) {
        companion object {
            fun of(type: WorkoutType) = when (type) {
                WorkoutType.Run -> Profile(95, 158, 165, 10.2, 0.11)
                WorkoutType.Cycle -> Profile(90, 145, 0, 21.0, 0.10)
                WorkoutType.Walk, WorkoutType.Auto -> Profile(85, 118, 112, 5.4, 0.08)
            }
        }
    }
}
