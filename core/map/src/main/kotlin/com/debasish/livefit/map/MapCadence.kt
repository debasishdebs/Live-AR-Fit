package com.debasish.livefit.map

import com.debasish.livefit.model.Geo

/** Glasses map cadence (spec §2.5): every [periodMs] or after ≥ [moveM] movement, whichever first; never within [minIntervalMs]. */
class MapCadence(private val periodMs: Long = 3_000, private val moveM: Double = 25.0, private val minIntervalMs: Long = 1_000) {
    private var lastMs: Long? = null
    private var lastLat: Double? = null
    private var lastLon: Double? = null

    fun due(nowMs: Long, lat: Double?, lon: Double?): Boolean {
        val last = lastMs ?: return true
        val elapsed = nowMs - last
        if (elapsed < minIntervalMs) return false
        if (elapsed >= periodMs) return true
        val pLat = lastLat; val pLon = lastLon
        if (lat == null || lon == null || pLat == null || pLon == null) return false
        return Geo.distanceM(pLat, pLon, lat, lon) >= moveM
    }

    /** Called when a render was attempted (a failed send retries on the next cadence, spec §7). */
    fun rendered(nowMs: Long, lat: Double?, lon: Double?) { lastMs = nowMs; lastLat = lat; lastLon = lon }

    fun reset() { lastMs = null; lastLat = null; lastLon = null }
}
