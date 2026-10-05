package com.debasish.livefit.phone.ui.home

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Bolt
import androidx.compose.material.icons.rounded.DirectionsWalk
import androidx.compose.material.icons.rounded.Favorite
import androidx.compose.material.icons.rounded.Home
import androidx.compose.material.icons.rounded.LocalFireDepartment
import androidx.compose.material.icons.rounded.MusicNote
import androidx.compose.material.icons.rounded.Pause
import androidx.compose.material.icons.rounded.PlayArrow
import androidx.compose.material.icons.rounded.SkipNext
import androidx.compose.material.icons.rounded.Route
import androidx.compose.material.icons.rounded.Settings
import androidx.compose.material.icons.rounded.ShowChart
import androidx.compose.material.icons.rounded.Visibility
import androidx.compose.material.icons.rounded.Watch
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.unit.dp
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.debasish.livefit.model.LinkState
import com.debasish.livefit.model.WorkoutPhase
import com.debasish.livefit.model.WorkoutSnapshot
import com.debasish.livefit.model.formatElapsed
import com.debasish.livefit.phone.ServiceGraph
import com.debasish.livefit.phone.ui.components.WorkoutTypeSheet
import com.debasish.livefit.phone.ui.components.icon
import com.debasish.livefit.phone.ui.components.IconChip
import com.debasish.livefit.phone.ui.components.SoftCard
import com.debasish.livefit.phone.ui.theme.LiveFitColors

/** Home: device status, start hero, live metrics, pill navigation. All data via [services]. */
@Composable
fun HomeScreen(
    services: ServiceGraph,
    onSettings: () -> Unit,
    onWorkout: () -> Unit,
    onMusic: () -> Unit,
    onActivity: () -> Unit,
    onDevice: (String) -> Unit,
) {
    val snapshot by services.workout.snapshot.collectAsStateWithLifecycle()
    val glasses by services.glasses.status.collectAsStateWithLifecycle()
    val watch by services.watch.status.collectAsStateWithLifecycle()
    val music by services.music.nowPlaying.collectAsStateWithLifecycle()
    var picking by remember { mutableStateOf(false) }
    val m = snapshot.metrics

    Box(Modifier.fillMaxSize().background(LiveFitColors.Surface)) {
        Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState())) {
            Column(Modifier.fillMaxWidth().background(LiveFitColors.HeaderGradient).statusBarsPadding().padding(20.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Text("Rokid LiveFit", style = MaterialTheme.typography.headlineMedium)
                        Text("Ready when you are", style = MaterialTheme.typography.bodyMedium, color = LiveFitColors.InkSoft)
                    }
                    IconButton(onClick = onSettings, modifier = Modifier.clip(CircleShape).background(Color.White)) {
                        Icon(Icons.Rounded.Settings, contentDescription = "Settings", tint = LiveFitColors.Ink)
                    }
                }
                Spacer(Modifier.height(20.dp))
                Row(horizontalArrangement = Arrangement.SpaceEvenly, modifier = Modifier.fillMaxWidth()) {
                    DeviceBubble(Icons.Rounded.Visibility, "Glasses", LiveFitColors.ChipMint, glasses.link == LinkState.Connected) { onDevice("glasses") }
                    DeviceBubble(Icons.Rounded.Watch, "Watch", LiveFitColors.ChipSky, watch.link == LinkState.Connected) { onDevice("watch") }
                    DeviceBubble(Icons.Rounded.MusicNote, "Music", LiveFitColors.ChipRose, music?.isPlaying == true, onMusic)
                }
            }

            val inWorkout = snapshot.phase != WorkoutPhase.Idle
            StartHero(snapshot, onStart = { if (inWorkout) onWorkout() else picking = true })

            val dash = !inWorkout
            Row(Modifier.padding(horizontal = 16.dp), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                MetricTile(Icons.Rounded.Favorite, "Heart rate", if (dash) "--" else "${m.heartRate ?: "--"}", "bpm", LiveFitColors.ChipCoral, Modifier.weight(1f))
                MetricTile(Icons.Rounded.DirectionsWalk, "Steps", if (dash) "--" else "%,d".format(m.steps), "", LiveFitColors.ChipMint, Modifier.weight(1f))
            }
            Spacer(Modifier.height(12.dp))
            Row(Modifier.padding(horizontal = 16.dp), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                MetricTile(Icons.Rounded.LocalFireDepartment, "Calories", if (dash) "--" else "${m.calories}", "kcal", LiveFitColors.ChipAmber, Modifier.weight(1f))
                MetricTile(Icons.Rounded.Route, "Distance", if (dash) "--" else "%.2f".format(m.distanceKm), "km", LiveFitColors.ChipViolet, Modifier.weight(1f))
            }
            NowPlayingCard(music, onOpen = onMusic, onPlayPause = services.music::playPause, onNext = services.music::next)
            Spacer(Modifier.height(120.dp))
        }

        if (picking) WorkoutTypeSheet(onPick = { picking = false; services.workout.start(it); onWorkout() }, onDismiss = { picking = false })
    }
}

