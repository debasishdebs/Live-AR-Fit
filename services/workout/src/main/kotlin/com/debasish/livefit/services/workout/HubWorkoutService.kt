package com.debasish.livefit.services.workout

import com.debasish.livefit.model.ConfirmationKind
import com.debasish.livefit.model.DeltaAck
import com.debasish.livefit.model.ExerciseError
import com.debasish.livefit.model.ExerciseOp
import com.debasish.livefit.model.ExerciseRequest
import com.debasish.livefit.model.ExerciseResult
import com.debasish.livefit.model.SessionDelta
import com.debasish.livefit.model.SessionSummary
import com.debasish.livefit.model.WorkoutPhase
import com.debasish.livefit.model.WorkoutSnapshot
import com.debasish.livefit.model.WorkoutType
import com.debasish.livefit.services.Clock
import com.debasish.livefit.services.ConfirmationOutcome
import com.debasish.livefit.services.ConfirmationService
import com.debasish.livefit.services.SessionStore
import com.debasish.livefit.services.WatchExerciseGateway
import com.debasish.livefit.services.WorkoutService
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
import java.util.UUID

/** Phone hub: decides workout state; the watch executes and reports (spec §4.4, §4.8). Single-threaded scope. */
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
    private val pendingResults = HashMap<String, CompletableDeferred<ExerciseResult>>()
    private val abandoned = HashSet<String>()

    init {
        scope.launch { gateway.results.collect { onResult(it) } }
        scope.launch { gateway.deltas.collect { onDelta(it) } }
    }

    override fun start(type: WorkoutType) {
        when (_snapshot.value.phase) {
            WorkoutPhase.Idle, WorkoutPhase.Summary -> Unit
            WorkoutPhase.Syncing -> { notice(SYNCING_NOTICE); return }
            else -> return // duplicate start from another device while starting/running
        }
        val id = newId()
        beginSession(id)
        _snapshot.value = WorkoutSnapshot(sessionId = id, phase = WorkoutPhase.Starting, type = type)
        scope.launch { runStart(id, type, force = false) }
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

    private fun beginSession(id: String) {
        currentId = id
        current = SessionAssembler(id)
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
        gateway.send(ExerciseRequest(requestId = requestId, sessionId = sessionId, op = op))
        val r = withTimeoutOrNull(resultTimeoutMs) { waiter.await() }
        pendingResults.remove(requestId)
        return r
    }

    private suspend fun onResult(r: ExerciseResult) {
        val waiter = pendingResults.remove(r.requestId)
        if (waiter != null) { waiter.complete(r); return }
        if (r.ok && r.sessionId in abandoned) sendStop(r.sessionId) // late ok: stop only that session
    }

    private suspend fun sendStop(sessionId: String) =
        gateway.send(ExerciseRequest(requestId = newId(), sessionId = sessionId, op = ExerciseOp.Stop))

    private suspend fun abandon(id: String) {
        abandoned += id
        store.discard(id)
        if (currentId == id) resetIdle()
    }

    private suspend fun onDelta(d: SessionDelta) {
        if (d.sessionId in abandoned) { gateway.ack(DeltaAck(sessionId = d.sessionId, seq = d.seq)); return }
        if (d.sessionId != currentId) return // adoption handled in Task 7
        val a = current ?: return
        val storedSeq = store.storeDelta(d) // durable first
        a.add(d)
        gateway.ack(DeltaAck(sessionId = d.sessionId, seq = storedSeq))
        publish()
    }

    private fun publish() {
        val a = current ?: return
        val snap = a.snapshot()
        _snapshot.value = if (a.deltaCount == 0) snap.copy(phase = WorkoutPhase.Starting, type = _snapshot.value.type) else snap
        _hrHistory.value = a.hrHistory()
    }

    private fun resetIdle() {
        currentId = null
        current = null
        _snapshot.value = WorkoutSnapshot()
        _hrHistory.value = emptyList()
    }

    private fun notice(text: String) { _notices.tryEmit(text) }

    companion object { const val SYNCING_NOTICE = "Syncing watch data…" }
}
