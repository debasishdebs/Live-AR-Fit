package com.debasish.livefit.phone.ui.workout

import com.debasish.livefit.model.Command
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
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
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Bolt
import androidx.compose.material.icons.rounded.CheckCircle
import androidx.compose.material.icons.rounded.Favorite
import androidx.compose.material.icons.rounded.LocalFireDepartment
import androidx.compose.material.icons.rounded.Mic
import androidx.compose.material.icons.rounded.Pause
import androidx.compose.material.icons.rounded.PlayArrow
import androidx.compose.material.icons.rounded.Route
import androidx.compose.material.icons.rounded.SkipNext
import androidx.compose.material.icons.rounded.Stop
import androidx.compose.material.icons.rounded.Timer
import androidx.compose.material.icons.automirrored.rounded.DirectionsWalk
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.debasish.livefit.model.HeartZones
import com.debasish.livefit.model.VoiceState
import com.debasish.livefit.model.WorkoutPhase
import com.debasish.livefit.model.WorkoutSnapshot
import com.debasish.livefit.model.formatElapsed
import com.debasish.livefit.phone.ServiceGraph
import com.debasish.livefit.phone.ui.components.IconChip
import com.debasish.livefit.phone.ui.components.ScreenHeader
import com.debasish.livefit.phone.ui.components.SoftCard
import com.debasish.livefit.phone.ui.components.chip
import com.debasish.livefit.phone.ui.components.icon
import com.debasish.livefit.phone.ui.components.zoneColor
import com.debasish.livefit.phone.ui.theme.LiveFitColors

/** Live workout and its summary, driven entirely by WorkoutService / MusicService / VoiceService. */
@Composable
fun WorkoutScreen(services: ServiceGraph, onBack: () -> Unit, onMusic: () -> Unit) {
    val s by services.workout.snapshot.collectAsStateWithLifecycle()
    val music by services.music.nowPlaying.collectAsStateWithLifecycle()
    val voice by services.voice.state.collectAsStateWithLifecycle()
    val toast by services.toast.collectAsStateWithLifecycle()

    Box(Modifier.fillMaxSize().background(LiveFitColors.Surface)) {
        Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState())) {
            ScreenHeader(if (s.phase == WorkoutPhase.Summary) "Summary" else "Workout", onBack) {
                PhasePill(s.phase)
            }
            if (s.phase == WorkoutPhase.Summary) Summary(s) { services.localCommand(Command.DismissSummary); onBack() }
            else Live(s)

            if (s.phase != WorkoutPhase.Summary) {
                music?.let { np ->
                    SoftCard(Modifier.padding(16.dp).fillMaxWidth(), onClick = onMusic) {
                        Row(Modifier.padding(14.dp), verticalAlignment = Alignment.CenterVertically) {
                            Box(Modifier.size(44.dp).clip(RoundedCornerShape(12.dp)).background(Brush.linearGradient(listOf(Color(0xFFFF8FB1), Color(0xFF8E7CFF)))))
                            Column(Modifier.weight(1f).padding(horizontal = 12.dp)) {
                                Text(np.title, style = MaterialTheme.typography.titleMedium, maxLines = 1)
                                Text(np.artist, style = MaterialTheme.typography.bodyMedium, color = LiveFitColors.InkSoft)
                            }
                            RoundButton(if (np.isPlaying) Icons.Rounded.Pause else Icons.Rounded.PlayArrow, LiveFitColors.ChipRose, 40) { services.localCommand(Command.PlayPause) }
                            Spacer(Modifier.width(8.dp))
                            RoundButton(Icons.Rounded.SkipNext, LiveFitColors.ChipRose, 40) { services.localCommand(Command.NextTrack) }
                        }
                    }
                }
                Spacer(Modifier.height(140.dp))
            }
        }

        val pillModifier = Modifier.align(Alignment.BottomCenter).navigationBarsPadding().padding(bottom = 24.dp)
        when (s.phase) {
            WorkoutPhase.Starting -> StatusPill(pillModifier, "Starting on your watch…")
            WorkoutPhase.Syncing -> StatusPill(pillModifier, "Syncing watch data…")
            WorkoutPhase.Stopping -> StatusPill(pillModifier, "Saving workout…")
            WorkoutPhase.Summary -> Unit
            else -> Controls(
                modifier = Modifier.align(Alignment.BottomCenter).navigationBarsPadding().padding(bottom = 16.dp),
                phase = s.phase,
                voice = voice,
                onPauseResume = { services.localCommand(if (s.phase == WorkoutPhase.Paused) Command.ResumeWorkout else Command.PauseWorkout) },
                onStop = { services.localCommand(Command.StopWorkout) },
                onVoice = { services.voice.listen() },
            )
        }

        toast?.let {
            Row(
                Modifier.align(Alignment.BottomCenter).navigationBarsPadding().padding(bottom = 124.dp).clip(RoundedCornerShape(20.dp)).background(LiveFitColors.Ink).padding(horizontal = 16.dp, vertical = 10.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Icon(Icons.Rounded.CheckCircle, contentDescription = null, tint = LiveFitColors.Mint, modifier = Modifier.size(18.dp))
                Text("  $it", color = Color.White, style = MaterialTheme.typography.titleMedium)
            }
        }
    }
}

