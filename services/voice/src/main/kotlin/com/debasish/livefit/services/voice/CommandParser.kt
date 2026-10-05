package com.debasish.livefit.services.voice

import com.debasish.livefit.model.Command
import com.debasish.livefit.model.WorkoutType

/**
 * Maps a recognised utterance to a [Command]. Shared by the fake and live voice services.
 *
 * Rules are checked in order, most specific first, so a music word ("pause music") never
 * reaches the workout rules and "play next song" is a skip, not play.
 */
object CommandParser {
    fun parse(utterance: String): Command? {
        val t = utterance.lowercase().replace(Regex("[^a-z ]"), " ").replace(Regex("\\s+"), " ").trim()
        if (t.isEmpty()) return null
        fun has(vararg words: String) = words.any { Regex("\\b$it\\b").containsMatchIn(t) }

        val music = has("music", "song", "songs", "track", "tune")
        val workout = has("workout", "work out", "exercise", "run", "running", "walk", "walking", "ride", "cycle", "cycling", "bike", "auto")
        val down = has("down", "quieter", "lower", "decrease")

        return when {
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
