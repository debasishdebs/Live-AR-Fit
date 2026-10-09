package com.debasish.livefit.sync

import com.debasish.livefit.model.FixSource
import com.debasish.livefit.model.LocationFix
import com.debasish.livefit.model.PageSet
import com.debasish.livefit.model.RouteFix
import com.debasish.livefit.model.RouteState
import com.debasish.livefit.model.RouteTrack
import com.debasish.livefit.model.SessionDelta
import com.debasish.livefit.model.WorkoutPhase
import com.debasish.livefit.model.WorkoutSnapshot
import com.debasish.livefit.model.WorkoutType
import com.debasish.livefit.model.toRouteFix
import com.debasish.livefit.services.Clock
import com.debasish.livefit.services.RouteStore
import com.debasish.livefit.services.SessionStore
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

private typealias FixKey = Pair<FixSource, Long>

private val RouteFix.key: FixKey get() = source to deviceTimeMs

/**
 * Phone hub `LocationSource` (spec §2.1/§2.2): merges the current session's watch fixes and phone fallback fixes into one
 * [RouteTrack], decides whether the phone GPS should run, and publishes what the glasses map draws.
 * - Rows are identified by device time; watch rows get a phone time only once calibrated, and every calibration change
 *   re-maps all of them and rebuilds the track (review #2). Uncalibrated rows are durable but not drawn, never live.
 * - Writes go through an unsaved queue retried on every tick and new fix; loading a session rebuilds rows missing from
 *   the store out of its stored deltas (review #1). The store's tombstones drop rows of discarded sessions.
 */
