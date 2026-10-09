package com.debasish.livefit.sync

import com.debasish.livefit.model.FixSource
import com.debasish.livefit.model.GpsStatus
import com.debasish.livefit.model.LivePosition
import com.debasish.livefit.model.LocationFix

/**
 * Which fix drives the marker and whether the phone fallback runs (spec §2.1). Only usable-live fixes count.
 * - Fallback starts after [fallbackAfterMs] without a usable-live watch fix (measurement time) during a GPS workout.
 * - It stops once watch fixes were usable-live continuously for [watchStableMs] of **observed** time (review #3): the run
 *   is timed by arrival, with no arrival gap > [continuityGapMs], so one late batch — however long a stretch it covers —
 *   is a single observation. A fresh but unusable (inaccurate, unknown accuracy, too far in the future) watch fix breaks
 *   the run; replayed (old) and uncalibrated fixes neither count nor break it.
 * - The marker and freshness use measurement time; while both are usable-live the watch wins the marker.
 */
class LiveLocationSelector(
    private val fallbackAfterMs: Long = 15_000,
    private val watchStableMs: Long = 10_000,
    private val continuityGapMs: Long = 3_000,
) {
    private var gpsWorkout = false
    private var noWatchSinceMs = 0L
    private var watchLive: LivePosition? = null
    /** Arrival (observed) time of the first and of the latest usable-live watch fix of the current recovery run. */
    private var runStartObservedMs: Long? = null
    private var lastLiveObservedMs: Long? = null
    private var phoneLive: LivePosition? = null

    var fallback: Boolean = false
        private set

    /** A new session (or none). */
    fun begin(gpsWorkout: Boolean, nowMs: Long) {
        this.gpsWorkout = gpsWorkout
        noWatchSinceMs = nowMs
        watchLive = null; phoneLive = null
        runStartObservedMs = null; lastLiveObservedMs = null
        fallback = false
    }

    /** The same session became (or stopped being) a recording GPS workout. */
    fun setGpsWorkout(on: Boolean, nowMs: Long) {
        if (on && !gpsWorkout) noWatchSinceMs = nowMs
        gpsWorkout = on
        update(nowMs)
    }

    /** [phoneTimeMs] = the fix time mapped with the calibrated offset; null while uncalibrated. [nowMs] = arrival time. */
    fun onWatchFix(fix: LocationFix, phoneTimeMs: Long?, nowMs: Long) {
        if (phoneTimeMs == null) return
        if (!Freshness.isUsableLive(phoneTimeMs, fix.accuracyM, nowMs)) {
            if (nowMs - phoneTimeMs <= Freshness.MAX_AGE_MS) { runStartObservedMs = null; lastLiveObservedMs = null } // fresh but poor
            update(nowMs)
            return
        }
        if (phoneTimeMs > (watchLive?.fixTimeMs ?: Long.MIN_VALUE)) {
            watchLive = LivePosition(fix.lat, fix.lon, fix.bearingDeg, FixSource.Watch, phoneTimeMs)
        }
        val last = lastLiveObservedMs
        if (runStartObservedMs == null || last == null || nowMs - last > continuityGapMs) runStartObservedMs = nowMs
        lastLiveObservedMs = nowMs
        update(nowMs)
    }

    /** Phone fixes are already in phone time. */
    fun onPhoneFix(fix: LocationFix, nowMs: Long) {
        if (Freshness.isUsableLive(fix.fixTimeMs, fix.accuracyM, nowMs) && fix.fixTimeMs >= (phoneLive?.fixTimeMs ?: Long.MIN_VALUE)) {
            phoneLive = LivePosition(fix.lat, fix.lon, fix.bearingDeg, FixSource.Phone, fix.fixTimeMs)
        }
        update(nowMs)
    }

    /** Re-evaluates the fallback at [nowMs]; returns whether the phone GPS should run. */
    fun update(nowMs: Long): Boolean {
        if (!gpsWorkout) { fallback = false; return false }
        if (!fallback) {
            if (nowMs - maxOf(watchLive?.fixTimeMs ?: Long.MIN_VALUE, noWatchSinceMs) >= fallbackAfterMs) fallback = true
        } else {
            val start = runStartObservedMs
            val last = lastLiveObservedMs
            if (start != null && last != null && last - start >= watchStableMs && nowMs - last <= continuityGapMs) fallback = false
        }
        return fallback
    }

    /** The marker: a live watch fix, else a live phone fix, else the newest one seen (drawn hollow while degraded). */
    fun current(nowMs: Long): LivePosition? {
        watchLive?.takeIf { nowMs - it.fixTimeMs <= Freshness.MAX_AGE_MS }?.let { return it }
        phoneLive?.takeIf { nowMs - it.fixTimeMs <= Freshness.MAX_AGE_MS }?.let { return it }
        return listOfNotNull(watchLive, phoneLive).maxByOrNull { it.fixTimeMs }
    }

    fun status(nowMs: Long): GpsStatus = Freshness.status(listOfNotNull(watchLive?.fixTimeMs, phoneLive?.fixTimeMs).maxOrNull(), nowMs)
}
