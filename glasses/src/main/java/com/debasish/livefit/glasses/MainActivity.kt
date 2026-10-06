package com.debasish.livefit.glasses

import android.bluetooth.BluetoothAdapter
import android.content.Intent
import android.os.BatteryManager
import android.os.Build
import android.os.Bundle
import android.util.Log
import android.view.KeyEvent
import android.view.WindowManager
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.lifecycleScope
import com.debasish.livefit.glasses.hud.ConfirmInput
import com.debasish.livefit.glasses.hud.HudClock
import com.debasish.livefit.glasses.hud.HudConnection
import com.debasish.livefit.glasses.hud.HudController
import com.debasish.livefit.glasses.hud.HudNav
import com.debasish.livefit.model.WorkoutPhase
import com.debasish.livefit.glasses.hud.HudOverlay
import com.debasish.livefit.glasses.hud.HudScreen
import com.debasish.livefit.glasses.voice.PushToTalk
import com.rokid.cxr.CXRServiceBridge

/**
 * Glasses HUD. Touchpad (see [HudNav]): tap = talk, back swipe = toggle full/glance, forward swipe = music screen,
 * double-tap (back) = exit. Music screen: swipe = move highlight, tap = play it, double-tap = back to the HUD.
 * A pending confirmation overrides all of these ([ConfirmInput]).
 */
class MainActivity : ComponentActivity() {

