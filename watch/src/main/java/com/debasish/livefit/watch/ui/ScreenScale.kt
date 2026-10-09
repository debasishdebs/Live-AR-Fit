package com.debasish.livefit.watch.ui

import androidx.compose.runtime.Composable
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

/**
 * Paddings are authored for the Galaxy round screen ([REFERENCE_DP] wide) and scale with the actual screen; a
 * square screen has no corners to clear, so it needs less. Pure.
 */
object ScreenScale {
    const val REFERENCE_DP = 225f
    private const val SQUARE_FACTOR = 0.6f

    fun dp(baseDp: Float, screenDp: Float, round: Boolean, referenceDp: Float = REFERENCE_DP): Float =
        baseDp * (screenDp / referenceDp) * if (round) 1f else SQUARE_FACTOR
}

/** [x] scales with the screen width, [y] with its height (both against the reference round screen). */
class ScreenInsets(private val widthDp: Float, private val heightDp: Float, private val round: Boolean) {
    fun x(baseDp: Int): Dp = ScreenScale.dp(baseDp.toFloat(), widthDp, round).dp
    fun y(baseDp: Int): Dp = ScreenScale.dp(baseDp.toFloat(), heightDp, round).dp
}

@Composable
internal fun rememberInsets(): ScreenInsets {
    val c = LocalConfiguration.current
    return ScreenInsets(c.screenWidthDp.toFloat(), c.screenHeightDp.toFloat(), c.isScreenRound)
}
