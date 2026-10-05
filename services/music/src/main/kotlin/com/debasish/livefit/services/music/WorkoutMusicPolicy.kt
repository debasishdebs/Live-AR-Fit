package com.debasish.livefit.services.music

import com.debasish.livefit.model.WorkoutPhase

enum class MusicOnStart { DontTouch, Resume, PlaySearch }
enum class MusicAction { None, Resume, PlaySearch, Pause }

/** Settings → Music behaviour on workout transitions (spec §5.5). */
object WorkoutMusicPolicy {
    fun actionFor(from: WorkoutPhase, to: WorkoutPhase, onStart: MusicOnStart, pauseOnStop: Boolean): MusicAction = when {
        from == WorkoutPhase.Starting && to == WorkoutPhase.Active -> when (onStart) {
            MusicOnStart.DontTouch -> MusicAction.None
            MusicOnStart.Resume -> MusicAction.Resume
            MusicOnStart.PlaySearch -> MusicAction.PlaySearch
        }
        // A normal stop goes straight to Summary when all deltas are already stored (no Stopping phase).
        to == WorkoutPhase.Stopping && (from == WorkoutPhase.Active || from == WorkoutPhase.Paused) ||
            to == WorkoutPhase.Summary && (from == WorkoutPhase.Active || from == WorkoutPhase.Paused || from == WorkoutPhase.Syncing) ->
            if (pauseOnStop) MusicAction.Pause else MusicAction.None
        else -> MusicAction.None
    }
}
