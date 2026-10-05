package com.debasish.livefit.services.workout

import com.debasish.livefit.model.WorkoutPhase
import com.debasish.livefit.model.WorkoutSnapshot
import com.debasish.livefit.model.WorkoutType
import com.debasish.livefit.services.MetricsSource
import com.debasish.livefit.services.WorkoutService
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/**
 * Real workout state machine; only the [source] decides whether data is fake or live.
 * Elapsed time excludes paused periods. Auto mode infers the type from speed.
 */
class DefaultWorkoutService(
    private val scope: CoroutineScope,
    private val source: MetricsSource,
    private val now: () -> Long = System::currentTimeMillis,
    private val tickMs: Long = 1_000,
) : WorkoutService {

    private val _snapshot = MutableStateFlow(WorkoutSnapshot())
    override val snapshot: StateFlow<WorkoutSnapshot> = _snapshot

    private var metricsJob: Job? = null
    private var clockJob: Job? = null
    private var activeSince = 0L
    private var banked = 0L
    private var hrSum = 0L
    private var hrCount = 0

    override fun start(type: WorkoutType) {
        val phase = _snapshot.value.phase
        if (phase != WorkoutPhase.Idle && phase != WorkoutPhase.Summary) return
        banked = 0; hrSum = 0; hrCount = 0
        _snapshot.value = WorkoutSnapshot(phase = WorkoutPhase.Starting, type = type)
        metricsJob = scope.launch {
            source.start(type).collect { m ->
                if (_snapshot.value.phase == WorkoutPhase.Starting) goActive()
                if (_snapshot.value.phase != WorkoutPhase.Active) return@collect
                m.heartRate?.let { hrSum += it; hrCount++ }
                _snapshot.update {
                    it.copy(
                        metrics = m,
                        detectedType = if (type == WorkoutType.Auto) detect(m.speedKmh, m.steps) else null,
                        avgHeartRate = if (hrCount > 0) (hrSum / hrCount).toInt() else null,
                        maxHeartRate = maxOf(it.maxHeartRate ?: 0, m.heartRate ?: 0).takeIf { v -> v > 0 },
                    )
                }
            }
        }
    }

    private fun goActive() {
        activeSince = now()
        _snapshot.update { it.copy(phase = WorkoutPhase.Active) }
        clockJob?.cancel()
        clockJob = scope.launch {
            while (true) {
                if (_snapshot.value.phase == WorkoutPhase.Active) {
                    _snapshot.update { it.copy(elapsedMs = banked + now() - activeSince) }
                }
                delay(tickMs)
            }
        }
    }

    override fun pause() {
        if (_snapshot.value.phase != WorkoutPhase.Active) return
        banked += now() - activeSince
        _snapshot.update { it.copy(phase = WorkoutPhase.Paused, elapsedMs = banked) }
    }

    override fun resume() {
        if (_snapshot.value.phase != WorkoutPhase.Paused) return
        activeSince = now()
        _snapshot.update { it.copy(phase = WorkoutPhase.Active) }
    }

    override fun stop() {
        val s = _snapshot.value
        if (s.phase != WorkoutPhase.Active && s.phase != WorkoutPhase.Paused && s.phase != WorkoutPhase.Starting) return
        if (s.phase == WorkoutPhase.Active) banked += now() - activeSince
        _snapshot.update { it.copy(phase = WorkoutPhase.Stopping, elapsedMs = banked) }
        scope.launch {
            metricsJob?.cancel()
            clockJob?.cancel()
            source.stop()
            _snapshot.update { it.copy(phase = WorkoutPhase.Summary) }
        }
    }

    override fun dismissSummary() {
        if (_snapshot.value.phase == WorkoutPhase.Summary) _snapshot.value = WorkoutSnapshot()
    }

    private fun detect(speedKmh: Double, steps: Int): WorkoutType = when {
        speedKmh >= 15 && steps == 0 -> WorkoutType.Cycle
        speedKmh >= 7.5 -> WorkoutType.Run
        else -> WorkoutType.Walk
    }
}
