package com.debasish.livefit.glasses.hud

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.graphics.vector.path
import androidx.compose.ui.unit.dp

/** Outlined footprints for "steps" (not in the Material set). Tinted at draw time. */
val Footprints: ImageVector by lazy {
    ImageVector.Builder("Footprints", 24.dp, 24.dp, 24f, 24f).apply {
        path(stroke = SolidColor(Color.White), strokeLineWidth = 2f, strokeLineCap = StrokeCap.Round) {
            // left sole
            moveTo(7f, 3f); curveTo(4.5f, 3f, 4f, 7f, 4.5f, 10f); curveTo(5f, 12.5f, 9f, 12.5f, 9.5f, 10f); curveTo(10f, 7f, 9.5f, 3f, 7f, 3f); close()
            moveTo(5.5f, 14f); curveTo(5.5f, 16.5f, 9f, 16.5f, 9f, 14f)
            // right sole
            moveTo(17f, 8f); curveTo(14.5f, 8f, 14f, 12f, 14.5f, 15f); curveTo(15f, 17.5f, 19f, 17.5f, 19.5f, 15f); curveTo(20f, 12f, 19.5f, 8f, 17f, 8f); close()
            moveTo(15.5f, 19f); curveTo(15.5f, 21.5f, 19f, 21.5f, 19f, 19f)
        }
    }.build()
}

/** Outlined smart-glasses icon for the glasses battery ring. */
val GlassesIcon: ImageVector by lazy {
    ImageVector.Builder("Glasses", 24.dp, 24.dp, 24f, 24f).apply {
        path(stroke = SolidColor(Color.White), strokeLineWidth = 2f, strokeLineCap = StrokeCap.Round) {
            // left lens
            moveTo(3f, 10f); lineTo(10f, 10f); lineTo(10f, 13f); curveTo(10f, 15f, 8.5f, 16f, 6.5f, 16f); curveTo(4.5f, 16f, 3f, 15f, 3f, 13f); close()
            // right lens
            moveTo(14f, 10f); lineTo(21f, 10f); lineTo(21f, 13f); curveTo(21f, 15f, 19.5f, 16f, 17.5f, 16f); curveTo(15.5f, 16f, 14f, 15f, 14f, 13f); close()
            // bridge
            moveTo(10f, 11f); curveTo(11f, 10f, 13f, 10f, 14f, 11f)
            // temples
            moveTo(3f, 10f); lineTo(1.5f, 8f)
            moveTo(21f, 10f); lineTo(22.5f, 8f)
        }
    }.build()
}
