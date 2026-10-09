package com.debasish.livefit.map

enum class OsmClass { Background, Water, Park, MinorRoad, MajorRoad }

/**
 * Raster tile pixels (MapTiler streets-v2 in production, the OSM standard style in keyless debug builds) → HUD palette
 * (spec §2.5): black background (transparent on the glasses), streets as dim green, water and parks dropped, labels
 * and buildings dropped. Only three output values, so PNGs compress well. The watch uses the same mapping with its
 * mint tint. Both styles' main colours are covered by HudPaletteTest.
 */
object HudPalette {
    const val HUD_GREEN = 0x3CFF6E
    const val MINOR_LEVEL = 0.30f
    const val MAJOR_LEVEL = 0.45f

    fun classify(argb: Int): OsmClass {
        if ((argb ushr 24) < 128) return OsmClass.Background
        val r = (argb shr 16) and 0xFF
        val g = (argb shr 8) and 0xFF
        val b = argb and 0xFF
        if (b >= r + 20 && b >= g) return OsmClass.Water
        if (g >= r + 12 && g >= b + 12) return OsmClass.Park
        if (r >= 0xE0 && r - b >= 0x30) return OsmClass.MajorRoad // yellow / orange / pink road casings
        val lum = (0.2126 * r + 0.7152 * g + 0.0722 * b) / 255.0
        return if (lum >= 0.97) OsmClass.MinorRoad else OsmClass.Background // white streets; beige land is ~0.94
    }

    fun convert(argb: Int, tint: Int = HUD_GREEN): Int = when (classify(argb)) {
        OsmClass.MinorRoad -> scaled(tint, MINOR_LEVEL)
        OsmClass.MajorRoad -> scaled(tint, MAJOR_LEVEL)
        else -> 0xFF000000.toInt()
    }

    /** Opaque [tint] scaled to [level] brightness (black = off). */
    fun scaled(tint: Int, level: Float): Int {
        val r = (((tint shr 16) and 0xFF) * level).toInt()
        val g = (((tint shr 8) and 0xFF) * level).toInt()
        val b = ((tint and 0xFF) * level).toInt()
        return (0xFF shl 24) or (r shl 16) or (g shl 8) or b
    }

    fun convertAll(pixels: IntArray, tint: Int = HUD_GREEN) {
        for (i in pixels.indices) pixels[i] = convert(pixels[i], tint)
    }
}
