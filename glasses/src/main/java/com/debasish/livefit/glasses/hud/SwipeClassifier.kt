package com.debasish.livefit.glasses.hud

/** Touchpad DPAD keys as the Rokid firmware sends them for horizontal swipes. */
enum class SwipeKey { Right, Left, Down, Up }

/** One touchpad swipe: [forward] = towards the eyes' front (RIGHT), [long] = the firmware reported two or more steps. */
data class Swipe(val forward: Boolean, val long: Boolean)

/**
 * Turns the touchpad's key burst into exactly one [Swipe] (spec §6.3). Verified on device: every gesture starts with
 * key 83 ([onTouch]); a short swipe is one RIGHT (LEFT) followed ~30–50 ms later by one DOWN (UP) "twin"; a long swipe
 * sends two RIGHT (LEFT) ~50–90 ms apart, then the twin. The gesture closes on its twin, on the next touch, or
 * [CLOSE_MS] after its last horizontal key ([onTimer] at [deadlineMs]); a twin arriving within [TWIN_MS] after a
 * timer-closed gesture is swallowed. Without a touch (adb `input keyevent`) a lone RIGHT/LEFT is a short swipe after
 * [CLOSE_MS], and a lone DOWN/UP is a short forward/back swipe at once. Times are one monotonic clock.
 */
class SwipeClassifier {
    private var forward = false
    private var steps = 0
    private var lastStepMs = 0L
    /** Until when a vertical key is the twin of a gesture already emitted. */
    private var twinUntilMs: Long? = null

    /** When [onTimer] should be called to close the open gesture; null = none open. */
    val deadlineMs: Long? get() = if (steps > 0) lastStepMs + CLOSE_MS else null

    /** Key 83: a finger touched the pad. Closes a gesture still open. */
    fun onTouch(nowMs: Long): Swipe? {
        twinUntilMs = null
        return emit()
    }

    fun onKey(key: SwipeKey, nowMs: Long): Swipe? = when (key) {
        SwipeKey.Right, SwipeKey.Left -> {
            val fwd = key == SwipeKey.Right
            val closed = if (steps > 0 && (fwd != forward || nowMs - lastStepMs >= CLOSE_MS)) emit() else null
            if (steps == 0) { forward = fwd; twinUntilMs = null }
            steps++
            lastStepMs = nowMs
            closed
        }
        SwipeKey.Down, SwipeKey.Up -> when {
            steps > 0 -> emit()
            twinUntilMs?.let { nowMs <= it } == true -> { twinUntilMs = null; null }
            else -> Swipe(forward = key == SwipeKey.Down, long = false)
        }
    }

    /** Closes the open gesture once [CLOSE_MS] passed without a further step. */
    fun onTimer(nowMs: Long): Swipe? {
        val deadline = deadlineMs ?: return null
        if (nowMs < deadline) return null
        twinUntilMs = lastStepMs + TWIN_MS
        return emit()
    }

    private fun emit(): Swipe? {
        if (steps == 0) return null
        return Swipe(forward, long = steps >= 2).also { steps = 0 }
    }

    companion object {
        /** Longer than the measured 50–90 ms between a long swipe's steps. */
        const val CLOSE_MS = 150L
        const val TWIN_MS = 400L
    }
}
