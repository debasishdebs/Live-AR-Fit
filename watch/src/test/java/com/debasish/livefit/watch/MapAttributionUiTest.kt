package com.debasish.livefit.watch

import com.debasish.livefit.map.Attributions
import com.debasish.livefit.watch.ui.attributionLines
import kotlin.test.Test
import kotlin.test.assertEquals

class MapAttributionUiTest {
    /** Review fix: the MapTiler credit is split per provider so it fits the bottom of a round screen; nothing is lost. */
    @Test fun attributionIsSplitOnePerCredit() {
        assertEquals(listOf("© MapTiler", "© OpenStreetMap contributors"), attributionLines(Attributions.MAPTILER.text))
        assertEquals(listOf("© OpenStreetMap contributors"), attributionLines(Attributions.OSM.text))
    }
}
