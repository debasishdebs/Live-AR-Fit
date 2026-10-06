package com.debasish.livefit.glasses.hud

import com.debasish.livefit.model.Command
import com.debasish.livefit.model.QueueItem
import com.debasish.livefit.model.QueueWindow
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class HudNavTest {
    private val q = QueueWindow((10L..14L).map { QueueItem(it, "Song $it") }, currentIndex = 2) // current = 12

    @Test fun backSwipeStillTogglesFullAndGlanceInAWorkout() {
        val glance = HudNav().onSwipe(forward = false, inWorkout = true, queue = q)
        assertEquals(HudNav(mode = HudMode.Glance), glance)
        assertEquals(HudNav(mode = HudMode.Full), glance.onSwipe(forward = false, inWorkout = true, queue = q))
    }

    @Test fun anySwipeLeavesGlanceForFull() =
        assertEquals(HudNav(mode = HudMode.Full), HudNav(mode = HudMode.Glance).onSwipe(forward = true, inWorkout = true, queue = q))

    @Test fun forwardSwipeOpensMusicFromFullAndFromReady() {
        assertEquals(HudPage.Music, HudNav().onSwipe(forward = true, inWorkout = true, queue = q).page)
        assertEquals(HudPage.Music, HudNav().onSwipe(forward = true, inWorkout = false, queue = q).page)
        assertEquals(HudNav(), HudNav().onSwipe(forward = false, inWorkout = false, queue = q), "back swipe without a workout does nothing")
    }

    @Test fun musicOpensOnTheCurrentSong() {
        val m = HudNav().onSwipe(true, true, q)
        assertEquals(2, m.highlightIndex(q))
        assertEquals(Command.PlayQueueItem(12), m.onTap(q))
    }

    @Test fun swipesMoveTheHighlightAndStopAtTheEnds() {
        var m = HudNav().onSwipe(true, true, q)
        m = m.onSwipe(true, true, q); assertEquals(3, m.highlightIndex(q))
        m = m.onSwipe(true, true, q).onSwipe(true, true, q); assertEquals(4, m.highlightIndex(q), "no wrap past the last item")
        repeat(6) { m = m.onSwipe(false, true, q) }
        assertEquals(0, m.highlightIndex(q), "no wrap before the first")
        assertEquals(Command.PlayQueueItem(10), m.onTap(q))
        assertEquals(HudPage.Music, m.page, "swipes never leave the music screen")
    }

    @Test fun highlightFollowsItsSongWhenTheWindowShifts() {
        val m = HudNav().onSwipe(true, true, q).onSwipe(true, true, q) // on 13
        val shifted = QueueWindow((11L..15L).map { QueueItem(it, "Song $it") }, currentIndex = 2) // next song started
        assertEquals(2, m.highlightIndex(shifted), "still on song 13")
        val gone = QueueWindow((20L..22L).map { QueueItem(it, "Other $it") }, currentIndex = 1)
        assertEquals(1, m.highlightIndex(gone), "falls back to the current song")
        assertEquals(0, m.highlightIndex(QueueWindow(gone.items, currentIndex = null)), "no current: first item")
    }

    @Test fun emptyQueueHasNothingToPlay() {
        val m = HudNav().onSwipe(true, true, q)
        assertNull(m.highlightIndex(QueueWindow()))
        assertNull(m.onTap(QueueWindow()))
        assertEquals(m, m.onSwipe(true, true, QueueWindow()))
    }

    @Test fun backLeavesMusicForTheWorkoutHudItCameFrom() {
        val m = HudNav().onSwipe(true, true, q)
        assertEquals(HudNav(), m.onBack())
        assertNull(HudNav().onBack(), "on the workout HUD back is not consumed (exits the app)")
        assertNull(HudNav().onTap(q), "tap on the workout HUD is talk, not play")
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
