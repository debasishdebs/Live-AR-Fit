package com.debasish.livefit.glasses.hud

import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.DirectionsBike
import androidx.compose.material.icons.automirrored.outlined.DirectionsRun
import androidx.compose.material.icons.automirrored.outlined.DirectionsWalk
import androidx.compose.material.icons.outlined.Bolt
import androidx.compose.material.icons.outlined.Check
import androidx.compose.material.icons.outlined.FavoriteBorder
import androidx.compose.material.icons.outlined.LocalFireDepartment
import androidx.compose.material.icons.outlined.Mic
import androidx.compose.material.icons.outlined.MusicNote
import androidx.compose.material.icons.outlined.PhoneAndroid
import androidx.compose.material.icons.outlined.Route
import androidx.compose.material.icons.outlined.Timer
import androidx.compose.material.icons.outlined.Watch
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ColorFilter
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.graphics.vector.rememberVectorPainter
import androidx.compose.foundation.Image
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.foundation.text.BasicText
import com.debasish.livefit.model.HeartZones
import com.debasish.livefit.model.HudItem
import com.debasish.livefit.model.HudPosition
import com.debasish.livefit.model.HudSettings
import com.debasish.livefit.model.LinkState
import com.debasish.livefit.model.StateFrame
import com.debasish.livefit.model.zoneLabel
import com.debasish.livefit.model.VoiceState
import com.debasish.livefit.model.WorkoutPhase
import com.debasish.livefit.model.WorkoutType
import com.debasish.livefit.model.formatElapsed

/**
 * Monochrome HUD for the green Micro-LED. Rules: black = transparent, outlines not fills,
 * hierarchy by brightness (100 / 60 / 35 %), centre kept clear, numbers in tabular digits.
 */
object Hud {
    val Green = Color(0xFF3CFF6E)
    const val PRIMARY = 1f
    const val SECONDARY = 0.6f
    const val TERTIARY = 0.35f
}

enum class HudMode { Full, Glance }

private val HudPosition.alignment: Alignment
    get() = when (this) {
        HudPosition.TopLeft -> Alignment.TopStart; HudPosition.TopCenter -> Alignment.TopCenter; HudPosition.TopRight -> Alignment.TopEnd
        HudPosition.CenterLeft -> Alignment.CenterStart; HudPosition.Center -> Alignment.Center; HudPosition.CenterRight -> Alignment.CenterEnd
        HudPosition.BottomLeft -> Alignment.BottomStart; HudPosition.BottomCenter -> Alignment.BottomCenter; HudPosition.BottomRight -> Alignment.BottomEnd
    }

private val WorkoutType.hudIcon: ImageVector
    get() = when (this) {
        WorkoutType.Walk, WorkoutType.Auto -> Icons.AutoMirrored.Outlined.DirectionsWalk
        WorkoutType.Run -> Icons.AutoMirrored.Outlined.DirectionsRun
        WorkoutType.Cycle -> Icons.AutoMirrored.Outlined.DirectionsBike
    }


sealed interface HudOverlay { data object None : HudOverlay }

@Composable
fun HudScreen(
    frame: StateFrame?,
    settings: HudSettings,
    connection: HudConnection,
    mode: HudMode,
    glassesBattery: Int?,
    hrHistory: List<Int>,
    overlay: HudOverlay = HudOverlay.None,
) {
    val phase = frame?.workout?.phase ?: WorkoutPhase.Idle
    val inWorkout = phase == WorkoutPhase.Starting || phase == WorkoutPhase.Active || phase == WorkoutPhase.Paused || phase == WorkoutPhase.Syncing
    Box(Modifier.fillMaxSize().background(Color.Black).padding(10.dp)) {
        Scaled(settings.scale.coerceIn(0.3f, 1f), settings.position.alignment) {
            Box(Modifier.fillMaxSize().padding(horizontal = 18.dp, vertical = 16.dp)) {
                when {
                    connection == HudConnection.Outdated -> Message("Update LiveFit", "on your glasses")
                    frame == null && connection == HudConnection.OpenPhoneApp -> WaitingForPhone()
                    frame == null -> Message("Connecting…", "to your phone")
                    inWorkout && mode == HudMode.Full -> Full(frame, settings, glassesBattery, hrHistory)
                    inWorkout -> Glance(frame)
                    phase == WorkoutPhase.Stopping -> Message("Saving workout…", formatElapsed(frame.workout.elapsedMs))
                    phase == WorkoutPhase.Summary -> SummaryCard(frame)
                    else -> Ready(frame, glassesBattery)
                }
                if (frame != null) {
                    val band = Modifier.align(Alignment.Center).offset(y = (-40).dp)
                    when {
                        frame.voice != VoiceState.Idle -> Listening(band, frame.voice)
                        frame.toast != null -> Toast(frame.toast!!, band)
                        phase == WorkoutPhase.Syncing -> Toast("Syncing watch…", band)
                        phase == WorkoutPhase.Paused -> PausedBadge(band)
                    }
                }
                if (connection == HudConnection.Connecting && frame != null) Label("phone reconnecting…", 22.sp, Hud.TERTIARY)
            }
        }
    }
}

