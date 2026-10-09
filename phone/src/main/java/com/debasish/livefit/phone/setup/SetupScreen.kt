package com.debasish.livefit.phone.setup

import android.app.Activity
import android.os.Build
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
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LifecycleEventEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import android.Manifest
import android.content.pm.PackageManager
import androidx.core.content.ContextCompat
import com.debasish.livefit.phone.ui.linked.hasMusicAccess
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
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
import com.debasish.livefit.model.Disclosures
import com.debasish.livefit.phone.LiveFitHubService
import com.debasish.livefit.phone.PeerPairing
import com.debasish.livefit.phone.ServiceGraph
import com.debasish.livefit.phone.ui.DisclosureActivity
import com.debasish.livefit.phone.ui.DisclosureKind
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
    // Green tick on a completed step, then a short beat before moving on (denied/failed steps never set it).
    var ticked by remember { mutableStateOf<SetupStep?>(null) }
    var glassesAuthorized by rememberSaveable { mutableStateOf(false) }
    var glassesPaired by rememberSaveable { mutableStateOf(false) }
    var cameBack by remember { mutableStateOf(false) } // arriving by Back must not bounce forward again
    var musicDisclosureShown by rememberSaveable { mutableStateOf(false) }
    var mapDisclosureShown by rememberSaveable { mutableStateOf(false) }
    fun has(p: String) = ContextCompat.checkSelfPermission(activity, p) == PackageManager.PERMISSION_GRANTED
    fun permissionsGranted() = setupPermissions(Build.VERSION.SDK_INT).all { has(it) }
    fun locationGranted() = has(Manifest.permission.ACCESS_FINE_LOCATION) || has(Manifest.permission.ACCESS_COARSE_LOCATION)
    fun tick(forStep: SetupStep, outcome: StepOutcome = StepOutcome.Granted, disclosureRequired: Boolean = false, disclosureShown: Boolean = true) {
        if (step == forStep && shouldAutoAdvance(outcome, disclosureRequired, disclosureShown)) ticked = forStep
    }
    val permissions = rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { _ ->
        LiveFitHubService.ensureRunning(activity) // Bluetooth now granted: the connectedDevice hub can start
        tick(SetupStep.Welcome, if (permissionsGranted()) StepOutcome.Granted else StepOutcome.Denied)
    }
    fun go(skip: Boolean = false) {
        if (skip) flow.skip() else flow.next()
        step = flow.step; pairNote = null; ticked = null; cameBack = false
        if (step == SetupStep.Done) services.settings.setSetupDone()
    }
    LaunchedEffect(ticked) {
        val t = ticked ?: return@LaunchedEffect
        delay(TICK_ADVANCE_MS)
        if (step == t) go()
    }
    // Steps already satisfied on entry (re-running setup): tick and move on. Disclosure steps wait for their disclosure.
    LaunchedEffect(step) {
        if (cameBack) return@LaunchedEffect
        when (step) {
            SetupStep.Welcome -> if (permissionsGranted()) tick(step, StepOutcome.AlreadySatisfied)
            SetupStep.Music -> if (hasMusicAccess(activity)) tick(step, StepOutcome.AlreadySatisfied, true, musicDisclosureShown)
            SetupStep.Map -> if (locationGranted()) tick(step, StepOutcome.AlreadySatisfied, true, mapDisclosureShown)
            SetupStep.Voice -> if (withContext(Dispatchers.IO) { services.settings.voiceLocale.value in SpeechPacks.query(activity).installed }) { flow.voicePackInstalled(); tick(step, StepOutcome.AlreadySatisfied) }
            else -> Unit
        }
    }
    // Back from the notification-access / location disclosure: tick if the user completed it.
    LifecycleEventEffect(Lifecycle.Event.ON_RESUME) {
        if (cameBack) return@LifecycleEventEffect
        when (step) {
            SetupStep.Music -> if (musicDisclosureShown && hasMusicAccess(activity)) tick(step, StepOutcome.Granted, true, true)
            SetupStep.Map -> if (mapDisclosureShown && locationGranted()) tick(step, StepOutcome.Granted, true, true)
            else -> Unit
        }
    }
    // Below Android 13 the companion pairing API is unavailable (Task 14): point at the vendor apps instead.
    fun pair(kind: DeviceKind, app: String) {
        pairNote = if (Build.VERSION.SDK_INT >= 33) "Tap Allow on your ${if (kind == DeviceKind.Watch) "watch" else "glasses"} when asked." else null
        PeerPairing.pair(activity, services, kind) { ok ->
            if (ok) LiveFitHubService.ensureRunning(activity) // an association also satisfies the connectedDevice prerequisite
            if (ok && kind == DeviceKind.Watch) tick(SetupStep.Watch)
            if (ok && kind == DeviceKind.Glasses) { glassesPaired = true; if (glassesAuthorized) tick(SetupStep.Glasses) }
            pairNote = if (ok) "Paired." else if (Build.VERSION.SDK_INT < 33) "Pair from $app, then tap Next (or Skip)." else "Pairing didn't complete. Try again or Skip."
        }
    }

    // D1: Hi Rokid answers silently in ~10 ms when LiveFit is already authorized, so say what happened.
    var authAsked by remember { mutableStateOf(false) }
    LaunchedEffect(Unit) {
        services.glasses.authResults.collect { ok ->
            if (authAsked) {
                authAsked = false; pairNote = authNote(ok)
                if (ok) { glassesAuthorized = true; if (glassesPaired) tick(SetupStep.Glasses) }
            }
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
            SetupStep.Welcome -> Triple(Icons.Rounded.Shield, "Welcome to Live AR Fit", "Allow microphone, nearby devices and notifications so the hub can run during workouts.\n\n${Disclosures.MIC}")
            SetupStep.Glasses -> Triple(GlassesIcon, "Link your Rokid glasses", "Authorize Live AR Fit in Hi Rokid, then pair so Android wakes Live AR Fit when the glasses are near." +
                if (Build.VERSION.SDK_INT < 33) " On this Android version, pair from Hi Rokid instead." else " Tap Allow on your glasses when asked.")
            SetupStep.Watch -> Triple(Icons.Rounded.Watch, "Link your Wear OS watch", (if (Build.VERSION.SDK_INT < 33) "Pair the watch" else "Pair the watch (tap Allow on your watch when asked)") +
                ", then open Live AR Fit on the watch once and tap Allow for heart-rate sensors." +
                if (Build.VERSION.SDK_INT < 33) " On this Android version, pair it from your watch's companion app (Galaxy Wearable, Pixel Watch, Wear OS…) instead." else "")
            SetupStep.Music -> Triple(Icons.Rounded.LibraryMusic, "Control YouTube Music", "Give Live AR Fit notification access so it can play, skip and like songs.")
            SetupStep.Map -> Triple(Icons.Rounded.MyLocation, "Map fallback", "Allow location so your phone can draw the route when the watch has no GPS fix. Optional — workouts record without it.")
            SetupStep.Voice -> Triple(Icons.Rounded.Mic, "Offline voice: English (India)", "Download the on-device voice pack. Voice stays off until it's installed — there's no online fallback.")
            SetupStep.Done -> Triple(Icons.Rounded.Favorite, "All set", "")
        }
        if (ticked == step) GrantTick(96.dp) else IconChip(icon, LiveFitColors.ChipMint, size = 96.dp, shapeRadius = 28.dp)
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
            SetupStep.Music -> Button(onClick = { musicDisclosureShown = true; activity.startActivity(DisclosureActivity.intent(activity, DisclosureKind.Music)) }) { Text("Open notification access") }
            SetupStep.Map -> Button(onClick = { mapDisclosureShown = true; activity.startActivity(DisclosureActivity.intent(activity, DisclosureKind.Location)) }) { Text("Allow location") }
            SetupStep.Voice -> Column(horizontalAlignment = Alignment.CenterHorizontally) {
                Button(enabled = !downloading, onClick = {
                    downloading = true; voiceNote = null
                    scope.launch {
                        val tag = services.settings.voiceLocale.value
                        val r = SpeechPacks.download(activity, tag) { progress = it }
                        downloading = false
                        val installed = r is SpeechPacks.Result.Success || tag in SpeechPacks.query(activity).installed
                        if (installed) { flow.voicePackInstalled(); services.refreshVoicePacks(); voiceNote = "Voice pack ready."; tick(SetupStep.Voice) }
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
            TextButton(onClick = { flow.back(); step = flow.step; pairNote = null; ticked = null; cameBack = true }, enabled = step != SetupStep.Welcome) { Text("Back") }
            Row {
                if (step != SetupStep.Done) TextButton(onClick = { go(skip = true) }) { Text("Skip") }
                Button(onClick = { if (step == SetupStep.Done) onFinished() else go() }) { Text(if (step == SetupStep.Done) "Start" else "Next") }
            }
        }
    }
}

/** What the setup and Settings screens say after a Hi Rokid authorization attempt (D1). */
fun authNote(ok: Boolean): String = if (ok) "Authorized." else "Authorization failed — open Hi Rokid and try again"
