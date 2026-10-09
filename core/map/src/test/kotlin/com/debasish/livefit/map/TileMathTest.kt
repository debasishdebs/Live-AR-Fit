package com.debasish.livefit.map

import com.debasish.livefit.model.WorkoutType
import kotlin.math.abs
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class TileMathTest {
    /** Spec §2.3: ~270 m across 480 px at latitude 20° on zoom 18; ~1.08 km on zoom 16. */
    @Test fun metersAcross480pxMatchSpec() {
        assertEquals(269.4, TileMath.metersPerPixel(20.0, 18) * 480, 1.0)
        assertEquals(1077.6, TileMath.metersPerPixel(20.0, 16) * 480, 3.0)
    }

    @Test fun knownTiles() {
        assertEquals(TileId(1, 1, 1), TileMath.tileFor(-0.1, 0.1, 1))
        assertEquals(TileId(10, 511, 340), TileMath.tileFor(51.5074, -0.1278, 10)) // London
    }

    @Test fun latLonRoundTrip() {
        val x = TileMath.lonToWorldX(77.5946, 18); val y = TileMath.latToWorldY(12.9716, 18)
        assertEquals(77.5946, TileMath.worldXToLon(x, 18), 1e-9)
        assertEquals(12.9716, TileMath.worldYToLat(y, 18), 1e-9)
    }

    @Test fun centreProjectsToTheMiddleAndOneTileEastIs256px() {
        val vp = Viewport(12.9716, 77.5946, 18, 480, 480)
        assertEquals(Px(240f, 240f), vp.project(12.9716, 77.5946))
        val oneTileEast = TileMath.worldXToLon(TileMath.lonToWorldX(77.5946, 18) + 256, 18)
        assertEquals(496f, vp.project(12.9716, oneTileEast).x, 0.01f)
    }

    /** Spec §2.4: no prefetch beyond the visible 3×3 tiles; the visible ones cover the whole image. */
    @Test fun tilesAreAtMostThreeByThreeAndCoverTheViewport() {
        for (i in 0 until 50) {
            val vp = Viewport(12.9716 + i * 0.00037, 77.5946 + i * 0.00041, 18, 480, 480)
            val tiles = vp.tiles()
            assertTrue(tiles.size in 4..9, "got ${tiles.size}")
            for ((x, y) in listOf(0f to 0f, 479f to 0f, 0f to 479f, 479f to 479f, 240f to 240f)) {
                assertTrue(tiles.any { x >= it.left && x < it.left + 256 && y >= it.top && y < it.top + 256 }, "($x,$y) uncovered at $i")
            }
        }
    }

    @Test fun tilesWrapAroundTheAntimeridian() {
        val tiles = Viewport(0.0, 179.9999, 18, 480, 480).tiles()
        val n = 1 shl 18
        assertTrue(tiles.all { it.tile.x in 0 until n })
        assertTrue(tiles.any { it.tile.x == 0 }, "east of 180° wraps to column 0")
    }

    /** Spec §2.3: zoom 18 default, 17 for Cycle. */
    @Test fun zoomPerWorkoutType() {
        assertEquals(17, Viewport.zoomFor(WorkoutType.Cycle))
        for (t in listOf(WorkoutType.Walk, WorkoutType.Run, WorkoutType.Auto)) assertEquals(18, Viewport.zoomFor(t))
    }

    @Test fun fitKeepsEveryPointInsideThePadding() {
        val pts = listOf(12.9716 to 77.5946, 12.9761 to 77.5990, 12.9700 to 77.6010)
        val vp = assertNotNull(Viewport.fit(pts, 300, 200, paddingPx = 16))
        for ((lat, lon) in pts) {
            val p = vp.project(lat, lon)
            assertTrue(p.x in 15.9f..284.1f && p.y in 15.9f..184.1f, "$p outside")
        }
        assertEquals(18, Viewport.fit(listOf(12.9716 to 77.5946), 300, 200)!!.zoom, "one point = max zoom")
        assertNull(Viewport.fit(emptyList(), 300, 200))
    }

    @Test fun metersPerPixelShrinksTowardsThePoles() =
        assertTrue(abs(TileMath.metersPerPixel(60.0, 18) - TileMath.metersPerPixel(0.0, 18) / 2) < 0.01)
}
