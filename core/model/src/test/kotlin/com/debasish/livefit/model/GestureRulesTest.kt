package com.debasish.livefit.model

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertTrue

class GestureRulesTest {
    private val d = GestureSettings()
    private fun applied(c: GestureChange) = assertIs<GestureChange.Applied>(c).settings

    @Test fun everyDefaultTableIsSafe() = assertEquals(emptyList(), GestureRules.problems(d))

    /** Spec §4.4: every page needs Close app … */
    @Test fun refusesRemovingTheOnlyCloseApp() = assertEquals(
        GestureChange.Refused("Glance needs a gesture for Close app"),
        GestureRules.change(d, GestureMode.Page, HudPage.Glance, Gesture.DoubleTap, GestureAction.Talk),
    )

    /** … and Next page or Previous page. */
    @Test fun refusesRemovingBothPageMoves() {
        val a = applied(GestureRules.change(d, GestureMode.Page, HudPage.Workout, Gesture.ShortForward, GestureAction.Talk))
        assertEquals(
            GestureChange.Refused("Workout needs a gesture for Next or Previous page"),
            GestureRules.change(a, GestureMode.Page, HudPage.Workout, Gesture.ShortBack, GestureAction.Talk),
        )
        assertIs<GestureChange.Refused>(
            GestureRules.change(a, GestureMode.Page, HudPage.Workout, Gesture.ShortBack, GestureAction.NextPage2), "±2 pages is not Next/Previous page",
        )
    }

    @Test fun safetyIsCheckedForDisabledPagesToo() =
        assertIs<GestureChange.Refused>(GestureRules.change(d, GestureMode.Page, HudPage.Map, Gesture.DoubleTap, GestureAction.None))

    @Test fun closeAppCanMoveToAnotherGestureFirst() {
        val s1 = applied(GestureRules.change(d, GestureMode.Page, HudPage.Glance, Gesture.Tap, GestureAction.CloseApp))
        val s2 = applied(GestureRules.change(s1, GestureMode.Page, HudPage.Glance, Gesture.DoubleTap, GestureAction.Talk))
        val t = GestureRules.table(s2, GestureMode.Page, HudPage.Glance)
        assertEquals(GestureAction.CloseApp, t[Gesture.Tap])
        assertEquals(GestureAction.Talk, t[Gesture.DoubleTap])
        assertEquals(GestureRules.table(d, GestureMode.Page, HudPage.Workout), GestureRules.table(s2, GestureMode.Page, HudPage.Workout), "other pages untouched")
    }

    @Test fun catalogueDependsOnContext() {
        assertTrue(GestureAction.EnterScroll in GestureRules.validActions(GestureMode.Page, HudPage.Playlist))
        assertFalse(GestureAction.EnterScroll in GestureRules.validActions(GestureMode.Page, HudPage.Glance))
        assertTrue(GestureAction.PlayHighlighted in GestureRules.validActions(GestureMode.Scroll, HudPage.Playlist))
        assertFalse(GestureAction.PlayHighlighted in GestureRules.validActions(GestureMode.Scroll, HudPage.MusicControls))
        assertTrue(GestureAction.PressSelected in GestureRules.validActions(GestureMode.Scroll, HudPage.MusicControls))
        assertTrue(GestureAction.ExitScroll in GestureRules.validActions(GestureMode.Scroll, HudPage.MusicControls))
        assertFalse(GestureAction.ExitScroll in GestureRules.validActions(GestureMode.Page, HudPage.MusicControls))
        for (p in HudPage.entries) assertTrue(GestureAction.Talk in GestureRules.validActions(GestureMode.Page, p))
        assertTrue(GestureRules.validActions(GestureMode.Scroll, HudPage.Stats).isEmpty())
    }

    @Test fun actionsInvalidForTheContextAreRefused() {
        assertEquals(
            GestureChange.Refused("Play highlighted isn't available on Glance"),
            GestureRules.change(d, GestureMode.Page, HudPage.Glance, Gesture.Tap, GestureAction.PlayHighlighted),
        )
        assertEquals(GestureChange.Refused("Stats has no scroll mode"), GestureRules.change(d, GestureMode.Scroll, HudPage.Stats, Gesture.Tap, GestureAction.Talk))
    }

    /** Spec §4.4: in scroll mode the ✕ Back item and the idle timeout still exit, so no Exit scroll gesture is needed. */
    @Test fun scrollWithoutExitScrollIsAllowed() {
        val s = applied(GestureRules.change(d, GestureMode.Scroll, HudPage.Playlist, Gesture.DoubleTap, GestureAction.NextSong))
        assertFalse(GestureAction.ExitScroll in GestureRules.table(s, GestureMode.Scroll, HudPage.Playlist).values)
    }

    /** Review Focus #4: a broken page keeps its last valid table; valid pages of the same frame still apply. */
    @Test fun brokenTableKeepsLastValidPerPage() {
        val lastValid = applied(GestureRules.change(d, GestureMode.Page, HudPage.Glance, Gesture.Tap, GestureAction.NextSong))
        val brokenGlance = Gesture.entries.associateWith { GestureAction.Talk }
        val customWorkout = GestureRules.table(d, GestureMode.Page, HudPage.Workout) + (Gesture.Tap to GestureAction.LikeSong)
        val received = GestureSettings(page = d.page + (HudPage.Glance to brokenGlance) + (HudPage.Workout to customWorkout))
        val s = GestureRules.sanitized(received, lastValid)
        assertEquals(GestureRules.table(lastValid, GestureMode.Page, HudPage.Glance), s.page.getValue(HudPage.Glance))
        assertEquals(customWorkout, s.page.getValue(HudPage.Workout))
        assertEquals(emptyList(), GestureRules.problems(s))
    }

    @Test fun brokenTableWithoutLastValidUsesDefaults() {
        val received = GestureSettings(page = d.page + (HudPage.Stats to Gesture.entries.associateWith { GestureAction.None }))
        assertEquals(GestureDefaults.pageTable(HudPage.Stats), GestureRules.sanitized(received).page.getValue(HudPage.Stats))
    }

    @Test fun invalidScrollActionFallsBack() {
        val received = GestureSettings(scroll = d.scroll + (HudPage.MusicControls to mapOf(Gesture.Tap to GestureAction.PlayHighlighted)))
        assertEquals(GestureDefaults.scrollTable(HudPage.MusicControls), GestureRules.sanitized(received).scroll.getValue(HudPage.MusicControls))
    }

    @Test fun idleTimeoutIsClamped() {
        assertEquals(3, GestureRules.sanitized(GestureSettings(idleTimeoutS = 1)).idleTimeoutS)
        assertEquals(15, GestureRules.sanitized(GestureSettings(idleTimeoutS = 20)).idleTimeoutS)
        assertEquals(9, GestureRules.withIdleTimeout(d, 9).idleTimeoutS)
        assertEquals(15, GestureRules.withIdleTimeout(d, 99).idleTimeoutS)
    }

    @Test fun missingGesturesAreFilledFromDefaults() {
        val s = GestureSettings(page = mapOf(HudPage.Glance to mapOf(Gesture.Tap to GestureAction.NextSong)))
        val t = GestureRules.table(s, GestureMode.Page, HudPage.Glance)
        assertEquals(GestureAction.NextSong, t[Gesture.Tap])
        assertEquals(GestureAction.CloseApp, t[Gesture.DoubleTap])
        assertEquals(GestureDefaults.pageTable(HudPage.Workout), GestureRules.table(s, GestureMode.Page, HudPage.Workout))
        assertEquals(6, t.size)
    }
}
