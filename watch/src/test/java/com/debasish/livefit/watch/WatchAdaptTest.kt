package com.debasish.livefit.watch

import com.debasish.livefit.watch.map.WatchMapModel
import com.debasish.livefit.watch.ui.AmbientStyle
import com.debasish.livefit.watch.ui.ScreenScale
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class WatchAdaptTest {
    @Test fun provenanceIsGenericWearOs() = assertEquals("wear-os/health-services", WatchProvenance.SOURCE)

    @Test fun buttonZoomIsClamped() {
        assertEquals(18, WatchMapModel.zoomBy(18, +1))
        assertEquals(14, WatchMapModel.zoomBy(14, -1))
        assertEquals(16, WatchMapModel.zoomBy(15, +1))
    }

    @Test fun referenceRoundScreenKeepsTheCurrentPadding() = assertEquals(28f, ScreenScale.dp(28f, ScreenScale.REFERENCE_DP, round = true), 0.001f)

    @Test fun smallerScreensScaleDown() = assertTrue(ScreenScale.dp(28f, 180f, round = true) < 28f)

    @Test fun squareScreensNeedLessInset() = assertTrue(ScreenScale.dp(28f, ScreenScale.REFERENCE_DP, round = false) < 28f)

    @Test fun ambientNormalIsGreyFilled() {
        val s = AmbientStyle.of(burnInProtection = false, lowBit = false)
        assertFalse(s.outline); assertFalse(s.monochrome)
        assertEquals(0 to 0, s.shift(7))
    }

    @Test fun ambientBurnInOutlinesAndShiftsEachMinute() {
        val s = AmbientStyle.of(burnInProtection = true, lowBit = true)
        assertTrue(s.outline); assertTrue(s.monochrome)
        assertTrue((0..20L).map { s.shift(it) }.toSet().size > 1)
        assertTrue((0..20L).all { val (x, y) = s.shift(it); x in -2..2 && y in -2..2 })
    }
}
