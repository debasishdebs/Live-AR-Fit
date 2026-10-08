package com.debasish.livefit.services.voice

import com.debasish.livefit.model.Command
import com.debasish.livefit.model.HudPage
import com.debasish.livefit.model.WorkoutType

/**
 * Maps a recognised utterance to a [Command]. Shared by the fake and live voice services.
 *
 * Rules are checked in order, most specific first, so a music word ("pause music") never
 * reaches the workout rules and "play next song" is a skip, not play. Page views ("playlist view", "show glance") come
 * first but only without an action verb, so "play my playlist" stays play and "start workout mode" still starts.
 */
object CommandParser {
    fun parse(utterance: String): Command? {
        val t = utterance.lowercase().replace(Regex("[^a-z ]"), " ").replace(Regex("\\s+"), " ").trim()
        if (t.isEmpty()) return null
        fun has(vararg words: String) = words.any { Regex("\\b$it\\b").containsMatchIn(t) }

        val music = has("music", "song", "songs", "track", "tune", "playlist")
        val workout = has("workout", "work out", "work", // "work": recognizers clip "start workout" to "start work"
            "exercise", "run", "running", "walk", "walking", "ride", "cycle", "cycling", "bike", "auto")
        val down = has("down", "quieter", "lower", "decrease")

        return when {
            // Glasses pages (P2): a page word with a view word ("glance" and "playlist" also alone), and no action verb.
            !has(*ACTION_VERBS) && pageOf(::has) != null -> Command.ShowGlassesPage(pageOf(::has)!!)

            // Volume
            has("volume", "louder", "quieter") || (has("turn") && has("up", "down")) ->
                Command.Volume(up = !down)

            // Track actions
            has("like", "love", "liked") -> Command.LikeTrack
            has("next", "skip") -> Command.NextTrack
            has("previous") || (has("last") && music) || (has("back") && (music || has("go"))) -> Command.PreviousTrack

            // Explicit music play / pause
            music && has("pause", "stop", "mute") -> Command.PauseMusic
            music && has("play", "resume", "start", "continue", "unpause") -> Command.PlayMusic
            has("play", "unpause") -> Command.PlayMusic

            // Workout
            has("start", "begin") && workout -> Command.StartWorkout(typeOf(::has))
            has("stop", "end", "finish") && workout -> Command.StopWorkout
            has("resume", "continue") -> Command.ResumeWorkout
            has("pause") -> Command.PauseWorkout

            else -> null
        }
    }

    private val ACTION_VERBS = arrayOf("start", "begin", "stop", "end", "finish", "pause", "resume", "continue", "play", "unpause",
        "mute", "next", "skip", "previous", "back", "last", "like", "love", "liked", "volume", "louder", "quieter", "turn")

    private fun pageOf(has: (Array<out String>) -> Boolean): HudPage? {
        fun any(vararg w: String) = has(w)
        val view = any("view", "screen", "page", "mode", "show", "open", "display", "switch", "go")
        return when {
            any("glance") -> HudPage.Glance
            any("playlist", "queue") || (view && any("music", "song", "songs", "track", "tracks")) -> HudPage.Playlist
            view && any("workout", "work out", "stats", "metrics") -> HudPage.Workout
            else -> null
        }
    }

    private fun typeOf(has: (Array<out String>) -> Boolean): WorkoutType {
        fun any(vararg w: String) = has(w)
        return when {
            any("run", "running") -> WorkoutType.Run
            any("cycle", "cycling", "ride", "bike") -> WorkoutType.Cycle
            any("auto") -> WorkoutType.Auto
            else -> WorkoutType.Walk
        }
    }
}
