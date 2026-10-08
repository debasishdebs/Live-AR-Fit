package com.debasish.livefit.glasses.hud

import com.debasish.livefit.model.Command
import com.debasish.livefit.model.QueueWindow

enum class HudPage { Workout, Music }

/** What a tap did outside confirmations: the new [nav], a music [command] to send, or [talk] (push-to-talk). */
data class TapOutcome(val nav: HudNav, val command: Command? = null, val talk: Boolean = false)

/**
 * Touchpad navigation outside confirmations (spec §6.3). One gesture = one [Swipe] ([SwipeClassifier]). Pages are the
 * ordered list [PAGES]; switching never wraps. Double-tap never navigates (it closes the app, see [CloseConfirm]).
 * - Workout page: any swipe = next/previous page; tap = talk. Full/glance ([mode]) is kept across page switches.
 * - Music page: the highlight is always shown, on the current song unless moved; short swipe moves it (no wrap);
 *   long swipe = next/previous page; tap = play/pause when the highlight is on the current song, else play the
 *   highlighted song (talk when the queue is empty). [IDLE_MS] without input puts the highlight back on the current song.
 * The highlight is kept as a queue id so it stays on its song when the window shifts; unknown → the current song.
 */
data class HudNav(
    val page: HudPage = HudPage.Workout,
    val mode: HudMode = HudMode.Full,
    /** Song the user moved the highlight to; null = the current song. */
    val highlightId: Long? = null,
    /** Last highlight move (wall clock ms), for the idle timeout. */
    val lastInputMs: Long = 0,
) {

    fun onSwipe(swipe: Swipe, queue: QueueWindow, nowMs: Long): HudNav = when {
        page == HudPage.Music && !swipe.long -> highlightIndex(queue)?.let { i ->
            copy(highlightId = queue.items[(i + if (swipe.forward) 1 else -1).coerceIn(0, queue.items.lastIndex)].queueId, lastInputMs = nowMs)
        } ?: this
        else -> PAGES[(PAGES.indexOf(page) + if (swipe.forward) 1 else -1).coerceIn(0, PAGES.lastIndex)]
            .let { if (it == page) this else resetHighlight().copy(page = it) }
    }

    fun onTap(queue: QueueWindow, nowMs: Long): TapOutcome {
        if (page != HudPage.Music) return TapOutcome(this, talk = true)
        val i = highlightIndex(queue) ?: return TapOutcome(this, talk = true)
        val command = if (i == queue.currentIndex) Command.PlayPause else Command.PlayQueueItem(queue.items[i].queueId)
        return TapOutcome(resetHighlight(), command = command)
    }

    /** The highlight returns to the current song after [IDLE_MS] without input. */
    fun timedOut(nowMs: Long): HudNav = if (highlightId != null && nowMs - lastInputMs >= IDLE_MS) resetHighlight() else this

    /** Highlighted row to draw: on the music page only, null when the queue is empty. */
    fun visibleHighlight(queue: QueueWindow): Int? = if (page == HudPage.Music) highlightIndex(queue) else null

    private fun resetHighlight() = copy(highlightId = null, lastInputMs = 0)

    private fun highlightIndex(queue: QueueWindow): Int? {
        if (queue.items.isEmpty()) return null
        return queue.items.indexOfFirst { it.queueId == highlightId }.takeIf { it >= 0 } ?: queue.currentIndex ?: 0
    }

    companion object {
        /** Page order for swipes; add new pages here. */
        val PAGES = listOf(HudPage.Workout, HudPage.Music)
        const val IDLE_MS = 6_000L
    }
}

/** The [rows] list rows shown around [highlight] (centred where possible) out of [size]. */
fun visibleRows(size: Int, highlight: Int?, rows: Int): IntRange {
    if (size <= 0) return IntRange.EMPTY
    val start = ((highlight ?: 0) - rows / 2).coerceIn(0, maxOf(0, size - rows))
    return start until minOf(size, start + rows)
}
