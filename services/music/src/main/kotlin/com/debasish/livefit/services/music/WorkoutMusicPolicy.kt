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

/**
 * How a search is played (D5, B2): through YouTube Music's media session without UI; with no session, its activity
 * only while LiveFit is in front (Android blocks background activity starts — the phone-in-pocket case), otherwise
 * headless: wake YouTube Music's media service and wait for its session.
 */
enum class SearchRoute {
    Session,
    /** The activity takes the foreground: bring LiveFit back afterwards because it was in front. */
    ActivityThenReturn,
    /** No UI: bind YouTube Music's media browser service and send it a media-button PLAY, then use the new session. */
    Headless;

    companion object {
        fun of(hasSession: Boolean, appInForeground: Boolean): SearchRoute = when {
            hasSession -> Session
            appInForeground -> ActivityThenReturn
            else -> Headless
        }
    }
}

/** B2: what a headless start does once YouTube Music's session is up. */
sealed interface HeadlessStep {
    data class PlayFromSearch(val query: String) : HeadlessStep
    data object Play : HeadlessStep
    data object Done : HeadlessStep

    companion object {
        /** [query]: the search to play, or null to resume (the media-button PLAY normally already did). */
        fun onSession(query: String?, isPlaying: Boolean): HeadlessStep = when {
            query != null -> PlayFromSearch(query)
            !isPlaying -> Play
            else -> Done
        }
    }
}