/** Live now-playing card; reflects play/pause/track changes made anywhere (Music tab, voice, watch). */
@Composable
private fun NowPlayingCard(np: com.debasish.livefit.model.NowPlaying?, onOpen: () -> Unit, onPlayPause: () -> Unit, onNext: () -> Unit) {
    SoftCard(Modifier.padding(16.dp).fillMaxWidth(), onClick = onOpen) {
        Row(Modifier.padding(14.dp), verticalAlignment = Alignment.CenterVertically) {
            Box(
                Modifier.size(48.dp).clip(RoundedCornerShape(14.dp)).background(Brush.linearGradient(listOf(Color(0xFFFF8FB1), Color(0xFF8E7CFF)))),
                contentAlignment = Alignment.Center,
            ) { Icon(Icons.Rounded.MusicNote, contentDescription = null, tint = Color.White) }
            Column(Modifier.weight(1f).padding(horizontal = 12.dp)) {
                Text(np?.title ?: "Nothing playing", style = MaterialTheme.typography.titleMedium, maxLines = 1)
                Text(
                    when { np == null -> "YouTube Music"; np.isPlaying -> "▶ ${np.artist}"; else -> "❚❚ Paused · ${np.artist}" },
                    style = MaterialTheme.typography.bodyMedium, color = LiveFitColors.InkSoft, maxLines = 1,
                )
            }
            SmallRound(if (np?.isPlaying == true) Icons.Rounded.Pause else Icons.Rounded.PlayArrow, onPlayPause)
            Spacer(Modifier.size(8.dp))
            SmallRound(Icons.Rounded.SkipNext, onNext)
        }
    }
}

@Composable
private fun SmallRound(icon: ImageVector, onClick: () -> Unit) {
    Box(Modifier.size(40.dp).clip(CircleShape).background(LiveFitColors.ChipRose.first).clickable(onClick = onClick), contentAlignment = Alignment.Center) {
        Icon(icon, contentDescription = null, tint = LiveFitColors.ChipRose.second, modifier = Modifier.size(22.dp))
    }
}

@Composable
private fun DeviceBubble(icon: ImageVector, label: String, colors: Pair<Color, Color>, online: Boolean, onClick: () -> Unit) {
    Column(horizontalAlignment = Alignment.CenterHorizontally, modifier = Modifier.clickable(onClick = onClick)) {
        Box {
            Box(
                Modifier.size(64.dp).clip(CircleShape).background(Color.White).border(1.dp, LiveFitColors.Line, CircleShape),
                contentAlignment = Alignment.Center,
            ) { IconChip(icon, colors, size = 44.dp, shapeRadius = 22.dp) }
            Box(Modifier.align(Alignment.TopEnd).size(14.dp).clip(CircleShape).background(Color.White).padding(2.dp).clip(CircleShape).background(if (online) LiveFitColors.Mint else Color(0xFFC5CBD3)))
        }
        Spacer(Modifier.height(6.dp))
        Text(label, style = MaterialTheme.typography.labelMedium, color = LiveFitColors.InkSoft)
    }
}

