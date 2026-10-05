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
import androidx.compose.material.icons.rounded.Pause
import androidx.compose.material.icons.rounded.PlayArrow
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
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

@Composable
fun LinkedMusicScreen(services: ServiceGraph, onBack: () -> Unit) {
    val context = LocalContext.current
    val connected by services.musicConnected.collectAsStateWithLifecycle()
    val onStart by services.settings.musicOnStart.collectAsStateWithLifecycle()
    val search by services.settings.musicSearch.collectAsStateWithLifecycle()
    val pauseOnStop by services.settings.pauseMusicOnStop.collectAsStateWithLifecycle()
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
                trailing = { Switch(pauseOnStop, services.settings::setPauseMusicOnStop) })
        }
        Text("V2 adds Google sign-in here for playlists.", color = LiveFitColors.InkSoft, modifier = Modifier.padding(20.dp))
    }
}
