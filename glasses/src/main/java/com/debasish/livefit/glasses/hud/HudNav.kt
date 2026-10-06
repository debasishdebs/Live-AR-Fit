package com.debasish.livefit.glasses.hud

import com.debasish.livefit.model.Command
import com.debasish.livefit.model.QueueWindow

enum class HudPage { Workout, Music }

/** What a tap did outside confirmations: the new [nav], a song to [play], or [talk] (push-to-talk). */
data class TapOutcome(val nav: HudNav, val play: Command.PlayQueueItem? = null, val talk: Boolean = false)

/**
 * Touchpad navigation outside confirmations (spec §6.3). Rokid swipes arrive as DPAD keys: forward = RIGHT/DOWN,
 * back = LEFT/UP. Double-tap never navigates (it closes the app, see [CloseConfirm]).
 * - Page mode, workout page: tap = talk; back swipe toggles full/glance; glance → any swipe → full; forward swipe
 *   (full, or any non-workout screen) opens the music page.
 * - Page mode, music page: back swipe returns to the workout page (full/glance as it was); tap enters list mode
 *   (talk when the queue is empty).
 * - List mode (music page, highlight shown): swipes move the highlight (no wrap), tap plays it and returns to page
 *   mode; [LIST_IDLE_MS] without input also returns to page mode ([timedOut]).
 * The highlight is kept as a queue id so it stays on its song when the window shifts; unknown → the current song.
 */
data class HudNav(
    val page: HudPage = HudPage.Workout,
    val mode: HudMode = HudMode.Full,
    val highlightId: Long? = null,
    val listMode: Boolean = false,
    /** Last touchpad input in list mode (wall clock ms), for the idle timeout. */
    val lastInputMs: Long = 0,
) {

    fun onSwipe(forward: Boolean, inWorkout: Boolean, queue: QueueWindow, nowMs: Long): HudNav = when {
        page == HudPage.Music && listMode -> highlightIndex(queue)?.let { i ->
            copy(highlightId = queue.items[(i + if (forward) 1 else -1).coerceIn(0, queue.items.lastIndex)].queueId, lastInputMs = nowMs)
        } ?: this
        page == HudPage.Music -> if (forward) this else pageMode().copy(page = HudPage.Workout)
        inWorkout && mode == HudMode.Glance -> copy(mode = HudMode.Full)
        forward -> pageMode().copy(page = HudPage.Music)
        inWorkout -> copy(mode = HudMode.Glance)
        else -> this
    }

    fun onTap(queue: QueueWindow, nowMs: Long): TapOutcome = when {
        page != HudPage.Music -> TapOutcome(this, talk = true)
        !listMode -> if (queue.items.isEmpty()) TapOutcome(this, talk = true) else TapOutcome(copy(listMode = true, highlightId = null, lastInputMs = nowMs))
        else -> TapOutcome(pageMode(), play = highlightIndex(queue)?.let { Command.PlayQueueItem(queue.items[it].queueId) })
    }

    /** List mode ends after [LIST_IDLE_MS] without input. */
    fun timedOut(nowMs: Long): HudNav = if (listMode && nowMs - lastInputMs >= LIST_IDLE_MS) pageMode() else this

    /** Highlighted row to draw: only in list mode, null when the queue is empty. */
    fun visibleHighlight(queue: QueueWindow): Int? = if (listMode) highlightIndex(queue) else null

    private fun pageMode() = copy(listMode = false, highlightId = null, lastInputMs = 0)

    private fun highlightIndex(queue: QueueWindow): Int? {
        if (queue.items.isEmpty()) return null
        return queue.items.indexOfFirst { it.queueId == highlightId }.takeIf { it >= 0 } ?: queue.currentIndex ?: 0
    }

    companion object { const val LIST_IDLE_MS = 6_000L }
}

/** The [rows] list rows shown around [highlight] (centred where possible) out of [size]. */
fun visibleRows(size: Int, highlight: Int?, rows: Int): IntRange {
    if (size <= 0) return IntRange.EMPTY
    val start = ((highlight ?: 0) - rows / 2).coerceIn(0, maxOf(0, size - rows))
    return start until minOf(size, start + rows)
}