@Composable
private fun Message(title: String, subtitle: String) {
    Column(Modifier.fillMaxSize(), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.Center) {
        Label(title, 32.sp, Hud.SECONDARY, FontWeight.Bold)
        Label(subtitle, 26.sp, Hud.TERTIARY)
    }
}

/** Draws [content] in a box of [scale] x the screen at [alignment], with everything shrunk to match. */
@Composable
private fun androidx.compose.foundation.layout.BoxScope.Scaled(scale: Float, alignment: Alignment, content: @Composable () -> Unit) {
    val base = androidx.compose.ui.platform.LocalDensity.current
    Box(Modifier.align(alignment).fillMaxWidth(scale).fillMaxHeight(scale)) {
        androidx.compose.runtime.CompositionLocalProvider(
            androidx.compose.ui.platform.LocalDensity provides androidx.compose.ui.unit.Density(base.density * scale, base.fontScale),
        ) { content() }
    }
}

@Composable
private fun WaitingForPhone() {
    Column(Modifier.fillMaxSize(), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.Center) {
        Glyph(Icons.Outlined.PhoneAndroid, 56.dp, Hud.SECONDARY)
        Spacer(Modifier.height(12.dp))
        Label("Open Rokid LiveFit", 30.sp, Hud.SECONDARY, FontWeight.Bold)
        Label("on your phone", 26.sp, Hud.TERTIARY)
    }
}

/** No workout running: device status and how to start. */
@Composable
private fun Ready(frame: StateFrame, battery: Int?) {
    Column(Modifier.fillMaxSize(), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.Center) {
        Row(horizontalArrangement = Arrangement.spacedBy(18.dp), verticalAlignment = Alignment.CenterVertically) {
            BatteryRing(Icons.Outlined.Watch, frame.devices.watch.batteryPct, frame.devices.watch.link == LinkState.Connected)
            BatteryRing(Icons.Outlined.PhoneAndroid, frame.devices.phone.batteryPct, frame.devices.phone.link == LinkState.Connected)
            BatteryRing(GlassesIcon, battery, connected = true)
        }
        Spacer(Modifier.height(28.dp))
        Glyph(Icons.Outlined.FavoriteBorder, 56.dp, Hud.SECONDARY)
        Label("LiveFit ready", 34.sp, Hud.PRIMARY, FontWeight.Bold)
        Spacer(Modifier.height(10.dp))
        Row(verticalAlignment = Alignment.CenterVertically) {
            Glyph(Icons.Outlined.Mic, 28.dp, Hud.SECONDARY)
            Label(" Tap to talk", 26.sp, Hud.SECONDARY)
        }
        Label("\"start workout\"", 26.sp, Hud.TERTIARY)
    }
}

