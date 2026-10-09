package com.debasish.livefit.glasses.hud

import com.debasish.livefit.model.Command
import com.debasish.livefit.model.Gesture
import com.debasish.livefit.model.GestureAction
import com.debasish.livefit.model.GestureChange
import com.debasish.livefit.model.GestureMode
import com.debasish.livefit.model.GestureRules
import com.debasish.livefit.model.GestureSettings
import com.debasish.livefit.model.HudPage
import com.debasish.livefit.model.PageSet
import com.debasish.livefit.model.PageSettings
import com.debasish.livefit.model.QueueItem
import com.debasish.livefit.model.QueueWindow
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue

class HudNavTest {
    private val q = QueueWindow((10L..14L).map { QueueItem(it, "Song $it") }, currentIndex = 2) // current = 12, Back row = 5
    private val all = PageSet.available(PageSettings(), mapEligible = true)
    private fun ctx(available: List<HudPage> = all, gestures: GestureSettings = GestureSettings(), queue: QueueWindow = q) = NavContext(queue, gestures, available)
    private fun HudNav.g(gesture: Gesture, c: NavContext = ctx(), now: Long = 0) = onGesture(gesture, c, now)
    private fun scrollOn(page: HudPage, now: Long = 0, c: NavContext = ctx()) = HudNav(page = page).g(Gesture.Tap, c, now).nav

    @Test fun startsOnWorkoutInPageMode() {
        assertEquals(HudPage.Workout, HudNav().page)
        assertEquals(GestureMode.Page, HudNav().mode)
    }

    @Test fun swipesMapToGestures() {
        assertEquals(Gesture.ShortForward, Swipe(forward = true, long = false).gesture())
        assertEquals(Gesture.ShortBack, Swipe(forward = false, long = false).gesture())
        assertEquals(Gesture.LongForward, Swipe(forward = true, long = true).gesture())
        assertEquals(Gesture.LongBack, Swipe(forward = false, long = true).gesture())
    }

    /** Spec §4.3: short swipe = next/previous page, long = ±2, cycling Glance → … → Music controls. */
    @Test fun defaultSwipesMovePagesInCycleOrder() {
        assertEquals(HudPage.Stats, HudNav().g(Gesture.ShortForward).nav.page)
        assertEquals(HudPage.Playlist, HudNav().g(Gesture.LongForward).nav.page)
        assertEquals(HudPage.MusicControls, HudNav(page = HudPage.Glance).g(Gesture.ShortBack).nav.page)
        assertEquals(HudPage.Workout, HudNav(page = HudPage.MusicControls).g(Gesture.LongForward).nav.page)
    }

    @Test fun disabledAndIneligiblePagesAreSkipped() {
        val c = ctx(available = PageSet.available(PageSettings(disabled = setOf(HudPage.Stats)), mapEligible = false))
        assertEquals(HudPage.Playlist, HudNav().g(Gesture.ShortForward, c).nav.page)
        assertEquals(HudPage.MusicControls, HudNav(page = HudPage.Playlist).g(Gesture.ShortForward, c).nav.page)
    }

    @Test fun tapIsTalkOnGlanceWorkoutStatsAndMap() {
        for (p in listOf(HudPage.Glance, HudPage.Workout, HudPage.Stats, HudPage.Map)) {
            val o = HudNav(page = p).g(Gesture.Tap)
            assertTrue(o.talk, "$p"); assertNull(o.command); assertEquals(HudNav(page = p), o.nav)
        }
    }

    @Test fun doubleTapClosesOnEveryPageAndInScrollMode() {
        for (p in HudPage.entries) assertTrue(HudNav(page = p).g(Gesture.DoubleTap).close, "$p")
        assertTrue(scrollOn(HudPage.Playlist).g(Gesture.DoubleTap).close)
        assertTrue(scrollOn(HudPage.MusicControls).g(Gesture.DoubleTap).close)
    }

    @Test fun tapOnPlaylistEntersScrollWithTheHighlightOnTheCurrentSong() {
        val n = scrollOn(HudPage.Playlist, now = 1_000)
        assertEquals(GestureMode.Scroll, n.mode)
        assertEquals(2, n.highlightRow(q))
        assertEquals(1_000L, n.lastInputMs)
        assertNull(HudNav(page = HudPage.Playlist).highlightRow(q), "page mode shows no highlight")
    }

    @Test fun scrollSwipesMoveTheHighlightAndStopAtTheBackRow() {
        var n = scrollOn(HudPage.Playlist)
        n = n.g(Gesture.ShortForward).nav; assertEquals(3, n.highlightRow(q))
        n = n.g(Gesture.LongForward).nav; assertEquals(5, n.highlightRow(q), "✕ Back row after the songs")
        n = n.g(Gesture.ShortForward).nav; assertEquals(5, n.highlightRow(q), "stops at the end")
        n = n.g(Gesture.LongBack).nav; assertEquals(3, n.highlightRow(q))
        repeat(5) { n = n.g(Gesture.ShortBack).nav }; assertEquals(0, n.highlightRow(q))
    }

