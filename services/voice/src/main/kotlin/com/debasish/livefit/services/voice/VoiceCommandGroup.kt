package com.debasish.livefit.services.voice

import com.debasish.livefit.model.Command

/**
 * Voice command groups for Settings → Voice → Voice commands (P3), in list order. Each can be turned off except
 * [YesNo]: answers to confirmations (e.g. "End workout?") must always work.
 */
enum class VoiceCommandGroup(val label: String, val examples: String, val toggleable: Boolean = true) {
    StartWorkout("Start workout", "\"start workout\", \"start a run\""),
    PauseResume("Pause / resume workout", "\"pause\", \"resume workout\""),
    StopWorkout("Stop workout", "\"stop workout\", \"end my run\""),
    MusicPlayPause("Play / pause music", "\"play music\", \"pause music\""),
    NextPrevious("Next / previous song", "\"next song\", \"previous\""),
    Volume("Volume", "\"volume up\", \"quieter\""),
    Like("Like song", "\"like this song\""),
    PageViews("Glasses views", "\"glance view\", \"workout view\", \"playlist view\""),
    YesNo("Yes / no answers", "\"yes\", \"no\" to a question", toggleable = false);

    val blockedMessage: String get() = "'$label' is turned off in Settings"

    companion object {
        /** Group of a voice command; null for touch-only commands. */
        fun of(command: Command): VoiceCommandGroup? = when (command) {
            is Command.StartWorkout -> StartWorkout
            Command.PauseWorkout, Command.ResumeWorkout -> PauseResume
            Command.StopWorkout -> StopWorkout
            Command.PlayMusic, Command.PauseMusic -> MusicPlayPause
            Command.NextTrack, Command.PreviousTrack -> NextPrevious
            is Command.Volume -> Volume
            Command.LikeTrack -> Like
            is Command.ShowGlassesPage -> PageViews
            is Command.Answer -> YesNo
            Command.DismissSummary, Command.PlayPause, is Command.SetVolume, is Command.PlayQueueItem -> null
        }

        fun isAllowed(command: Command, disabled: Set<VoiceCommandGroup>): Boolean =
            of(command)?.let { !it.toggleable || it !in disabled } ?: true

        /** [disabled] after the user turns [group] on or off; a locked group never changes. */
        fun withEnabled(disabled: Set<VoiceCommandGroup>, group: VoiceCommandGroup, enabled: Boolean): Set<VoiceCommandGroup> = when {
            !group.toggleable -> disabled
            enabled -> disabled - group
            else -> disabled + group
        }

        /** Persisted form of the turned-off groups (comma-separated names); locked groups are never stored. */
        fun encode(disabled: Set<VoiceCommandGroup>): String = disabled.filter { it.toggleable }.joinToString(",") { it.name }

        fun decode(text: String): Set<VoiceCommandGroup> =
            text.split(',').mapNotNull { n -> entries.firstOrNull { it.name == n.trim() } }.filter { it.toggleable }.toSet()
    }
}

/** Voice dispatcher front: runs commands whose group is on; a turned-off one only toasts [VoiceCommandGroup.blockedMessage]. */
class VoiceCommandGate(
    private val disabled: () -> Set<VoiceCommandGroup>,
    private val toast: (String) -> Unit,
    private val dispatch: suspend (Command) -> Unit,
) {
    suspend operator fun invoke(command: Command) {
        if (VoiceCommandGroup.isAllowed(command, disabled())) dispatch(command)
        else VoiceCommandGroup.of(command)?.let { toast(it.blockedMessage) }
    }
}
