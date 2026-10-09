package com.debasish.livefit.watch.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.DirectionsBike
import androidx.compose.material.icons.automirrored.rounded.DirectionsRun
import androidx.compose.material.icons.automirrored.rounded.DirectionsWalk
import androidx.compose.material.icons.rounded.AutoMode
import androidx.compose.material.icons.rounded.Bolt
import androidx.compose.material.icons.rounded.Check
import androidx.compose.material.icons.rounded.Favorite
import androidx.compose.material.icons.rounded.FavoriteBorder
import androidx.compose.material.icons.rounded.LocalFireDepartment
import androidx.compose.material.icons.rounded.Pause
import androidx.compose.material.icons.rounded.PhoneAndroid
import androidx.compose.material.icons.rounded.PlayArrow
import androidx.compose.material.icons.rounded.Route
import androidx.compose.material.icons.rounded.SkipNext
import androidx.compose.material.icons.rounded.SkipPrevious
import androidx.compose.material.icons.rounded.Stop
import androidx.compose.material.icons.rounded.Timer
import androidx.compose.material.icons.rounded.Visibility
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.wear.compose.material.HorizontalPageIndicator
import androidx.wear.compose.material.Icon
import androidx.wear.compose.material.MaterialTheme
import androidx.wear.compose.material.PageIndicatorState
import androidx.wear.compose.material.Text
import com.debasish.livefit.model.HeartZones
import com.debasish.livefit.model.HudPage
import com.debasish.livefit.model.WorkoutPhase
import com.debasish.livefit.model.WorkoutSnapshot
import com.debasish.livefit.model.WorkoutType
import com.debasish.livefit.model.formatElapsed
import com.debasish.livefit.model.Command
import com.debasish.livefit.model.NowPlaying
import com.debasish.livefit.model.zoneLabel
import com.debasish.livefit.watch.WatchPageModel
import com.debasish.livefit.watch.WatchUiState
import com.debasish.livefit.watch.map.WatchTiles

/** Same palette as the phone, on OLED black. */
internal object W {
    val Mint = Color(0xFF14C3A2)
    val Sky = Color(0xFF3D8BFF)
    val Coral = Color(0xFFFF6B4F)
    val Amber = Color(0xFFFFB627)
    val Violet = Color(0xFF9B7CFF)
    val Rose = Color(0xFFFF6F9C)
    val Pill = Color(0xFF202327)
    val Dim = Color(0xFF9AA0A6)
}

private val WorkoutType.icon: ImageVector
    get() = when (this) {
        WorkoutType.Walk -> Icons.AutoMirrored.Rounded.DirectionsWalk
        WorkoutType.Run -> Icons.AutoMirrored.Rounded.DirectionsRun
        WorkoutType.Cycle -> Icons.AutoMirrored.Rounded.DirectionsBike
        WorkoutType.Auto -> Icons.Rounded.AutoMode
    }

/** Traffic-light effort colour: green while easy/moderate, amber when hard, red at max. */
private fun zoneColor(zone: Int?) = when (zone) {
    null -> Color(0xFF3A3F45)
    0, 1, 2 -> Color(0xFF2EE66B)
    3 -> Color(0xFFFFC21A)
    4 -> Color(0xFFFF8A1F)
    else -> Color(0xFFFF3B30)
}

private fun zoneName(zone: Int?) = when (zone) {
    1 -> "Warm-up"; 2 -> "Fat burn"; 3 -> "Cardio"; 4 -> "Hard"; 5 -> "Max"; else -> "Rest"
}

private val AMBIENT_PHASES = setOf(WorkoutPhase.Starting, WorkoutPhase.Active, WorkoutPhase.Paused, WorkoutPhase.Syncing)

@Composable
fun WatchApp(state: WatchUiState, onCommand: (Command) -> Unit, onVolume: (Float) -> Unit, onGrantPermissions: () -> Unit, tiles: WatchTiles, ambient: Boolean = false, ambientStyle: AmbientStyle = AmbientStyle.Default, ambientTick: Long = 0, locationDisclosure: Boolean = false, onLocationDisclosure: (Boolean) -> Unit = {}) {
    val s = state.snapshot
    MaterialTheme {
        Box(Modifier.fillMaxSize().background(Color.Black)) {
            // AOD: a low-power workout screen; the interactive one (with seconds and controls) returns on wake.
            if (ambient && s.phase in AMBIENT_PHASES) { AmbientLive(s, ambientStyle, ambientTick); return@Box }
            when (s.phase) {
                WorkoutPhase.Idle -> Ready(state.phoneOnline, state.glassesOnline) { onCommand(Command.StartWorkout(it)) }
                WorkoutPhase.Summary -> Summary(s) { onCommand(Command.DismissSummary) }
                WorkoutPhase.Stopping -> Saving(s)
                else -> Live(s, state, onCommand, onVolume, tiles)
            }
            if (state.offline) OfflineBadge(Modifier.align(Alignment.TopCenter).padding(top = rememberInsets().y(18)))
            state.confirmation?.let { c -> ConfirmOverlay(c) { yes -> onCommand(Command.Answer(c.id, yes)) } }
            if (state.needsPermissions.isNotEmpty() && s.phase == WorkoutPhase.Idle) PermissionCard(state.needsPermissions, onGrantPermissions)
            if (locationDisclosure && s.phase == WorkoutPhase.Idle && state.needsPermissions.isEmpty()) LocationDisclosureCard(onLocationDisclosure)
        }
    }
}

