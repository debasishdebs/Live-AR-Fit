package com.debasish.livefit.sync

import com.debasish.livefit.model.EndReason
import com.debasish.livefit.model.ExerciseError
import com.debasish.livefit.model.ExerciseOp
import com.debasish.livefit.model.ExerciseRequest
import com.debasish.livefit.model.ExerciseResult
import com.debasish.livefit.model.ExerciseState
import com.debasish.livefit.model.ExerciseStateReport
import com.debasish.livefit.model.Sample
import com.debasish.livefit.model.SessionEvent
import com.debasish.livefit.model.WorkoutPhase
import com.debasish.livefit.model.WorkoutType
import com.debasish.livefit.services.Clock
import com.debasish.livefit.services.workout.AutoTypeDetector
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlin.coroutines.cancellation.CancellationException
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withTimeoutOrNull
import java.util.UUID

interface ExerciseBackend {
    fun missingPermissions(): List<String>
    /** Exercise type name if another app currently owns an exercise, else null. */
    suspend fun otherAppTracking(): String?
    suspend fun start(type: WorkoutType, useGps: Boolean): Boolean
    /** Each returns false when Health Services refused or failed — never swallowed. */
    suspend fun pause(): Boolean
    suspend fun resume(): Boolean
    suspend fun end(): Boolean
    /**
     * After process death: re-registers for updates if our exercise still runs (true), reports it gone (false),
     * or null when Health Services couldn't be asked. [last] seeds the cumulative totals.
     */
    suspend fun reattach(last: Sample?): Boolean?
    val updates: Flow<BackendUpdate>
}

sealed interface BackendUpdate {
    data class Reading(val sample: Sample) : BackendUpdate
    data class Ended(val by: EndReason) : BackendUpdate
    /**
     * Authoritative Active/Paused state, reported when it changes and first after start/reattach.
     * [activeMs] is the backend's own active duration at [atMs], if known.
     */
    data class Phase(val paused: Boolean, val atMs: Long, val activeMs: Long? = null) : BackendUpdate
}

