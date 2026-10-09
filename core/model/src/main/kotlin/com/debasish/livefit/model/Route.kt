package com.debasish.livefit.model

import kotlinx.serialization.Serializable
import kotlin.math.asin
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.min
import kotlin.math.pow
import kotlin.math.sin
import kotlin.math.sqrt

object Geo {
    const val EARTH_RADIUS_M = 6_371_008.8

    fun distanceM(lat1: Double, lon1: Double, lat2: Double, lon2: Double): Double {
        val p1 = Math.toRadians(lat1); val p2 = Math.toRadians(lat2)
        val dp = p2 - p1; val dl = Math.toRadians(lon2 - lon1)
        val a = sin(dp / 2).pow(2) + cos(p1) * cos(p2) * sin(dl / 2).pow(2)
        return 2 * EARTH_RADIUS_M * asin(sqrt(min(1.0, a)))
    }

    /** Initial great-circle bearing, 0 = north, clockwise, 0..360. */
    fun bearingDeg(lat1: Double, lon1: Double, lat2: Double, lon2: Double): Float {
        val p1 = Math.toRadians(lat1); val p2 = Math.toRadians(lat2); val dl = Math.toRadians(lon2 - lon1)
        val y = sin(dl) * cos(p2)
        val x = cos(p1) * sin(p2) - sin(p1) * cos(p2) * cos(dl)
        return ((Math.toDegrees(atan2(y, x)) + 360.0) % 360.0).toFloat()
    }
}

/**
 * One kept route point. [deviceTimeMs] is the fix time on the measuring device's clock (stable across re-calibration,
 * used for duplicate detection and as the storage key); [fixTimeMs] is phone time, used for ordering (spec §2.2).
 */
@Serializable
data class RoutePoint(
    val source: FixSource,
    val lat: Double,
    val lon: Double,
    val accuracyM: Float,
    val deviceTimeMs: Long,
    val fixTimeMs: Long,
    val bearingDeg: Float? = null,
)

/** The one accuracy rule (spec §2.1/§2.2): route filter and usable-live, phone and watch. Unknown (null) is never accurate. */
object FixQuality {
    const val MAX_ACCURACY_M = 30f
    fun accurate(accuracyM: Float?): Boolean = accuracyM != null && accuracyM <= MAX_ACCURACY_M
}

/**
 * One durable route row (route_point, spec §2.2). Identity = ([source], [deviceTimeMs]) on the measuring device's clock and
 * never changes; [phoneTimeMs] is the calibrated phone time — null while the watch clock is uncalibrated, rewritten when a
 * (re-)calibration normalizes the session (review #2); [receivedAtMs] is the phone time the fix was first received, never
 * rewritten, so "more than 2 min in the future" is judged against receipt in every rebuild and in history (review r2 #2).
 * Only accurate fixes become rows.
 */
@Serializable
data class RouteFix(
    val source: FixSource,
    val lat: Double,
    val lon: Double,
    val accuracyM: Float,
    val deviceTimeMs: Long,
    val phoneTimeMs: Long?,
    val receivedAtMs: Long,
    val bearingDeg: Float? = null,
) {
    /** The ordered point once it has a phone time that is not > 2 min after receipt; uncalibrated rows stay out of ordering. */
    fun point(): RoutePoint? = phoneTimeMs?.let(::pointAt)

    /** History display only: a never-normalized row falls back to device time; the receipt-time rule still applies. */
    fun historyPoint(): RoutePoint? = pointAt(phoneTimeMs ?: deviceTimeMs)

    private fun pointAt(t: Long): RoutePoint? =
        if (t - receivedAtMs > RouteTrack.MAX_FUTURE_MS) null else RoutePoint(source, lat, lon, accuracyM, deviceTimeMs, t, bearingDeg)
}

/** A route row for this fix (received by the phone at [receivedAtMs]), or null when its accuracy is unknown or worse than 30 m. */
fun LocationFix.toRouteFix(source: FixSource, phoneTimeMs: Long?, receivedAtMs: Long): RouteFix? {
    val acc = accuracyM?.takeIf { FixQuality.accurate(it) } ?: return null
    return RouteFix(source, lat, lon, acc, fixTimeMs, phoneTimeMs, receivedAtMs, bearingDeg)
}

enum class RouteAdd { Added, Inaccurate, TooClose, Duplicate, Future }

enum class GpsStatus { Waiting, Live, Delayed, Lost }

/** The current-position marker: the latest live point, or the last one while degraded (spec §2.1). */
data class LivePosition(val lat: Double, val lon: Double, val bearingDeg: Float?, val source: FixSource, val fixTimeMs: Long)

