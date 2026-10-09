package com.debasish.livefit.phone.ui.settings

import android.Manifest
import android.content.pm.PackageManager
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Bluetooth
import androidx.compose.material.icons.rounded.Code
import androidx.compose.material.icons.rounded.Dashboard
import androidx.compose.material.icons.rounded.Info
import androidx.compose.material.icons.rounded.Language
import androidx.compose.material.icons.rounded.Mic
import androidx.compose.material.icons.rounded.Shield
import androidx.compose.material.icons.rounded.Straighten
import androidx.compose.material.icons.rounded.TouchApp
import androidx.compose.material.icons.rounded.ViewCarousel
import androidx.compose.material.icons.rounded.Watch
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material.icons.rounded.DeleteSweep
import androidx.compose.material.icons.rounded.LibraryMusic
import androidx.compose.material.icons.rounded.MyLocation
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.core.content.ContextCompat
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.debasish.livefit.phone.HubLocationPolicy
import com.debasish.livefit.phone.LiveFitHubService
import com.debasish.livefit.phone.BuildConfig
import com.debasish.livefit.model.WorkoutPhase
import com.debasish.livefit.phone.ServiceGraph
import com.debasish.livefit.phone.ui.AppActivity
import com.debasish.livefit.phone.ui.list.ListSources
import com.debasish.livefit.phone.ui.components.GlassesIcon
import kotlinx.coroutines.launch
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.debasish.livefit.phone.ui.components.ChipRow
import com.debasish.livefit.phone.ui.components.ScreenHeader
import com.debasish.livefit.phone.ui.components.SectionLabel
import com.debasish.livefit.phone.ui.components.SoftCard
import com.debasish.livefit.phone.ui.theme.LiveFitColors
import androidx.compose.ui.platform.LocalContext
import com.debasish.livefit.phone.ui.linked.hasMusicAccess
import com.debasish.livefit.phone.ui.linked.musicStatusLabel

