package com.debasish.livefit.services.workout

import com.debasish.livefit.model.EndReason
import com.debasish.livefit.model.Metrics
import com.debasish.livefit.model.Provenance
import com.debasish.livefit.model.SessionDelta
import com.debasish.livefit.model.SessionEvent
import com.debasish.livefit.model.SessionStatus
import com.debasish.livefit.model.SessionSummary
import com.debasish.livefit.model.WorkoutPhase
import com.debasish.livefit.model.WorkoutSnapshot
import com.debasish.livefit.model.WorkoutType
import java.util.TreeMap

/**
 * Derives a session's state purely from its deltas (events + cumulative samples). Used by the
 * phone hub (authoritative) and by the watch over its own buffer while offline. All times are
 * watch-clock timestamps from the deltas; no wall clock is read here.
 */
data class HrStats(val sum: Long = 0, val count: Int = 0, val max: Int? = null)

class SessionAssembler(val sessionId: String) {
    private val deltas = TreeMap<Long, SessionDelta>()
    /** Watch restart: HR stats of every folded sample, and the checkpoint delta's seq whose samples they already cover. */
    private var hrBaseline = HrStats()
    private var baselineSeq: Long? = null

    var finalSeq: Long? = null
        private set

    val deltaCount: Int get() = deltas.size
    val hasSamples: Boolean get() = deltas.values.any { it.samples.isNotEmpty() }

    val contiguousSeq: Long
        get() {
            var s = -1L
            while (deltas.containsKey(s + 1)) s++
            return s
        }

    val isComplete: Boolean get() = finalSeq?.let { contiguousSeq >= it } ?: false

    fun add(delta: SessionDelta): Boolean {
        require(delta.sessionId == sessionId) { "delta for ${delta.sessionId} given to $sessionId" }
        if (deltas.containsKey(delta.seq)) return false
        deltas[delta.seq] = delta
        if (delta.final) finalSeq = delta.seq
        return true
    }

    private fun events() = deltas.values.flatMap { it.events }.sortedBy { it.tMs }
    private fun samples() = deltas.values.flatMap { it.samples }.sortedBy { it.tMs }

    private fun latestTimestamp(): Long? =
        (events().map { it.tMs } + samples().map { it.tMs }).maxOrNull()

    fun phase(): WorkoutPhase = when (events().lastOrNull { it !is SessionEvent.TypeDetected }) {
        null -> WorkoutPhase.Starting
        is SessionEvent.Started, is SessionEvent.Resumed -> WorkoutPhase.Active
        is SessionEvent.Paused -> WorkoutPhase.Paused
        is SessionEvent.Stopped -> WorkoutPhase.Stopping
        is SessionEvent.TypeDetected -> WorkoutPhase.Active
    }

    /** Active time; a running segment counts up to [atMs], or to the latest recorded timestamp. */
    fun activeMs(atMs: Long? = null): Long {
        var total = 0L
        var runningSince: Long? = null
        for (e in events()) {
            when (e) {
                is SessionEvent.Started, is SessionEvent.Resumed -> if (runningSince == null) runningSince = e.tMs
                is SessionEvent.Paused -> runningSince?.let { total += e.tMs - it; runningSince = null }
                is SessionEvent.Stopped -> { runningSince?.let { total += e.tMs - it }; return total }
                is SessionEvent.TypeDetected -> Unit
            }
        }
        val since = runningSince ?: return total
        return total + ((atMs ?: latestTimestamp() ?: since) - since).coerceAtLeast(0)
    }

    /** The Started event's time (watch clock), if received. */
    fun startedAtMs(): Long? = events().filterIsInstance<SessionEvent.Started>().firstOrNull()?.tMs

    /** When the current phase began: the last Started / Paused / Resumed / Stopped event. */
    fun phaseSinceMs(): Long? = events().lastOrNull { it !is SessionEvent.TypeDetected }?.tMs

    private fun type(): WorkoutType =
        events().filterIsInstance<SessionEvent.Started>().firstOrNull()?.type ?: WorkoutType.Walk

    private fun detectedType(): WorkoutType? =
        events().filterIsInstance<SessionEvent.TypeDetected>().lastOrNull()?.type

    /** Restores a watch checkpoint (Task 10): [delta] carries the folded events + sample tail, [hr] all folded heart rates. */
    fun addCheckpoint(delta: SessionDelta, hr: HrStats) {
        if (!add(delta)) return
        hrBaseline = hr
        baselineSeq = delta.seq
    }

    private fun heartRates() = samples().mapNotNull { it.hr }

    fun hrHistory(limit: Int = 120): List<Int> = heartRates().takeLast(limit)

    private fun hrStats(): HrStats {
        val rest = deltas.values.filter { it.seq != baselineSeq }.flatMap { it.samples }.mapNotNull { it.hr }
        return HrStats(hrBaseline.sum + rest.sum(), hrBaseline.count + rest.size, listOfNotNull(hrBaseline.max, rest.maxOrNull()).maxOrNull())
    }

    /** Cumulative totals as a running maximum: a reading that dips (e.g. after the watch reattaches) never lowers them. */
    private data class Totals(val steps: Int, val km: Double, val kcal: Double)
    private fun totals(): Totals = samples().fold(Totals(0, 0.0, 0.0)) { t, s ->
        Totals(maxOf(t.steps, s.stepsTotal), maxOf(t.km, s.distanceKmTotal), maxOf(t.kcal, s.kcalTotal))
    }

    fun snapshot(): WorkoutSnapshot {
        val last = samples().lastOrNull()
        val hr = hrStats()
        val t = totals()
        return WorkoutSnapshot(
            sessionId = sessionId,
            phase = phase(),
            type = type(),
            detectedType = detectedType(),
            elapsedMs = activeMs(),
            metrics = Metrics(
                heartRate = samples().lastOrNull { it.hr != null }?.hr,
                calories = t.kcal.toInt(),
                steps = t.steps,
                distanceKm = t.km,
                speedKmh = last?.speedKmh ?: 0.0, // instantaneous, not cumulative
            ),
            avgHeartRate = if (hr.count == 0) null else (hr.sum / hr.count).toInt(),
            maxHeartRate = hr.max,
            latestSampleMs = last?.tMs,
        )
    }

    fun provenance(): Provenance {
        val all = deltas.values.map { it.provenance }.distinct()
        return if (all.size == 1 && all[0] is Provenance.Live) all[0] else Provenance.Fake
    }

    fun summary(status: SessionStatus, endReasonOverride: EndReason? = null): SessionSummary {
        val snap = snapshot()
        val ev = events()
        val stopped = ev.filterIsInstance<SessionEvent.Stopped>().lastOrNull()
        return SessionSummary(
            id = sessionId,
            type = snap.type,
            detectedType = snap.detectedType,
            startMs = ev.filterIsInstance<SessionEvent.Started>().firstOrNull()?.tMs ?: (samples().firstOrNull()?.tMs ?: 0),
            endMs = stopped?.tMs,
            activeMs = snap.elapsedMs,
            avgHr = snap.avgHeartRate,
            maxHr = snap.maxHeartRate,
            steps = snap.metrics.steps,
            distanceKm = snap.metrics.distanceKm,
            kcal = snap.metrics.calories,
            provenance = provenance(),
            status = status,
            endReason = endReasonOverride ?: stopped?.reason,
        )
    }

    /** All stored deltas in seq order (used to rebuild after a phone restart). */
    fun deltasInOrder(): List<SessionDelta> = deltas.values.toList()

    fun lastSample(): com.debasish.livefit.model.Sample? = samples().lastOrNull()
}
