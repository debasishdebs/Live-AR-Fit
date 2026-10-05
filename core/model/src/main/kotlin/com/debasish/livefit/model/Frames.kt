package com.debasish.livefit.model

import kotlinx.serialization.Serializable

@Serializable
enum class ConfirmationKind { TakeOverWorkout, StopWorkoutByVoice }

/** Shown on all three devices at once; the first Answer from any device wins (spec §4.6). */
@Serializable
data class Confirmation(
    val id: String,
    val kind: ConfirmationKind,
    val title: String,
    val message: String,
    val yesLabel: String = "Yes",
    val noLabel: String = "No",
    /** Which choice the glasses highlight first. */
    val defaultYes: Boolean = true,
    val expiresAtMs: Long,
)

/** Phone → watch and glasses on every change (coalesced 100 ms) and every 5 s heartbeat. */
@Serializable
data class StateFrame(
    val protocolVersion: Int = PROTOCOL_VERSION,
    val workout: WorkoutSnapshot,
    val music: NowPlaying? = null,
    val devices: Devices = Devices(),
    val voice: VoiceState = VoiceState.Idle,
    val confirmation: Confirmation? = null,
    val toast: String? = null,
    /** Set when the hub detected a protocol mismatch with this device. */
    val outdated: DeviceKind? = null,
    val sentAtMs: Long = 0,
)

/** Phone → glasses on change and on every (re)connect. */
@Serializable
data class HudSettingsFrame(val protocolVersion: Int = PROTOCOL_VERSION, val settings: HudSettings)

/** Every command travels with a unique id so resends are applied once (spec §4.5). */
@Serializable
data class CommandEnvelope(
    val protocolVersion: Int = PROTOCOL_VERSION,
    val id: String,
    val origin: DeviceKind,
    val command: Command,
)