@Composable
private fun StatusPill(modifier: Modifier, text: String) {
    Row(modifier.clip(RoundedCornerShape(24.dp)).background(LiveFitColors.ChipSky.first).padding(horizontal = 18.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically) {
        CircularProgressIndicator(Modifier.size(18.dp), strokeWidth = 2.dp, color = LiveFitColors.ChipSky.second)
        Text("  $text", color = LiveFitColors.ChipSky.second, style = MaterialTheme.typography.titleMedium)
    }
}

@Composable
private fun Live(s: WorkoutSnapshot) {
    val zone = HeartZones.zoneFor(s.metrics.heartRate)
    Column(Modifier.fillMaxWidth().padding(horizontal = 16.dp), horizontalAlignment = Alignment.CenterHorizontally) {
        // Headline row 1: workout type + live timer
        Row(verticalAlignment = Alignment.CenterVertically) {
            IconChip(s.displayType.icon, s.displayType.chip, size = 44.dp)
            Text("  ${s.displayType.label}" + if (s.type.name == "Auto") "  · auto" else "", style = MaterialTheme.typography.titleLarge)
        }
        Spacer(Modifier.height(4.dp))
        Row(verticalAlignment = Alignment.CenterVertically) {
            Icon(Icons.Rounded.Timer, contentDescription = "Timer", tint = LiveFitColors.InkSoft)
            Text(" ${formatElapsed(s.elapsedMs)}", fontSize = 44.sp, fontWeight = FontWeight.Bold)
        }
        Spacer(Modifier.height(12.dp))

        // Headline row 2: heart-rate ring + calories
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceEvenly, verticalAlignment = Alignment.CenterVertically) {
            HeartRing(s.metrics.heartRate, zone)
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                IconChip(Icons.Rounded.LocalFireDepartment, LiveFitColors.ChipAmber, size = 48.dp)
                Text("${s.metrics.calories}", fontSize = 40.sp, fontWeight = FontWeight.Bold)
                Text("kcal", color = LiveFitColors.InkSoft)
            }
        }
        Spacer(Modifier.height(16.dp))

        // Secondary: steps, distance, speed
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            SmallStat(Icons.AutoMirrored.Rounded.DirectionsWalk, "%,d".format(s.metrics.steps), "steps", LiveFitColors.ChipMint, Modifier.weight(1f))
            SmallStat(Icons.Rounded.Route, "%.2f".format(s.metrics.distanceKm), "km", LiveFitColors.ChipViolet, Modifier.weight(1f))
            SmallStat(Icons.Rounded.Bolt, "%.1f".format(s.metrics.speedKmh), "km/h", LiveFitColors.ChipSky, Modifier.weight(1f))
        }
    }
}

@Composable
private fun HeartRing(hr: Int?, zone: Int?) {
    val color = zoneColor(zone)
    Box(Modifier.size(150.dp), contentAlignment = Alignment.Center) {
        Canvas(Modifier.fillMaxSize()) {
            val stroke = Stroke(width = 14.dp.toPx(), cap = StrokeCap.Round)
            drawArc(LiveFitColors.Line, 135f, 270f, false, style = stroke)
            val frac = ((hr ?: 0) / 190f).coerceIn(0f, 1f)
            drawArc(color, 135f, 270f * frac, false, style = stroke)
        }
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Icon(Icons.Rounded.Favorite, contentDescription = "Heart rate", tint = LiveFitColors.ChipCoral.second)
            Text(hr?.toString() ?: "--", fontSize = 40.sp, fontWeight = FontWeight.Bold)
            Text(if (zone != null) "bpm · Z$zone" else "bpm", color = LiveFitColors.InkSoft)
        }
    }
}

@Composable
private fun SmallStat(icon: ImageVector, value: String, unit: String, colors: Pair<Color, Color>, modifier: Modifier) {
    SoftCard(modifier) {
        Column(Modifier.padding(12.dp), horizontalAlignment = Alignment.CenterHorizontally) {
            IconChip(icon, colors, size = 32.dp, shapeRadius = 10.dp)
            Spacer(Modifier.height(6.dp))
            Text(value, style = MaterialTheme.typography.titleLarge)
            Text(unit, style = MaterialTheme.typography.labelMedium, color = LiveFitColors.InkSoft)
        }
    }
}