@Composable
private fun Saving(s: WorkoutSnapshot) {
    Column(Modifier.fillMaxSize(), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.Center) {
        Icon(s.displayType.icon, contentDescription = null, tint = W.Mint, modifier = Modifier.size(28.dp))
        Text("Saving workout…", fontSize = 15.sp)
        Text(formatElapsed(s.elapsedMs), fontSize = 13.sp, color = W.Dim)
    }
}

@Composable
private fun Ready(phoneOnline: Boolean, glassesOnline: Boolean, onStart: (WorkoutType) -> Unit) {
    var typeIndex by remember { mutableIntStateOf(0) }
    val type = WorkoutType.entries[typeIndex]
    Column(Modifier.fillMaxSize(), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.Center) {
        Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            StatusDot(Icons.Rounded.PhoneAndroid, phoneOnline)
            StatusDot(Icons.Rounded.Visibility, glassesOnline)
        }
        Spacer(Modifier.height(10.dp))
        Box(
            Modifier.size(110.dp).clip(CircleShape).background(Brush.linearGradient(listOf(W.Mint, W.Sky))).clickable { onStart(type) },
            contentAlignment = Alignment.Center,
        ) { Icon(Icons.Rounded.PlayArrow, contentDescription = "Start", tint = Color.White, modifier = Modifier.size(56.dp)) }
        Spacer(Modifier.height(12.dp))
        // Tap to cycle the workout type.
        Row(
            Modifier.clip(RoundedCornerShape(20.dp)).background(W.Pill).clickable { typeIndex = (typeIndex + 1) % WorkoutType.entries.size }.padding(horizontal = 16.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Icon(type.icon, contentDescription = null, tint = W.Mint, modifier = Modifier.size(20.dp))
            Text("  ${type.label}", fontSize = 16.sp, fontWeight = FontWeight.SemiBold)
        }
    }
}

@Composable
private fun StatusDot(icon: ImageVector, online: Boolean) {
    Box {
        Icon(icon, contentDescription = null, tint = W.Dim, modifier = Modifier.size(18.dp))
        Box(Modifier.align(Alignment.TopEnd).size(6.dp).clip(CircleShape).background(if (online) W.Mint else W.Dim))
    }
}

/**
 * Live pager over the shared page set (spec §3). The pager is rebuilt when the page set changes, opening on the page
 * that was shown or, if it vanished (disabled, Map ineligible), on Workout. A new session starts on Workout.
 */
@Composable
private fun Live(s: WorkoutSnapshot, state: WatchUiState, onCommand: (Command) -> Unit, onVolume: (Float) -> Unit, tiles: WatchTiles) {
    val pages = WatchPageModel.pages(state)
    val shown = remember(s.sessionId) { mutableStateOf(HudPage.Workout) }
    key(pages) {
        val pager = rememberPagerState(initialPage = WatchPageModel.initialIndex(pages, shown.value), pageCount = { pages.size })
        LaunchedEffect(pager) { snapshotFlow { pager.settledPage }.collect { i -> pages.getOrNull(i)?.let { shown.value = it } } }
        Box(Modifier.fillMaxSize()) {
            HorizontalPager(pager, Modifier.fillMaxSize()) { i ->
                when (pages[i]) {
                    HudPage.Glance -> GlancePage(s)
                    HudPage.Workout -> HeartPage(s, state.hrHistory, onCommand)
                    HudPage.Stats -> StatsPage(s)
                    HudPage.Playlist -> PlaylistPage(state.queue, onCommand)
                    HudPage.Map -> WatchMapPage(state.route, state.live, s.sessionId, s.type, tiles)
                    HudPage.MusicControls -> MusicPage(state.music, onCommand, onVolume)
                }
            }
            HorizontalPageIndicator(
                pageIndicatorState = remember(pager) {
                    object : PageIndicatorState {
                        override val pageOffset get() = pager.currentPageOffsetFraction
                        override val selectedPage get() = pager.currentPage
                        override val pageCount get() = pages.size
                    }
                },
                modifier = Modifier.padding(bottom = rememberInsets().y(6)),
            )
        }
    }
}

