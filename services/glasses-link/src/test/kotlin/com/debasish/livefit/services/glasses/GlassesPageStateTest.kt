package com.debasish.livefit.services.glasses

import com.debasish.livefit.model.GlassesChannels
import com.debasish.livefit.model.HudPage
import com.debasish.livefit.model.PageState
import com.debasish.livefit.model.Wire
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class GlassesPageStateTest {
    @Test fun pageStateIsDecoded() = assertEquals(
        PageState(page = HudPage.Map, seq = 3),
        GlassesInbound().pageState(GlassesChannels.PAGE_STATE, Wire.encode(PageState(page = HudPage.Map, seq = 3))),
    )

    @Test fun otherChannelsAndVersionsAreNotPageStates() {
        val g = GlassesInbound()
        assertNull(g.pageState(GlassesChannels.COMMAND, Wire.encode(PageState(page = HudPage.Map, seq = 3))))
        assertNull(g.pageState(GlassesChannels.PAGE_STATE, """{"protocolVersion":3,"page":"Map","seq":1}"""))
    }
}
