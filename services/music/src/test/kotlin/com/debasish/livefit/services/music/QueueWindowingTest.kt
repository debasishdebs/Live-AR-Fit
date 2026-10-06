package com.debasish.livefit.services.music

import com.debasish.livefit.model.QueueItem
import com.debasish.livefit.model.QueueWindow
import kotlin.test.Test
import kotlin.test.assertEquals

class QueueWindowingTest {
    private val queue = (0L until 25L).map { QueueItem(queueId = 100 + it, title = "Song $it", artist = "Artist $it") }
    private fun ids(w: QueueWindow) = w.items.map { it.queueId - 100 }

    @Test fun emptyQueueGivesAnEmptyWindow() {
        assertEquals(QueueWindow(), QueueWindowing.window(emptyList(), activeQueueId = 5, size = 10))
        assertEquals(QueueWindow(), QueueWindowing.window(emptyList(), activeQueueId = null, size = 10))
    }

    @Test fun currentAtStartIsFollowedByUpcomingOnly() {
        val w = QueueWindowing.window(queue, activeQueueId = 100, size = 10)
        assertEquals((0L until 10L).toList(), ids(w))
        assertEquals(0, w.currentIndex)
    }

    @Test fun currentInTheMiddleKeepsAllHistoryThatFitsThenUpcoming() {
        val w = QueueWindowing.window(queue, activeQueueId = 104, size = 10) // 4 played, 20 upcoming
        assertEquals((0L until 10L).toList(), ids(w), "4 previous + current + 5 upcoming")
        assertEquals(4, w.currentIndex)
        assertEquals(104, w.current?.queueId)
    }

    @Test fun historyIsCappedSoTheWindowHoldsN() {
        val w = QueueWindowing.window(queue, activeQueueId = 115, size = 10) // 15 played: only 9 fit before current
        assertEquals((6L..15L).toList(), ids(w))
        assertEquals(9, w.currentIndex)
    }

    @Test fun currentAtEndShowsHistoryBeforeIt() {
        val w = QueueWindowing.window(queue, activeQueueId = 124, size = 10)
        assertEquals((15L..24L).toList(), ids(w))
        assertEquals(9, w.currentIndex)
    }

    @Test fun nLargerThanTheQueueShowsTheWholeQueue() {
        val w = QueueWindowing.window(queue, activeQueueId = 110, size = 50)
        assertEquals((0L until 25L).toList(), ids(w))
        assertEquals(10, w.currentIndex)
        val short = QueueWindowing.window(queue.take(3), activeQueueId = 101, size = 25)
        assertEquals(listOf(0L, 1L, 2L), ids(short))
        assertEquals(1, short.currentIndex)
    }

    @Test fun missingOrUnknownActiveItemShowsTheStartWithoutCurrent() {
        for (active in listOf(null, 999L)) {
            val w = QueueWindowing.window(queue, activeQueueId = active, size = 10)
            assertEquals((0L until 10L).toList(), ids(w), "active=$active")
            assertEquals(null, w.currentIndex)
        }
    }

    @Test fun sizeIsClampedToItsBounds() {
        assertEquals(QueueWindowing.MIN_SIZE, QueueWindowing.window(queue, 100, size = 1).items.size)
        assertEquals(25, QueueWindowing.window(queue, 100, size = 500).items.size)
        assertEquals(5, QueueWindowing.clampSize(-3))
        assertEquals(50, QueueWindowing.clampSize(80))
        assertEquals(25, QueueWindowing.DEFAULT_SIZE)
    }

    @Test fun longTextIsTrimmedToKeepTheFrameSmall() {
        val long = "x".repeat(300)
        val w = QueueWindowing.window(listOf(QueueItem(1, long, long)), activeQueueId = 1, size = 10)
        assertEquals(QueueWindowing.MAX_TEXT, w.items.single().title.length)
        assertEquals(QueueWindowing.MAX_TEXT, w.items.single().artist.length)
        assertEquals("…", w.items.single().title.takeLast(1))
    }
}
