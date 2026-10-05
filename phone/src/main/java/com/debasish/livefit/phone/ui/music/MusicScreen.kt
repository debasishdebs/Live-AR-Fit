package com.debasish.livefit.phone.ui.music

import com.debasish.livefit.model.Command
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.VolumeDown
import androidx.compose.material.icons.automirrored.rounded.VolumeUp
import androidx.compose.material.icons.rounded.Favorite
import androidx.compose.material.icons.rounded.FavoriteBorder
import androidx.compose.material.icons.rounded.MusicNote
import androidx.compose.material.icons.rounded.Pause
import androidx.compose.material.icons.rounded.PlayArrow
import androidx.compose.material.icons.rounded.SkipNext
import androidx.compose.material.icons.rounded.SkipPrevious
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.debasish.livefit.model.formatElapsed
import com.debasish.livefit.phone.ServiceGraph
import com.debasish.livefit.phone.ui.Throttle
import com.debasish.livefit.phone.ui.components.ScreenHeader
import com.debasish.livefit.phone.ui.theme.LiveFitColors

@Composable
fun MusicScreen(services: ServiceGraph, onBack: () -> Unit) {
    val np by services.music.nowPlaying.collectAsStateWithLifecycle()
    val volume by services.music.volume.collectAsStateWithLifecycle()
    val throttle = remember { Throttle(100) }
    var dragging by remember { mutableStateOf<Float?>(null) }

    Column(Modifier.fillMaxSize().background(LiveFitColors.Surface)) {
        ScreenHeader("Music", onBack)
        Column(Modifier.padding(horizontal = 24.dp), horizontalAlignment = Alignment.CenterHorizontally) {
            Box(
                Modifier.fillMaxWidth().aspectRatio(1f).clip(RoundedCornerShape(32.dp))
                    .background(Brush.linearGradient(listOf(Color(0xFFFFB3C7), Color(0xFFB9A8FF), Color(0xFF9EE6D6)))),
                contentAlignment = Alignment.Center,
            ) { Icon(Icons.Rounded.MusicNote, contentDescription = null, tint = Color.White, modifier = Modifier.size(96.dp)) }
            Spacer(Modifier.height(24.dp))
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text(np?.title ?: "Nothing playing", style = MaterialTheme.typography.titleLarge, maxLines = 1)
                    Text(np?.artist ?: "Open YouTube Music", style = MaterialTheme.typography.bodyMedium, color = LiveFitColors.InkSoft)
                }
                Icon(
                    if (np?.liked == true) Icons.Rounded.Favorite else Icons.Rounded.FavoriteBorder,
                    contentDescription = "Like",
                    tint = if (np?.liked == true) LiveFitColors.ChipRose.second else LiveFitColors.InkSoft,
                    modifier = Modifier.size(32.dp).clickable { services.localCommand(Command.LikeTrack) },
                )
            }
            Spacer(Modifier.height(16.dp))
            val p = np
            LinearProgressIndicator(
                progress = { if (p != null && p.durationMs > 0) p.positionMs / p.durationMs.toFloat() else 0f },
                modifier = Modifier.fillMaxWidth().height(6.dp).clip(RoundedCornerShape(3.dp)),
                color = LiveFitColors.ChipRose.second,
                trackColor = LiveFitColors.ChipRose.first,
            )
            Row(Modifier.fillMaxWidth().padding(top = 4.dp)) {
                Text(formatElapsed(p?.positionMs ?: 0), style = MaterialTheme.typography.labelMedium, color = LiveFitColors.InkSoft, modifier = Modifier.weight(1f))
                Text(formatElapsed(p?.durationMs ?: 0), style = MaterialTheme.typography.labelMedium, color = LiveFitColors.InkSoft)
            }
            Spacer(Modifier.height(20.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(28.dp), verticalAlignment = Alignment.CenterVertically) {
                Round(Icons.Rounded.SkipPrevious, 60, LiveFitColors.ChipRose) { services.localCommand(Command.PreviousTrack) }
                Box(
                    Modifier.size(88.dp).clip(CircleShape).background(Brush.linearGradient(listOf(Color(0xFFFF6F9C), Color(0xFF8E7CFF)))).clickable { services.localCommand(Command.PlayPause) },
                    contentAlignment = Alignment.Center,
                ) { Icon(if (np?.isPlaying == true) Icons.Rounded.Pause else Icons.Rounded.PlayArrow, contentDescription = "Play or pause", tint = Color.White, modifier = Modifier.size(44.dp)) }
                Round(Icons.Rounded.SkipNext, 60, LiveFitColors.ChipRose) { services.localCommand(Command.NextTrack) }
            }
            Spacer(Modifier.height(20.dp))
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(Icons.AutoMirrored.Rounded.VolumeDown, contentDescription = null, tint = LiveFitColors.InkSoft)
                Slider(
                    value = dragging ?: volume,
                    onValueChange = { v -> dragging = v; if (throttle.allow()) services.localCommand(Command.SetVolume(v)) },
                    onValueChangeFinished = { dragging?.let { services.localCommand(Command.SetVolume(it)) }; dragging = null },
                    modifier = Modifier.weight(1f),
                    colors = SliderDefaults.colors(thumbColor = LiveFitColors.ChipRose.second, activeTrackColor = LiveFitColors.ChipRose.second, inactiveTrackColor = LiveFitColors.ChipRose.first),
                )
                Icon(Icons.AutoMirrored.Rounded.VolumeUp, contentDescription = null, tint = LiveFitColors.InkSoft)
            }
            Text("Playing through YouTube Music · demo data", style = MaterialTheme.typography.labelMedium, color = LiveFitColors.InkSoft)
        }
    }
}

@Composable
private fun Round(icon: ImageVector, sizeDp: Int, colors: Pair<Color, Color>, onClick: () -> Unit) {
    Box(Modifier.size(sizeDp.dp).clip(CircleShape).background(colors.first).clickable(onClick = onClick), contentAlignment = Alignment.Center) {
        Icon(icon, contentDescription = null, tint = colors.second, modifier = Modifier.size((sizeDp / 2).dp))
    }
}
