package com.debasish.livefit.phone.ui.theme

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Shapes
import androidx.compose.material3.Typography
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.foundation.shape.RoundedCornerShape

/** Brand palette: white surfaces, Rokid-style mint/sky accents, soft pastel icon chips. */
object LiveFitColors {
    val Mint = Color(0xFF14C3A2)
    val MintDeep = Color(0xFF0E9F86)
    val Sky = Color(0xFF3D8BFF)
    val Ink = Color(0xFF15181D)
    val InkSoft = Color(0xFF6B7280)
    val Line = Color(0xFFECEFF3)
    val Surface = Color(0xFFFFFFFF)
    val SurfaceSoft = Color(0xFFF6F8FA)

    // Pastel chip pairs (background, foreground) used for iconography.
    val ChipMint = Color(0xFFE3F8F3) to Color(0xFF0E9F86)
    val ChipSky = Color(0xFFE6F0FF) to Color(0xFF2F6FE0)
    val ChipCoral = Color(0xFFFFEAE5) to Color(0xFFE5583B)
    val ChipAmber = Color(0xFFFFF4DC) to Color(0xFFD08A00)
    val ChipViolet = Color(0xFFF0EAFF) to Color(0xFF7550E0)
    val ChipRose = Color(0xFFFFE8F1) to Color(0xFFD63F7A)
    val ChipSlate = Color(0xFFEEF1F5) to Color(0xFF55606E)

    val HeaderGradient = Brush.verticalGradient(
        listOf(Color(0xFFDDF7F0), Color(0xFFE8F1FF), Color(0xFFFFFFFF)),
    )
}

private val colors = lightColorScheme(
    primary = LiveFitColors.Mint,
    onPrimary = Color.White,
    primaryContainer = LiveFitColors.ChipMint.first,
    onPrimaryContainer = LiveFitColors.MintDeep,
    secondary = LiveFitColors.Sky,
    background = LiveFitColors.Surface,
    onBackground = LiveFitColors.Ink,
    surface = LiveFitColors.Surface,
    onSurface = LiveFitColors.Ink,
    surfaceVariant = LiveFitColors.SurfaceSoft,
    onSurfaceVariant = LiveFitColors.InkSoft,
    outline = LiveFitColors.Line,
    outlineVariant = LiveFitColors.Line,
)

private val typography = Typography(
    headlineMedium = TextStyle(fontSize = 26.sp, fontWeight = FontWeight.Bold, letterSpacing = (-0.3).sp),
    titleLarge = TextStyle(fontSize = 20.sp, fontWeight = FontWeight.SemiBold),
    titleMedium = TextStyle(fontSize = 16.sp, fontWeight = FontWeight.SemiBold),
    bodyMedium = TextStyle(fontSize = 14.sp),
    labelMedium = TextStyle(fontSize = 12.sp, fontWeight = FontWeight.Medium),
)

private val shapes = Shapes(
    small = RoundedCornerShape(12.dp),
    medium = RoundedCornerShape(18.dp),
    large = RoundedCornerShape(24.dp),
)

@Composable
fun LiveFitTheme(content: @Composable () -> Unit) =
    MaterialTheme(colorScheme = colors, typography = typography, shapes = shapes, content = content)
