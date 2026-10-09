package com.debasish.livefit.map

import com.debasish.livefit.model.WorkoutType
import kotlin.math.floor

/** A tile placed at screen offset ([left], [top]) inside a viewport; may be partly outside it. */
data class PlacedTile(val tile: TileId, val left: Float, val top: Float)

/** North-up view centred on a point (spec §2.3); shared by the glasses renderer and the watch map so both look alike. */
data class Viewport(val centerLat: Double, val centerLon: Double, val zoom: Int, val widthPx: Int, val heightPx: Int) {
    private val cx = TileMath.lonToWorldX(centerLon, zoom)
    private val cy = TileMath.latToWorldY(centerLat, zoom)

    fun project(lat: Double, lon: Double): Px = Px(
        (TileMath.lonToWorldX(lon, zoom) - cx + widthPx / 2.0).toFloat(),
        (TileMath.latToWorldY(lat, zoom) - cy + heightPx / 2.0).toFloat(),
    )

    /** Exactly the tiles that intersect the view: at most 3×3 for 480 px (spec §2.4: no prefetch beyond them). */
    fun tiles(): List<PlacedTile> {
        val size = TileMath.TILE_SIZE
        val n = 1 shl zoom
        val left = cx - widthPx / 2.0
        val top = cy - heightPx / 2.0
        val firstCol = floor(left / size).toInt()
        val lastCol = floor((left + widthPx - 1e-6) / size).toInt()
        val firstRow = floor(top / size).toInt()
        val lastRow = floor((top + heightPx - 1e-6) / size).toInt()
        val out = ArrayList<PlacedTile>()
        for (row in firstRow..lastRow) {
            if (row < 0 || row >= n) continue
            for (col in firstCol..lastCol) {
                out += PlacedTile(TileId(zoom, Math.floorMod(col, n), row), (col * size - left).toFloat(), (row * size - top).toFloat())
            }
        }
        return out
    }

    fun metersPerPixel(): Double = TileMath.metersPerPixel(centerLat, zoom)

    companion object {
        const val DEFAULT_ZOOM = 18
        const val CYCLE_ZOOM = 17
        const val MIN_ZOOM = 14
        const val MAX_ZOOM = 18

        fun zoomFor(type: WorkoutType): Int = if (type == WorkoutType.Cycle) CYCLE_ZOOM else DEFAULT_ZOOM

        /** The highest zoom (≤ [maxZoom]) whose view holds every point inside [paddingPx]; null for no points. */
        fun fit(points: List<Pair<Double, Double>>, widthPx: Int, heightPx: Int, paddingPx: Int = 16, maxZoom: Int = MAX_ZOOM): Viewport? {
            if (points.isEmpty()) return null
            val minLat = points.minOf { it.first }; val maxLat = points.maxOf { it.first }
            val minLon = points.minOf { it.second }; val maxLon = points.maxOf { it.second }
            fun at(z: Int): Viewport {
                val x = (TileMath.lonToWorldX(minLon, z) + TileMath.lonToWorldX(maxLon, z)) / 2
                val y = (TileMath.latToWorldY(maxLat, z) + TileMath.latToWorldY(minLat, z)) / 2
                return Viewport(TileMath.worldYToLat(y, z), TileMath.worldXToLon(x, z), z, widthPx, heightPx)
            }
            for (z in maxZoom downTo 1) {
                val w = TileMath.lonToWorldX(maxLon, z) - TileMath.lonToWorldX(minLon, z)
                val h = TileMath.latToWorldY(minLat, z) - TileMath.latToWorldY(maxLat, z)
                if (w <= widthPx - 2 * paddingPx && h <= heightPx - 2 * paddingPx) return at(z)
            }
            return at(1)
        }
    }
}
