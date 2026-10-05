package com.debasish.livefit.services.workout

import com.debasish.livefit.model.DeltaAck
import com.debasish.livefit.model.ExerciseError
import com.debasish.livefit.model.ExerciseRequest
import com.debasish.livefit.model.ExerciseResult
import com.debasish.livefit.model.ExerciseState
import com.debasish.livefit.model.ExerciseStateReport
import com.debasish.livefit.model.SessionClaim
import com.debasish.livefit.model.SessionDelta
import com.debasish.livefit.services.WatchExerciseGateway
import kotlinx.coroutines.flow.MutableSharedFlow

class FakeWatchGateway : WatchExerciseGateway {
    val sent = mutableListOf<ExerciseRequest>()
    val acks = mutableListOf<DeltaAck>()
    override val results = MutableSharedFlow<ExerciseResult>(extraBufferCapacity = 64)
    override val stateReports = MutableSharedFlow<ExerciseStateReport>(extraBufferCapacity = 64)
    override val deltas = MutableSharedFlow<SessionDelta>(extraBufferCapacity = 8_192)
    override val claims = MutableSharedFlow<SessionClaim>(extraBufferCapacity = 64)

    override suspend fun send(request: ExerciseRequest) { sent += request }
    override suspend fun ack(ack: DeltaAck) { acks += ack }

    fun reply(index: Int = sent.lastIndex, ok: Boolean = true, error: ExerciseError? = null, state: ExerciseState = ExerciseState.Active) {
        val r = sent[index]
        results.tryEmit(ExerciseResult(requestId = r.requestId, sessionId = r.sessionId, ok = ok, error = error, state = state))
    }
}
