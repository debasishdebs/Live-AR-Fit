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
    /** Phone media volume 0..1 (shown on the watch volume arc). */
    val volume: Float = 0.5f,
)

@Serializable
enum class VoiceState { Idle, Listening, Processing }

@Serializable
enum class DeviceKind { Phone, Watch, Glasses }

@Serializable
data class DeviceState(val link: LinkState = LinkState.Disconnected, val batteryPct: Int? = null)

@Serializable
data class Devices(
    val phone: DeviceState = DeviceState(LinkState.Connected),
    val watch: DeviceState = DeviceState(),
    val glasses: DeviceState = DeviceState(),
)

/** Commands any input (voice, phone UI, watch, glasses touchpad) can issue. Always sent inside a [CommandEnvelope]. */
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
    /** Voice: ±10 %. */
    @Serializable data class Volume(val up: Boolean) : Command
    /** Watch arc / bezel and phone slider: absolute 0..1. */
    @Serializable data class SetVolume(val level: Float) : Command
    @Serializable data class Answer(val confirmationId: String, val yes: Boolean) : Command
}
