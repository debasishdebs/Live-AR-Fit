package com.debasish.livefit.glasses.hud

import com.debasish.livefit.model.Command
import com.debasish.livefit.model.Gesture
import com.debasish.livefit.model.GestureAction
import com.debasish.livefit.model.GestureDefaults
import com.debasish.livefit.model.GestureMode
import com.debasish.livefit.model.GestureRules
import com.debasish.livefit.model.GestureSettings
import com.debasish.livefit.model.HudPage
import com.debasish.livefit.model.PageSet
import com.debasish.livefit.model.QueueWindow

/** One classified swipe as a configurable gesture (spec §4.1). */
fun Swipe.gesture(): Gesture = when {
    forward && long -> Gesture.LongForward
    forward -> Gesture.ShortForward
    long -> Gesture.LongBack
    else -> Gesture.ShortBack
}

/** Music controls selector, in order (spec §4.3: ⏮ ⏯ ⏭ ✕). */
enum class MusicControl { Previous, PlayPause, Next, Back }

/** What a gesture is resolved against: the queue, the (sanitized) gesture table and the pages available now. */
data class NavContext(val queue: QueueWindow, val gestures: GestureSettings, val available: List<HudPage>)

/** A gesture's result: the new [nav], a [command] for the hub, push-to-talk, or the Close app action. */
data class NavOutcome(val nav: HudNav, val command: Command? = null, val talk: Boolean = false, val close: Boolean = false)

/**
 * Glasses navigation outside confirmations (spec §3.3, §4). Every gesture resolves through the table for the current
 * page and mode (Page mode on every page, Scroll mode on Playlist and Music controls). Any actual page change clears
 * scroll mode, the highlight and the selector; scroll mode stays after Play highlighted / Press selected; the idle
 * timer restarts on every gesture handled in scroll mode. The Playlist highlight is kept as a queue id (it stays on its
 * song when the window shifts) and can rest on the ✕ Back row after the songs.
 */
