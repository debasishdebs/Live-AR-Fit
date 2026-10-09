package com.debasish.livefit.map

import kotlin.math.PI
import kotlin.math.atan
import kotlin.math.cos
import kotlin.math.floor
import kotlin.math.ln
import kotlin.math.pow
import kotlin.math.sinh
import kotlin.math.tan

/** Spec §2.4: always visible on every map (glasses image, watch map). */
const val OSM_ATTRIBUTION = "© OpenStreetMap contributors"

/** One slippy-map tile (OpenStreetMap numbering). */
data class TileId(val z: Int, val x: Int, val y: Int)

/** A pixel position inside a viewport (origin top-left). */
data class Px(val x: Float, val y: Float)

/** Web-Mercator slippy-tile math in "world pixels" (256 × 2^z per side), spec §2.3. */
object TileMath {
    const val TILE_SIZE = 256
    const val EARTH_CIRCUMFERENCE_M = 40_075_016.686
    const val MAX_LAT = 85.05112878

    fun worldSize(z: Int): Double = TILE_SIZE * 2.0.pow(z)

    fun lonToWorldX(lon: Double, z: Int): Double = (lon + 180.0) / 360.0 * worldSize(z)

    fun latToWorldY(lat: Double, z: Int): Double {
        val r = Math.toRadians(lat.coerceIn(-MAX_LAT, MAX_LAT))
        return (1.0 - ln(tan(r) + 1.0 / cos(r)) / PI) / 2.0 * worldSize(z)
    }

    fun worldXToLon(x: Double, z: Int): Double = x / worldSize(z) * 360.0 - 180.0

    fun worldYToLat(y: Double, z: Int): Double = Math.toDegrees(atan(sinh(PI - 2.0 * PI * y / worldSize(z))))

    fun tileFor(lat: Double, lon: Double, z: Int): TileId {
        val n = 1 shl z
        val x = Math.floorMod(floor(lonToWorldX(lon, z) / TILE_SIZE).toInt(), n)
        val y = floor(latToWorldY(lat, z) / TILE_SIZE).toInt().coerceIn(0, n - 1)
        return TileId(z, x, y)
    }

    fun metersPerPixel(lat: Double, z: Int): Double = EARTH_CIRCUMFERENCE_M * cos(Math.toRadians(lat)) / worldSize(z)
}
