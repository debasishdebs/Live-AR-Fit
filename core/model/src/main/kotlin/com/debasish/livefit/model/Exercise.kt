package com.debasish.livefit.model

import kotlinx.serialization.Serializable

/** Hub → watch exercise control; every op names its session (spec §4.8). */
@Serializable
sealed interface ExerciseOp {
    /** [gps] comes from the phone setting "Use GPS outdoors" (spec §5.1). */
    @Serializable data class Start(val type: WorkoutType, val force: Boolean = false, val gps: Boolean = false) : ExerciseOp
    @Serializable data object Pause : ExerciseOp
    @Serializable data object Resume : ExerciseOp
    @Serializable data object Stop : ExerciseOp
}

@Serializable
data class ExerciseRequest(
    val protocolVersion: Int = PROTOCOL_VERSION,
    val requestId: String,
    val sessionId: String,
    val op: ExerciseOp,
)

@Serializable
sealed interface ExerciseError {
    @Serializable data class PermissionMissing(val permissions: List<String>) : ExerciseError
    @Serializable data class OtherAppTracking(val appExerciseType: String) : ExerciseError
    @Serializable data object SensorUnavailable : ExerciseError
    @Serializable data class WrongSession(val activeSessionId: String?) : ExerciseError
    @Serializable data object NoSuchSession : ExerciseError
    @Serializable data class Internal(val message: String) : ExerciseError
}

@Serializable
enum class ExerciseState { Idle, Preparing, Active, Paused, Ended }

@Serializable
data class ExerciseResult(
    val protocolVersion: Int = PROTOCOL_VERSION,
    val requestId: String,
    val sessionId: String,
    val ok: Boolean,
    val error: ExerciseError? = null,
    val state: ExerciseState,
    val activeSessionId: String? = null,
)

/** Unsolicited: the real Health Services state changed (e.g. another app ended our workout). */
@Serializable
data class ExerciseStateReport(
    val protocolVersion: Int = PROTOCOL_VERSION,
    val sessionId: String,
    val state: ExerciseState,
    val endedBy: EndReason? = null,
    /** Watch wall-clock time the report was made; lets the phone ignore an Idle report older than its session. */
    val atMs: Long? = null,
)
