package com.debasish.livefit.model

import kotlinx.serialization.Serializable

@Serializable
enum class LinkState { Disconnected, Connecting, Connected }

@Serializable
data class DeviceStatus(
    val name: String,
    val link: LinkState = LinkState.Disconnected,
    val batteryPct: Int? = null,
    val detail: String? = null,
)

@Serializable
data class NowPlaying(
    val title: String,
    val artist: String,
    val isPlaying: Boolean,
    val liked: Boolean = false,
    val positionMs: Long = 0,
    val durationMs: Long = 0,
)

@Serializable
enum class VoiceState { Idle, Listening, Processing }

/** Commands any input (voice, phone UI, watch, glasses touchpad) can issue. */
@Serializable
sealed interface Command {
    @Serializable data class StartWorkout(val type: WorkoutType) : Command
    @Serializable data object PauseWorkout : Command
    @Serializable data object ResumeWorkout : Command
    @Serializable data object StopWorkout : Command
    @Serializable data object DismissSummary : Command
    /** Touch toggle (phone/watch button). Voice uses the explicit [PlayMusic] / [PauseMusic]. */
    @Serializable data object PlayPause : Command
    @Serializable data object PlayMusic : Command
    @Serializable data object PauseMusic : Command
    @Serializable data object NextTrack : Command
    @Serializable data object PreviousTrack : Command
    @Serializable data object LikeTrack : Command
    @Serializable data class Volume(val up: Boolean) : Command
}
