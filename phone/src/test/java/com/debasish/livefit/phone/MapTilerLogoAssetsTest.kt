package com.debasish.livefit.phone

import java.io.File
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** The MapTiler artwork is shown as published: no recolour/tint paths remain in the code that draws it. */
class MapTilerLogoAssetsTest {
    private fun src(rel: String) = File("src/main/$rel").readText()

    @Test fun lightVariantIsOfficialAndHasNoCurrentColor() {
        val xml = src("res/drawable/maptiler_logo_light.xml")
        assertTrue("maptiler-logo-adaptive.svg" in xml) // source URL recorded
        assertFalse("fillColor=\"currentColor\"" in xml)
    }

    @Test fun phoneRowAndGlassesRendererApplyNoColorFilter() {
        assertFalse("ColorFilter" in src("java/com/debasish/livefit/phone/ui/components/MapAttributionRow.kt"))
        assertFalse("ColorFilter" in src("java/com/debasish/livefit/phone/map/GlassesMapRenderer.kt"))
    }
}
