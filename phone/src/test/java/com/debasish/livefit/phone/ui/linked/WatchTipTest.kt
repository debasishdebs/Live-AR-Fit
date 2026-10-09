package com.debasish.livefit.phone.ui.linked

import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertFalse

class WatchTipTest {
    @Test fun samsungNamesGetTheGalaxyPath() {
        for (n in listOf("Galaxy Watch6 Classic (A1B2)", "SM-R960", "galaxy watch")) assertContains(watchMediaControlsTip(n), "Settings → Apps → Media controls")
    }
    @Test fun pixelGetsPixelWording() = assertContains(watchMediaControlsTip("Pixel Watch 3"), "Auto-launch media controls")
    @Test fun otherOrUnknownIsGeneric() {
        for (n in listOf("TicWatch Pro 5", "Watch", "", null)) {
            val t = watchMediaControlsTip(n)
            assertContains(t, "Turn off auto-launch of media controls in your watch's settings")
            assertFalse("Galaxy" in t || "Pixel" in t)
        }
    }
}
