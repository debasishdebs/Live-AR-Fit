package com.debasish.livefit.phone.setup

import android.provider.Settings
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.CubicBezierEasing
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.scale
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.PathMeasure
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.debasish.livefit.phone.ui.theme.LiveFitColors

/** Reduced motion: the system animator scale is 0 (Developer options / "Remove animations"). */
@Composable
private fun reducedMotion(): Boolean {
    val resolver = LocalContext.current.contentResolver
    return remember { runCatching { Settings.Global.getFloat(resolver, Settings.Global.ANIMATOR_DURATION_SCALE, 1f) == 0f }.getOrDefault(false) }
}

/** Green disc that scales in while the tick stroke draws (Material emphasized-decelerate easing); static when motion is reduced. */
@Composable
fun GrantTick(size: Dp = 96.dp, modifier: Modifier = Modifier) {
    val still = reducedMotion()
    val scale = remember { Animatable(if (still) 1f else 0.6f) }
    val draw = remember { Animatable(if (still) 1f else 0f) }
    LaunchedEffect(still) {
        if (still) return@LaunchedEffect
        scale.animateTo(1f, tween(250, easing = CubicBezierEasing(0.05f, 0.7f, 0.1f, 1f)))
    }
    LaunchedEffect(still) {
        if (still) return@LaunchedEffect
        draw.animateTo(1f, tween(TICK_DRAW_MS, delayMillis = 100, easing = CubicBezierEasing(0.2f, 0f, 0f, 1f)))
    }
    Canvas(modifier.size(size).scale(scale.value)) {
        drawCircle(LiveFitColors.Mint)
        val w = this.size.width
        val tick = Path().apply {
            moveTo(w * 0.28f, w * 0.52f); lineTo(w * 0.44f, w * 0.68f); lineTo(w * 0.73f, w * 0.34f)
        }
        val measure = PathMeasure().apply { setPath(tick, false) }
        val part = Path()
        measure.getSegment(0f, measure.length * draw.value, part, true)
        drawPath(part, Color.White, style = Stroke(width = w * 0.09f, cap = StrokeCap.Round, join = StrokeJoin.Round))
    }
}