/** Watch executor for hub requests; scoped to one session at a time (spec §4.8). */
class WatchExerciseController(
    scope: CoroutineScope,
    private val backend: ExerciseBackend,
    private val recorder: WatchSessionRecorder,
    private val clock: Clock,
    private val sendResult: suspend (ExerciseResult) -> Unit,
    private val sendState: suspend (ExerciseStateReport) -> Unit,
    /** The phone's GPS choice per type, persisted; reused for offline starts (the phone setting isn't reachable then). */
    private val gpsPrefs: GpsPreferences,
    private val newId: () -> String = { UUID.randomUUID().toString() },
    private val endTimeoutMs: Long = 5_000,
) {
    private val recent = LinkedHashMap<String, ExerciseResult>()
    private val _lastError = MutableStateFlow<ExerciseError?>(null)
    val lastError: StateFlow<ExerciseError?> = _lastError
    private val detector = AutoTypeDetector()
    /** Set while a user stop waits for Health Services' final update. */
    private var stopping: CompletableDeferred<Unit>? = null
    private var stopAtMs = 0L
    /** The in-flight stop; a concurrent stop awaits it and returns the same outcome. */
    private var stopOutcome: CompletableDeferred<Boolean>? = null
    /**
     * Hub and offline starts run one at a time: Health Services ends our own exercise when a second one starts,
     * so a start that waited here sees the first one's session and is refused without reaching the backend.
     */
    private val startLock = Mutex()

    val activeSessionId: String? get() = recorder.sessionId?.takeIf { !recorder.isFinalized }

    init {
        scope.launch {
            backend.updates.collect { u ->
                when (u) {
                    is BackendUpdate.Reading -> onReading(u.sample)
                    is BackendUpdate.Phase -> onBackendPhase(u)
                    is BackendUpdate.Ended -> {
                        stopping?.let { it.complete(Unit); return@collect } // our own stop: localStop writes the final delta
                        val id = activeSessionId ?: return@collect
                        recorder.event(SessionEvent.Stopped(clock.nowMs(), u.by), final = true)
                        runCatching { sendState(ExerciseStateReport(sessionId = id, state = ExerciseState.Ended, endedBy = u.by)) }
                    }
                }
            }
        }
    }

    private suspend fun onReading(s: Sample) {
        if (activeSessionId == null) return
        // Readings that arrive while stopping belong to the session but must not extend its active time.
        val sample = if (stopping != null) s.copy(tMs = minOf(s.tMs, stopAtMs)) else s
        recorder.sample(sample)
        if (recorder.type == WorkoutType.Auto) detector.onSample(sample)?.let { recorder.event(SessionEvent.TypeDetected(sample.tMs, it)) }
    }

    /**
     * "Actual state wins" (spec §4.8): Health Services paused or resumed without the event being recorded — e.g. the
     * process died right after the call. Recorded at the time implied by the backend's active duration.
     */
    private fun onBackendPhase(u: BackendUpdate.Phase) {
        if (activeSessionId == null || stopping != null) return
        val a = recorder.assembler ?: return
        val since = a.phaseSinceMs() ?: return
        if (u.atMs < since) return // older than our own last pause/resume
        val ours = a.activeMs(atMs = u.atMs)
        when (a.phase()) {
            WorkoutPhase.Active -> if (u.paused) {
                val overcounted = u.activeMs?.let { ours - it }?.coerceAtLeast(0) ?: 0
                recorder.event(SessionEvent.Paused((u.atMs - overcounted).coerceAtLeast(since)))
            }
            WorkoutPhase.Paused -> if (!u.paused) {
                val missed = u.activeMs?.let { it - ours }?.coerceAtLeast(0) ?: 0
                recorder.event(SessionEvent.Resumed((u.atMs - missed).coerceAtLeast(since)))
            }
            else -> Unit
        }
    }

    suspend fun handle(req: ExerciseRequest) {
        recent[req.requestId]?.let { runCatching { sendResult(it) }; return }
        val result = execute(req)
        recent[req.requestId] = result
        if (recent.size > 20) recent.remove(recent.keys.first())
        _lastError.value = result.error
        runCatching { sendResult(result) } // phone unreachable: it times out and reconciles (spec §4.8)
    }

    private suspend fun execute(req: ExerciseRequest): ExerciseResult {
        val active = activeSessionId
        fun result(ok: Boolean, error: ExerciseError? = null, state: ExerciseState) =
            ExerciseResult(requestId = req.requestId, sessionId = req.sessionId, ok = ok, error = error, state = state, activeSessionId = activeSessionId)

        return when (val op = req.op) {
            is ExerciseOp.Start -> startLock.withLock {
                val current = activeSessionId // re-read: another start may have finished while we waited
                when {
                    current == req.sessionId -> result(true, state = ExerciseState.Active)
                    current != null -> result(false, ExerciseError.WrongSession(current), ExerciseState.Active)
                    // An ended session is still replaying to the phone; starting now would make the phone adopt the new one first.
                    recorder.holdsData -> result(false, ExerciseError.Internal(SYNCING_PREVIOUS), ExerciseState.Idle)
                    else -> {
                        gpsPrefs.set(op.type, op.gps)
                        when (val e = startExercise({ req.sessionId }, op.type, op.force, op.gps)) {
                            null -> result(true, state = ExerciseState.Active)
                            else -> result(false, e, ExerciseState.Idle)
                        }
                    }
                }
            }
            else -> if (active != req.sessionId) result(false, ExerciseError.WrongSession(active), if (active == null) ExerciseState.Idle else ExerciseState.Active)
            else when (op) {
                ExerciseOp.Pause -> if (localPause()) result(true, state = ExerciseState.Paused) else result(false, ExerciseError.Internal("Couldn't pause on the watch"), ExerciseState.Active)
                ExerciseOp.Resume -> if (localResume()) result(true, state = ExerciseState.Active) else result(false, ExerciseError.Internal("Couldn't resume on the watch"), ExerciseState.Paused)
                else -> if (localStop()) result(true, state = ExerciseState.Ended) else result(false, ExerciseError.Internal("Couldn't end the workout on the watch"), ExerciseState.Active)
            }
        }
    }

    /** Shared by hub and offline starts: permission → other app → Health Services → recording. */
    private suspend fun startExercise(sessionId: () -> String, type: WorkoutType, force: Boolean, gps: Boolean): ExerciseError? =
        try { doStartExercise(sessionId, type, force, gps) }
        catch (e: CancellationException) { throw e }
        catch (e: Exception) { ExerciseError.Internal(e.message ?: e.javaClass.simpleName) }

    private suspend fun doStartExercise(sessionId: () -> String, type: WorkoutType, force: Boolean, gps: Boolean): ExerciseError? {
        val missing = backend.missingPermissions()
        if (missing.isNotEmpty()) return ExerciseError.PermissionMissing(missing)
        if (!force) backend.otherAppTracking()?.let { return ExerciseError.OtherAppTracking(it) }
        if (!backend.start(type, gps)) return ExerciseError.SensorUnavailable
        detector.reset()
        recorder.begin(sessionId(), type, clock.nowMs()) // id made only once the start succeeded
        return null
    }

    /**
     * Watch-only start while the phone is unreachable (spec §4.4 "Started on the watch with no phone"): local UUID,
     * same permission and takeover checks; the caller asks the takeover question on the watch.
     */
    suspend fun localStart(type: WorkoutType, force: Boolean = false): ExerciseError? = startLock.withLock {
        activeSessionId?.let { return@withLock ExerciseError.WrongSession(it) }
        val e = startExercise(newId, type, force, gpsPrefs.get(type))
        _lastError.value = e
        e
    }

    /** Offline controls from the watch UI use the same paths and are recorded as events only on success. */
    /** Backend exceptions count as a refusal (false), never escape; cancellation propagates. */
    private suspend fun safely(op: suspend () -> Boolean): Boolean = runCatchingNonCancel(op) ?: false

    private suspend fun <T> runCatchingNonCancel(op: suspend () -> T): T? =
        try { op() } catch (e: CancellationException) { throw e } catch (_: Exception) { null }

    suspend fun localPause(): Boolean {
        if (activeSessionId == null || recorder.assembler?.phase() != WorkoutPhase.Active) return false
        if (!safely { backend.pause() }) return false
        // Health Services' own Paused update may have been recorded while pause() ran.
        if (recorder.assembler?.phase() == WorkoutPhase.Active) recorder.event(SessionEvent.Paused(clock.nowMs()))
        return true
    }

    suspend fun localResume(): Boolean {
        if (activeSessionId == null || recorder.assembler?.phase() != WorkoutPhase.Paused) return false
        if (!safely { backend.resume() }) return false
        if (recorder.assembler?.phase() == WorkoutPhase.Paused) recorder.event(SessionEvent.Resumed(clock.nowMs()))
        return true
    }

    /**
     * Ends the exercise, keeps recording the readings Health Services delivers while shutting down, then writes
     * the final delta. If end() fails nothing is finalized and the workout keeps running.
     */
    suspend fun localStop(): Boolean {
        stopOutcome?.let { return it.await() } // a stop is already in progress: share its outcome
        if (activeSessionId == null) return false
        val outcome = CompletableDeferred<Boolean>()
        stopOutcome = outcome
        val ok = try { doStop() } catch (e: Throwable) { outcome.completeExceptionally(e); stopOutcome = null; throw e }
        outcome.complete(ok)
        stopOutcome = null
        return ok
    }

    private suspend fun doStop(): Boolean {
        val id = activeSessionId ?: return false
        val waiter = CompletableDeferred<Unit>()
        stopAtMs = clock.nowMs()
        stopping = waiter
        try {
            if (!safely { backend.end() }) {
                // end() failed: if the exercise is in fact gone, finalize instead of leaving a zombie session.
                if (runCatchingNonCancel { backend.reattach(recorder.assembler?.lastSample()) } != false) return false
                recorder.event(SessionEvent.Stopped(stopAtMs, EndReason.System), final = true)
                runCatching { sendState(ExerciseStateReport(sessionId = id, state = ExerciseState.Ended, endedBy = EndReason.System)) }
                return true
            }
            withTimeoutOrNull(endTimeoutMs) { waiter.await() } // no ENDED update in time: end() itself succeeded
        } finally {
            stopping = null
        }
        recorder.event(SessionEvent.Stopped(stopAtMs, EndReason.User), final = true)
        return true
    }

    /**
     * Called by WatchRuntime.init in every new process (no Activity needed): reattach to our running exercise,
     * or record the end of a session whose exercise is gone ("actual state wins", spec §4.8).
     */
    suspend fun recover() {
        val id = activeSessionId ?: return
        detector.reset(alreadyReported = recorder.assembler?.snapshot()?.detectedType)
        // Health Services not reachable (null): keep retrying with back-off until it answers.
        var attempt = 0
        while (true) {
            when (backend.reattach(recorder.assembler?.lastSample())) {
                true -> return
                false -> {
                    recorder.event(SessionEvent.Stopped(clock.nowMs(), EndReason.System), final = true)
                    runCatching { sendState(ExerciseStateReport(sessionId = id, state = ExerciseState.Ended, endedBy = EndReason.System)) }
                    return
                }
                null -> delay(RECOVER_BACKOFF_MS[minOf(attempt++, RECOVER_BACKOFF_MS.lastIndex)])
            }
            if (activeSessionId != id) return // finished meanwhile (stop, or ended by another app)
        }
    }

    companion object {
        private val RECOVER_BACKOFF_MS = listOf(5_000L, 10_000L, 30_000L, 60_000L)
        const val SYNCING_PREVIOUS = "Watch is still saving the previous workout — try again in a moment"
    }
}