@Composable
private fun Summary(s: WorkoutSnapshot, onDone: () -> Unit) {
    Column(Modifier.padding(16.dp), horizontalAlignment = Alignment.CenterHorizontally) {
        IconChip(s.displayType.icon, s.displayType.chip, size = 72.dp, shapeRadius = 24.dp)
        Spacer(Modifier.height(8.dp))
        Text("${s.displayType.label} complete", style = MaterialTheme.typography.headlineMedium)
        Spacer(Modifier.height(16.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            SmallStat(Icons.Rounded.Timer, formatElapsed(s.elapsedMs), "time", LiveFitColors.ChipSlate, Modifier.weight(1f))
            SmallStat(Icons.Rounded.Favorite, "${s.avgHeartRate ?: "--"}", "avg bpm", LiveFitColors.ChipCoral, Modifier.weight(1f))
            SmallStat(Icons.Rounded.LocalFireDepartment, "${s.metrics.calories}", "kcal", LiveFitColors.ChipAmber, Modifier.weight(1f))
        }
        Spacer(Modifier.height(10.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            SmallStat(Icons.AutoMirrored.Rounded.DirectionsWalk, "%,d".format(s.metrics.steps), "steps", LiveFitColors.ChipMint, Modifier.weight(1f))
            SmallStat(Icons.Rounded.Route, "%.2f".format(s.metrics.distanceKm), "km", LiveFitColors.ChipViolet, Modifier.weight(1f))
            SmallStat(Icons.Rounded.Favorite, "${s.maxHeartRate ?: "--"}", "max bpm", LiveFitColors.ChipRose, Modifier.weight(1f))
        }
        Spacer(Modifier.height(24.dp))
        Box(
            Modifier.fillMaxWidth().clip(RoundedCornerShape(28.dp)).background(Brush.linearGradient(listOf(LiveFitColors.Mint, LiveFitColors.Sky))).clickable(onClick = onDone).padding(16.dp),
            contentAlignment = Alignment.Center,
        ) { Text("Done", color = Color.White, style = MaterialTheme.typography.titleMedium) }
    }
}

@Composable
private fun PhasePill(phase: WorkoutPhase) {
    val (bg, fg, label) = when (phase) {
        WorkoutPhase.Active -> Triple(LiveFitColors.ChipMint.first, LiveFitColors.MintDeep, "● LIVE")
        WorkoutPhase.Paused -> Triple(LiveFitColors.ChipAmber.first, LiveFitColors.ChipAmber.second, "❚❚ PAUSED")
        WorkoutPhase.Starting -> Triple(LiveFitColors.ChipSky.first, LiveFitColors.ChipSky.second, "STARTING")
        else -> Triple(LiveFitColors.ChipSlate.first, LiveFitColors.ChipSlate.second, phase.name.uppercase())
    }
    Text(label, color = fg, style = MaterialTheme.typography.labelMedium, modifier = Modifier.padding(end = 12.dp).clip(RoundedCornerShape(12.dp)).background(bg).padding(horizontal = 10.dp, vertical = 4.dp))
}

@Composable
private fun Controls(modifier: Modifier, phase: WorkoutPhase, voice: VoiceState, onPauseResume: () -> Unit, onStop: () -> Unit, onVoice: () -> Unit) {
    Row(modifier, horizontalArrangement = Arrangement.spacedBy(20.dp), verticalAlignment = Alignment.CenterVertically) {
        RoundButton(Icons.Rounded.Stop, LiveFitColors.ChipCoral, 60, onStop)
        Box(
            Modifier.size(84.dp).clip(CircleShape).background(Brush.linearGradient(listOf(LiveFitColors.Mint, LiveFitColors.Sky))).clickable(onClick = onPauseResume),
            contentAlignment = Alignment.Center,
        ) {
            Icon(if (phase == WorkoutPhase.Paused) Icons.Rounded.PlayArrow else Icons.Rounded.Pause, contentDescription = "Pause or resume", tint = Color.White, modifier = Modifier.size(42.dp))
        }
        val listening = voice != VoiceState.Idle
        RoundButton(Icons.Rounded.Mic, if (listening) LiveFitColors.Mint to Color.White else LiveFitColors.ChipSky, 60, onVoice)
    }
}

@Composable
private fun RoundButton(icon: ImageVector, colors: Pair<Color, Color>, sizeDp: Int, onClick: () -> Unit) {
    Box(Modifier.size(sizeDp.dp).clip(CircleShape).background(colors.first).clickable(onClick = onClick), contentAlignment = Alignment.Center) {
        Icon(icon, contentDescription = null, tint = colors.second, modifier = Modifier.size((sizeDp * 0.5).dp))
    }
}
