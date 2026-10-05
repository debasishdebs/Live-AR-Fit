package com.debasish.livefit.services.workout

import com.debasish.livefit.model.ConfirmationKind
import com.debasish.livefit.model.DeltaAck
import com.debasish.livefit.model.EndReason
import com.debasish.livefit.model.ExerciseError
import com.debasish.livefit.model.ExerciseOp
import com.debasish.livefit.model.ExerciseRequest
import com.debasish.livefit.model.ExerciseResult
import com.debasish.livefit.model.ExerciseState
import com.debasish.livefit.model.ExerciseStateReport
import com.debasish.livefit.model.SessionClaim
import com.debasish.livefit.model.SessionDelta
import com.debasish.livefit.model.SessionStatus
import com.debasish.livefit.model.SessionSummary
import com.debasish.livefit.model.WorkoutPhase
import com.debasish.livefit.model.WorkoutSnapshot
import com.debasish.livefit.model.WorkoutType
import com.debasish.livefit.services.Clock
import com.debasish.livefit.services.ConfirmationOutcome
import com.debasish.livefit.services.ConfirmationService
import com.debasish.livefit.services.SessionStore
import com.debasish.livefit.services.StoredSessionState
import com.debasish.livefit.services.WatchExerciseGateway
import com.debasish.livefit.services.WorkoutService
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withTimeoutOrNull
import java.util.UUID

/**
 * Phone hub: owns workout state; the watch executes on Health Services and reports.
 * Spec: §4.4 deltas/acks/adoption, §4.8 exercise control, §4.9 completion rule.
 * Handlers can interleave at store suspension points, so [mutex] serialises the state-mutating ones
 * (it is never held across the take-over confirmation or request waits).
 */
