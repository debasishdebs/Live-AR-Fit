package com.debasish.livefit.watch.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.offset
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.wear.compose.material.Text
import com.debasish.livefit.model.WorkoutPhase
import com.debasish.livefit.model.WorkoutSnapshot

/** Always-on elapsed time: whole minutes, since the ambient screen refreshes only about once a minute (B1). */
fun ambientElapsed(ms: Long): String {
    val min = ms / 60_000
    return if (min >= 60) "%d h %02d min".format(min / 60, min % 60) else "$min min"
}

/**
 * AOD drawing rules from the system's ambient details: burn-in protection → outline text and a one-pixel-class shift
 * every update; low-bit ambient → pure white (no grey / anti-aliased shades). Pure.
 */
class AmbientStyle private constructor(val outline: Boolean, val monochrome: Boolean) {
    /** Offset in dp for update number [tick]: cycles through a small ring so no pixel stays lit; (0,0) without burn-in protection. */
    fun shift(tick: Long): Pair<Int, Int> =
        if (!outline) 0 to 0 else RING[(tick % RING.size).toInt().let { if (it < 0) it + RING.size else it }]

    companion object {
        private val RING = listOf(0 to 0, 2 to 0, 2 to 2, 0 to 2, -2 to 2, -2 to 0, -2 to -2, 0 to -2, 2 to -2)
        fun of(burnInProtection: Boolean, lowBit: Boolean) = AmbientStyle(outline = burnInProtection, monochrome = lowBit)
        val Default = of(burnInProtection = false, lowBit = false)
    }
}

/** Low-power workout screen for AOD: grey text on black, no controls, no seconds. Live values return on wake. */
@Composable
internal fun AmbientLive(s: WorkoutSnapshot, style: AmbientStyle = AmbientStyle.Default, tick: Long = 0) {
    val grey = if (style.monochrome) Color.White else Color(0xFFBDBDBD)
    val white = Color.White
    val (dx, dy) = style.shift(tick)
    val outline = if (style.outline) TextStyle(drawStyle = Stroke(width = 1.5f)) else TextStyle.Default
    Column(Modifier.fillMaxSize().offset(dx.dp, dy.dp), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.Center) {
        Text(s.displayType.label + if (s.phase == WorkoutPhase.Paused) " · paused" else "", fontSize = 14.sp, color = grey, style = outline)
        Text(ambientElapsed(s.elapsedMs), fontSize = 30.sp, color = white, style = outline)
        Text("♥ ${s.metrics.heartRate ?: "--"}", fontSize = 20.sp, color = grey, style = outline)
        Text("${s.metrics.steps} steps", fontSize = 14.sp, color = grey, style = outline)
    }
}