/**
 * Page 1: HR in an edge-hugging ring coloured by effort (green -> amber -> red), a faint moving
 * HR line behind the number, zone label, timer, type, pause/stop.
 */
@Composable
private fun HeartPage(s: WorkoutSnapshot, history: List<Int>, onCommand: (Command) -> Unit) {
    val zone = HeartZones.zoneFor(s.metrics.heartRate)
    val color = zoneColor(zone)
    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        Canvas(Modifier.fillMaxSize().padding(rememberInsets().x(6))) {
            val stroke = Stroke(width = 10.dp.toPx(), cap = StrokeCap.Round)
            drawArc(Color(0xFF26292D), 120f, 300f, false, style = stroke)
            drawArc(color, 120f, 300f * ((s.metrics.heartRate ?: 0) / 190f).coerceIn(0f, 1f), false, style = stroke)
            // Background HR trend (last 60 s), across the middle band.
            if (history.size >= 2) {
                val lo = 60f; val hi = 190f
                val band = size.height * 0.28f
                val top = size.height * 0.36f
                val left = size.width * 0.16f
                val width = size.width * 0.68f
                val step = width / 59f
                val start = left + width - step * (history.size - 1)
                val path = androidx.compose.ui.graphics.Path()
                history.forEachIndexed { i, hr ->
                    val x = start + i * step
                    val y = top + band - ((hr - lo) / (hi - lo)).coerceIn(0f, 1f) * band
                    if (i == 0) path.moveTo(x, y) else path.lineTo(x, y)
                }
                drawPath(path, color.copy(alpha = 0.35f), style = Stroke(width = 3.dp.toPx(), cap = StrokeCap.Round))
            }
        }
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(s.displayType.icon, contentDescription = null, tint = W.Mint, modifier = Modifier.size(18.dp))
                Text(" ${s.displayType.label}", fontSize = 14.sp, color = W.Dim)
            }
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(Icons.Rounded.Favorite, contentDescription = "Heart rate", tint = color, modifier = Modifier.size(26.dp))
                Text(" ${s.metrics.heartRate ?: "--"}", fontSize = 52.sp, fontWeight = FontWeight.Bold)
            }
            Text(if (zone != null && zone > 0) "${zoneLabel(zone)} · ${zoneName(zone)}" else "bpm", fontSize = 13.sp, color = color)
            Spacer(Modifier.height(4.dp))
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(Icons.Rounded.Timer, contentDescription = "Timer", tint = W.Dim, modifier = Modifier.size(18.dp))
                Text(" ${formatElapsed(s.elapsedMs)}", fontSize = 22.sp, fontWeight = FontWeight.SemiBold)
            }
            Spacer(Modifier.height(8.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                val paused = s.phase == WorkoutPhase.Paused
                RoundIcon(if (paused) Icons.Rounded.PlayArrow else Icons.Rounded.Pause, W.Mint) { onCommand(if (paused) Command.ResumeWorkout else Command.PauseWorkout) }
                if (paused) RoundIcon(Icons.Rounded.Stop, W.Coral) { onCommand(Command.StopWorkout) }
            }
        }
    }
}

/** Page 2: compact stat pills. Calories is a headline metric, so it gets the big pill. */
@Composable
private fun StatsPage(s: WorkoutSnapshot) {
    Column(Modifier.fillMaxSize().padding(horizontal = rememberInsets().x(28)), verticalArrangement = Arrangement.Center) {
        Pill(Icons.Rounded.LocalFireDepartment, W.Amber, "${s.metrics.calories}", "kcal", big = true)
        Spacer(Modifier.height(4.dp))
        Pill(Icons.AutoMirrored.Rounded.DirectionsWalk, W.Mint, "%,d".format(s.metrics.steps), "steps")
        Spacer(Modifier.height(4.dp))
        Pill(Icons.Rounded.Route, W.Violet, "%.2f".format(s.metrics.distanceKm), "km")
        Spacer(Modifier.height(4.dp))
        Pill(Icons.Rounded.Bolt, W.Sky, "%.1f".format(s.metrics.speedKmh), "km/h")
        Spacer(Modifier.height(4.dp))
        Pill(Icons.Rounded.Favorite, W.Coral, "${s.avgHeartRate ?: "--"}/${s.maxHeartRate ?: "--"}", "avg/max")
    }
}

