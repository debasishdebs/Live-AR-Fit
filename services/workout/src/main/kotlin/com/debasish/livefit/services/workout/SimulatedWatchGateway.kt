package com.debasish.livefit.services.workout

import com.debasish.livefit.model.DeltaAck
import com.debasish.livefit.model.EndReason
import com.debasish.livefit.model.ExerciseError
import com.debasish.livefit.model.ExerciseOp
import com.debasish.livefit.model.ExerciseRequest
import com.debasish.livefit.model.ExerciseResult
import com.debasish.livefit.model.ExerciseState
import com.debasish.livefit.model.ExerciseStateReport
import com.debasish.livefit.model.Provenance
import com.debasish.livefit.model.Sample
import com.debasish.livefit.model.SessionClaim
import com.debasish.livefit.model.SessionDelta
import com.debasish.livefit.model.SessionEvent
import com.debasish.livefit.model.WorkoutType
import com.debasish.livefit.services.Clock
import com.debasish.livefit.services.WatchExerciseGateway
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.launch
import kotlin.math.roundToInt
import kotlin.math.sin
import kotlin.random.Random

/**
 * Demo watch: same protocol as the real one, Fake provenance, plausible HR/cadence curves.
 * Must be used from a single-threaded scope (the hub's Main.immediate scope); state is unsynchronized by design.
 */
class SimulatedWatchGateway(
    private val scope: CoroutineScope,
    private val clock: Clock,
    private val tickMs: Long = 1_000,
) : WatchExerciseGateway {
    override val results = MutableSharedFlow<ExerciseResult>(extraBufferCapacity = 16)
    override val stateReports = MutableSharedFlow<ExerciseStateReport>(extraBufferCapacity = 16)
    override val deltas = MutableSharedFlow<SessionDelta>(extraBufferCapacity = 256)
    override val claims = emptyFlow<SessionClaim>()

    private var sessionId: String? = null
    private var seq = 0L
    private var paused = false
    private var ticker: Job? = null
    private var steps = 0.0; private var km = 0.0; private var kcal = 0.0; private var t = 0

    override suspend fun ack(ack: DeltaAck) = Unit

    override suspend fun send(request: ExerciseRequest) {
        val op = request.op
        if (op !is ExerciseOp.Start && request.sessionId != sessionId) {
            results.emit(
                ExerciseResult(
                    requestId = request.requestId, sessionId = request.sessionId, ok = false,
                    error = ExerciseError.WrongSession(sessionId),
                    state = when { sessionId == null -> ExerciseState.Idle; paused -> ExerciseState.Paused; else -> ExerciseState.Active },
                    activeSessionId = sessionId,
                ),
            )
            return
        }
        when (op) {
            is ExerciseOp.Start -> {
                ticker?.cancel()
                sessionId = request.sessionId; seq = 0; paused = false; steps = 0.0; km = 0.0; kcal = 0.0; t = 0
                reply(request, ExerciseState.Active)
                emit(events = listOf(SessionEvent.Started(clock.nowMs(), op.type)))
                ticker = scope.launch { while (true) { delay(tickMs); if (!paused) tick(op.type) } }
            }
            ExerciseOp.Pause -> { paused = true; reply(request, ExerciseState.Paused); emit(events = listOf(SessionEvent.Paused(clock.nowMs()))) }
            ExerciseOp.Resume -> { paused = false; reply(request, ExerciseState.Active); emit(events = listOf(SessionEvent.Resumed(clock.nowMs()))) }
            ExerciseOp.Stop -> {
                ticker?.cancel()
                reply(request, ExerciseState.Ended)
                emit(events = listOf(SessionEvent.Stopped(clock.nowMs(), EndReason.User)), final = true)
                sessionId = null
            }
        }
    }

    private suspend fun reply(r: ExerciseRequest, state: ExerciseState) =
        results.emit(ExerciseResult(requestId = r.requestId, sessionId = r.sessionId, ok = true, state = state, activeSessionId = sessionId))

    private suspend fun tick(type: WorkoutType) {
        val (rest, peak, cadence, speed) = when (type) {
            WorkoutType.Run -> listOf(95.0, 158.0, 165.0, 10.2)
            WorkoutType.Cycle -> listOf(90.0, 145.0, 0.0, 21.0)
            else -> listOf(85.0, 118.0, 112.0, 5.4)
        }
        val warm = minOf(1.0, t / 90.0)
        val hr = (rest + (peak - rest) * warm + 4 * sin(t / 9.0) + Random.nextDouble(-2.0, 2.0)).roundToInt()
        val v = (speed * (0.85 + 0.15 * warm)).coerceAtLeast(0.0)
        steps += cadence / 60.0 * tickMs / 1000.0
        km += v * tickMs / 3_600_000.0
        kcal += hr * 0.09 * tickMs / 60_000.0
        t++
        emit(samples = listOf(Sample(clock.nowMs(), hr, steps.toInt(), km, kcal, v)))
    }

    private suspend fun emit(events: List<SessionEvent> = emptyList(), samples: List<Sample> = emptyList(), final: Boolean = false) {
        val id = sessionId ?: return
        deltas.emit(SessionDelta(sessionId = id, seq = seq++, events = events, samples = samples, provenance = Provenance.Fake, final = final))
    }
}