class HubWorkoutService(
    private val scope: CoroutineScope,
    private val gateway: WatchExerciseGateway,
    private val store: SessionStore,
    private val confirm: ConfirmationService,
    private val clock: Clock,
    private val newId: () -> String = { UUID.randomUUID().toString() },
    private val resultTimeoutMs: Long = 10_000,
    private val incompleteAfterMs: Long = 24 * 60 * 60 * 1000L,
    private val incompleteCheckMs: Long = 60_000,
    /** Phone setting "Use GPS outdoors" per workout type (spec §5.1). */
    private val gpsFor: (WorkoutType) -> Boolean = { false },
) : WorkoutService {

    private val _snapshot = MutableStateFlow(WorkoutSnapshot())
    override val snapshot: StateFlow<WorkoutSnapshot> = _snapshot
    private val _notices = MutableSharedFlow<String>(extraBufferCapacity = 16)
    val notices: SharedFlow<String> = _notices
    private val _hrHistory = MutableStateFlow<List<Int>>(emptyList())
    val hrHistory: StateFlow<List<Int>> = _hrHistory
    private val _finished = MutableSharedFlow<SessionSummary>(extraBufferCapacity = 4)
    val finished: SharedFlow<SessionSummary> = _finished

    private var currentId: String? = null
    private var current: SessionAssembler? = null
    private var claim: SessionClaim? = null
    private var syncUntilSeq: Long? = null
    private var endedByReport = false
    private var endReason: EndReason? = null
    private var endedAtMs: Long? = null
    private var finishedCurrent = false
    private val mutex = Mutex()
    private var startJob: Job? = null
    private val pendingResults = HashMap<String, CompletableDeferred<ExerciseResult>>()

    init {
        scope.launch { gateway.results.collect { onResult(it) } }
        scope.launch { gateway.deltas.collect { onDelta(it) } }
        scope.launch { gateway.claims.collect { onClaim(it) } }
        scope.launch { gateway.stateReports.collect { onStateReport(it) } }
        scope.launch { restore() }
        scope.launch { while (true) { delay(incompleteCheckMs); checkIncomplete() } }
    }

    // ---- Commands -----------------------------------------------------------------------

    override fun start(type: WorkoutType) {
        when (_snapshot.value.phase) {
            WorkoutPhase.Idle, WorkoutPhase.Summary -> Unit
            WorkoutPhase.Syncing -> { notice(SYNCING_NOTICE); return }
            else -> return // duplicate start from another device while starting/running
        }
        val id = newId()
        beginSession(id)
        _snapshot.value = WorkoutSnapshot(sessionId = id, phase = WorkoutPhase.Starting, type = type)
        startJob = scope.launch { runStart(id, type, force = false) }
    }

    override fun pause() = scopedOp(ExerciseOp.Pause, setOf(WorkoutPhase.Active), "pause")
    override fun resume() = scopedOp(ExerciseOp.Resume, setOf(WorkoutPhase.Paused), "resume")

    override fun stop() {
        if (_snapshot.value.phase == WorkoutPhase.Starting) {
            val id = currentId ?: return
            scope.launch { abandon(id); sendStop(id) }
            return
        }
        scopedOp(ExerciseOp.Stop, setOf(WorkoutPhase.Active, WorkoutPhase.Paused), "stop")
    }

    override fun dismissSummary() {
        if (_snapshot.value.phase == WorkoutPhase.Summary) resetIdle()
    }

    // ---- Start / ops ----------------------------------------------------------------------

    private fun beginSession(id: String) {
        currentId = id
        current = SessionAssembler(id)
        claim = null
        syncUntilSeq = null
        endedByReport = false
        endReason = null
        endedAtMs = null
        finishedCurrent = false
        _hrHistory.value = emptyList()
    }

    private suspend fun runStart(id: String, type: WorkoutType, force: Boolean) {
        val r = request(id, ExerciseOp.Start(type, force, gpsFor(type)))
        if (currentId != id) return
        if (r == null) { abandon(id); notice("Watch didn't respond"); return }
        if (r.ok) return
        when (val e = r.error) {
            is ExerciseError.OtherAppTracking -> {
                val outcome = confirm.ask(
                    ConfirmationKind.TakeOverWorkout,
                    title = "Take over workout?",
                    message = "Another app is tracking a workout on your watch. Take over?",
                    defaultYes = true,
                )
                if (currentId != id) return
                if (outcome == ConfirmationOutcome.Yes) runStart(id, type, force = true)
                else { resetIdle(); notice(if (outcome == ConfirmationOutcome.Superseded) "Cancelled" else "Samsung Health is still tracking") }
            }
            is ExerciseError.PermissionMissing -> { resetIdle(); notice("Watch needs permission: " + e.permissions.joinToString { it.substringAfterLast('.') }) }
            is ExerciseError.WrongSession -> { resetIdle(); notice("Watch is busy with another workout") }
            ExerciseError.SensorUnavailable -> { resetIdle(); notice("Watch sensors unavailable") }
            is ExerciseError.Internal -> { resetIdle(); notice(e.message) }
            else -> { resetIdle(); notice("Couldn't start workout") }
        }
    }

    private fun scopedOp(op: ExerciseOp, allowed: Set<WorkoutPhase>, verb: String) {
        val phase = _snapshot.value.phase
        if (phase == WorkoutPhase.Syncing) { notice(SYNCING_NOTICE); return }
        if (phase !in allowed) return
        val id = currentId ?: return
        scope.launch {
            val r = request(id, op)
            when {
                r == null -> notice("Watch didn't respond")
                !r.ok -> notice("Couldn't $verb workout")
            }
        }
    }

    private suspend fun request(sessionId: String, op: ExerciseOp): ExerciseResult? {
        val requestId = newId()
        val waiter = CompletableDeferred<ExerciseResult>()
        pendingResults[requestId] = waiter
        try {
            gateway.send(ExerciseRequest(requestId = requestId, sessionId = sessionId, op = op))
            return withTimeoutOrNull(resultTimeoutMs) { waiter.await() }
        } finally {
            pendingResults.remove(requestId)
        }
    }

    private suspend fun onResult(r: ExerciseResult) {
        val waiter = pendingResults.remove(r.requestId)
        if (waiter != null) { waiter.complete(r); return }
        if (r.ok) mutex.withLock { if (isDiscarded(r.sessionId)) sendStop(r.sessionId) } // late ok: stop only that session
    }

    /** Abandoned starts are tombstoned in the store, so this survives a phone restart. */
    private suspend fun isDiscarded(id: String) = store.lifecycle(id)?.state == StoredSessionState.Discarded

    private suspend fun sendStop(sessionId: String) =
        gateway.send(ExerciseRequest(requestId = newId(), sessionId = sessionId, op = ExerciseOp.Stop))

    private suspend fun abandon(id: String) {
        store.discard(id) // tombstone: later data and claims for it are rejected, even after a restart
        if (currentId == id) {
            startJob?.cancel() // also drops a pending take-over question
            startJob = null
            resetIdle()
        }
    }

    // ---- Watch data -----------------------------------------------------------------------

    private suspend fun onDelta(d: SessionDelta) = mutex.withLock { onDeltaLocked(d) }

    private suspend fun onDeltaLocked(d: SessionDelta) {
        when (store.lifecycle(d.sessionId)?.state) {
            // Abandoned start, or a session already finalized (e.g. replay after a lost final ack):
            // ack so the watch can drop it, but never adopt it — that would end the current workout.
            StoredSessionState.Discarded, StoredSessionState.Finalized -> { gateway.ack(DeltaAck(sessionId = d.sessionId, seq = d.seq)); return }
            else -> Unit
        }
        if (d.sessionId != currentId || finishedCurrent) adopt(d.sessionId)
        val a = current ?: return
        val storedSeq = store.storeDelta(d) // durable first, then ack
        a.add(d)
        gateway.ack(DeltaAck(sessionId = d.sessionId, seq = storedSeq))
        publish()
    }

    private suspend fun onClaim(c: SessionClaim) = mutex.withLock { onClaimLocked(c) }

    private suspend fun onClaimLocked(c: SessionClaim) {
        when (store.lifecycle(c.sessionId)?.state) {
            StoredSessionState.Discarded -> { sendStop(c.sessionId); return }
            StoredSessionState.Finalized -> { gateway.ack(DeltaAck(sessionId = c.sessionId, seq = c.lastSeq)); return } // lets the watch free its buffer
            else -> Unit
        }
        if (c.sessionId != currentId || finishedCurrent) adopt(c.sessionId)
        val a = current ?: return
        claim = c
        if (a.contiguousSeq < c.lastSeq) syncUntilSeq = c.lastSeq
        publish()
    }

    private suspend fun onStateReport(r: ExerciseStateReport) = mutex.withLock { onStateReportLocked(r) }

    private suspend fun onStateReportLocked(r: ExerciseStateReport) {
        val a = current
        if (r.state == ExerciseState.Idle && r.sessionId != currentId && a != null && !finishedCurrent && isEnded(a) && !a.isComplete) {
            finalize(a, SessionStatus.Incomplete) // watch holds no buffer for our session: data is gone
            notice("Workout saved as incomplete")
            return
        }
        if (r.sessionId != currentId || finishedCurrent) return
        if (r.state == ExerciseState.Ended) {
            endedByReport = true
            endReason = r.endedBy
            if (endedAtMs == null) endedAtMs = clock.nowMs()
            store.markEnded(r.sessionId, r.endedBy, endedAtMs!!) // survives a phone restart
            r.endedBy?.let { if (it != EndReason.User) notice("Workout ended by ${describe(it)}") }
            publish()
        }
    }

    /** Takes over [id] as the current session (spec §4.4 step 5 and conflict rule step 7). */
    private suspend fun adopt(id: String) {
        val previous = current
        if (previous != null && !finishedCurrent && previous.sessionId != id) {
            if (previous.hasSamples) {
                val summary = previous.summary(if (previous.isComplete) SessionStatus.Complete else SessionStatus.Incomplete, endReason)
                store.finalize(summary)
                _finished.emit(summary)
            } else {
                store.discard(previous.sessionId)
            }
        }
        beginSession(id)
        val a = current!!
        store.deltas(id).forEach { a.add(it) }
    }

    private suspend fun restore() = mutex.withLock { restoreLocked() }

    private suspend fun restoreLocked() {
        if (currentId != null) return
        val id = store.openSessionIds().lastOrNull() ?: return
        val a = SessionAssembler(id)
        store.deltas(id).forEach { a.add(it) }
        if (a.deltaCount == 0 || currentId != null) return
        val life = store.lifecycle(id)
        beginSession(id)
        current = a
        life?.endedAtMs?.let { at -> // ended before the restart: keep Stopping and the original deadline
            endedByReport = true
            endReason = life.endReason
            endedAtMs = at
        }
        publish()
    }

    // ---- Publishing & completion -------------------------------------------------------------

    private fun isEnded(a: SessionAssembler) = endedByReport || a.phase() == WorkoutPhase.Stopping

    private suspend fun publish() {
        val a = current ?: return
        if (finishedCurrent) return
        syncUntilSeq?.let { if (a.contiguousSeq >= it) syncUntilSeq = null }
        val ended = isEnded(a)
        if (ended && endedAtMs == null) {
            endedAtMs = clock.nowMs()
            store.markEnded(a.sessionId, endReason, endedAtMs!!)
        }
        if (a.isComplete && syncUntilSeq == null) { finalize(a, SessionStatus.Complete); return }
        var snap = a.snapshot()
        if (a.deltaCount == 0) snap = snap.copy(type = claim?.type ?: _snapshot.value.type, phase = WorkoutPhase.Starting)
        snap = when {
            syncUntilSeq != null -> snap.copy(phase = WorkoutPhase.Syncing)
            ended -> snap.copy(phase = WorkoutPhase.Stopping)
            else -> snap
        }
        _snapshot.value = snap
        _hrHistory.value = a.hrHistory()
    }

    private suspend fun finalize(a: SessionAssembler, status: SessionStatus) {
        val summary = a.summary(status, endReason)
        store.finalize(summary)
        finishedCurrent = true
        _snapshot.value = a.snapshot().copy(phase = WorkoutPhase.Summary)
        _hrHistory.value = a.hrHistory()
        _finished.emit(summary)
    }

    private suspend fun checkIncomplete() = mutex.withLock { checkIncompleteLocked() }

    private suspend fun checkIncompleteLocked() {
        val a = current ?: return
        val ended = endedAtMs ?: return
        if (finishedCurrent || a.isComplete) return
        if (clock.nowMs() - ended >= incompleteAfterMs) {
            finalize(a, SessionStatus.Incomplete)
            notice("Workout saved as incomplete")
        }
    }

    private fun resetIdle() {
        currentId = null
        current = null
        claim = null
        syncUntilSeq = null
        finishedCurrent = false
        _snapshot.value = WorkoutSnapshot()
        _hrHistory.value = emptyList()
    }

    private fun describe(reason: EndReason) = when (reason) {
        EndReason.OtherApp -> "another app"
        EndReason.System -> "the watch"
        EndReason.Error -> "an error"
        EndReason.User -> "you"
    }

    private fun notice(text: String) { _notices.tryEmit(text) }

    companion object { const val SYNCING_NOTICE = "Syncing watch data…" }
}