@Composable
private fun Pill(icon: ImageVector, tint: Color, value: String, unit: String, big: Boolean = false) {
    Row(
        Modifier.fillMaxWidth().clip(RoundedCornerShape(50)).background(Brush.horizontalGradient(listOf(tint.copy(alpha = 0.28f), W.Pill, W.Pill))).padding(horizontal = 14.dp, vertical = if (big) 10.dp else 6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(icon, contentDescription = unit, tint = tint, modifier = Modifier.size(if (big) 26.dp else 20.dp))
        Text("  $value", fontSize = if (big) 28.sp else 18.sp, fontWeight = FontWeight.Bold)
        Text(" $unit", fontSize = 13.sp, color = W.Dim)
    }
}

/** Page 3: music remote (YouTube Music on the phone in the live build). */
@Composable
private fun MusicPage(np: NowPlaying?, onCommand: (Command) -> Unit, onVolume: (Float) -> Unit) {
    Box(Modifier.fillMaxSize()) {
        VolumeArc(level = np?.volume ?: 0.5f, onChange = onVolume)
        Column(Modifier.fillMaxSize().padding(horizontal = rememberInsets().x(26)), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.Center) {
            Text(np?.title ?: "Nothing playing", fontSize = 16.sp, fontWeight = FontWeight.SemiBold, maxLines = 1, textAlign = TextAlign.Center)
            Text(np?.artist.orEmpty(), fontSize = 13.sp, color = W.Dim, maxLines = 1)
            if (np != null && np.durationMs > 0) {
                Spacer(Modifier.height(6.dp))
                Box(Modifier.width(90.dp).height(3.dp).clip(RoundedCornerShape(2.dp)).background(W.Pill)) {
                    Box(Modifier.fillMaxHeight().fillMaxWidth((np.positionMs.toFloat() / np.durationMs).coerceIn(0f, 1f)).background(W.Rose))
                }
            }
            Spacer(Modifier.height(12.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp), verticalAlignment = Alignment.CenterVertically) {
                RoundIcon(Icons.Rounded.SkipPrevious, W.Rose, sizeDp = 40) { onCommand(Command.PreviousTrack) }
                Box(
                    Modifier.size(64.dp).clip(CircleShape).background(Brush.linearGradient(listOf(W.Rose, W.Violet))).clickable { onCommand(Command.PlayPause) },
                    contentAlignment = Alignment.Center,
                ) { Icon(if (np?.isPlaying == true) Icons.Rounded.Pause else Icons.Rounded.PlayArrow, contentDescription = "Play or pause", tint = Color.White, modifier = Modifier.size(32.dp)) }
                RoundIcon(Icons.Rounded.SkipNext, W.Rose, sizeDp = 40) { onCommand(Command.NextTrack) }
            }
            Spacer(Modifier.height(10.dp))
            Icon(
                if (np?.liked == true) Icons.Rounded.Favorite else Icons.Rounded.FavoriteBorder,
                contentDescription = "Like",
                tint = if (np?.liked == true) W.Rose else W.Dim,
                modifier = Modifier.size(28.dp).clickable { onCommand(Command.LikeTrack) },
            )
        }
    }
}

@Composable
private fun Summary(s: WorkoutSnapshot, onDone: () -> Unit) {
    Column(Modifier.fillMaxSize().padding(horizontal = rememberInsets().x(30)), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.Center) {
        Icon(s.displayType.icon, contentDescription = null, tint = W.Mint, modifier = Modifier.size(28.dp))
        Text("${s.displayType.label} done", fontSize = 16.sp, fontWeight = FontWeight.SemiBold)
        Spacer(Modifier.height(6.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(14.dp)) {
            Mini(Icons.Rounded.Timer, Color.White, formatElapsed(s.elapsedMs))
            Mini(Icons.Rounded.Favorite, W.Coral, "${s.avgHeartRate ?: "--"}")
        }
        Row(horizontalArrangement = Arrangement.spacedBy(14.dp)) {
            Mini(Icons.Rounded.LocalFireDepartment, W.Amber, "${s.metrics.calories}")
            Mini(Icons.Rounded.Route, W.Violet, "%.2f".format(s.metrics.distanceKm))
        }
        Spacer(Modifier.height(10.dp))
        RoundIcon(Icons.Rounded.Check, W.Mint, sizeDp = 48, onClick = onDone)
    }
}

@Composable
private fun Mini(icon: ImageVector, tint: Color, value: String) {
    Row(Modifier.padding(vertical = 3.dp), verticalAlignment = Alignment.CenterVertically) {
        Icon(icon, contentDescription = null, tint = tint, modifier = Modifier.size(16.dp))
        Text(" $value", fontSize = 18.sp, fontWeight = FontWeight.Bold)
    }
}

@Composable
private fun RoundIcon(icon: ImageVector, tint: Color, sizeDp: Int = 44, onClick: () -> Unit) {
    Box(Modifier.size(sizeDp.dp).clip(CircleShape).background(tint.copy(alpha = 0.22f)).clickable(onClick = onClick), contentAlignment = Alignment.Center) {
        Icon(icon, contentDescription = null, tint = tint, modifier = Modifier.size((sizeDp / 2).dp))
    }
}