@Composable
fun SettingsScreen(
    services: ServiceGraph,
    onBack: () -> Unit,
    onLanguages: () -> Unit,
    onDeveloper: () -> Unit,
    onNavigate: (String) -> Unit,
) {
    val voiceLocale by services.settings.voiceLocale.collectAsStateWithLifecycle()
    val gps by services.settings.gpsOutdoors.collectAsStateWithLifecycle()
    val glasses by services.glasses.status.collectAsStateWithLifecycle()
    val watch by services.watch.status.collectAsStateWithLifecycle()
    val glassesStatus = glasses.link.name + (glasses.batteryPct?.let { " · $it%" } ?: "")
    val watchStatus = watch.link.name + (watch.batteryPct?.let { " · $it%" } ?: "")
    val musicStatus = musicStatusLabel(services.musicConnected.collectAsStateWithLifecycle().value, hasMusicAccess(LocalContext.current))
    val nearby by services.settings.startWhenNearby.collectAsStateWithLifecycle()
    val hubHasLocation by LiveFitHubService.locationCapable.collectAsStateWithLifecycle()
    val fineLocation = ContextCompat.checkSelfPermission(LocalContext.current, Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED
    val nearbyStatus = "Start LiveFit when nearby: " + nearby.filterValues { it }.keys
        .joinToString { if (it == com.debasish.livefit.model.DeviceKind.Glasses) "glasses" else "watch" }.ifEmpty { "off" }
    val phase = services.workout.snapshot.collectAsStateWithLifecycle().value.phase
    // Clearing only touches finished workouts, but stay out of the way while one is running or saving.
    val canClear = phase == WorkoutPhase.Idle || phase == WorkoutPhase.Summary
    var confirmClear by remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()
    if (confirmClear && canClear) AlertDialog(
        onDismissRequest = { confirmClear = false },
        title = { Text("Clear history?") },
        text = { Text("All finished workouts stored on this phone are deleted.") },
        confirmButton = { TextButton(onClick = { confirmClear = false; if (canClear) scope.launch { services.history.clearFinished() } }) { Text("Clear") } },
        dismissButton = { TextButton(onClick = { confirmClear = false }) { Text("Cancel") } },
    )
    Column(Modifier.fillMaxSize().background(LiveFitColors.SurfaceSoft).verticalScroll(rememberScrollState())) {
        ScreenHeader("Settings", onBack)

        SectionLabel("General")
        Group {
            ChipRow(Icons.Rounded.Language, LiveFitColors.ChipSky, "Languages", "Voice packs · voice: $voiceLocale", onLanguages)
            Divider()
            ChipRow(Icons.Rounded.Straighten, LiveFitColors.ChipAmber, "Units", "Metric", { onNavigate("units") })
        }
        SectionLabel("Linked services")
        Group {
            ChipRow(GlassesIcon, LiveFitColors.ChipMint, "Rokid glasses", glassesStatus, { onNavigate("linked/glasses") })
            Divider()
            ChipRow(Icons.Rounded.Watch, LiveFitColors.ChipViolet, "Watch", watchStatus, { onNavigate("linked/watch") })
            Divider()
            ChipRow(Icons.Rounded.LibraryMusic, LiveFitColors.ChipRose, "YouTube Music", musicStatus, { onNavigate("linked/music") })
            Divider()
            ChipRow(Icons.Rounded.Bluetooth, LiveFitColors.ChipSky, "Nearby devices", nearbyStatus, { onNavigate("nearby") })
            Divider()
            ChipRow(Icons.Rounded.MyLocation, LiveFitColors.ChipAmber, "Phone GPS", HubLocationPolicy.phoneGpsLabel(fineLocation, hubHasLocation), { onNavigate("permissions") })
        }
        SectionLabel("Glasses")
        Group {
            ChipRow(Icons.Rounded.Dashboard, LiveFitColors.ChipSky, "Glasses display", "Size, position, metrics", { onNavigate("hud") })
            Divider()
            val pagesOff = services.settings.pages.collectAsStateWithLifecycle().value.disabled.size
            ChipRow(Icons.Rounded.ViewCarousel, LiveFitColors.ChipMint, "Pages", "Glasses + Watch · " + if (pagesOff == 0) "all on" else "$pagesOff turned off",
                { onNavigate(AppActivity.listRoute(ListSources.PAGES, filter = null)) })
            Divider()
            ChipRow(Icons.Rounded.TouchApp, LiveFitColors.ChipViolet, "Glasses gestures", "Per page and mode",
                { onNavigate(AppActivity.listRoute(ListSources.GESTURES, filter = null)) })
        }
        SectionLabel("Voice")
        Group {
            val voiceOff = services.settings.disabledVoiceGroups.collectAsStateWithLifecycle().value.size
            ChipRow(Icons.Rounded.Mic, LiveFitColors.ChipViolet, "Voice commands", if (voiceOff == 0) "All on" else "$voiceOff turned off",
                { onNavigate(com.debasish.livefit.phone.ui.AppActivity.listRoute(com.debasish.livefit.phone.ui.list.ListSources.VOICE_COMMANDS, filter = null)) })
        }
        SectionLabel("Workout")
        Group {
            ChipRow(Icons.Rounded.MyLocation, LiveFitColors.ChipMint, "Use GPS outdoors", "Walk, Run, Cycle, Auto · live map", onClick = { services.settings.setGpsOutdoors(!gps) },
                trailing = { Switch(gps, null) })
        }
        SectionLabel("Data")
        Group {
            ChipRow(Icons.Rounded.DeleteSweep, LiveFitColors.ChipCoral, "Clear history",
                if (canClear) "Removes all workouts on this phone" else "Available when no workout is running", { confirmClear = true }, enabled = canClear)
        }
        SectionLabel("Advanced")
        Group {
            if (BuildConfig.DEBUG) { ChipRow(Icons.Rounded.Code, LiveFitColors.ChipSlate, "Developer tools", "Spike console", onDeveloper); Divider() }
            ChipRow(Icons.Rounded.Shield, LiveFitColors.ChipCoral, "Permissions", null, { onNavigate("permissions") })
            Divider()
            ChipRow(Icons.Rounded.Info, LiveFitColors.ChipRose, "About", "Live AR Fit ${BuildConfig.VERSION_NAME}", { onNavigate("about") })
        }
    }
}

@Composable
private fun Group(content: @Composable () -> Unit) {
    SoftCard(Modifier.padding(horizontal = 16.dp).fillMaxWidth()) { Column { content() } }
}

@Composable
private fun Divider() = HorizontalDivider(Modifier.padding(start = 70.dp), color = LiveFitColors.Line)
