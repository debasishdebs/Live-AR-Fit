package com.debasish.livefit.map

import com.debasish.livefit.model.FixSource
import com.debasish.livefit.model.RoutePoint
import kotlin.test.Test
import kotlin.test.assertTrue

class RouteThumbnailTest {
    private fun pt(n: Int) = RoutePoint(FixSource.Watch, 12.97 + n * 0.0001, 77.59 + n * 0.00005, 5f, n * 1_000L, n * 1_000L)

    /** Spec §2.2: history detail shows a static route thumbnail. */
    @Test fun routeFitsInsideThePadding() {
        val px = RouteThumbnail.project((0 until 300).map(::pt), 600, 300, paddingPx = 8)
        assertTrue(px.size in 2..600)
        assertTrue(px.all { it.x in 7.9f..592.1f && it.y in 7.9f..292.1f })
    }

    @Test fun noPointsNoThumbnail() = assertTrue(RouteThumbnail.project(emptyList(), 600, 300).isEmpty())
}
