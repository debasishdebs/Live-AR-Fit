package com.debasish.livefit.sync

import com.debasish.livefit.model.FixQuality
import com.debasish.livefit.model.GpsStatus

/**
 * Spec §2.1 "usable live" and the degraded display thresholds. All times are phone time (the watch map passes its own
 * clock). This is the one predicate used by the phone selector, RouteHub and the watch map (review #7/#8).
 */
object Freshness {
    const val MAX_AGE_MS = 10_000L
    const val FUTURE_TOLERANCE_MS = 2_000L
    const val MAX_ACCURACY_M = FixQuality.MAX_ACCURACY_M
    const val DELAYED_UNTIL_MS = 30_000L

    /** −2 s ≤ age ≤ 10 s and accuracy known and ≤ 30 m; null time = uncalibrated = never live. */
    fun isUsableLive(phoneTimeMs: Long?, accuracyM: Float?, nowMs: Long): Boolean {
        if (phoneTimeMs == null || !FixQuality.accurate(accuracyM)) return false
        val age = nowMs - phoneTimeMs
        return age >= -FUTURE_TOLERANCE_MS && age <= MAX_AGE_MS
    }

    /** From the newest usable-live fix: none → Waiting; ≤ 10 s Live; ≤ 30 s Delayed; older Lost. */
    fun status(lastLivePhoneTimeMs: Long?, nowMs: Long): GpsStatus {
        val t = lastLivePhoneTimeMs ?: return GpsStatus.Waiting
        val age = nowMs - t
        return when {
            age <= MAX_AGE_MS -> GpsStatus.Live
            age <= DELAYED_UNTIL_MS -> GpsStatus.Delayed
            else -> GpsStatus.Lost
        }
    }
}