    /** Spec §3.3: scroll mode stays after Play highlighted. */
    @Test fun playHighlightedStaysInScroll() {
        val onCurrent = scrollOn(HudPage.Playlist).g(Gesture.Tap)
        assertEquals(Command.PlayPause, onCurrent.command)
        assertEquals(GestureMode.Scroll, onCurrent.nav.mode)
        val other = scrollOn(HudPage.Playlist).g(Gesture.ShortForward).nav.g(Gesture.Tap)
        assertEquals(Command.PlayQueueItem(13), other.command)
        assertEquals(GestureMode.Scroll, other.nav.mode)
        assertEquals(3, other.nav.highlightRow(q))
    }

    @Test fun theBackRowExitsScroll() {
        var n = scrollOn(HudPage.Playlist)
        repeat(3) { n = n.g(Gesture.ShortForward).nav }
        val o = n.g(Gesture.Tap)
        assertNull(o.command)
        assertEquals(HudNav(page = HudPage.Playlist), o.nav)
    }

    @Test fun emptyQueueOffersOnlyBack() {
        val c = ctx(queue = QueueWindow())
        val n = scrollOn(HudPage.Playlist, c = c)
        assertEquals(0, n.highlightRow(QueueWindow()))
        assertEquals(GestureMode.Page, n.g(Gesture.Tap, c).nav.mode)
    }

    @Test fun musicControlsSelectorCyclesAndPresses() {
        var n = scrollOn(HudPage.MusicControls)
        assertEquals(MusicControl.PlayPause, n.visibleSelector())
        assertEquals(Command.PlayPause, n.g(Gesture.Tap).command)
        n = n.g(Gesture.ShortForward).nav; assertEquals(MusicControl.Next, n.visibleSelector())
        val next = n.g(Gesture.Tap); assertEquals(Command.NextTrack, next.command); assertEquals(GestureMode.Scroll, next.nav.mode)
        n = n.g(Gesture.ShortForward).nav; assertEquals(MusicControl.Back, n.visibleSelector())
        assertEquals(MusicControl.Previous, n.g(Gesture.ShortForward).nav.visibleSelector(), "the selector wraps")
        assertEquals(Command.PreviousTrack, n.g(Gesture.ShortForward).nav.g(Gesture.Tap).command)
        assertEquals(HudNav(page = HudPage.MusicControls), n.g(Gesture.Tap).nav, "✕ leaves scroll mode")
        assertNull(HudNav(page = HudPage.MusicControls).visibleSelector())
    }

    @Test fun musicControlsLongSwipesChangeVolume() {
        val n = scrollOn(HudPage.MusicControls)
        assertEquals(Command.Volume(up = true), n.g(Gesture.LongForward).command)
        assertEquals(Command.Volume(up = false), n.g(Gesture.LongBack).command)
    }

    /** Spec §3.3: any actual page change clears scroll mode and the highlight. */
    @Test fun pageChangeClearsScrollAndHighlight() {
        val custom = assertIs<GestureChange.Applied>(GestureRules.change(GestureSettings(), GestureMode.Scroll, HudPage.Playlist, Gesture.LongForward, GestureAction.NextPage)).settings
        val c = ctx(gestures = custom)
        val moved = scrollOn(HudPage.Playlist, c = c).g(Gesture.ShortForward, c).nav
        assertEquals(HudNav(page = HudPage.Map), moved.g(Gesture.LongForward, c).nav)
    }

    /** Spec §3.3: the idle timer restarts on every gesture handled in scroll mode. */
    @Test fun idleTimeoutExitsScrollAndRestartsOnEveryScrollGesture() {
        val n = scrollOn(HudPage.Playlist, now = 0).g(Gesture.ShortForward, now = 4_000).nav
        assertEquals(GestureMode.Scroll, n.timedOut(8_999, idleMs = 5_000).mode)
        assertEquals(HudNav(page = HudPage.Playlist), n.timedOut(9_000, idleMs = 5_000))
        assertEquals(n, n.timedOut(4_500, 5_000), "not idle yet")
    }

    /** Spec §3.3: confirmations pause the timer; it restarts when they close. */
    @Test fun resumeIdleRestartsTheTimerAfterAConfirmation() {
        val n = scrollOn(HudPage.MusicControls, now = 0).resumeIdle(20_000)
        assertEquals(GestureMode.Scroll, n.timedOut(24_999, 5_000).mode)
        assertEquals(HudNav(page = HudPage.Workout), HudNav().resumeIdle(20_000), "no timer in page mode")
    }

