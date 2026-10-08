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

    private val short = Swipe(forward = true, long = false)
    private val shortBack = Swipe(forward = false, long = false)
    private val long = Swipe(forward = true, long = true)
    private val longBack = Swipe(forward = false, long = true)

    @Test fun pagesAreAnOrderedListWithWorkoutFirst() = assertEquals(listOf(HudPage.Workout, HudPage.Music), HudNav.PAGES)

    @Test fun anySwipeOnTheWorkoutPageMovesBetweenPages() {
        assertEquals(music, HudNav().onSwipe(short, q, 0), "short forward = next page")
        assertEquals(music, HudNav().onSwipe(long, q, 0), "long forward = next page")
        assertEquals(HudNav(), HudNav().onSwipe(shortBack, q, 0), "back before the first page does nothing")
        assertEquals(HudNav(), HudNav().onSwipe(longBack, q, 0))
    }

    @Test fun longSwipeOnTheMusicPageMovesBetweenPagesWithoutWrapping() {
        assertEquals(HudNav(), music.onSwipe(longBack, q, 0), "long back = workout page")
        assertEquals(music, music.onSwipe(long, q, 0), "forward past the last page does nothing")
    }

    @Test fun workoutPageKeepsFullOrGlanceAcrossPageSwitches() {
        val glance = HudNav(mode = HudMode.Glance)
        assertEquals(glance, glance.onSwipe(long, q, 0).onSwipe(longBack, q, 0))
    }

    @Test fun musicPageShowsTheHighlightAtOnceOnTheCurrentSong() {
        val m = HudNav().onSwipe(short, q, 0)
        assertEquals(2, m.visibleHighlight(q))
        assertNull(HudNav().visibleHighlight(q), "no highlight on the workout page")
        assertNull(music.visibleHighlight(QueueWindow()), "nothing to highlight")
        assertEquals(0, music.visibleHighlight(QueueWindow(q.items, currentIndex = null)), "no current: first item")
    }

    @Test fun shortSwipesOnTheMusicPageMoveTheHighlightAndStopAtTheEnds() {
        var m = music.onSwipe(short, q, 0); assertEquals(3, m.visibleHighlight(q))
        m = m.onSwipe(short, q, 0).onSwipe(short, q, 0); assertEquals(4, m.visibleHighlight(q), "no wrap past the last item")
        repeat(6) { m = m.onSwipe(shortBack, q, 0) }
        assertEquals(0, m.visibleHighlight(q), "no wrap before the first")
        assertEquals(HudPage.Music, m.page, "short swipes never leave the music page")
    }

    @Test fun shortSwipeWithAnEmptyQueueDoesNothing() = assertEquals(music, music.onSwipe(short, QueueWindow(), 0))

    @Test fun leavingTheMusicPageForgetsTheHighlight() {
        val back = music.onSwipe(short, q, 0).onSwipe(longBack, q, 0).onSwipe(long, q, 0)
        assertEquals(2, back.visibleHighlight(q), "returns on the current song")
    }

    @Test fun tapOnWorkoutPageIsTalk() {
        val t = HudNav().onTap(q, 0)
        assertTrue(t.talk); assertNull(t.command); assertEquals(HudNav(), t.nav)
    }

    @Test fun tapOnTheCurrentSongIsPlayPause() {
        val t = music.onTap(q, 0)
        assertEquals(Command.PlayPause, t.command); assertFalse(t.talk); assertEquals(music, t.nav)
        assertEquals(Command.PlayPause, music.onSwipe(short, q, 0).onSwipe(shortBack, q, 0).onTap(q, 0).command, "moved back onto it")
    }

    @Test fun tapOnAnotherSongPlaysItAndTheHighlightReturnsToTheCurrentSong() {
        val t = music.onSwipe(short, q, 0).onTap(q, 0)
        assertEquals(Command.PlayQueueItem(13), t.command)
        assertFalse(t.talk)
        assertEquals(music, t.nav)
    }

    @Test fun tapWithNoCurrentSongPlaysTheHighlightedOne() =
        assertEquals(Command.PlayQueueItem(10), music.onTap(QueueWindow(q.items, currentIndex = null), 0).command)

    @Test fun tapOnMusicPageWithAnEmptyQueueIsTalk() {
        val t = music.onTap(QueueWindow(), 0)
        assertTrue(t.talk); assertNull(t.command)
    }

    @Test fun highlightReturnsToTheCurrentSongAfterSixSecondsIdle() {
        val m = music.onSwipe(short, q, nowMs = 1_000).onSwipe(short, q, nowMs = 3_000)
        assertEquals(m, m.timedOut(3_000 + HudNav.IDLE_MS - 1), "each swipe restarts the idle timer")
        assertEquals(music, m.timedOut(3_000 + HudNav.IDLE_MS))
        assertEquals(music, music.timedOut(Long.MAX_VALUE), "already on the current song")
    }

    @Test fun highlightFollowsItsSongWhenTheWindowShifts() {
        val m = music.onSwipe(short, q, 0) // on 13
        val shifted = QueueWindow((11L..15L).map { QueueItem(it, "Song $it") }, currentIndex = 2) // next song started
        assertEquals(2, m.visibleHighlight(shifted), "still on song 13")
        assertEquals(Command.PlayPause, m.onTap(shifted, 0).command, "13 is now the current song")
        val gone = QueueWindow((20L..22L).map { QueueItem(it, "Other $it") }, currentIndex = 1)
        assertEquals(1, m.visibleHighlight(gone), "falls back to the current song")
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
