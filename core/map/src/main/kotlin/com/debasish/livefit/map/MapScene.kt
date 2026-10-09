package com.debasish.livefit.map

import com.debasish.livefit.model.GpsStatus
import com.debasish.livefit.model.RouteState
import kotlin.math.hypot

data class MapArrow(val at: Px, val bearingDeg: Float?, val hollow: Boolean)
data class ScaleBar(val lengthPx: Float, val label: String)

/** Everything a map renderer draws; the glasses PNG renderer and the watch Canvas draw the same scene. */
data class MapScene(
    val viewport: Viewport?,
    val route: List<Px>,
    val start: Px?,
    val arrow: MapArrow?,
    val scale: ScaleBar?,
    val caption: String?,
    val attribution: String = OSM_ATTRIBUTION,
)

object MapSceneBuilder {
    const val MAX_ROUTE_POINTS = 600
    const val MIN_STEP_PX = 2f
    const val NO_TILES_CAPTION = "No map — route only"
    private val NICE_M = listOf(10, 20, 50, 100, 200, 500, 1_000, 2_000, 5_000)

    fun caption(status: GpsStatus): String? = when (status) {
        GpsStatus.Waiting -> "Waiting for GPS…"
        GpsStatus.Delayed -> "GPS delayed"
        GpsStatus.Lost -> "GPS lost"
        GpsStatus.Live -> null
    }

    /** North-up, centred on the marker (or the last route point while waiting); no point at all = caption only. */
    fun build(state: RouteState, zoom: Int, widthPx: Int, heightPx: Int): MapScene {
        val caption = caption(state.status)
        val centre = state.live?.let { it.lat to it.lon } ?: state.route.lastOrNull()?.let { it.lat to it.lon }
            ?: return MapScene(null, emptyList(), null, null, null, caption)
        val vp = Viewport(centre.first, centre.second, zoom, widthPx, heightPx)
        return MapScene(
            viewport = vp,
            route = decimate(state.route.map { vp.project(it.lat, it.lon) }),
            start = state.start?.let { vp.project(it.lat, it.lon) },
            arrow = state.live?.let { MapArrow(vp.project(it.lat, it.lon), it.bearingDeg, hollow = state.status != GpsStatus.Live) },
            scale = scaleBar(vp),
            caption = caption,
        )
    }

    /** Drops points closer than [minStepPx] to the last kept one and caps the count at [max]; first and last are kept. */
    fun decimate(points: List<Px>, minStepPx: Float = MIN_STEP_PX, max: Int = MAX_ROUTE_POINTS): List<Px> {
        if (points.size <= 2) return points
        val kept = ArrayList<Px>()
        kept += points.first()
        for (i in 1 until points.size - 1) {
            val p = points[i]; val l = kept.last()
            if (hypot(p.x - l.x, p.y - l.y) >= minStepPx) kept += p
        }
        kept += points.last()
        if (kept.size <= max) return kept
        val step = (kept.size + max - 2) / (max - 1)
        val thinned = kept.filterIndexed { i, _ -> i % step == 0 }.toMutableList()
        if (thinned.last() != kept.last()) thinned += kept.last()
        return thinned
    }

    /** The longest "nice" length that fits a quarter of the width. */
    fun scaleBar(vp: Viewport): ScaleBar {
        val mpp = vp.metersPerPixel()
        val maxM = vp.widthPx / 4.0 * mpp
        val m = NICE_M.lastOrNull { it <= maxM } ?: NICE_M.first()
        return ScaleBar((m / mpp).toFloat(), if (m >= 1_000) "${m / 1_000} km" else "$m m")
    }
}
