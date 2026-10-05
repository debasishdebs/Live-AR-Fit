package com.debasish.livefit.phone.ui

/** Rate-limits continuous inputs (volume slider) to ≤ 10 commands/s like the watch (spec §6.2). */
class Throttle(private val minIntervalMs: Long, private val now: () -> Long = System::currentTimeMillis) {
    private var last = Long.MIN_VALUE / 2
    fun allow(): Boolean {
        val t = now()
        if (t - last < minIntervalMs) return false
        last = t
        return true
    }
}
