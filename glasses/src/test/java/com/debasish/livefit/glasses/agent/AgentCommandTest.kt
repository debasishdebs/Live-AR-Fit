package com.debasish.livefit.glasses.agent

import com.debasish.livefit.model.Command
import com.debasish.livefit.model.HudPage
import com.debasish.livefit.model.PageSettings
import com.debasish.livefit.model.WorkoutType
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class AgentCommandTest {
    private fun get(target: String) = "GET $target HTTP/1.1\r\nHost: 127.0.0.1:47123\r\n\r\n"

    @Test fun everyCommandParses() {
        AgentCommand.entries.forEach { c ->
            assertEquals(AgentRequest.Run(c), AgentRequestParser.parse(get("/lf?cmd=${c.wire}")), c.wire)
        }
        assertEquals(
            listOf("start", "pause", "resume", "stop", "play_pause", "next", "previous", "stats", "map", "music", "playlist", "glance", "workout"),
            AgentCommand.entries.map { it.wire },
        )
    }

    @Test fun versionAndOtherParamsAreIgnored() {
        assertEquals(AgentRequest.Run(AgentCommand.Pause), AgentRequestParser.parse(get("/lf?cmd=pause&v=1")))
        assertEquals(AgentRequest.Run(AgentCommand.Pause), AgentRequestParser.parse(get("/lf?v=1&cmd=pause&t=123")))
    }

    @Test fun caseWhitespaceAndSeparatorsAreForgiven() {
        assertEquals(AgentRequest.Run(AgentCommand.Pause), AgentRequestParser.parse(get("/lf?cmd=%20PAUSE%20")))
        assertEquals(AgentRequest.Run(AgentCommand.PlayPause), AgentRequestParser.parse(get("/lf?cmd=Play+Pause")))
        assertEquals(AgentRequest.Run(AgentCommand.PlayPause), AgentRequestParser.parse(get("/lf?cmd=play-pause")))
        assertEquals(AgentRequest.Run(AgentCommand.Next), AgentRequestParser.parse("GET /lf?cmd=next HTTP/1.0\n\n"))
    }

    @Test fun unknownOrMissingCommandIsUnknown() {
        assertEquals(AgentRequest.Unknown, AgentRequestParser.parse(get("/lf?cmd=dance")))
        assertEquals(AgentRequest.Unknown, AgentRequestParser.parse(get("/lf?cmd=")))
        assertEquals(AgentRequest.Unknown, AgentRequestParser.parse(get("/lf")))
        assertEquals(AgentRequest.Unknown, AgentRequestParser.parse(get("/lf?v=1")))
        assertEquals(AgentRequest.Unknown, AgentRequestParser.parse(get("/lf?cmd=%ZZ")))
    }

    @Test fun otherPathsMethodsAndGarbage() {
        assertEquals(AgentRequest.NotFound, AgentRequestParser.parse(get("/other?cmd=pause")))
        assertEquals(AgentRequest.NotFound, AgentRequestParser.parse(get("/lf/x?cmd=pause")))
        assertEquals(AgentRequest.BadMethod, AgentRequestParser.parse("POST /lf?cmd=pause HTTP/1.1\r\n\r\n"))
        assertEquals(AgentRequest.Malformed, AgentRequestParser.parse(""))
        assertEquals(AgentRequest.Malformed, AgentRequestParser.parse("hello\r\n\r\n"))
        assertEquals(AgentRequest.Malformed, AgentRequestParser.parse("GET /lf?cmd=pause\r\n\r\n"))
        assertEquals(AgentRequest.Malformed, AgentRequestParser.parse("\u0000\u0001\u0002 garbage"))
    }

    @Test fun oversizeIsTooLarge() {
        val big = get("/lf?cmd=pause&pad=" + "x".repeat(AgentRequestParser.MAX_REQUEST_BYTES))
        assertEquals(AgentRequest.TooLarge, AgentRequestParser.parse(big))
    }

    @Test fun commandsMapToTheTouchpadAndVoiceCommands() {
        assertEquals(
            mapOf(
                AgentCommand.Start to Command.StartWorkout(WorkoutType.Walk), // like voice "start workout"
                AgentCommand.Pause to Command.PauseWorkout,
                AgentCommand.Resume to Command.ResumeWorkout,
                AgentCommand.Stop to Command.StopWorkout,
                AgentCommand.PlayPause to Command.PlayPause,
                AgentCommand.Next to Command.NextTrack,
                AgentCommand.Previous to Command.PreviousTrack,
                AgentCommand.Stats to Command.ShowGlassesPage(HudPage.Stats),
                AgentCommand.Map to Command.ShowGlassesPage(HudPage.Map),
                AgentCommand.Music to Command.ShowGlassesPage(HudPage.MusicControls),
                AgentCommand.Playlist to Command.ShowGlassesPage(HudPage.Playlist),
                AgentCommand.Glance to Command.ShowGlassesPage(HudPage.Glance),
                AgentCommand.Workout to Command.ShowGlassesPage(HudPage.Workout),
            ),
            AgentCommand.entries.associateWith { it.command },
        )
    }

    private val live = AgentContext(connected = true, pages = PageSettings(), mapEligible = true)

    @Test fun connectedRepliesArePastTenseAcksAndSendTheCommand() {
        val says = AgentCommand.entries.associateWith { AgentReplies.plan(it, live) }
        says.forEach { (c, r) ->
            assertTrue(r.ok, c.wire)
            assertEquals(c.command, r.send, c.wire)
            assertTrue(r.say.isNotBlank() && r.say.length <= 40, c.wire)
        }
        assertEquals("Workout paused", says.getValue(AgentCommand.Pause).say)
        assertEquals("Next song", says.getValue(AgentCommand.Next).say)
        assertEquals("Showing the map", says.getValue(AgentCommand.Map).say)
    }

    @Test fun phoneNotConnectedSendsNothing() {
        AgentCommand.entries.forEach {
            val r = AgentReplies.plan(it, live.copy(connected = false))
            assertFalse(r.ok)
            assertNull(r.send)
            assertEquals("Your phone isn't connected", r.say)
        }
    }

    /** The hub's page gate still applies (it toasts); the agent's card explains instead of claiming the page opened. */
    @Test fun disabledPageIsStillSentButExplained() {
        val r = AgentReplies.plan(AgentCommand.Playlist, live.copy(pages = PageSettings(disabled = setOf(HudPage.Playlist))))
        assertFalse(r.ok)
        assertEquals(Command.ShowGlassesPage(HudPage.Playlist), r.send)
        assertEquals("Playlist page is turned off in Settings", r.say)
    }

    @Test fun mapOutsideAGpsWorkoutIsExplained() {
        val r = AgentReplies.plan(AgentCommand.Map, live.copy(mapEligible = false))
        assertFalse(r.ok)
        assertEquals("The map shows during a GPS workout", r.say)
    }

    @Test fun stopAsksForConfirmation() {
        val r = AgentReplies.plan(AgentCommand.Stop, live)
        assertEquals(Command.StopWorkout, r.send)
        assertTrue("confirm" in r.say.lowercase())
    }

    @Test fun jsonBodyIsEscaped() {
        assertEquals("""{"ok":true,"say":"a \"b\" \\ c"}""", AgentJson.reply(true, "a \"b\" \\ c"))
    }
}
