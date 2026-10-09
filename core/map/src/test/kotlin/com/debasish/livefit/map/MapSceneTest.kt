package com.debasish.livefit.map

import com.debasish.livefit.model.FixSource
import com.debasish.livefit.model.GpsStatus
import com.debasish.livefit.model.LivePosition
import com.debasish.livefit.model.RoutePoint
import com.debasish.livefit.model.RouteState
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class MapSceneTest {
    private val lat0 = 20.0
    private val lon0 = 77.0
    private fun pt(northM: Double, t: Long = 0) = RoutePoint(FixSource.Watch, lat0 + northM / 111_195.0, lon0, 5f, t, t)

    @Test fun waitingWithoutPointsIsCaptionOnly() {
        val s = MapSceneBuilder.build(RouteState(sessionId = "s"), 18, 480, 480)
        assertNull(s.viewport)
        assertEquals("Waiting for GPS…", s.caption)
        assertEquals("© OpenStreetMap contributors", s.attribution, "attribution always present")
    }

    @Test fun waitingWithStoredPointsCentresOnTheLastPoint() {
        val s = MapSceneBuilder.build(RouteState(sessionId = "s", route = listOf(pt(0.0), pt(50.0))), 18, 480, 480)
        val vp = assertNotNull(s.viewport)
        assertEquals(lat0 + 50.0 / 111_195.0, vp.centerLat, 1e-9)
        assertNull(s.arrow)
        assertEquals("Waiting for GPS…", s.caption)
    }

    @Test fun liveArrowIsCentredAndSolid() {
        val live = LivePosition(lat0, lon0, 45f, FixSource.Watch, 0)
        val s = MapSceneBuilder.build(RouteState(sessionId = "s", route = listOf(pt(-40.0), pt(0.0)), start = pt(-40.0), live = live, status = GpsStatus.Live), 18, 480, 480)
        val a = assertNotNull(s.arrow)
        assertEquals(Px(240f, 240f), a.at)
        assertFalse(a.hollow)
        assertNull(s.caption)
        assertTrue(assertNotNull(s.start).y > 240f, "start is south of the current position (below)")
    }

    /** Spec §2.1: hollow arrow + "GPS delayed" for 10–30 s, "GPS lost" after. */
    @Test fun degradedStatesAreHollow() {
        val live = LivePosition(lat0, lon0, null, FixSource.Phone, 0)
        val delayed = MapSceneBuilder.build(RouteState(sessionId = "s", live = live, status = GpsStatus.Delayed), 18, 480, 480)
        assertTrue(assertNotNull(delayed.arrow).hollow); assertEquals("GPS delayed", delayed.caption)
        val lost = MapSceneBuilder.build(RouteState(sessionId = "s", live = live, status = GpsStatus.Lost), 18, 480, 480)
        assertTrue(assertNotNull(lost.arrow).hollow); assertEquals("GPS lost", lost.caption)
    }

    @Test fun decimationDropsSubPixelStepsAndCaps() {
        val line = List(10_000) { Px(it * 0.1f, 0f) }
        val d = MapSceneBuilder.decimate(line)
        assertTrue(d.size <= 600, "${d.size}")
        assertEquals(line.first(), d.first()); assertEquals(line.last(), d.last())
        val zigzag = List(5_000) { Px(it * 3f, if (it % 2 == 0) 0f else 3f) }
        assertTrue(MapSceneBuilder.decimate(zigzag).size <= 600)
    }

    @Test fun scaleBarPicksANiceLength() {
        val z18 = MapSceneBuilder.scaleBar(Viewport(lat0, lon0, 18, 480, 480))
        assertEquals("50 m", z18.label)
        assertEquals(89.1f, z18.lengthPx, 1f)
        assertEquals("1 km", MapSceneBuilder.scaleBar(Viewport(lat0, lon0, 14, 480, 480)).label)
    }
}
