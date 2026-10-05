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
