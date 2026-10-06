package com.debasish.livefit.sync

import com.debasish.livefit.model.Command
import com.debasish.livefit.model.CommandEnvelope
import com.debasish.livefit.model.ConfirmationKind
import com.debasish.livefit.model.DeviceKind
import com.debasish.livefit.model.PROTOCOL_VERSION
import com.debasish.livefit.services.ConfirmationOutcome
import com.debasish.livefit.services.ConfirmationService
import com.debasish.livefit.services.MusicService
import com.debasish.livefit.services.WorkoutService
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch

/** Single entry point for commands from any device (spec §4.5, §4.7). */
class HubCommandRouter(
    private val workout: WorkoutService,
    private val music: MusicService,
    private val confirm: ConfirmationService,
    private val scope: CoroutineScope,
    private val toast: (String) -> Unit,
    private val deduper: CommandDeduper = CommandDeduper(),
    /** Called just before a StartWorkout is applied, with its origin (null = voice). */
    private val onStartRequested: (DeviceKind?) -> Unit = {},
) {
    private val _outdated = MutableStateFlow<DeviceKind?>(null)
    val outdated: StateFlow<DeviceKind?> = _outdated

    /** Called by link codecs that drop messages with a different protocol version. */
    fun markOutdated(kind: DeviceKind) {
        _outdated.value = kind
        toast("Update LiveFit on your ${kind.name.lowercase()}")
    }

    fun dispatch(envelope: CommandEnvelope) {
        if (envelope.protocolVersion != PROTOCOL_VERSION) {
            markOutdated(envelope.origin)
            return
        }
        if (!deduper.firstTime(envelope.id)) return
        apply(envelope.command, envelope.origin)
    }

    /** Voice path: destructive commands are confirmed on all devices first. */
    suspend fun dispatchVoice(command: Command) {
        if (command == Command.StopWorkout) {
            val o = confirm.ask(ConfirmationKind.StopWorkoutByVoice, "End workout?", "You said stop. End the workout?", defaultYes = true)
            if (o != ConfirmationOutcome.Yes) { toast("Cancelled"); return }
        }
        apply(command, origin = null)
    }

    private fun apply(command: Command, origin: DeviceKind?) {
        if (command is Command.StartWorkout) onStartRequested(origin)
        when (command) {
            is Command.StartWorkout -> workout.start(command.type)
            Command.PauseWorkout -> workout.pause()
            Command.ResumeWorkout -> workout.resume()
            Command.StopWorkout -> workout.stop()
            Command.DismissSummary -> workout.dismissSummary()
            Command.PlayPause -> music.playPause()
            Command.PlayMusic -> music.play()
            Command.PauseMusic -> music.pause()
            Command.NextTrack -> music.next()
            Command.PreviousTrack -> music.previous()
            Command.LikeTrack -> music.toggleLike()
            is Command.Volume -> music.setVolume((music.volume.value + if (command.up) 0.1f else -0.1f).coerceIn(0f, 1f))
            is Command.SetVolume -> music.setVolume(command.level.coerceIn(0f, 1f))
            is Command.PlayQueueItem -> music.playQueueItem(command.queueId)
            is Command.Answer -> scope.launch { confirm.answer(command.confirmationId, command.yes) }
        }
        describe(command)?.let(toast)
    }

    companion object {
        /** Toast text for music/volume commands; null = silent. */
        fun describe(command: Command): String? = when (command) {
            // Workout commands get feedback from state changes and HubWorkoutService notices (a rejected Start must not say "started").
            is Command.StartWorkout, Command.PauseWorkout, Command.ResumeWorkout, Command.StopWorkout, Command.DismissSummary -> null
            Command.PlayPause -> "Play / pause"
            Command.PlayMusic -> "Playing"
            Command.PauseMusic -> "Music paused"
            Command.NextTrack -> "Next song"
            Command.PreviousTrack -> "Previous song"
            Command.LikeTrack -> "Liked"
            is Command.Volume -> if (command.up) "Volume up" else "Volume down"
            is Command.SetVolume -> "Volume ${(command.level * 100).toInt().coerceIn(0, 100)}%"
            is Command.PlayQueueItem -> "Playing selected song"
            is Command.Answer -> null
        }
    }
}
