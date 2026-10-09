package com.debasish.livefit.glasses.hud

import com.debasish.livefit.model.HudPage
import com.debasish.livefit.model.PageState
import com.debasish.livefit.model.Wire

/**
 * lf_page_state (spec §2.5): the visible page on every change and on every (re)connect, so the phone knows whether the
 * Map page is visible even after it restarted. [seq] grows per glasses process. [send] returns false (or throws) when
 * the bridge refused the message: the latest page is then pending — replacing any older pending state — and is re-sent
 * by [retryPending] (every second, HudController) and by an unchanged [onPage], until a send succeeds (review #10).
 */
class PageReporter(private val send: (String) -> Boolean) {
    private var seq = 0L
    private var pending = false
    @Volatile var page: HudPage = HudPage.Workout
        private set

    val hasPending: Boolean
        @Synchronized get() = pending

    @Synchronized
    fun onPage(p: HudPage) {
        if (p == page && seq > 0 && !pending) return
        page = p
        emit()
    }

    @Synchronized
    fun resend() = emit()

    @Synchronized
    fun retryPending() { if (pending) emit() }

    private fun emit() {
        seq++
        pending = !runCatching { send(Wire.encode(PageState(page = page, seq = seq))) }.getOrDefault(false)
    }
}
