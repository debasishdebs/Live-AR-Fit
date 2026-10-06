package com.debasish.livefit.phone.ui.linked

import android.content.Intent
import android.provider.Settings
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.LibraryMusic
import androidx.compose.material.icons.automirrored.rounded.QueueMusic
import androidx.compose.material.icons.rounded.Pause
import androidx.compose.material.icons.rounded.PlayArrow
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.foundation.layout.Row
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.debasish.livefit.phone.ServiceGraph
import com.debasish.livefit.phone.ui.components.ChipRow
import com.debasish.livefit.phone.ui.components.ScreenHeader
import com.debasish.livefit.phone.ui.components.SectionLabel
import com.debasish.livefit.phone.ui.components.SoftCard
import com.debasish.livefit.phone.ui.theme.LiveFitColors
import com.debasish.livefit.services.music.MusicOnStart
import com.debasish.livefit.services.music.QueueWindowing
import kotlin.math.roundToInt

@Composable
fun LinkedMusicScreen(services: ServiceGraph, onBack: () -> Unit) {
    val context = LocalContext.current
    val connected by services.musicConnected.collectAsStateWithLifecycle()
    val onStart by services.settings.musicOnStart.collectAsStateWithLifecycle()
    val search by services.settings.musicSearch.collectAsStateWithLifecycle()
    val pauseOnStop by services.settings.pauseMusicOnStop.collectAsStateWithLifecycle()
    val queueSize by services.settings.glassesQueueSize.collectAsStateWithLifecycle()
    Column(Modifier.fillMaxSize().background(LiveFitColors.SurfaceSoft).verticalScroll(rememberScrollState())) {
        ScreenHeader("YouTube Music", onBack)
        SoftCard(Modifier.padding(horizontal = 16.dp).fillMaxWidth()) {
            ChipRow(Icons.Rounded.LibraryMusic, LiveFitColors.ChipRose, "Music control", if (connected) "Connected" else "Needs notification access",
                { context.startActivity(Intent(Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) })
        }
        SectionLabel("When a workout starts")
        SoftCard(Modifier.padding(horizontal = 16.dp).fillMaxWidth()) {
            Column {
                MusicOnStart.entries.forEach { option ->
                    val label = when (option) { MusicOnStart.DontTouch -> "Don't touch music"; MusicOnStart.Resume -> "Resume last played"; MusicOnStart.PlaySearch -> "Play saved search" }
                    ChipRow(Icons.Rounded.PlayArrow, LiveFitColors.ChipRose, label, null, { services.settings.setMusicOnStart(option) },
                        trailing = { RadioButton(selected = onStart == option, onClick = { services.settings.setMusicOnStart(option) }) })
                }
                if (onStart == MusicOnStart.PlaySearch) OutlinedTextField(search, services.settings::setMusicSearch, label = { Text("Search") },
                    modifier = Modifier.fillMaxWidth().padding(16.dp), singleLine = true)
            }
        }
        SectionLabel("When a workout stops")
        SoftCard(Modifier.padding(horizontal = 16.dp).fillMaxWidth()) {
            ChipRow(Icons.Rounded.Pause, LiveFitColors.ChipRose, "Pause music", null, { services.settings.setPauseMusicOnStop(!pauseOnStop) },
                trailing = { Switch(pauseOnStop, null) })
        }
        SectionLabel("Glasses music screen")
        SoftCard(Modifier.padding(horizontal = 16.dp).fillMaxWidth()) {
            // Draft while dragging; persisted (and re-windowed within ~1 s) when the drag ends.
            var draft by remember(queueSize) { mutableFloatStateOf(queueSize.toFloat()) }
            Column(Modifier.padding(16.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    com.debasish.livefit.phone.ui.components.IconChip(Icons.AutoMirrored.Rounded.QueueMusic, LiveFitColors.ChipRose)
                    Slider(
                        value = draft, onValueChange = { draft = it.roundToInt().toFloat() },
                        onValueChangeFinished = { services.settings.setGlassesQueueSize(draft.roundToInt()) },
                        valueRange = QueueWindowing.MIN_SIZE.toFloat()..QueueWindowing.MAX_SIZE.toFloat(),
                        steps = QueueWindowing.MAX_SIZE - QueueWindowing.MIN_SIZE - 1,
                        modifier = Modifier.weight(1f).padding(horizontal = 12.dp),
                        colors = SliderDefaults.colors(thumbColor = LiveFitColors.ChipRose.second, activeTrackColor = LiveFitColors.ChipRose.second, inactiveTrackColor = LiveFitColors.ChipRose.first),
                    )
                    Text("${draft.roundToInt()}", style = MaterialTheme.typography.titleMedium)
                }
                Text("Songs listed: played ones before the current song, then up next", style = MaterialTheme.typography.labelMedium, color = LiveFitColors.InkSoft)
            }
        }
        Text("V2 adds Google sign-in here for playlists.", color = LiveFitColors.InkSoft, modifier = Modifier.padding(20.dp))
    }
}
