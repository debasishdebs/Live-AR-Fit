package com.debasish.livefit.services.voice

import com.debasish.livefit.model.Command
import com.debasish.livefit.model.HudPage
import com.debasish.livefit.model.WorkoutType
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** P3: Settings → Voice → Voice commands. One toggle per group; yes/no answers are always on. */
class VoiceCommandGroupTest {

    @Test fun everyParsedCommandHasAGroup() {
        val utterances = listOf("start workout", "start a run", "pause", "resume", "stop workout", "play music", "pause music",
            "next song", "previous song", "volume up", "quieter", "like this song", "glance view", "workout view", "playlist view")
        val expected = listOf(VoiceCommandGroup.StartWorkout, VoiceCommandGroup.StartWorkout, VoiceCommandGroup.PauseResume,
            VoiceCommandGroup.PauseResume, VoiceCommandGroup.StopWorkout, VoiceCommandGroup.MusicPlayPause, VoiceCommandGroup.MusicPlayPause,
            VoiceCommandGroup.NextPrevious, VoiceCommandGroup.NextPrevious, VoiceCommandGroup.Volume, VoiceCommandGroup.Volume,
            VoiceCommandGroup.Like, VoiceCommandGroup.PageViews, VoiceCommandGroup.PageViews, VoiceCommandGroup.PageViews)
        assertEquals(expected, utterances.map { VoiceCommandGroup.of(CommandParser.parse(it)!!) })
        assertEquals(VoiceCommandGroup.YesNo, VoiceCommandGroup.of(Command.Answer("c", yes = true)))
    }

    @Test fun touchOnlyCommandsHaveNoGroup() {
        for (c in listOf(Command.DismissSummary, Command.PlayPause, Command.SetVolume(0.3f), Command.PlayQueueItem(1))) assertNull(VoiceCommandGroup.of(c), "$c")
    }

    @Test fun listOrderAndOnlyYesNoIsLocked() {
        assertEquals(listOf("StartWorkout", "PauseResume", "StopWorkout", "MusicPlayPause", "NextPrevious", "Volume", "Like", "PageViews", "YesNo"),
            VoiceCommandGroup.entries.map { it.name })
        assertEquals(listOf(VoiceCommandGroup.YesNo), VoiceCommandGroup.entries.filterNot { it.toggleable })
    }

    @Test fun disabledGroupsBlockTheirCommandsOnly() {
        val off = setOf(VoiceCommandGroup.NextPrevious, VoiceCommandGroup.PageViews)
        assertFalse(VoiceCommandGroup.isAllowed(Command.NextTrack, off))
        assertFalse(VoiceCommandGroup.isAllowed(Command.ShowGlassesPage(HudPage.Glance), off))
        assertTrue(VoiceCommandGroup.isAllowed(Command.PauseMusic, off))
        assertTrue(VoiceCommandGroup.isAllowed(Command.StartWorkout(WorkoutType.Run), emptySet()), "default: all on")
    }

    @Test fun yesNoCannotBeTurnedOff() {
        assertTrue(VoiceCommandGroup.isAllowed(Command.Answer("c", yes = false), VoiceCommandGroup.entries.toSet()))
        assertEquals(emptySet(), VoiceCommandGroup.decode(VoiceCommandGroup.encode(setOf(VoiceCommandGroup.YesNo))), "never stored as off")
    }

    @Test fun disabledSetPersistsAndIgnoresUnknownNames() {
        val off = setOf(VoiceCommandGroup.Volume, VoiceCommandGroup.Like)
        assertEquals(off, VoiceCommandGroup.decode(VoiceCommandGroup.encode(off)))
        assertEquals(emptySet(), VoiceCommandGroup.decode(""))
        assertEquals(setOf(VoiceCommandGroup.Like), VoiceCommandGroup.decode("Like,Gone, ,YesNo"))
    }

    @Test fun togglingUpdatesTheDisabledSetButNeverLocksOutYesNo() {
        val off = VoiceCommandGroup.withEnabled(emptySet(), VoiceCommandGroup.Volume, enabled = false)
        assertEquals(setOf(VoiceCommandGroup.Volume), off)
        assertEquals(emptySet(), VoiceCommandGroup.withEnabled(off, VoiceCommandGroup.Volume, enabled = true))
        assertEquals(off, VoiceCommandGroup.withEnabled(off, VoiceCommandGroup.YesNo, enabled = false))
    }

    @Test fun blockedMessageNamesTheCommand() =
        assertEquals("'Next / previous song' is turned off in Settings", VoiceCommandGroup.NextPrevious.blockedMessage)

    @Test fun gateRunsAllowedCommandsAndToastsBlockedOnes() = runTest {
        var off = setOf(VoiceCommandGroup.MusicPlayPause)
        val ran = mutableListOf<Command>()
        val toasts = mutableListOf<String>()
        val gate = VoiceCommandGate(disabled = { off }, toast = { toasts += it }, dispatch = { ran += it })
        gate(Command.PauseMusic)
        gate(Command.ShowGlassesPage(HudPage.Playlist))
        assertEquals(listOf<Command>(Command.ShowGlassesPage(HudPage.Playlist)), ran)
        assertEquals(listOf("'Play / pause music' is turned off in Settings"), toasts)
        off = emptySet() // read on every command, so a settings change applies at once
        gate(Command.PauseMusic)
        assertEquals(Command.PauseMusic, ran.last())
    }
}
