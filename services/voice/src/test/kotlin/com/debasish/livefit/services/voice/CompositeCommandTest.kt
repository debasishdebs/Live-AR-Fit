package com.debasish.livefit.services.voice

import com.debasish.livefit.model.Command
import com.debasish.livefit.model.HudPage
import com.debasish.livefit.model.WorkoutType
import kotlin.test.Test
import kotlin.test.assertEquals

/** F5: several commands in one utterance, split on "and", "then", "and then" and commas. */
class CompositeCommandTest {
    private val pack = LanguageRegistry.forLocale("en-IN")!!

    private fun assertComposite(utterance: String, commands: List<Command>, notUnderstood: List<String> = emptyList()) =
        assertEquals(ParsedUtterance(commands, notUnderstood), pack.parseUtterance(utterance), "utterance: \"$utterance\"")

    @Test fun pauseMusicAndStopWorkout() = assertComposite("Pause music and stop workout", listOf(Command.PauseMusic, Command.StopWorkout))

    @Test fun orderIsKept() = assertComposite("stop the workout and pause the music", listOf(Command.StopWorkout, Command.PauseMusic))

    @Test fun thenAndThenAndCommas() {
        assertComposite("start a run then play music", listOf(Command.StartWorkout(WorkoutType.Run), Command.PlayMusic))
        assertComposite("next song and then like it", listOf(Command.NextTrack, Command.LikeTrack))
        assertComposite("Pause the workout, pause music.", listOf(Command.PauseWorkout, Command.PauseMusic))
        assertComposite("resume workout, then volume up and next song", listOf(Command.ResumeWorkout, Command.Volume(up = true), Command.NextTrack))
    }

    /** P2: page views combine with other commands. */
    @Test fun pageViewsCombine() {
        assertComposite("pause music and playlist view", listOf(Command.PauseMusic, Command.ShowGlassesPage(HudPage.Playlist)))
        assertComposite("start a run, then glance view", listOf(Command.StartWorkout(WorkoutType.Run), Command.ShowGlassesPage(HudPage.Glance)))
    }

    @Test fun singleCommandsAndFillerParseAsBefore() {
        assertComposite("start workout", listOf(Command.StartWorkout(WorkoutType.Walk)))
        assertComposite("hey, start the workout please", listOf(Command.StartWorkout(WorkoutType.Walk)))
        assertComposite("okay, next song, please", listOf(Command.NextTrack))
        assertComposite("android and", emptyList(), listOf("android"))
    }

    @Test fun unparsedClausesAreReportedAndTheRestStillRuns() {
        assertComposite("pause music and order a pizza", listOf(Command.PauseMusic), listOf("order a pizza"))
        assertComposite("blah blah, then skip", listOf(Command.NextTrack), listOf("blah blah"))
    }

    @Test fun nothingUnderstood() = assertComposite("what is the weather", emptyList(), listOf("what is the weather"))

    @Test fun conjunctionsInsideWordsDoNotSplit() {
        // "band", "thence", "android" contain the split words but are not conjunctions.
        assertEquals(listOf("play band music"), EnglishClauses.split("play band music"))
        assertEquals(listOf("pause music", "stop workout"), EnglishClauses.split("pause music AND stop workout"))
    }
}