/** What the map renderers draw. */
data class RouteState(
    val sessionId: String? = null,
    val type: WorkoutType = WorkoutType.Walk,
    val route: List<RoutePoint> = emptyList(),
    val start: RoutePoint? = null,
    val live: LivePosition? = null,
    val status: GpsStatus = GpsStatus.Waiting,
)

/**
 * Chronological merge of watch and phone fixes (spec §2.2): sorted by phone-time [RoutePoint.fixTimeMs] (ties watch first),
 * inaccurate (> 30 m) and too-close (< 3 m from the same source's chronological neighbour) points dropped, duplicates
 * (same source, ±50 ms device time, ±1 m) ignored, points > 2 min in the future rejected. Phone points within ±5 s of a
 * watch point stay in [points] (storage) but are hidden from [drawn].
 */
class RouteTrack {
    private val sorted = ArrayList<RoutePoint>()
    val points: List<RoutePoint> get() = sorted

    fun add(p: RoutePoint, nowMs: Long): RouteAdd {
        if (!FixQuality.accurate(p.accuracyM)) return RouteAdd.Inaccurate
        if (p.fixTimeMs - nowMs > MAX_FUTURE_MS) return RouteAdd.Future
        val i = insertionIndex(p)
        val neighbours = listOfNotNull(neighbour(i - 1, -1, p.source), neighbour(i, 1, p.source))
        if (neighbours.any { kotlin.math.abs(it.deviceTimeMs - p.deviceTimeMs) <= DUP_TIME_MS && distance(it, p) <= DUP_DISTANCE_M }) return RouteAdd.Duplicate
        if (neighbours.any { distance(it, p) < MIN_SPACING_M }) return RouteAdd.TooClose
        sorted.add(i, p)
        return RouteAdd.Added
    }

    fun drawn(): List<RoutePoint> {
        val watchTimes = sorted.filter { it.source == FixSource.Watch }.map { it.fixTimeMs }
        if (watchTimes.isEmpty()) return sorted.toList()
        return sorted.filter { it.source == FixSource.Watch || !nearWatch(it.fixTimeMs, watchTimes) }
    }

    /** Bearing from the last two drawn points (used when the fix has none). */
    fun lastBearing(): Float? {
        val d = drawn()
        if (d.size < 2) return null
        val a = d[d.size - 2]; val b = d.last()
        return Geo.bearingDeg(a.lat, a.lon, b.lat, b.lon)
    }

    private fun before(a: RoutePoint, b: RoutePoint): Boolean =
        a.fixTimeMs < b.fixTimeMs || (a.fixTimeMs == b.fixTimeMs && a.source == FixSource.Watch && b.source == FixSource.Phone)

    private fun insertionIndex(p: RoutePoint): Int {
        var lo = 0; var hi = sorted.size
        while (lo < hi) { val mid = (lo + hi) ushr 1; if (before(p, sorted[mid])) hi = mid else lo = mid + 1 }
        return lo
    }

    private fun neighbour(from: Int, step: Int, source: FixSource): RoutePoint? {
        var j = from
        while (j in sorted.indices) { if (sorted[j].source == source) return sorted[j]; j += step }
        return null
    }

    private fun nearWatch(t: Long, times: List<Long>): Boolean {
        val found = times.binarySearch(t)
        if (found >= 0) return true
        val i = -found - 1
        return (i < times.size && times[i] - t <= OVERLAP_MS) || (i > 0 && t - times[i - 1] <= OVERLAP_MS)
    }

    private fun distance(a: RoutePoint, b: RoutePoint) = Geo.distanceM(a.lat, a.lon, b.lat, b.lon)

    companion object {
        const val MAX_ACCURACY_M = FixQuality.MAX_ACCURACY_M
        const val MIN_SPACING_M = 3.0
        const val DUP_TIME_MS = 50L
        const val DUP_DISTANCE_M = 1.0
        const val OVERLAP_MS = 5_000L
        const val MAX_FUTURE_MS = 120_000L

        /**
         * Rebuilds a track from scratch: after a calibration change with the real [nowMs] (review #2: a rebuild, not an
         * incremental repair, so the duplicate filter can never block corrected times), or from history without a clock.
         */
        fun of(points: List<RoutePoint>, nowMs: Long = Long.MAX_VALUE): RouteTrack =
            RouteTrack().also { t -> points.sortedBy { it.fixTimeMs }.forEach { t.add(it, nowMs) } }
    }
}