@Composable
private fun SummaryCard(frame: StateFrame) {
    val w = frame.workout
    Column(Modifier.fillMaxSize(), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.Center) {
        Glyph(w.displayType.hudIcon, 56.dp, Hud.PRIMARY)
        Label("${w.displayType.label.uppercase()} DONE", 34.sp, Hud.PRIMARY, FontWeight.Bold)
        Spacer(Modifier.height(18.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(28.dp)) {
            Metric(Icons.Outlined.Timer, formatElapsed(w.elapsedMs))
            Metric(Icons.Outlined.FavoriteBorder, "${w.avgHeartRate ?: "--"}")
        }
        Spacer(Modifier.height(10.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(28.dp)) {
            Metric(Icons.Outlined.LocalFireDepartment, "${w.metrics.calories}")
            Metric(Icons.Outlined.Route, "%.2f".format(w.metrics.distanceKm))
        }
    }
}

@Composable
private fun Full(frame: StateFrame, settings: HudSettings, battery: Int?, hrHistory: List<Int>) {
    val w = frame.workout
    val m = w.metrics
    val show = settings.items
    Column(Modifier.fillMaxSize()) {
        // Status row (tertiary)
        if (HudItem.StatusBar in show) Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            if (w.phase == WorkoutPhase.Active) RecDot()
            Spacer(Modifier.weight(1f))
            BatteryRing(Icons.Outlined.Watch, frame.devices.watch.batteryPct, frame.devices.watch.link == LinkState.Connected)
            Spacer(Modifier.width(12.dp))
            BatteryRing(Icons.Outlined.PhoneAndroid, frame.devices.phone.batteryPct, frame.devices.phone.link == LinkState.Connected)
            Spacer(Modifier.width(12.dp))
            Glyph(Icons.Outlined.MusicNote, 26.dp, if (frame.music?.isPlaying == true) Hud.SECONDARY else Hud.TERTIARY)
            Spacer(Modifier.width(12.dp))
            BatteryRing(GlassesIcon, battery, connected = true)
        }
        Spacer(Modifier.height(14.dp))

        // Headline 1: workout type + timer (primary)
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            if (HudItem.WorkoutType in show) {
                Glyph(w.displayType.hudIcon, 34.dp, Hud.PRIMARY)
                Label(" ${w.displayType.label.uppercase()}", 26.sp, Hud.PRIMARY, FontWeight.Bold)
                if (w.type == WorkoutType.Auto) AutoBadge()
            }
            Spacer(Modifier.weight(1f))
            if (HudItem.Timer in show) {
                Glyph(Icons.Outlined.Timer, 30.dp, Hud.PRIMARY)
                Label(" ${formatElapsed(w.elapsedMs)}", 30.sp, Hud.PRIMARY, FontWeight.Bold)
            }
        }

        Spacer(Modifier.weight(1f)) // keep the centre of vision clear

        // Headline 2: heart rate + calories (primary)
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            if (HudItem.HeartRate in show) {
                Glyph(Icons.Outlined.FavoriteBorder, 40.dp, Hud.PRIMARY)
                Label(" ${m.heartRate ?: "--"}", 54.sp, Hud.PRIMARY, FontWeight.Bold)
            }
            Spacer(Modifier.weight(1f))
            if (HudItem.Calories in show) {
                Glyph(Icons.Outlined.LocalFireDepartment, 40.dp, Hud.PRIMARY)
                Label(" ${m.calories}", 54.sp, Hud.PRIMARY, FontWeight.Bold)
            }
        }
        if (HudItem.HeartTrend in show) {
            Spacer(Modifier.height(8.dp))
            HeartTrend(hrHistory, HeartZones.zoneFor(m.heartRate))
        }
        Spacer(Modifier.height(14.dp))

        // Secondary metrics
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
            if (HudItem.Steps in show) Metric(Footprints, "%,d".format(m.steps))
            if (HudItem.Distance in show) Metric(Icons.Outlined.Route, "%.2f".format(m.distanceKm))
            if (HudItem.Speed in show) Metric(Icons.Outlined.Bolt, "%.1f".format(m.speedKmh))
        }
        Spacer(Modifier.height(10.dp))

        // Music line (tertiary)
        frame.music?.takeIf { HudItem.Music in show }?.let { np ->
            Row(verticalAlignment = Alignment.CenterVertically) {
                Glyph(Icons.Outlined.MusicNote, 26.dp, Hud.TERTIARY)
                Label(" ${np.title} · ${np.artist}", 24.sp, Hud.TERTIARY, maxLines = 1)
            }
        }
    }
}

/** Glance mode: only timer and heart rate, low in the field of view. */
@Composable
private fun Glance(frame: StateFrame) {
    val w = frame.workout
    Column(Modifier.fillMaxSize(), verticalArrangement = Arrangement.Bottom, horizontalAlignment = Alignment.CenterHorizontally) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Glyph(Icons.Outlined.Timer, 28.dp, Hud.SECONDARY)
            Label(" ${formatElapsed(w.elapsedMs)}", 30.sp, Hud.SECONDARY, FontWeight.Bold)
        }
        Row(verticalAlignment = Alignment.CenterVertically) {
            Glyph(Icons.Outlined.FavoriteBorder, 44.dp, Hud.PRIMARY)
            Label(" ${w.metrics.heartRate ?: "--"}", 64.sp, Hud.PRIMARY, FontWeight.Bold)
        }
        Spacer(Modifier.height(24.dp))
    }
}

