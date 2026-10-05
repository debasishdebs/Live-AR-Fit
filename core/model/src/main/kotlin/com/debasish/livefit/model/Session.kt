package com.debasish.livefit.model

import kotlinx.serialization.Serializable

@Serializable
enum class EndReason { User, OtherApp, System, Error }

/** Workout events recorded by the watch; they make pause intervals and active time reconstructable. */
@Serializable
sealed interface SessionEvent {
    val tMs: Long

    @Serializable data class Started(override val tMs: Long, val type: WorkoutType) : SessionEvent
    @Serializable data class Paused(override val tMs: Long) : SessionEvent
    @Serializable data class Resumed(override val tMs: Long) : SessionEvent
    @Serializable data class TypeDetected(override val tMs: Long, val type: WorkoutType) : SessionEvent
    @Serializable data class Stopped(override val tMs: Long, val reason: EndReason) : SessionEvent
}

/** One sensor reading; totals are cumulative since session start. Timestamps use the watch clock. */
@Serializable
data class Sample(
    val tMs: Long,
    val hr: Int? = null,
    val stepsTotal: Int = 0,
    val distanceKmTotal: Double = 0.0,
    val kcalTotal: Double = 0.0,
    val speedKmh: Double? = null,
)

@Serializable
sealed interface Provenance {
    @Serializable data class Live(val sourceId: String) : Provenance
    @Serializable data object Fake : Provenance
}

/** Watch → phone. seq starts at 0 and increases by 1 per delta within a session. */
@Serializable
data class SessionDelta(
    val protocolVersion: Int = PROTOCOL_VERSION,
    val sessionId: String,
    val seq: Long,
    val events: List<SessionEvent> = emptyList(),
    val samples: List<Sample> = emptyList(),
    val provenance: Provenance,
    val final: Boolean = false,
)

/** Phone → watch: highest contiguous seq durably stored on the phone. */
@Serializable
data class DeltaAck(val protocolVersion: Int = PROTOCOL_VERSION, val sessionId: String, val seq: Long)

/** Watch → phone on reconnect when it holds a session. */
@Serializable
data class SessionClaim(
    val protocolVersion: Int = PROTOCOL_VERSION,
    val sessionId: String,
    val type: WorkoutType,
    val startMs: Long,
    val phase: WorkoutPhase,
    val activeMs: Long,
    val lastSeq: Long,
)

@Serializable
enum class SessionStatus { Active, Stopping, Complete, Incomplete }

/** What history stores per session (spec §5.6). */
@Serializable
data class SessionSummary(
    val id: String,
    val type: WorkoutType,
    val detectedType: WorkoutType? = null,
    val startMs: Long,
    val endMs: Long? = null,
    val activeMs: Long,
    val avgHr: Int? = null,
    val maxHr: Int? = null,
    val steps: Int = 0,
    val distanceKm: Double = 0.0,
    val kcal: Int = 0,
    val provenance: Provenance,
    val status: SessionStatus,
    val endReason: EndReason? = null,
)
