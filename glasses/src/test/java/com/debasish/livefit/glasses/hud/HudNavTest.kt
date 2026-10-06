package com.debasish.livefit.glasses.hud

import com.debasish.livefit.model.Command
import com.debasish.livefit.model.QueueItem
import com.debasish.livefit.model.QueueWindow
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class HudNavTest {
    private val q = QueueWindow((10L..14L).map { QueueItem(it, "Song $it") }, currentIndex = 2) // current = 12
    private val music = HudNav(page = HudPage.Music)

    /** Music page in list mode, entered by a tap at [at]. */
    private fun list(at: Long = 0) = music.onTap(q, at).nav

    @Test fun backSwipeStillTogglesFullAndGlanceInAWorkout() {
        val glance = HudNav().onSwipe(forward = false, inWorkout = true, queue = q, nowMs = 0)
        assertEquals(HudNav(mode = HudMode.Glance), glance)
        assertEquals(HudNav(mode = HudMode.Full), glance.onSwipe(forward = false, inWorkout = true, queue = q, nowMs = 0))
    }

    @Test fun anySwipeLeavesGlanceForFull() =
        assertEquals(HudNav(mode = HudMode.Full), HudNav(mode = HudMode.Glance).onSwipe(forward = true, inWorkout = true, queue = q, nowMs = 0))

    @Test fun forwardSwipeOpensMusicFromFullAndFromReady() {
        assertEquals(music, HudNav().onSwipe(forward = true, inWorkout = true, queue = q, nowMs = 0))
        assertEquals(music, HudNav().onSwipe(forward = true, inWorkout = false, queue = q, nowMs = 0))
        assertEquals(HudNav(), HudNav().onSwipe(forward = false, inWorkout = false, queue = q, nowMs = 0), "back swipe without a workout does nothing")
    }

    @Test fun musicOpensInPageModeWithNoHighlight() {
        val m = HudNav().onSwipe(true, true, q, 0)
        assertFalse(m.listMode)
        assertNull(m.visibleHighlight(q), "no highlight until a tap")
    }

    @Test fun pageModeSwipesSwitchPages() {
        val glance = HudNav(page = HudPage.Music, mode = HudMode.Glance)
        assertEquals(HudNav(mode = HudMode.Glance), glance.onSwipe(forward = false, inWorkout = true, queue = q, nowMs = 0), "back = workout page, full/glance as it was")
        assertEquals(music, music.onSwipe(forward = true, inWorkout = true, queue = q, nowMs = 0), "forward on the last page stays")
        assertEquals(HudNav(), music.onSwipe(forward = false, inWorkout = false, queue = q, nowMs = 0), "also from Ready")
    }

    @Test fun tapOnWorkoutPageIsTalk() {
        val t = HudNav().onTap(q, 0)
        assertTrue(t.talk); assertNull(t.play); assertEquals(HudNav(), t.nav)
    }

    @Test fun tapOnMusicPageEntersListModeOnTheCurrentSong() {
        val t = music.onTap(q, 1_000)
        assertFalse(t.talk); assertNull(t.play, "the first tap only chooses")
        assertTrue(t.nav.listMode)
        assertEquals(2, t.nav.visibleHighlight(q))
    }

    @Test fun tapOnMusicPageWithAnEmptyQueueIsTalk() {
        val t = music.onTap(QueueWindow(), 0)
        assertTrue(t.talk); assertFalse(t.nav.listMode)
    }

    @Test fun listModeSwipesMoveTheHighlightAndStopAtTheEnds() {
        var m = list()
        m = m.onSwipe(true, true, q, 0); assertEquals(3, m.visibleHighlight(q))
        m = m.onSwipe(true, true, q, 0).onSwipe(true, true, q, 0); assertEquals(4, m.visibleHighlight(q), "no wrap past the last item")
        repeat(6) { m = m.onSwipe(false, true, q, 0) }
        assertEquals(0, m.visibleHighlight(q), "no wrap before the first")
        assertEquals(HudPage.Music, m.page, "list-mode swipes never leave the music page")
    }

    @Test fun listModeTapPlaysTheHighlightAndReturnsToPageMode() {
        val t = list().onSwipe(true, true, q, 0).onTap(q, 0)
        assertEquals(Command.PlayQueueItem(13), t.play)
        assertFalse(t.talk)
        assertEquals(music, t.nav)
    }

    @Test fun listModeEndsAfterSixSecondsWithoutInput() {
        val m = list(at = 1_000).onSwipe(true, true, q, nowMs = 3_000)
        assertEquals(m, m.timedOut(3_000 + HudNav.LIST_IDLE_MS - 1), "each input restarts the idle timer")
        assertEquals(music, m.timedOut(3_000 + HudNav.LIST_IDLE_MS))
        assertEquals(music, music.timedOut(Long.MAX_VALUE), "page mode is unaffected")
    }

    @Test fun highlightFollowsItsSongWhenTheWindowShifts() {
        val m = list().onSwipe(true, true, q, 0) // on 13
        val shifted = QueueWindow((11L..15L).map { QueueItem(it, "Song $it") }, currentIndex = 2) // next song started
        assertEquals(2, m.visibleHighlight(shifted), "still on song 13")
        val gone = QueueWindow((20L..22L).map { QueueItem(it, "Other $it") }, currentIndex = 1)
        assertEquals(1, m.visibleHighlight(gone), "falls back to the current song")
        assertEquals(0, m.visibleHighlight(QueueWindow(gone.items, currentIndex = null)), "no current: first item")
    }

    @Test fun queueEmptiedInListModeHasNothingToPlay() {
        val m = list()
        assertNull(m.visibleHighlight(QueueWindow()))
        assertEquals(m, m.onSwipe(true, true, QueueWindow(), 0))
        val t = m.onTap(QueueWindow(), 0)
        assertNull(t.play); assertFalse(t.talk); assertEquals(music, t.nav)
    }

    @Test fun visibleRowsKeepTheHighlightInView() {
        assertEquals(0 until 3, visibleRows(size = 3, highlight = 1, rows = 7))
        assertEquals(0 until 7, visibleRows(size = 25, highlight = 0, rows = 7))
        assertEquals(7 until 14, visibleRows(size = 25, highlight = 10, rows = 7))
        assertEquals(18 until 25, visibleRows(size = 25, highlight = 24, rows = 7))
        assertEquals(0 until 7, visibleRows(size = 25, highlight = null, rows = 7))
        assertEquals(IntRange.EMPTY, visibleRows(size = 0, highlight = null, rows = 7))
    }
}