/**
 * Single chart: heart-rate line over the last ~2 min, drawn over faint zone guide lines
 * (zone = intensity band by % of max HR: Z1 easy ... Z5 max). Current zone shown as "Z2".
 */
@Composable
private fun HeartTrend(history: List<Int>, zone: Int?) {
    Row(Modifier.fillMaxWidth().height(56.dp), verticalAlignment = Alignment.CenterVertically) {
        Canvas(Modifier.weight(1f).fillMaxHeight()) {
            val maxHr = 190f
            val lo = maxHr * 0.45f
            val hi = maxHr * 0.95f
            fun yOf(hr: Float) = size.height - ((hr - lo) / (hi - lo)).coerceIn(0f, 1f) * size.height
            // Zone boundaries at 50/60/70/80/90 % of max HR, dotted and dim.
            val dots = androidx.compose.ui.graphics.PathEffect.dashPathEffect(floatArrayOf(4.dp.toPx(), 6.dp.toPx()))
            for (pct in listOf(0.5f, 0.6f, 0.7f, 0.8f, 0.9f)) {
                val y = yOf(maxHr * pct)
                drawLine(Hud.Green.copy(alpha = Hud.TERTIARY), androidx.compose.ui.geometry.Offset(0f, y), androidx.compose.ui.geometry.Offset(size.width, y), strokeWidth = 2.dp.toPx(), pathEffect = dots)
            }
            if (history.size < 2) return@Canvas
            val stepX = size.width / (HR_HISTORY - 1)
            val startX = size.width - stepX * (history.size - 1)
            val path = androidx.compose.ui.graphics.Path()
            history.forEachIndexed { i, hr ->
                val x = startX + i * stepX
                if (i == 0) path.moveTo(x, yOf(hr.toFloat())) else path.lineTo(x, yOf(hr.toFloat()))
            }
            drawPath(path, Hud.Green.copy(alpha = Hud.PRIMARY), style = Stroke(width = 4.dp.toPx(), cap = androidx.compose.ui.graphics.StrokeCap.Round))
        }
        Label("  ${zoneLabel(zone)}", 30.sp, Hud.SECONDARY, FontWeight.Bold)
    }
}

/** Number of 1 s samples kept for the trend line. */
const val HR_HISTORY = 120

@Composable
private fun Metric(icon: ImageVector, value: String) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Glyph(icon, 30.dp, Hud.SECONDARY)
        Label(" $value", 30.sp, Hud.SECONDARY, FontWeight.SemiBold)
    }
}

/**
 * Device icon inside a ring whose filled arc is the battery level (clockwise from 12 o'clock).
 * Disconnected = dotted ring and a slash through the icon.
 */
@Composable
private fun BatteryRing(icon: ImageVector, batteryPct: Int?, connected: Boolean) {
    Box(Modifier.size(40.dp), contentAlignment = Alignment.Center) {
        Canvas(Modifier.fillMaxSize()) {
            val w = 4.dp.toPx()
            val inset = w / 2
            val arcSize = androidx.compose.ui.geometry.Size(size.width - w, size.height - w)
            val topLeft = androidx.compose.ui.geometry.Offset(inset, inset)
            if (!connected) {
                drawArc(Hud.Green.copy(alpha = Hud.TERTIARY), 0f, 360f, false, topLeft, arcSize,
                    style = Stroke(w, pathEffect = androidx.compose.ui.graphics.PathEffect.dashPathEffect(floatArrayOf(3.dp.toPx(), 4.dp.toPx()))))
                return@Canvas
            }
            // Empty part: barely visible hairline. Filled part: full brightness, so the level reads at a glance.
            drawArc(Hud.Green.copy(alpha = 0.12f), 0f, 360f, false, topLeft, arcSize, style = Stroke(w * 0.6f))
            val pct = (batteryPct ?: 0).coerceIn(0, 100) / 100f
            drawArc(Hud.Green.copy(alpha = Hud.PRIMARY), -90f, 360f * pct, false, topLeft, arcSize, style = Stroke(w, cap = androidx.compose.ui.graphics.StrokeCap.Butt))
        }
        Glyph(icon, 22.dp, if (connected) Hud.SECONDARY else Hud.TERTIARY)
        if (!connected) {
            // Strike-through: diagonal slash across the icon.
            Canvas(Modifier.size(26.dp)) {
                drawLine(Hud.Green.copy(alpha = Hud.SECONDARY), androidx.compose.ui.geometry.Offset(0f, size.height), androidx.compose.ui.geometry.Offset(size.width, 0f),
                    strokeWidth = 3.dp.toPx(), cap = androidx.compose.ui.graphics.StrokeCap.Round)
            }
        }
    }
}

