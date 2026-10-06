package com.debasish.livefit.services.voice

import com.debasish.livefit.model.Command
import com.debasish.livefit.model.WorkoutType
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

/**
 * Utterances as the speech recognizer returns them: mixed case, punctuation, filler words,
 * and ASR splits like "work out". Each group covers one command.
 */
class CommandParserTest {

    private fun assertParses(expected: Command?, vararg utterances: String) {
        for (u in utterances) assertEquals(expected, CommandParser.parse(u), "utterance: \"$u\"")
    }

    // --- Start workout ---------------------------------------------------------------

    @Test fun startWorkoutDefaultsToWalk() = assertParses(
        Command.StartWorkout(WorkoutType.Walk),
        "start workout", "Start workout.", "Start work out", "start my workout", "begin workout",
        "start exercise", "hey, start the workout please", "start a walk", "start walking", "begin walking",
        "start work", // seen on device: the recognizer clipped "start workout"
    )

    @Test fun startRun() = assertParses(
        Command.StartWorkout(WorkoutType.Run),
        "start a run", "start running", "begin run", "start my run", "Start run workout",
    )

    @Test fun startCycle() = assertParses(
        Command.StartWorkout(WorkoutType.Cycle),
        "start cycling", "start a bike ride", "start ride", "begin cycle workout",
    )

    @Test fun startAuto() = assertParses(
        Command.StartWorkout(WorkoutType.Auto),
        "start auto workout", "start auto", "begin auto mode",
    )

    // --- End / pause / resume workout ------------------------------------------------

    @Test fun stopWorkout() = assertParses(
        Command.StopWorkout,
        "stop workout", "end workout", "finish workout", "End the workout.", "stop my run",
        "finish my walk", "end ride", "stop work out", "stop exercise",
    )

    @Test fun pauseWorkout() = assertParses(
        Command.PauseWorkout,
        "pause workout", "pause the workout", "pause my run", "pause",
    )

    @Test fun resumeWorkout() = assertParses(
        Command.ResumeWorkout,
        "resume workout", "resume", "continue workout", "continue my run", "resume the walk",
    )

    // --- Music -----------------------------------------------------------------------

    @Test fun nextSong() = assertParses(
        Command.NextTrack,
        "next song", "Next song.", "next", "skip", "skip this song", "next track", "play next song", "skip track",
    )

    @Test fun previousSong() = assertParses(
        Command.PreviousTrack,
        "previous song", "previous", "previous track", "last song", "go back a song", "play previous song", "back to last track",
    )

    @Test fun likeSong() = assertParses(
        Command.LikeTrack,
        "like", "like this song", "like song", "I love this song", "love it", "add to liked songs",
    )

    @Test fun playMusic() = assertParses(
        Command.PlayMusic,
        "play", "play music", "Play the music", "resume music", "resume song", "start music", "continue music", "unpause music",
    )

    @Test fun pauseMusic() = assertParses(
        Command.PauseMusic,
        "pause music", "pause the song", "pause song", "stop music", "stop the song", "mute music",
    )

    @Test fun volumeUp() = assertParses(
        Command.Volume(up = true),
        "volume up", "louder", "turn it up", "turn the volume up", "increase volume",
    )

    @Test fun volumeDown() = assertParses(
        Command.Volume(up = false),
        "volume down", "quieter", "turn it down", "lower the volume", "decrease volume",
    )

    // --- Disambiguation ----------------------------------------------------------------

    /** "pause"/"stop"/"resume" with a music word must never touch the workout. */
    @Test fun musicWordsWinOverWorkoutVerbs() {
        assertParses(Command.PauseMusic, "pause music")
        assertParses(Command.PlayMusic, "resume music")
        assertParses(Command.PauseMusic, "stop music")
    }

    /** "start/play" + song words is music, not a workout. */
    @Test fun startMusicIsNotAWorkout() = assertParses(Command.PlayMusic, "start music", "start the song")

    /** Bare "stop" is too risky to guess (ending a workout loses nothing but is surprising). */
    @Test fun bareStopIsIgnored() = assertParses(null, "stop", "stop it")

    // --- Not commands ------------------------------------------------------------------

    @Test fun unrelatedSpeechIsIgnored() = assertParses(
        null,
        "what's the weather", "how far have I gone", "", "   ", "hello", "the workout was great yesterday",
    )
}
