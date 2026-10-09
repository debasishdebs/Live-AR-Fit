package com.debasish.livefit.phone.setup

import android.Manifest
import android.app.Activity
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.provider.Settings
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Favorite
import androidx.compose.material.icons.rounded.LibraryMusic
import androidx.compose.material.icons.rounded.Mic
import androidx.compose.material.icons.rounded.MyLocation
import androidx.compose.material.icons.rounded.Shield
import androidx.compose.material.icons.rounded.Watch
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.debasish.livefit.phone.ui.linked.watchMediaControlsTip
import androidx.compose.material3.Button
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.debasish.livefit.model.DeviceKind
import com.debasish.livefit.phone.LiveFitHubService
import com.debasish.livefit.phone.PeerPairing
import com.debasish.livefit.phone.ServiceGraph
import com.debasish.livefit.phone.ui.components.GlassesIcon
import com.debasish.livefit.phone.ui.components.IconChip
import com.debasish.livefit.phone.ui.theme.LiveFitColors
import com.debasish.livefit.services.glasses.AuthActivity
import com.debasish.livefit.services.voice.android.SpeechPacks
import kotlinx.coroutines.launch

@Composable
fun SetupScreen(services: ServiceGraph, onFinished: () -> Unit) {
    val activity = LocalContext.current as Activity
    var step by rememberSaveable { mutableStateOf(SetupStep.Welcome) }
    val flow = remember { SetupFlow(step) }
    var progress by remember { mutableStateOf<Float?>(null) }
    var downloading by remember { mutableStateOf(false) }
    var voiceNote by remember { mutableStateOf<String?>(null) }
    val watchStatus by services.watch.status.collectAsStateWithLifecycle()
    var pairNote by remember { mutableStateOf<String?>(null) }
    val scope = rememberCoroutineScope()
    val permissions = rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) {
        LiveFitHubService.ensureRunning(activity) // Bluetooth now granted: the connectedDevice hub can start
        // Ask for battery-optimization exemption only after the permission dialogs are dismissed.
        activity.startActivity(Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS, Uri.parse("package:${activity.packageName}")))
    }
    val locationPermission = rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) {
        LiveFitHubService.promoteLocation(activity) // granted: the visible app re-promotes the hub with `location`
    }
    fun go(skip: Boolean = false) {
        if (skip) flow.skip() else flow.next()
        step = flow.step; pairNote = null
        if (step == SetupStep.Done) services.settings.setSetupDone()
    }
    // Below Android 13 the companion pairing API is unavailable (Task 14): point at the vendor apps instead.
    fun pair(kind: DeviceKind, app: String) {
        pairNote = if (Build.VERSION.SDK_INT >= 33) "Tap Allow on your ${if (kind == DeviceKind.Watch) "watch" else "glasses"} when asked." else null
        PeerPairing.pair(activity, services, kind) { ok ->
            if (ok) LiveFitHubService.ensureRunning(activity) // an association also satisfies the connectedDevice prerequisite
            pairNote = if (ok) "Paired." else if (Build.VERSION.SDK_INT < 33) "Pair from $app, then tap Next (or Skip)." else "Pairing didn't complete. Try again or Skip."
        }
    }

    // D1: Hi Rokid answers silently in ~10 ms when LiveFit is already authorized, so say what happened.
    var authAsked by remember { mutableStateOf(false) }
    LaunchedEffect(Unit) {
        services.glasses.authResults.collect { ok ->
            if (authAsked) { authAsked = false; pairNote = authNote(ok) }
        }
    }
    fun authorize() {
        authAsked = true
        pairNote = "Authorizing with Hi Rokid…"
        if (AuthActivity.launch(activity).isFailure) { authAsked = false; pairNote = authNote(false) }
    }

    Column(Modifier.fillMaxSize().background(LiveFitColors.HeaderGradient).statusBarsPadding().padding(24.dp), horizontalAlignment = Alignment.CenterHorizontally) {
        LinearProgressIndicator(progress = { (step.ordinal + 1) / SetupStep.entries.size.toFloat() }, modifier = Modifier.fillMaxWidth(), color = LiveFitColors.Mint)
        Column(Modifier.weight(1f).verticalScroll(rememberScrollState()), horizontalAlignment = Alignment.CenterHorizontally) {
        Spacer(Modifier.height(48.dp))
        val (icon, title, body) = when (step) {
            SetupStep.Welcome -> Triple(Icons.Rounded.Shield, "Welcome to Live AR Fit", "Allow microphone, nearby devices, notifications and background use so the hub can run during workouts.")
            SetupStep.Glasses -> Triple(GlassesIcon, "Link your Rokid glasses", "Authorize LiveFit in Hi Rokid, then pair so Android wakes LiveFit when the glasses are near." +
                if (Build.VERSION.SDK_INT < 33) " On this Android version, pair from Hi Rokid instead." else " Tap Allow on your glasses when asked.")
            SetupStep.Watch -> Triple(Icons.Rounded.Watch, "Link your Wear OS watch", (if (Build.VERSION.SDK_INT < 33) "Pair the watch" else "Pair the watch (tap Allow on your watch when asked)") +
                ", then open Live AR Fit on the watch once and tap Allow for heart-rate sensors." +
                if (Build.VERSION.SDK_INT < 33) " On this Android version, pair it from your watch's companion app (Galaxy Wearable, Pixel Watch, Wear OS…) instead." else "")
            SetupStep.Music -> Triple(Icons.Rounded.LibraryMusic, "Control YouTube Music", "Give LiveFit notification access so it can play, skip and like songs.")
            SetupStep.Map -> Triple(Icons.Rounded.MyLocation, "Map fallback", "Allow location so your phone can draw the route when the watch has no GPS fix. Optional — workouts record without it.")
            SetupStep.Voice -> Triple(Icons.Rounded.Mic, "Offline voice: English (India)", "Download the on-device voice pack. Voice stays off until it's installed — there's no online fallback.")
            SetupStep.Done -> Triple(Icons.Rounded.Favorite, "All set", "")
        }
        IconChip(icon, LiveFitColors.ChipMint, size = 96.dp, shapeRadius = 28.dp)
        Spacer(Modifier.height(20.dp))
        Text(title, style = MaterialTheme.typography.headlineMedium, textAlign = TextAlign.Center)
        Text(body, style = MaterialTheme.typography.bodyMedium, color = LiveFitColors.InkSoft, textAlign = TextAlign.Center, modifier = Modifier.padding(top = 8.dp))
        Spacer(Modifier.height(24.dp))
        when (step) {
            SetupStep.Welcome -> Button(onClick = {
                permissions.launch(setupPermissions(Build.VERSION.SDK_INT))
            }) { Text("Grant access") }
            SetupStep.Glasses -> Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                OutlinedButton(onClick = { authorize() }) { Text("Authorize") }
                Button(onClick = { pair(DeviceKind.Glasses, "Hi Rokid") }) { Text("Pair") }
            }
            SetupStep.Watch -> Button(onClick = { pair(DeviceKind.Watch, "your watch's companion app (Galaxy Wearable, Pixel Watch, Wear OS…)") }) { Text("Pair watch") }
            SetupStep.Music -> Button(onClick = { activity.startActivity(Intent(Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS)) }) { Text("Open notification access") }
            SetupStep.Map -> Button(onClick = { locationPermission.launch(arrayOf(Manifest.permission.ACCESS_FINE_LOCATION, Manifest.permission.ACCESS_COARSE_LOCATION)) }) { Text("Allow location") }
            SetupStep.Voice -> Column(horizontalAlignment = Alignment.CenterHorizontally) {
                Button(enabled = !downloading, onClick = {
                    downloading = true; voiceNote = null
                    scope.launch {
                        val tag = services.settings.voiceLocale.value
                        val r = SpeechPacks.download(activity, tag) { progress = it }
                        downloading = false
                        val installed = r is SpeechPacks.Result.Success || tag in SpeechPacks.query(activity).installed
                        if (installed) { flow.voicePackInstalled(); services.refreshVoicePacks(); voiceNote = "Voice pack ready." }
                        else voiceNote = when (r) {
                            SpeechPacks.Result.Scheduled -> "Download scheduled. Google will finish it in the background; enable voice later in Languages."
                            is SpeechPacks.Result.Error -> r.message
                            else -> "Voice pack not installed yet."
                        }
                    }
                }) { Text(if (downloading) "Downloading…" else "Download voice pack") }
                progress?.takeIf { downloading }?.let { LinearProgressIndicator(progress = { it }, modifier = Modifier.fillMaxWidth().padding(top = 12.dp), color = LiveFitColors.Mint) }
            }
            SetupStep.Done -> Unit
        }
        (if (step == SetupStep.Voice) voiceNote else pairNote)?.let {
            Text(it, style = MaterialTheme.typography.bodySmall, color = LiveFitColors.InkSoft, textAlign = TextAlign.Center, modifier = Modifier.padding(top = 12.dp))
        }
        if (step == SetupStep.Watch) {
            Text(watchMediaControlsTip(watchStatus.name), style = MaterialTheme.typography.bodySmall, color = LiveFitColors.InkSoft, textAlign = TextAlign.Center, modifier = Modifier.padding(top = 16.dp))
        }
        }
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
            TextButton(onClick = { flow.back(); step = flow.step; pairNote = null }, enabled = step != SetupStep.Welcome) { Text("Back") }
            Row {
                if (step != SetupStep.Done) TextButton(onClick = { go(skip = true) }) { Text("Skip") }
                Button(onClick = { if (step == SetupStep.Done) onFinished() else go() }) { Text(if (step == SetupStep.Done) "Start" else "Next") }
            }
        }
    }
}

/** What the setup and Settings screens say after a Hi Rokid authorization attempt (D1). */
fun authNote(ok: Boolean): String = if (ok) "Authorized." else "Authorization failed — open Hi Rokid and try again"