    /** Review #9: a confirmation longer than the idle timeout; after it closes Scroll mode lasts the full resumed interval. */
    @Test fun confirmationLongerThanTheTimeoutResumesTheFullInterval() {
        var nav = scrollOn(HudPage.Playlist, now = 0)
        var gate = IdleGate()
        gate.onOverlay(true, nav, 1_000).let { (g, n) -> gate = g; nav = n }
        assertNull(gate.deadlineMs(nav, 5_000), "paused while the confirmation is up")
        assertEquals(GestureMode.Scroll, gate.tick(nav, 5_000, 19_999).mode)
        gate.onOverlay(false, nav, 20_000).let { (g, n) -> gate = g; nav = n }
        assertEquals(25_000L, gate.deadlineMs(nav, 5_000), "deadline computed after the resume")
        assertEquals(GestureMode.Scroll, gate.tick(nav, 5_000, 20_000).mode, "not dropped at the moment of dismissal")
        assertEquals(GestureMode.Scroll, gate.tick(nav, 5_000, 24_999).mode)
        assertEquals(HudNav(page = HudPage.Playlist), gate.tick(nav, 5_000, 25_000))
    }

    /** Review #9: the phone changes the idle timeout while the wearer scrolls; it applies without another gesture. */
    @Test fun changingTheTimeoutWhileScrollingAppliesWithoutAGesture() {
        val nav = scrollOn(HudPage.MusicControls, now = 0)
        val gate = IdleGate()
        assertEquals(GestureMode.Scroll, gate.tick(nav, 15_000, 6_000).mode)
        assertEquals(HudNav(page = HudPage.MusicControls), gate.tick(nav, 5_000, 6_000), "a shorter timeout has already elapsed")
        assertEquals(10_000L, gate.deadlineMs(nav, 10_000), "a longer one moves the deadline out")
        assertNull(IdleGate().deadlineMs(HudNav(page = HudPage.MusicControls), 5_000), "no timer in page mode")
    }

    /** Review Focus #5 / spec §3.3: the visible page disappears → Workout immediately, page mode, nothing highlighted. */
    @Test fun disablingVisibleScrollPageReturnsToWorkoutInPageMode() {
        val n = scrollOn(HudPage.Playlist).g(Gesture.ShortForward).nav
        assertEquals(HudNav(), n.reconcile(PageSet.available(PageSettings(disabled = setOf(HudPage.Playlist)), mapEligible = true)))
        assertEquals(HudNav(), HudNav(page = HudPage.Map).reconcile(PageSet.available(PageSettings(), mapEligible = false)), "GPS workout ended")
        assertEquals(n, n.reconcile(all), "still available: untouched")
    }

    /** Spec §4.4: a custom mapping changes what each gesture does. */
    @Test fun customMappingAppliedLive() {
        val s1 = assertIs<GestureChange.Applied>(GestureRules.change(GestureSettings(), GestureMode.Page, HudPage.Glance, Gesture.Tap, GestureAction.CloseApp)).settings
        val s2 = assertIs<GestureChange.Applied>(GestureRules.change(s1, GestureMode.Page, HudPage.Glance, Gesture.DoubleTap, GestureAction.NextSong)).settings
        val c = ctx(gestures = s2)
        assertTrue(HudNav(page = HudPage.Glance).g(Gesture.Tap, c).close)
        assertEquals(Command.NextTrack, HudNav(page = HudPage.Glance).g(Gesture.DoubleTap, c).command)
        assertTrue(HudNav(page = HudPage.Workout).g(Gesture.Tap, c).talk, "other pages keep their table")
    }

    @Test fun voiceShowGoesToAvailablePagesOnlyAndIsIdempotent() {
        val noMap = PageSet.available(PageSettings(), mapEligible = false)
        assertEquals(HudNav(), HudNav().show(HudPage.Map, noMap))
        assertEquals(HudNav(page = HudPage.Stats), HudNav().show(HudPage.Stats, noMap))
        val scrolling = scrollOn(HudPage.Playlist).g(Gesture.ShortForward).nav
        assertEquals(scrolling, scrolling.show(HudPage.Playlist, all), "same page again: nothing changes")
    }

    @Test fun visibleRowsKeepTheHighlightInView() {
        assertEquals(0 until 3, visibleRows(size = 3, highlight = 1, rows = 7))
        assertEquals(0 until 7, visibleRows(size = 25, highlight = 0, rows = 7))
        assertEquals(7 until 14, visibleRows(size = 25, highlight = 10, rows = 7))
        assertEquals(18 until 25, visibleRows(size = 25, highlight = 24, rows = 7))
        assertEquals(IntRange.EMPTY, visibleRows(size = 0, highlight = null, rows = 7))
    }
}