class RouteHub(
    private val scope: CoroutineScope,
    private val clock: Clock,
    private val clockSync: WatchClockSync,
    private val store: RouteStore,
    private val sessions: SessionStore,
    private val tickMs: Long = 1_000,
    private val log: (String) -> Unit = {},
) {
    private val _state = MutableStateFlow(RouteState())
    val state: StateFlow<RouteState> = _state
    private val _fallbackWanted = MutableStateFlow(false)
    val fallbackWanted: StateFlow<Boolean> = _fallbackWanted

    private val mutex = Mutex()
    private var sessionId: String? = null
    private var type = WorkoutType.Walk
    private var gpsActive = false
    /** Every accurate fix of the current session, by identity — the in-memory copy of its route_point rows. */
    private val fixes = LinkedHashMap<FixKey, RouteFix>()
    /** The watch offset [fixes] are mapped with; null = not mapped by this process yet. */
    private var appliedOffset: Long? = null
    private var track = RouteTrack()
    private val selector = LiveLocationSelector()
    /** Rows the store has not accepted yet, per session, oldest session first. */
    private val unsaved = LinkedHashMap<String, LinkedHashMap<FixKey, RouteFix>>()
    /** Sessions with watch rows that may lack the current offset's phone time (normalized after the inserts). */
    private val unnormalized = LinkedHashSet<String>()

    /** Every [tickMs]: apply a new calibration, retry unsaved rows, and re-publish so status and fallback timers advance. */
    fun start(): Job = scope.launch { while (isActive) { delay(tickMs); mutex.withLock { applyOffset(); flush(); publish() } } }

    suspend fun onWorkout(snapshot: WorkoutSnapshot) = mutex.withLock {
        val id = snapshot.sessionId?.takeIf { snapshot.phase != WorkoutPhase.Idle }
        val active = PageSet.mapEligible(snapshot)
        val now = clock.nowMs()
        if (id != sessionId) {
            sessionId = id
            load(id)
            selector.begin(active, now)
        } else if (active != gpsActive) {
            selector.setGpsWorkout(active, now)
        }
        type = snapshot.type
        gpsActive = active
        applyOffset()
        flush()
        publish()
    }

    suspend fun onWatchDelta(d: SessionDelta) {
        if (d.locations.isEmpty()) return
        mutex.withLock {
            applyOffset()
            val now = clock.nowMs()
            val offset = appliedOffset
            val rows = d.locations.mapNotNull { f -> f.toRouteFix(FixSource.Watch, offset?.let { f.fixTimeMs - it }, receivedAtMs = now) }
            if (d.sessionId == sessionId) {
                var lowered = false
                // Review r3: a replay keeps the identity's EARLIEST receipt (in memory and in what gets saved).
                val merged = rows.map { r ->
                    val old = fixes[r.key]
                    when {
                        old == null -> r.also { fixes[r.key] = it; it.point()?.let { p -> track.add(p, now) } }
                        r.receivedAtMs < old.receivedAtMs -> old.copy(receivedAtMs = r.receivedAtMs).also { fixes[r.key] = it; lowered = true }
                        else -> old
                    }
                }
                if (lowered) rebuild()
                for (f in d.locations) selector.onWatchFix(f, clockSync.toPhoneTime(f.fixTimeMs), now)
                enqueue(d.sessionId, merged)
            } else {
                enqueue(d.sessionId, rows) // another session's replay: history only
            }
            unnormalized += d.sessionId // its delta may have stored the same rows with phoneTimeMs = null
            flush()
            publish()
        }
    }

    suspend fun onPhoneFix(f: LocationFix) = mutex.withLock {
        val id = sessionId ?: return@withLock
        if (!gpsActive) return@withLock
        applyOffset()
        val now = clock.nowMs()
        selector.onPhoneFix(f, now)
        val r = f.toRouteFix(FixSource.Phone, phoneTimeMs = f.fixTimeMs, receivedAtMs = now)
        if (r != null && fixes.putIfAbsent(r.key, r) == null) {
            r.point()?.let { track.add(it, now) }
            enqueue(id, listOf(r))
        }
        flush()
        publish()
    }

    /** Stored rows, plus rows rebuilt from the session's stored deltas that never reached route_point (review #1). */
    private suspend fun load(id: String?) {
        fixes.clear()
        appliedOffset = null
        if (id != null) {
            guard("route load") { store.routeFixes(id) }?.forEach { fixes[it.key] = it }
            val fromDeltas = guard("delta load") { sessions.deltas(id) }.orEmpty()
                // Receipt = now: the true receipt is not in the delta, and rows normally exist already (stored with their delta).
                .flatMap { it.locations }.mapNotNull { it.toRouteFix(FixSource.Watch, phoneTimeMs = null, receivedAtMs = clock.nowMs()) }
            val missing = fromDeltas.filter { fixes.putIfAbsent(it.key, it) == null }
            if (missing.isNotEmpty()) {
                log("rebuilt ${missing.size} route rows from stored deltas")
                enqueue(id, missing)
            }
            unnormalized += id
        }
        rebuild()
    }

    /** A new (or first) calibration: re-map every watch row, rebuild the track chronologically (review #2). */
    private fun applyOffset() {
        val offset = clockSync.offsetMs.value ?: return
        if (offset == appliedOffset) return
        appliedOffset = offset
        val remap = { _: FixKey, r: RouteFix -> if (r.source == FixSource.Watch) r.copy(phoneTimeMs = r.deviceTimeMs - offset) else r }
        fixes.replaceAll(remap)
        unsaved.values.forEach { it.replaceAll(remap) }
        sessionId?.let { unnormalized += it }
        rebuild()
    }

    private fun rebuild() { track = RouteTrack.of(fixes.values.mapNotNull { it.point() }, clock.nowMs()) }

    private fun enqueue(id: String, rows: List<RouteFix>) {
        if (rows.isEmpty()) return
        val q = unsaved.getOrPut(id) { LinkedHashMap() }
        // Pending rows keep the earliest receipt of every attempt (review r3: a failed first write must not let a replay's later stamp win).
        for (r in rows) q.merge(r.key, r) { old, new -> new.copy(receivedAtMs = minOf(old.receivedAtMs, new.receivedAtMs)) }
    }

    /** Inserts first, then normalization (so rows a concurrent storeDelta wrote with null get their phone time). */
    private suspend fun flush() {
        for (id in unsaved.keys.toList()) {
            val rows = unsaved.getValue(id).values.toList()
            if (guard("route store") { store.storeRouteFixes(id, rows) } == null) return
            unsaved.remove(id)
        }
        val offset = appliedOffset ?: return
        for (id in unnormalized.toList()) {
            if (guard("route normalize") { store.normalizeWatchTimes(id, offset) } == null) return
            unnormalized.remove(id)
        }
    }

    private suspend fun <T> guard(what: String, block: suspend () -> T): T? = try {
        block()
    } catch (e: CancellationException) {
        throw e
    } catch (e: Exception) {
        log("$what failed: $e"); null
    }

    private fun publish() {
        val now = clock.nowMs()
        _fallbackWanted.value = selector.update(now)
        val drawn = track.drawn()
        val live = selector.current(now)?.let { it.copy(bearingDeg = it.bearingDeg ?: track.lastBearing()) }
        _state.value = RouteState(sessionId, type, drawn, drawn.firstOrNull(), live, selector.status(now))
    }
}