data class HudNav(
    val page: HudPage = HudPage.Workout,
    val mode: GestureMode = GestureMode.Page,
    /** Song the highlight is on; null = the current song. */
    val highlightId: Long? = null,
    /** The highlight is on the ✕ Back row. */
    val highlightBack: Boolean = false,
    val selector: MusicControl = MusicControl.PlayPause,
    /** Last gesture handled in scroll mode (wall clock ms), for the idle timeout. */
    val lastInputMs: Long = 0,
) {
    fun onGesture(g: Gesture, ctx: NavContext, nowMs: Long): NavOutcome {
        val action = GestureRules.table(ctx.gestures, mode, page)[g] ?: GestureAction.None
        val base = if (mode == GestureMode.Scroll) copy(lastInputMs = nowMs) else this
        return base.perform(action, ctx, nowMs)
    }

    private fun perform(a: GestureAction, ctx: NavContext, nowMs: Long): NavOutcome = when (a) {
        GestureAction.None -> NavOutcome(this)
        GestureAction.Talk -> NavOutcome(this, talk = true)
        GestureAction.CloseApp -> NavOutcome(this, close = true)
        GestureAction.NextPage -> NavOutcome(go(PageSet.step(page, 1, ctx.available)))
        GestureAction.PreviousPage -> NavOutcome(go(PageSet.step(page, -1, ctx.available)))
        GestureAction.NextPage2 -> NavOutcome(go(PageSet.step(page, 2, ctx.available)))
        GestureAction.PreviousPage2 -> NavOutcome(go(PageSet.step(page, -2, ctx.available)))
        GestureAction.EnterScroll ->
            NavOutcome(if (page in GestureDefaults.SCROLL_PAGES) HudNav(page = page, mode = GestureMode.Scroll, lastInputMs = nowMs) else this)
        GestureAction.ExitScroll -> NavOutcome(pageMode())
        GestureAction.HighlightNext -> NavOutcome(moveHighlight(1, ctx.queue))
        GestureAction.HighlightPrevious -> NavOutcome(moveHighlight(-1, ctx.queue))
        GestureAction.HighlightNext2 -> NavOutcome(moveHighlight(2, ctx.queue))
        GestureAction.HighlightPrevious2 -> NavOutcome(moveHighlight(-2, ctx.queue))
        GestureAction.PlayHighlighted -> playHighlighted(ctx.queue)
        GestureAction.SelectorNext -> NavOutcome(moveSelector(1))
        GestureAction.SelectorPrevious -> NavOutcome(moveSelector(-1))
        GestureAction.PressSelected -> pressSelected()
        GestureAction.PlayPause -> NavOutcome(this, Command.PlayPause)
        GestureAction.NextSong -> NavOutcome(this, Command.NextTrack)
        GestureAction.PreviousSong -> NavOutcome(this, Command.PreviousTrack)
        GestureAction.VolumeUp -> NavOutcome(this, Command.Volume(up = true))
        GestureAction.VolumeDown -> NavOutcome(this, Command.Volume(up = false))
        GestureAction.LikeSong -> NavOutcome(this, Command.LikeTrack)
    }

    /** Go to [target]; an actual change starts the new page fresh in page mode. */
    fun go(target: HudPage): HudNav = if (target == page) this else HudNav(page = target)

    /** Voice page views (lf_page): only to an available page; the same page again changes nothing. */
    fun show(target: HudPage, available: List<HudPage>): HudNav = if (target in available) go(target) else this

    /** The visible page became unavailable (disabled, Map ineligible) → Workout (spec §3.3). */
    fun reconcile(available: List<HudPage>): HudNav = go(PageSet.resolve(page, available))

    fun timedOut(nowMs: Long, idleMs: Long): HudNav = if (mode == GestureMode.Scroll && nowMs - lastInputMs >= idleMs) pageMode() else this

    /** A confirmation closed: the paused idle timer starts again. */
    fun resumeIdle(nowMs: Long): HudNav = if (mode == GestureMode.Scroll) copy(lastInputMs = nowMs) else this

    /** Playlist scroll mode only: a queue row, or `queue.items.size` for the ✕ Back row. */
    fun highlightRow(queue: QueueWindow): Int? {
        if (mode != GestureMode.Scroll || page != HudPage.Playlist) return null
        if (highlightBack || queue.items.isEmpty()) return queue.items.size
        return queue.items.indexOfFirst { it.queueId == highlightId }.takeIf { it >= 0 } ?: queue.currentIndex ?: 0
    }

    fun visibleSelector(): MusicControl? = if (mode == GestureMode.Scroll && page == HudPage.MusicControls) selector else null

    private fun pageMode() = HudNav(page = page)

    private fun moveHighlight(d: Int, queue: QueueWindow): HudNav {
        val row = highlightRow(queue) ?: return this
        val next = (row + d).coerceIn(0, queue.items.size)
        return if (next == queue.items.size) copy(highlightId = null, highlightBack = true)
        else copy(highlightId = queue.items[next].queueId, highlightBack = false)
    }

    private fun playHighlighted(queue: QueueWindow): NavOutcome {
        val row = highlightRow(queue) ?: return NavOutcome(this)
        if (row == queue.items.size) return NavOutcome(pageMode()) // ✕ Back
        val item = queue.items[row]
        val cmd = if (row == queue.currentIndex) Command.PlayPause else Command.PlayQueueItem(item.queueId)
        return NavOutcome(copy(highlightId = item.queueId, highlightBack = false), cmd)
    }

    private fun moveSelector(d: Int): HudNav {
        if (visibleSelector() == null) return this
        val n = MusicControl.entries.size
        return copy(selector = MusicControl.entries[Math.floorMod(selector.ordinal + d, n)])
    }

    private fun pressSelected(): NavOutcome = when (visibleSelector()) {
        null -> NavOutcome(this)
        MusicControl.Previous -> NavOutcome(this, Command.PreviousTrack)
        MusicControl.PlayPause -> NavOutcome(this, Command.PlayPause)
        MusicControl.Next -> NavOutcome(this, Command.NextTrack)
        MusicControl.Back -> NavOutcome(pageMode())
    }
}

/** The [rows] list rows shown around [highlight] (centred where possible) out of [size]. */
fun visibleRows(size: Int, highlight: Int?, rows: Int): IntRange {
    if (size <= 0) return IntRange.EMPTY
    val start = ((highlight ?: 0) - rows / 2).coerceIn(0, maxOf(0, size - rows))
    return start until minOf(size, start + rows)
}

/**
 * Scroll-mode idle timer with confirmation pauses (spec §3.3), as one ordered path (review #9): an overlay change goes
 * through [onOverlay] first — a dismissal restarts the timer via [HudNav.resumeIdle] — and only then is a deadline
 * computed, from the gate and the *current* idle timeout. So a long confirmation never drops the wearer out of Scroll
 * mode at dismissal, and a new timeout from the phone applies without another gesture.
 */
data class IdleGate(val paused: Boolean = false) {
    fun onOverlay(up: Boolean, nav: HudNav, nowMs: Long): Pair<IdleGate, HudNav> =
        IdleGate(paused = up) to if (paused && !up) nav.resumeIdle(nowMs) else nav

    /** When Scroll mode times out with [idleMs]; null in page mode or while an overlay pauses the timer. */
    fun deadlineMs(nav: HudNav, idleMs: Long): Long? = if (!paused && nav.mode == GestureMode.Scroll) nav.lastInputMs + idleMs else null

    /** [nav] after the timer at [nowMs]. */
    fun tick(nav: HudNav, idleMs: Long, nowMs: Long): HudNav =
        deadlineMs(nav, idleMs)?.takeIf { nowMs >= it }?.let { nav.timedOut(nowMs, idleMs) } ?: nav
}
