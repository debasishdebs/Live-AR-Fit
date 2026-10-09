package com.debasish.livefit.map

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class HudPaletteTest {
    // A sample "tile": the OSM standard style's main colours, as they appear in a real 256×256 tile.
    private val land = 0xFFF2EFE9.toInt()
    private val residential = 0xFFE0DFDF.toInt()
    private val building = 0xFFD9D0C9.toInt()
    private val water = 0xFFAAD3DF.toInt()
    private val park = 0xFFC8FACC.toInt()
    private val grass = 0xFFCDEBB0.toInt()
    private val forest = 0xFFADD19E.toInt()
    private val minorRoad = 0xFFFFFFFF.toInt()
    private val secondary = 0xFFF7FABF.toInt()
    private val primary = 0xFFFCD6A4.toInt()
    private val motorway = 0xFFE892A2.toInt()
    private val label = 0xFF333333.toInt()
    private val transparent = 0x00FFFFFF

    /** Review fix: production tiles are MapTiler streets-v2, so its main colours must map the same way. */
    @Test fun classifiesTheMapTilerStreetsV2Palette() {
        val mtLand = 0xFFEAE4D3.toInt()
        val mtBuilding = 0xFFDFD8CC.toInt()
        val mtWater = 0xFF8DBEE3.toInt()
        val mtPark = 0xFFC1DB9F.toInt()
        val mtMinorRoad = 0xFFFFFFFF.toInt()
        val mtPrimary = 0xFFFFE9A6.toInt()
        val mtMotorway = 0xFFF7B46A.toInt()
        for (c in listOf(mtLand, mtBuilding)) assertEquals(OsmClass.Background, HudPalette.classify(c), "%08x".format(c))
        assertEquals(OsmClass.Water, HudPalette.classify(mtWater))
        assertEquals(OsmClass.Park, HudPalette.classify(mtPark))
        assertEquals(OsmClass.MinorRoad, HudPalette.classify(mtMinorRoad))
        for (c in listOf(mtPrimary, mtMotorway)) assertEquals(OsmClass.MajorRoad, HudPalette.classify(c), "%08x".format(c))
    }

    @Test fun classifiesTheOsmStandardPalette() {
        for (c in listOf(land, residential, building, label, transparent)) assertEquals(OsmClass.Background, HudPalette.classify(c), "%08x".format(c))
        assertEquals(OsmClass.Water, HudPalette.classify(water))
        for (c in listOf(park, grass, forest)) assertEquals(OsmClass.Park, HudPalette.classify(c), "%08x".format(c))
        assertEquals(OsmClass.MinorRoad, HudPalette.classify(minorRoad))
        for (c in listOf(secondary, primary, motorway)) assertEquals(OsmClass.MajorRoad, HudPalette.classify(c), "%08x".format(c))
    }

    /** Spec §2.5: black background, streets as dim green luminance, water/park dropped. */
    @Test fun convertsASampleTileToTheHudPalette() {
        val tile = IntArray(256 * 256) { i -> listOf(land, water, park, minorRoad, primary, building, label)[i % 7] }
        HudPalette.convertAll(tile)
        val black = 0xFF000000.toInt()
        assertEquals(black, tile[0], "land → black (transparent on the HUD)")
        assertEquals(black, tile[1], "water dropped")
        assertEquals(black, tile[2], "park dropped")
        assertEquals(HudPalette.scaled(HudPalette.HUD_GREEN, HudPalette.MINOR_LEVEL), tile[3])
        assertEquals(HudPalette.scaled(HudPalette.HUD_GREEN, HudPalette.MAJOR_LEVEL), tile[4])
        assertEquals(3, tile.distinct().size, "three levels only, so the PNG stays small")
    }

    @Test fun streetsAreDimNotBright() {
        val g = (HudPalette.convert(primary) shr 8) and 0xFF
        assertTrue(g in 100..120, "major road green channel $g ≈ 45 % of 0xFF")
        assertEquals(0xFF000000.toInt() or (0x06 shl 16) or (0x3A shl 8) or 0x30, HudPalette.convert(minorRoad, tint = 0x14C3A2), "watch mint tint, minor road")
    }
}