    private lateinit var controller: HudController
    private lateinit var ptt: PushToTalk
    private var nav by mutableStateOf(HudNav())
    private var lastSwipe = 0L
    private val confirmInput = ConfirmInput()
    private var highlightYes by mutableStateOf(true)
    private var localToast by mutableStateOf<String?>(null)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        val bridge = CXRServiceBridge(this)
        bridge.setStatusListener(object : CXRServiceBridge.StatusListener {
            override fun onConnected(name: String?, mac: String?, type: Int) { Log.i(TAG, "phone connected $name") }
            override fun onDisconnected() { Log.i(TAG, "phone disconnected") }
            override fun onConnecting(name: String?, mac: String?, type: Int) {}
            override fun onARTCStatus(p0: Float, p1: Boolean) {}
            override fun onRokidAccountChanged(p0: String?) {}
            override fun onAudioNoise(p0: Float) {}
        })
        controller = HudController(lifecycleScope, bridge, getSharedPreferences("hud", 0), onDiscoverable = { s -> runOnUiThread { requestDiscoverable(s) } }).also { it.start() }
        ptt = PushToTalk(
            controller::sendRaw,
            hasPermission = { checkSelfPermission(android.Manifest.permission.RECORD_AUDIO) == android.content.pm.PackageManager.PERMISSION_GRANTED },
            onError = {
                Log.w(TAG, "push-to-talk: $it")
                // Called from the recording thread; snapshot state is safe to write from any thread.
                localToast = if (it.startsWith("Mic") || it.contains("RECORD_AUDIO")) "Mic unavailable" else "Push-to-talk failed"
            },
        )
        val batteryManager = getSystemService(BatteryManager::class.java)
        setContent {
            var battery by androidx.compose.runtime.remember { mutableStateOf(batteryManager.getIntProperty(BatteryManager.BATTERY_PROPERTY_CAPACITY)) }
            androidx.compose.runtime.LaunchedEffect(Unit) {
                while (true) { kotlinx.coroutines.delay(30_000); battery = batteryManager.getIntProperty(BatteryManager.BATTERY_PROPERTY_CAPACITY) }
            }
            // F4: glasses-local time, refreshed on each minute boundary; 12/24 h re-read so a settings change applies.
            fun clockNow() = HudClock.format(System.currentTimeMillis(), android.text.format.DateFormat.is24HourFormat(this@MainActivity))
            var clock by androidx.compose.runtime.remember { mutableStateOf(clockNow()) }
            androidx.compose.runtime.LaunchedEffect(Unit) {
                while (true) { kotlinx.coroutines.delay(HudClock.msToNextMinute(System.currentTimeMillis())); clock = clockNow() }
            }
            val frame by controller.frame.collectAsStateWithLifecycle()
            val settings by controller.settings.collectAsStateWithLifecycle()
            val connection by controller.connection.collectAsStateWithLifecycle()
            val history by controller.hrHistory.collectAsStateWithLifecycle()
            val listening by ptt.recording.collectAsStateWithLifecycle()
            val queue by controller.queue.collectAsStateWithLifecycle()
            androidx.compose.runtime.LaunchedEffect(frame?.confirmation?.id) {
                // Mic belongs to the confirmation: close it when it is resolved elsewhere, expires or is replaced.
                ptt.stop()
                localToast = null
                confirmInput.onConfirmation(frame?.confirmation)
                if (confirmInput.takeMicRequest(frame?.confirmation)) ptt.start(maxMs = 6_000) // auto mic for a spoken answer
                highlightYes = confirmInput.highlightYes
            }
            androidx.compose.runtime.LaunchedEffect(localToast) {
                // While a confirmation is shown the error stays inside it; the id effect clears it.
                if (localToast != null && frame?.confirmation == null) { kotlinx.coroutines.delay(3_000); localToast = null }
            }
            val confirmation = frame?.confirmation
            val toastText = localToast
            val overlay = when {
                confirmation != null -> HudOverlay.Confirm(confirmation, highlightYes, listening, micError = toastText)
                toastText != null -> HudOverlay.LocalToast(toastText)
                listening -> HudOverlay.LocalListening
                else -> HudOverlay.None
            }
            HudScreen(frame, settings, connection, nav.mode, battery, history, overlay = overlay, clock = clock,
                page = nav.page, queue = queue, musicHighlight = nav.highlightIndex(queue))
        }
    }

    // ---- Pairing (D2): the system prompt takes us out of the foreground; the phone reconnects the session afterwards. ----
    private var discoverableSeconds = 120
    private var lastDiscoverableMs = 0L
    private val advertisePermission = registerForActivityResult(ActivityResultContracts.RequestPermission()) { if (it) promptDiscoverable() }
    private val discoverable = registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { Log.i(TAG, "discoverable result ${it.resultCode}") }

    private fun requestDiscoverable(seconds: Int) {
        val now = System.currentTimeMillis()
        if (now - lastDiscoverableMs < 10_000) return // a resent request must not stack prompts
        lastDiscoverableMs = now
        discoverableSeconds = seconds
        if (Build.VERSION.SDK_INT >= 31 && checkSelfPermission(android.Manifest.permission.BLUETOOTH_ADVERTISE) != android.content.pm.PackageManager.PERMISSION_GRANTED) {
            advertisePermission.launch(android.Manifest.permission.BLUETOOTH_ADVERTISE)
        } else promptDiscoverable()
    }

    private fun promptDiscoverable() {
        runCatching {
            ptt.stop()
            discoverable.launch(Intent(BluetoothAdapter.ACTION_REQUEST_DISCOVERABLE).putExtra(BluetoothAdapter.EXTRA_DISCOVERABLE_DURATION, discoverableSeconds))
        }.onFailure { Log.w(TAG, "discoverable prompt failed", it) }
    }

    override fun onKeyUp(keyCode: Int, event: KeyEvent): Boolean {
        val pending = confirmInput.onConfirmation(controller.frame.value?.confirmation).let { confirmInput.hasPending }
        when (keyCode) {
            KeyEvent.KEYCODE_ENTER, KeyEvent.KEYCODE_DPAD_CENTER -> {
                val play = if (pending) null else nav.onTap(controller.queue.value)
                when {
                    pending -> confirmInput.onTap()?.let { controller.send(it); ptt.stop() }
                    play != null -> controller.send(play) // music screen: play the highlighted song
                    controller.connection.value != HudConnection.Outdated -> ptt.toggle() // the hub ignores voice from a mismatched app
                }
            }
            // One swipe can emit several key events; debounce like the UPI app does.
            // Rokid swipes arrive as horizontal keys: RIGHT/DOWN = forward, LEFT/UP = back.
            KeyEvent.KEYCODE_DPAD_LEFT, KeyEvent.KEYCODE_DPAD_RIGHT, KeyEvent.KEYCODE_DPAD_UP, KeyEvent.KEYCODE_DPAD_DOWN -> {
                val now = System.currentTimeMillis()
                if (now - lastSwipe > 350) {
                    if (pending) { confirmInput.onSwipe(); highlightYes = confirmInput.highlightYes }
                    else nav = nav.onSwipe(
                        forward = keyCode == KeyEvent.KEYCODE_DPAD_RIGHT || keyCode == KeyEvent.KEYCODE_DPAD_DOWN,
                        inWorkout = inWorkout(), queue = controller.queue.value,
                    )
                }
                lastSwipe = now
            }
            else -> return super.onKeyUp(keyCode, event)
        }
        return true
    }

    /** Double-tap (back) answers No while a confirmation is pending instead of leaving the app. */
    @Suppress("OVERRIDE_DEPRECATION", "DEPRECATION")
    override fun onBackPressed() {
        confirmInput.onConfirmation(controller.frame.value?.confirmation)
        val answer = confirmInput.onBack()
        val leaveMusic = if (answer == null) nav.onBack() else null
        when {
            answer != null -> { controller.send(answer); ptt.stop() }
            leaveMusic != null -> nav = leaveMusic // music screen → workout HUD, not out of the app
            else -> super.onBackPressed()
        }
    }

    private fun inWorkout(): Boolean = controller.frame.value?.workout?.phase.let {
        it == WorkoutPhase.Starting || it == WorkoutPhase.Active || it == WorkoutPhase.Paused || it == WorkoutPhase.Syncing
    }

    /** Never leave the mic open once the app is no longer in the foreground. */
    override fun onStop() {
        ptt.stop()
        super.onStop()
    }

    companion object { const val TAG = "LiveFitGlasses" }
}
