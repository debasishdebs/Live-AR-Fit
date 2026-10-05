package com.debasish.livefit.model

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

/** Everything the glasses HUD needs for one redraw; sent phone -> glasses about once a second. */
@Serializable
data class HudFrame(
    val workout: WorkoutSnapshot,
    val watch: LinkState,
    val phone: LinkState,
    val music: NowPlaying? = null,
    val voice: VoiceState = VoiceState.Idle,
    /** Short confirmation like "Next song"; glasses show it briefly then fade. */
    val toast: String? = null,
    val settings: HudSettings = HudSettings(),
    /** Battery 0..100 of the other devices, for the HUD's battery rings (glasses read their own). */
    val phoneBattery: Int? = null,
    val watchBattery: Int? = null,
    val sentAtMs: Long = 0,
)

/** Wire format shared by phone, watch and glasses. Channel names are CXR custom-cmd names. */
object Protocol {
    const val CH_HUD = "lf_hud"
    const val CH_COMMAND = "lf_cmd"
    const val CH_LISTEN = "lf_listen"

    /** Wearable Data Layer paths (phone <-> watch). */
    const val PATH_STATE = "/rf/state"
    const val PATH_COMMAND = "/rf/cmd"

    // "cmd" not the default "type": StartWorkout has its own `type` field.
    val json = Json { ignoreUnknownKeys = true; encodeDefaults = false; classDiscriminator = "cmd" }

    fun encodeHud(frame: HudFrame): String = json.encodeToString(HudFrame.serializer(), frame)
    fun decodeHud(text: String): HudFrame = json.decodeFromString(HudFrame.serializer(), text)
    fun encodeCommand(command: Command): String = json.encodeToString(Command.serializer(), command)
    fun decodeCommand(text: String): Command = json.decodeFromString(Command.serializer(), text)
}
