package com.debasish.livefit.services

import com.debasish.livefit.model.Command
import com.debasish.livefit.model.DeviceStatus
import com.debasish.livefit.model.HudFrame
import com.debasish.livefit.model.Metrics
import com.debasish.livefit.model.NowPlaying
import com.debasish.livefit.model.VoiceState
import com.debasish.livefit.model.WorkoutSnapshot
import com.debasish.livefit.model.WorkoutType
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.StateFlow

/**
 * Service contracts. Each lives in its own :services:* module with a Fake (mock-ups)
 * and, later, a Live implementation; apps only ever see these interfaces.
 */

/** A wearable data source (Galaxy Watch now; ring / band later). */
interface MetricsSource {
    val id: String
    val status: StateFlow<DeviceStatus>
    /** Starts tracking and emits readings until [stop]. */
    fun start(type: WorkoutType): Flow<Metrics>
    suspend fun stop()
}

/** Owns the workout state machine: Idle -> Starting -> Active <-> Paused -> Stopping -> Summary. */
interface WorkoutService {
    val snapshot: StateFlow<WorkoutSnapshot>
    fun start(type: WorkoutType)
    fun pause()
    fun resume()
    fun stop()
    fun dismissSummary()
}

interface GlassesLinkService {
    val status: StateFlow<DeviceStatus>
    /** Requests coming from the glasses (touchpad push-to-talk, commands). */
    val events: Flow<GlassesEvent>
    /** Opens the link and launches the HUD app on the glasses. No-op for fakes. */
    fun connect() {}
    suspend fun push(frame: HudFrame)
}

sealed interface GlassesEvent {
    data object Listen : GlassesEvent
    data class Issue(val command: Command) : GlassesEvent
}

interface WatchLinkService {
    val status: StateFlow<DeviceStatus>
    /** Commands tapped on the watch (start/stop workout, music). */
    val commands: Flow<Command> get() = kotlinx.coroutines.flow.emptyFlow()
    /** Phone -> watch state, so the watch shows the hub's workout and music. */
    suspend fun push(frame: HudFrame) {}
}

interface MusicService {
    val nowPlaying: StateFlow<NowPlaying?>
    /** 0..1 */
    val volume: StateFlow<Float>
    fun playPause()
    fun play()
    fun pause()
    fun next()
    fun previous()
    fun toggleLike()
    fun setVolume(level: Float)
}

interface VoiceService {
    val state: StateFlow<VoiceState>
    /** Recognised commands, from any utterance. */
    val commands: Flow<Command>
    /** Push-to-talk (glasses touchpad or phone button). */
    fun listen()
}

/** Injected time source so state machines are testable with virtual time. */
fun interface Clock { fun nowMs(): Long }

enum class ConfirmationOutcome { Yes, No, Timeout, Superseded }

/** At most one pending confirmation; first answer from any device wins; silence = No after 15 s. */
interface ConfirmationService {
    val pending: StateFlow<com.debasish.livefit.model.Confirmation?>
    suspend fun ask(kind: com.debasish.livefit.model.ConfirmationKind, title: String, message: String, defaultYes: Boolean = true): ConfirmationOutcome
    /** Returns true only for the first answer to the currently pending confirmation. */
    fun answer(confirmationId: String, yes: Boolean): Boolean
}

/** Phone side of hub → watch exercise control and watch → phone session data (spec §4.4, §4.8). */
interface WatchExerciseGateway {
    suspend fun send(request: com.debasish.livefit.model.ExerciseRequest)
    suspend fun ack(ack: com.debasish.livefit.model.DeltaAck)
    val results: Flow<com.debasish.livefit.model.ExerciseResult>
    val stateReports: Flow<com.debasish.livefit.model.ExerciseStateReport>
    val deltas: Flow<com.debasish.livefit.model.SessionDelta>
    val claims: Flow<com.debasish.livefit.model.SessionClaim>
}

/** Durable session storage on the phone. */
interface SessionStore {
    /** Stores durably (idempotent by sessionId+seq); returns the highest contiguous seq now stored. */
    suspend fun storeDelta(delta: com.debasish.livefit.model.SessionDelta): Long
    suspend fun deltas(sessionId: String): List<com.debasish.livefit.model.SessionDelta>
    /** Sessions with stored deltas but no finalized summary, oldest first. */
    suspend fun openSessionIds(): List<String>
    suspend fun finalize(summary: com.debasish.livefit.model.SessionSummary)
    /** Deletes the session's data but keeps a Discarded tombstone, so late data for it is still ignored after a restart. */
    suspend fun discard(sessionId: String)
    /** What the hub must remember across a phone restart (spec §4.8 abandoned starts, §4.9 end time); null = never seen. */
    suspend fun lifecycle(sessionId: String): SessionLifecycle?
    /** Records the end event. The first [endedAtMs] is kept (it starts the 24 h deadline); a later non-null reason fills a missing one. */
    suspend fun markEnded(sessionId: String, endReason: com.debasish.livefit.model.EndReason?, endedAtMs: Long)
}

enum class StoredSessionState { Open, Finalized, Discarded }

data class SessionLifecycle(
    val state: StoredSessionState,
    val endReason: com.debasish.livefit.model.EndReason? = null,
    val endedAtMs: Long? = null,
)
