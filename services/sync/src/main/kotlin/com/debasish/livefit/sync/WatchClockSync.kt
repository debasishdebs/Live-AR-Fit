package com.debasish.livefit.sync

import com.debasish.livefit.services.Clock
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

/**
 * Phone-side watch clock calibration (spec §2.1): phone sends t0, watch replies with its clock tw, phone receives at t1;
 * offset = tw − (t0 + t1)/2, accepted only if t1 − t0 ≤ [maxRttMs], else retried up to [maxTries]. The offset lives in
 * memory only: until one sync succeeded in this process, watch fixes have no phone time and are never live.
 */
class WatchClockSync(private val clock: Clock, private val maxRttMs: Long = 1_000, private val maxTries: Int = 5) {
    private val _offset = MutableStateFlow<Long?>(null)
    /** Watch clock minus phone clock. */
    val offsetMs: StateFlow<Long?> = _offset
    val calibrated: Boolean get() = _offset.value != null

    /** [ping] sends t0 and returns the watch's tw, or null on failure/timeout. A failed round keeps the previous offset. */
    suspend fun calibrate(ping: suspend (t0: Long) -> Long?): Boolean {
        repeat(maxTries) {
            val t0 = clock.nowMs()
            val tw = ping(t0)
            val t1 = clock.nowMs()
            if (tw != null && t1 - t0 <= maxRttMs) {
                _offset.value = tw - (t0 + t1) / 2
                return true
            }
        }
        return false
    }

    fun toPhoneTime(watchMs: Long): Long? = _offset.value?.let { watchMs - it }

    companion object {
        /** Re-calibrate every 5 min (spec §2.1). */
        const val PERIOD_MS = 300_000L
        /** While no sync has succeeded yet, try again this often (plan decision). */
        const val RETRY_UNCALIBRATED_MS = 30_000L
    }
}