@Composable
private fun LinkIcon(icon: ImageVector, state: LinkState) {
    Box {
        Glyph(icon, 26.dp, Hud.TERTIARY)
        if (state == LinkState.Connected) Box(Modifier.align(Alignment.TopEnd).size(6.dp).background(Hud.Green.copy(alpha = Hud.SECONDARY), CircleShape))
    }
}

@Composable
private fun RecDot() {
    val t = rememberInfiniteTransition(label = "rec")
    val a by t.animateFloat(Hud.TERTIARY, Hud.SECONDARY, infiniteRepeatable(tween(1_200), RepeatMode.Reverse), label = "recAlpha")
    Row(verticalAlignment = Alignment.CenterVertically) {
        Box(Modifier.size(10.dp).background(Hud.Green.copy(alpha = a), CircleShape))
        Label(" REC", 24.sp, Hud.TERTIARY, FontWeight.Bold)
    }
}

@Composable
private fun AutoBadge() {
    Box(Modifier.padding(start = 6.dp).border(2.dp, Hud.Green.copy(alpha = Hud.SECONDARY), RoundedCornerShape(6.dp)).padding(horizontal = 5.dp)) {
        Label("A", 16.sp, Hud.SECONDARY, FontWeight.Bold)
    }
}

@Composable
private fun PausedBadge(modifier: Modifier) {
    Box(modifier.border(2.dp, Hud.Green.copy(alpha = Hud.SECONDARY), RoundedCornerShape(14.dp)).padding(horizontal = 18.dp, vertical = 8.dp)) {
        Label("❚❚ PAUSED", 26.sp, Hud.SECONDARY, FontWeight.Bold)
    }
}

/** Slow pulsing outlined ring while listening; the only continuous animation on the HUD. */
@Composable
private fun Listening(modifier: Modifier, state: VoiceState) {
    val t = rememberInfiniteTransition(label = "listen")
    val pulse by t.animateFloat(0.8f, 1f, infiniteRepeatable(tween(700), RepeatMode.Reverse), label = "pulse")
    Box(modifier.size(150.dp), contentAlignment = Alignment.Center) {
        Canvas(Modifier.fillMaxSize()) {
            drawCircle(Hud.Green.copy(alpha = Hud.SECONDARY), radius = size.minDimension / 2 * pulse, style = Stroke(3.dp.toPx()))
        }
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Glyph(Icons.Outlined.Mic, 44.dp, Hud.PRIMARY)
            Label(if (state == VoiceState.Listening) "listening" else "…", 18.sp, Hud.SECONDARY)
        }
    }
}

@Composable
private fun Toast(text: String, modifier: Modifier) {
    Row(
        modifier.background(Color.Black).border(2.dp, Hud.Green.copy(alpha = Hud.SECONDARY), RoundedCornerShape(14.dp)).padding(horizontal = 16.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Glyph(Icons.Outlined.Check, 28.dp, Hud.PRIMARY)
        Label(" $text", 26.sp, Hud.PRIMARY, FontWeight.Bold)
    }
}

@Composable
fun Glyph(icon: ImageVector, size: Dp, level: Float) {
    Image(rememberVectorPainter(icon), contentDescription = null, colorFilter = ColorFilter.tint(Hud.Green), modifier = Modifier.size(size).alpha(level))
}

@Composable
fun Label(text: String, size: TextUnit, level: Float, weight: FontWeight = FontWeight.Medium, maxLines: Int = 1) {
    BasicText(
        text,
        maxLines = maxLines,
        style = TextStyle(color = Hud.Green.copy(alpha = level), fontSize = size, fontWeight = weight, fontFamily = FontFamily.SansSerif, fontFeatureSettings = "tnum"),
    )
}
