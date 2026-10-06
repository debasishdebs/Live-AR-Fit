package com.debasish.livefit.glasses.hud

import com.debasish.livefit.model.WorkoutPhase

/**
 * Turns the touchpad's double-tap into one event (spec §6.3). On the owner's glasses a double-tap arrives as two
 * KEYCODE_NOTIFICATION (83) presses ~150 ms apart; other firmware sends BACK. Two 83 presses within [WINDOW_MS], or
 * one BACK, is a double-tap; keys within [SETTLE_MS] of a fired double-tap are its tail (a firmware sending both).
 * A lone 83 press does nothing.
 */
class DoubleTapDetector {
    private var firstMs: Long? = null
    private var firedMs: Long? = null

    /** A KEYCODE_NOTIFICATION key-down. True = a double-tap. */
    fun onNotificationKey(nowMs: Long): Boolean {
        if (settling(nowMs)) return false
        val first = firstMs
        if (first != null && nowMs - first <= WINDOW_MS) return fire(nowMs)
        firstMs = nowMs
        return false
    }

    /** A BACK press. True = a double-tap. */
    fun onBack(nowMs: Long): Boolean = if (settling(nowMs)) false else fire(nowMs)

    private fun settling(nowMs: Long) = firedMs?.let { nowMs - it < SETTLE_MS } == true
    private fun fire(nowMs: Long): Boolean { firstMs = null; firedMs = nowMs; return true }

    companion object {
        const val WINDOW_MS = 400L
        const val SETTLE_MS = 600L
    }
}

enum class DoubleTapAction {
    /** Answer the pending cross-device confirmation with No. */
    AnswerNo,
    /** Show "Close LiveFit?" (a workout is recording). */
    AskClose,
    /** Our close prompt was answered No by a double-tap. */
    Stay,
    /** Close the app now. */
    Leave,
}

/**
 * Glasses-local "Close LiveFit? The workout keeps recording." prompt (spec §6.3). Same touchpad rules as a hub
 * confirmation (swipe moves, tap answers, double-tap = No) and below it in priority; [TIMEOUT_MS] without an answer = stay.
 */
data class CloseConfirm(val shownAtMs: Long? = null, val highlightYes: Boolean = true) {
    val shown: Boolean get() = shownAtMs != null

    fun show(nowMs: Long) = CloseConfirm(shownAtMs = nowMs, highlightYes = true)

    /** Double-tap on any screen: hub prompt → No; our prompt → stay; recording workout → ask; else leave. */
    fun onDoubleTap(hubPending: Boolean, phase: WorkoutPhase?, nowMs: Long): Pair<CloseConfirm, DoubleTapAction> = when {
        hubPending -> this to DoubleTapAction.AnswerNo
        shown -> CloseConfirm() to DoubleTapAction.Stay
        phase in RECORDING -> show(nowMs) to DoubleTapAction.AskClose
        else -> CloseConfirm() to DoubleTapAction.Leave
    }

    fun onSwipe(): CloseConfirm = if (shown) copy(highlightYes = !highlightYes) else this

    /** Answers the prompt: true = close, false = stay, null = nothing shown (the tap is not ours). */
    fun onTap(): Pair<CloseConfirm, Boolean?> = if (shown) CloseConfirm() to highlightYes else this to null

    fun timedOut(nowMs: Long): CloseConfirm = if (shownAtMs != null && nowMs - shownAtMs >= TIMEOUT_MS) CloseConfirm() else this

    companion object {
        const val TIMEOUT_MS = 8_000L
        /** Phases where the phone/watch keep recording after the glasses app closes. */
        val RECORDING = setOf(WorkoutPhase.Starting, WorkoutPhase.Active, WorkoutPhase.Paused, WorkoutPhase.Syncing)
    }
}
