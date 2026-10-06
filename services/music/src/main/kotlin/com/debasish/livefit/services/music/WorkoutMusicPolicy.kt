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

/** Query for play() when no YouTube Music session exists: the saved search, or null (just open) when blank. */
fun resumeQuery(saved: String?): String? = saved?.trim()?.takeIf { it.isNotEmpty() }

/** How a search is played (D5): through YouTube Music's media session without UI, else via its activity. */
enum class SearchRoute {
    Session, Activity,
    /** The activity takes the foreground: bring LiveFit back afterwards because it was in front. */
    ActivityThenReturn;

    companion object {
        fun of(hasSession: Boolean, appInForeground: Boolean): SearchRoute = when {
            hasSession -> Session
            appInForeground -> ActivityThenReturn
            else -> Activity
        }
    }
}
