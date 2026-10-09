package com.debasish.livefit.glasses.hud

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.VolumeUp
import androidx.compose.material.icons.outlined.Bolt
import androidx.compose.material.icons.outlined.Close
import androidx.compose.material.icons.outlined.Explore
import androidx.compose.material.icons.outlined.FavoriteBorder
import androidx.compose.material.icons.outlined.LocalFireDepartment
import androidx.compose.material.icons.outlined.MusicNote
import androidx.compose.material.icons.outlined.Pause
import androidx.compose.material.icons.outlined.PlayArrow
import androidx.compose.material.icons.outlined.Route
import androidx.compose.material.icons.outlined.SkipNext
import androidx.compose.material.icons.outlined.SkipPrevious
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.debasish.livefit.model.NowPlaying
import com.debasish.livefit.model.StateFrame

object MusicControlsModel {
    fun progress(np: NowPlaying?): Float =
        if (np == null || np.durationMs <= 0) 0f else (np.positionMs.toFloat() / np.durationMs).coerceIn(0f, 1f)
}

/** Stats page (spec §3.1): steps, distance, speed, calories, avg/max HR. */
@Composable
fun StatsScreen(frame: StateFrame) {
    val w = frame.workout
    val m = w.metrics
    Column(Modifier.fillMaxSize(), verticalArrangement = Arrangement.Center) {
        Label("STATS", 22.sp, Hud.TERTIARY, FontWeight.Bold)
        Spacer(Modifier.height(12.dp))
        StatRow(Footprints, "%,d".format(m.steps), "steps")
        StatRow(Icons.Outlined.Route, "%.2f".format(m.distanceKm), "km")
        StatRow(Icons.Outlined.Bolt, "%.1f".format(m.speedKmh), "km/h")
        StatRow(Icons.Outlined.LocalFireDepartment, "${m.calories}", "kcal")
        StatRow(Icons.Outlined.FavoriteBorder, "${w.avgHeartRate ?: "--"} / ${w.maxHeartRate ?: "--"}", "avg / max")
    }
}

@Composable
private fun StatRow(icon: ImageVector, value: String, unit: String) {
    Row(Modifier.fillMaxWidth().height(56.dp), verticalAlignment = Alignment.CenterVertically) {
        Glyph(icon, 34.dp, Hud.SECONDARY)
        Label(" $value", 40.sp, Hud.PRIMARY, FontWeight.Bold)
        Label(" $unit", 24.sp, Hud.TERTIARY)
    }
}

/** Map page (spec §2.5): the phone-rendered 480×480 image (attribution is inside it). */
@Composable
fun MapScreen(image: ImageBitmap?) {
    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        if (image == null) {
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                Glyph(Icons.Outlined.Explore, 56.dp, Hud.SECONDARY)
                Label("Loading map…", 30.sp, Hud.SECONDARY, FontWeight.Bold)
            }
        } else {
            Image(image, contentDescription = "Map", modifier = Modifier.fillMaxWidth().aspectRatio(1f))
        }
    }
}

/** Music controls page (spec §3.1): title/artist, progress, ⏮ ⏯ ⏭ ✕ (outlined selector in scroll mode), volume bar. */
@Composable
fun MusicControlsScreen(np: NowPlaying?, selector: MusicControl?, clock: String) {
    Column(Modifier.fillMaxSize()) {
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Glyph(Icons.Outlined.MusicNote, 26.dp, Hud.TERTIARY)
            Label(" PLAYER", 22.sp, Hud.TERTIARY, FontWeight.Bold)
            Spacer(Modifier.weight(1f))
            if (clock.isNotEmpty()) Label(clock, 24.sp, Hud.SECONDARY, FontWeight.Bold)
        }
        Spacer(Modifier.height(14.dp))
        Label(np?.title ?: "Nothing playing", 30.sp, Hud.PRIMARY, FontWeight.Bold, overflow = TextOverflow.Ellipsis)
        np?.artist?.takeIf { it.isNotEmpty() }?.let { Label(it, 24.sp, Hud.SECONDARY, overflow = TextOverflow.Ellipsis) }
        Spacer(Modifier.height(12.dp))
        Bar(MusicControlsModel.progress(np), Modifier.fillMaxWidth())
        Spacer(Modifier.height(18.dp))
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceEvenly) {
            ControlButton(Icons.Outlined.SkipPrevious, selector == MusicControl.Previous)
            ControlButton(if (np?.isPlaying == true) Icons.Outlined.Pause else Icons.Outlined.PlayArrow, selector == MusicControl.PlayPause)
            ControlButton(Icons.Outlined.SkipNext, selector == MusicControl.Next)
            ControlButton(Icons.Outlined.Close, selector == MusicControl.Back)
        }
        Spacer(Modifier.height(18.dp))
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Glyph(Icons.AutoMirrored.Outlined.VolumeUp, 28.dp, Hud.SECONDARY)
            Spacer(Modifier.width(10.dp))
            Bar(np?.volume ?: 0f, Modifier.weight(1f))
        }
        Spacer(Modifier.weight(1f))
        Label(if (selector == null) "tap: controls · swipe: pages" else "swipe: choose · tap: press · long swipe: volume", 18.sp, Hud.TERTIARY, maxLines = 2)
    }
}

@Composable
private fun ControlButton(icon: ImageVector, selected: Boolean) {
    val outline = if (selected) Modifier.border(3.dp, Hud.Green.copy(alpha = Hud.PRIMARY), RoundedCornerShape(14.dp))
    else Modifier.border(2.dp, Hud.Green.copy(alpha = Hud.TERTIARY), RoundedCornerShape(14.dp))
    Box(outline.size(64.dp), contentAlignment = Alignment.Center) { Glyph(icon, 36.dp, if (selected) Hud.PRIMARY else Hud.SECONDARY) }
}

@Composable
private fun Bar(fraction: Float, modifier: Modifier) {
    Box(modifier.height(8.dp).border(2.dp, Hud.Green.copy(alpha = Hud.TERTIARY), RoundedCornerShape(4.dp))) {
        Box(Modifier.fillMaxHeight().fillMaxWidth(fraction.coerceIn(0f, 1f)).background(Hud.Green.copy(alpha = Hud.SECONDARY), RoundedCornerShape(4.dp)))
    }
}
