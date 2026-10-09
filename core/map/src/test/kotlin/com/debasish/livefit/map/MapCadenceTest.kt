package com.debasish.livefit.map

import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class MapCadenceTest {
    private val lat = 12.9716
    private val lon = 77.5946
    private fun north(m: Double) = lat + m / 111_195.0

    /** Spec §2.5: every 3 s or after ≥ 25 m, whichever first; never more than 1/s. */
    @Test fun everyThreeSecondsOrTwentyFiveMetresButAtMostOncePerSecond() {
        val c = MapCadence()
        assertTrue(c.due(0, lat, lon)); c.rendered(0, lat, lon)
        assertFalse(c.due(2_999, lat, lon))
        assertTrue(c.due(3_000, lat, lon))
        c.rendered(3_000, lat, lon)
        assertFalse(c.due(3_800, north(26.0), lon), "≤ 1/s even when moving fast")
        assertTrue(c.due(4_200, north(26.0), lon), "≥ 25 m moved")
        assertFalse(c.due(4_200, north(24.0), lon))
    }

    @Test fun resetMakesTheNextRenderImmediate() {
        val c = MapCadence()
        c.rendered(0, lat, lon)
        c.reset()
        assertTrue(c.due(10, lat, lon))
    }

    @Test fun withoutAPositionOnlyThePeriodCounts() {
        val c = MapCadence()
        c.rendered(0, null, null)
        assertFalse(c.due(2_000, lat, lon))
        assertTrue(c.due(3_000, null, null))
    }
}
