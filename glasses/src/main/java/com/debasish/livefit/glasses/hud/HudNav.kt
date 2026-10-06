package com.debasish.livefit.glasses.hud

import com.debasish.livefit.model.Command
import com.debasish.livefit.model.QueueWindow

enum class HudPage { Workout, Music }

/**
 * Touchpad navigation outside confirmations (spec §6.3). Rokid swipes arrive as DPAD keys: forward = RIGHT/DOWN,
 * back = LEFT/UP.
 * - Workout HUD: back swipe toggles full/glance (as before); glance → any swipe → full; forward swipe (full, or any
 *   non-workout screen) opens the music screen.
 * - Music screen: swipes move the highlight (no wrap), tap plays it, back (double-tap) returns to the workout HUD.
 * The highlight is kept as a queue id so it stays on its song when the window shifts; unknown → the current song.
 */
data class HudNav(val page: HudPage = HudPage.Workout, val mode: HudMode = HudMode.Full, val highlightId: Long? = null) {

    fun onSwipe(forward: Boolean, inWorkout: Boolean, queue: QueueWindow): HudNav = when {
        page == HudPage.Music -> highlightIndex(queue)?.let { i ->
            copy(highlightId = queue.items[(i + if (forward) 1 else -1).coerceIn(0, queue.items.lastIndex)].queueId)
        } ?: this
        inWorkout && mode == HudMode.Glance -> copy(mode = HudMode.Full)
        forward -> copy(page = HudPage.Music, highlightId = null)
        inWorkout -> copy(mode = HudMode.Glance)
        else -> this
    }

    /** Index of the highlighted row in [queue], or null when the queue is empty. */
    fun highlightIndex(queue: QueueWindow): Int? {
        if (queue.items.isEmpty()) return null
        return queue.items.indexOfFirst { it.queueId == highlightId }.takeIf { it >= 0 } ?: queue.currentIndex ?: 0
    }

    /** Music screen: play the highlighted song. Null elsewhere (tap = talk) or with nothing listed. */
    fun onTap(queue: QueueWindow): Command.PlayQueueItem? =
        if (page != HudPage.Music) null else highlightIndex(queue)?.let { Command.PlayQueueItem(queue.items[it].queueId) }

    /** Back on the music screen returns to the workout HUD; null = not consumed. */
    fun onBack(): HudNav? = if (page == HudPage.Music) copy(page = HudPage.Workout, highlightId = null) else null
}

/** The [rows] list rows shown around [highlight] (centred where possible) out of [size]. */
fun visibleRows(size: Int, highlight: Int?, rows: Int): IntRange {
    if (size <= 0) return IntRange.EMPTY
    val start = ((highlight ?: 0) - rows / 2).coerceIn(0, maxOf(0, size - rows))
    return start until minOf(size, start + rows)
}