@Composable
private fun StartHero(s: WorkoutSnapshot, onStart: () -> Unit) {
    val inWorkout = s.phase != WorkoutPhase.Idle
    SoftCard(Modifier.padding(16.dp).fillMaxWidth()) {
        Row(Modifier.padding(20.dp), verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    IconChip(if (inWorkout) s.displayType.icon else Icons.Rounded.Bolt, LiveFitColors.ChipMint, size = 28.dp, shapeRadius = 8.dp)
                    Text(if (inWorkout) "  ${s.displayType.label} · ${s.phase.name.lowercase()}" else "  Walk · Run · Cycle · Auto", style = MaterialTheme.typography.titleMedium)
                }
                Spacer(Modifier.height(10.dp))
                Text(if (inWorkout) formatElapsed(s.elapsedMs) else "Start workout", style = MaterialTheme.typography.titleLarge)
                Text(if (inWorkout) "Tap to open the live view" else "Watch, glasses and music start together", style = MaterialTheme.typography.bodyMedium, color = LiveFitColors.InkSoft)
            }
            Box(
                Modifier.size(84.dp).shadow(12.dp, CircleShape, ambientColor = LiveFitColors.Mint, spotColor = LiveFitColors.Mint)
                    .clip(CircleShape)
                    .background(Brush.linearGradient(listOf(LiveFitColors.Mint, LiveFitColors.Sky)))
                    .clickable(onClick = onStart),
                contentAlignment = Alignment.Center,
            ) { Icon(Icons.Rounded.PlayArrow, contentDescription = "Start workout", tint = Color.White, modifier = Modifier.size(44.dp)) }
        }
    }
}

@Composable
private fun MetricTile(icon: ImageVector, label: String, value: String, unit: String, colors: Pair<Color, Color>, modifier: Modifier) {
    SoftCard(modifier) {
        Column(Modifier.padding(16.dp)) {
            IconChip(icon, colors, size = 36.dp, shapeRadius = 10.dp)
            Spacer(Modifier.height(14.dp))
            Row(verticalAlignment = Alignment.Bottom) {
                Text(value, style = MaterialTheme.typography.headlineMedium)
                if (unit.isNotEmpty()) Text(" $unit", style = MaterialTheme.typography.bodyMedium, color = LiveFitColors.InkSoft, modifier = Modifier.padding(bottom = 4.dp))
            }
            Text(label, style = MaterialTheme.typography.labelMedium, color = LiveFitColors.InkSoft)
        }
    }
}

enum class Tab { Home, Activity, Music, Settings }

/** App-wide floating footer; lives in the app shell so it stays on every page. */
@Composable
fun PillNav(modifier: Modifier, selected: Tab?, onTab: (Tab) -> Unit) {
    val shape = RoundedCornerShape(32.dp)
    Row(
        modifier.shadow(16.dp, shape).clip(shape).background(Color.White).padding(horizontal = 10.dp, vertical = 8.dp),
        horizontalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        NavItem(Icons.Rounded.Home, "Home", selected = selected == Tab.Home) { onTab(Tab.Home) }
        NavItem(Icons.Rounded.ShowChart, "Activity", selected = selected == Tab.Activity) { onTab(Tab.Activity) }
        NavItem(Icons.Rounded.MusicNote, "Music", selected = selected == Tab.Music) { onTab(Tab.Music) }
        NavItem(Icons.Rounded.Settings, "Settings", selected = selected == Tab.Settings) { onTab(Tab.Settings) }
    }
}

@Composable
private fun NavItem(icon: ImageVector, label: String, selected: Boolean, onClick: () -> Unit) {
    val bg = if (selected) LiveFitColors.ChipMint.first else Color.Transparent
    val fg = if (selected) LiveFitColors.MintDeep else LiveFitColors.InkSoft
    Column(
        Modifier.clip(RoundedCornerShape(24.dp)).background(bg).clickable(onClick = onClick).padding(horizontal = 16.dp, vertical = 6.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Icon(icon, contentDescription = label, tint = fg)
        Text(label, style = MaterialTheme.typography.labelMedium, color = fg)
    }
}
