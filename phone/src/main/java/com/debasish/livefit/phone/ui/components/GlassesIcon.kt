package com.debasish.livefit.phone.ui.components

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.graphics.vector.path
import androidx.compose.ui.unit.dp

/** Outlined smart-glasses icon; replaces the eye icon for glasses everywhere (approved design). */
val GlassesIcon: ImageVector by lazy {
    ImageVector.Builder("Glasses", 24.dp, 24.dp, 24f, 24f).apply {
        path(stroke = SolidColor(Color.Black), strokeLineWidth = 2f, strokeLineCap = StrokeCap.Round) {
            moveTo(3f, 10f); lineTo(10f, 10f); lineTo(10f, 13f); curveTo(10f, 15f, 8.5f, 16f, 6.5f, 16f); curveTo(4.5f, 16f, 3f, 15f, 3f, 13f); close()
            moveTo(14f, 10f); lineTo(21f, 10f); lineTo(21f, 13f); curveTo(21f, 15f, 19.5f, 16f, 17.5f, 16f); curveTo(15.5f, 16f, 14f, 15f, 14f, 13f); close()
            moveTo(10f, 11f); curveTo(11f, 10f, 13f, 10f, 14f, 11f)
            moveTo(3f, 10f); lineTo(1.5f, 8f)
            moveTo(21f, 10f); lineTo(22.5f, 8f)
        }
    }.build()
}
