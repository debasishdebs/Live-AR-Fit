package com.debasish.livefit.services.music

import com.debasish.livefit.model.QueueItem
import com.debasish.livefit.model.QueueWindow

/**
 * Picks the part of YouTube Music's queue the glasses music screen lists (spec §5.5). The session's queue ("Up next")
 * holds already-played and upcoming items, with `activeQueueItemId` marking the current one.
 * Window of [size] items: all history before the current item that fits (at most size − 1), the current item, then
 * upcoming items to fill the rest (fewer when the queue is shorter).
 */
object QueueWindowing {
    const val DEFAULT_SIZE = 25
    const val MIN_SIZE = 5
    const val MAX_SIZE = 50
    /** Title / artist length cap so a 50-item lf_queue frame stays a few KB. */
    const val MAX_TEXT = 60

    fun clampSize(size: Int): Int = size.coerceIn(MIN_SIZE, MAX_SIZE)

    fun window(queue: List<QueueItem>, activeQueueId: Long?, size: Int): QueueWindow {
        if (queue.isEmpty()) return QueueWindow()
        val n = clampSize(size)
        val current = activeQueueId?.let { id -> queue.indexOfFirst { it.queueId == id } }?.takeIf { it >= 0 }
            // No (or an unknown) active item: show the start of the queue, nothing highlighted as current.
            ?: return QueueWindow(queue.take(n).map(::trimmed), currentIndex = null)
        val previous = minOf(current, n - 1)
        val from = current - previous
        val to = minOf(queue.size, current + 1 + (n - previous - 1))
        return QueueWindow(queue.subList(from, to).map(::trimmed), currentIndex = previous)
    }

    private fun trimmed(item: QueueItem) = item.copy(title = trim(item.title), artist = trim(item.artist))
    private fun trim(s: String) = if (s.length <= MAX_TEXT) s else s.take(MAX_TEXT - 1) + "…"
}
