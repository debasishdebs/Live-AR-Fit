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
import com.debasish.livefit.glasses.hud.CloseConfirm
import com.debasish.livefit.glasses.hud.ConfirmInput
import com.debasish.livefit.glasses.hud.DoubleTapAction
import com.debasish.livefit.glasses.hud.DoubleTapDetector
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
 * Glasses HUD. Touchpad (see [HudNav]): workout page tap = talk, back swipe = toggle full/glance, forward swipe = music
 * page; music page back swipe = workout page, tap = list mode (swipe = move highlight, tap = play it, 6 s idle = leave).
 * Double-tap (two KEYCODE_NOTIFICATION or BACK, [DoubleTapDetector]) on any page = close the app, asking first while a
 * workout records ([CloseConfirm]). A pending hub confirmation overrides all of these ([ConfirmInput]), then our close prompt.
 */
class MainActivity : ComponentActivity() {

    private lateinit var controller: HudController
    private lateinit var ptt: PushToTalk
    private var nav by mutableStateOf(HudNav())
    private var lastSwipe = 0L
    private val confirmInput = ConfirmInput()
    private var highlightYes by mutableStateOf(true)
    private var localToast by mutableStateOf<String?>(null)
    private val doubleTap = DoubleTapDetector()
    private var closeConfirm by mutableStateOf(CloseConfirm())

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
                if (frame?.confirmation != null) closeConfirm = CloseConfirm() // the hub prompt replaces ours
            }
            androidx.compose.runtime.LaunchedEffect(closeConfirm.shownAtMs) {
                if (closeConfirm.shown) { kotlinx.coroutines.delay(CloseConfirm.TIMEOUT_MS); closeConfirm = closeConfirm.timedOut(System.currentTimeMillis()) }
            }
            androidx.compose.runtime.LaunchedEffect(nav.listMode, nav.lastInputMs) {
                if (nav.listMode) { kotlinx.coroutines.delay(HudNav.LIST_IDLE_MS); nav = nav.timedOut(System.currentTimeMillis()) }
            }
            androidx.compose.runtime.LaunchedEffect(localToast) {
                // While a confirmation is shown the error stays inside it; the id effect clears it.
                if (localToast != null && frame?.confirmation == null) { kotlinx.coroutines.delay(3_000); localToast = null }
            }
            val confirmation = frame?.confirmation
            val toastText = localToast
            val overlay = when {
                confirmation != null -> HudOverlay.Confirm(confirmation, highlightYes, listening, micError = toastText)
                closeConfirm.shown -> HudOverlay.CloseApp(closeConfirm.highlightYes)
                toastText != null -> HudOverlay.LocalToast(toastText)
                listening -> HudOverlay.LocalListening
                else -> HudOverlay.None
            }
            HudScreen(frame, settings, connection, nav.mode, battery, history, overlay = overlay, clock = clock,
                page = nav.page, queue = queue, musicHighlight = nav.visibleHighlight(queue))
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

    /** Every key is logged (debug) so touchpad codes can be checked on device; the double-tap key never reaches the system. */
    override fun dispatchKeyEvent(event: KeyEvent): Boolean {
        Log.d(TAG, "key action=${event.action} code=${event.keyCode} scan=${event.scanCode} repeat=${event.repeatCount} t=${event.eventTime}")
        if (event.keyCode == KeyEvent.KEYCODE_NOTIFICATION) {
            // Consumed (down and up) so the Rokid system does not move our task to the back on its own.
            if (event.action == KeyEvent.ACTION_DOWN && event.repeatCount == 0 && doubleTap.onNotificationKey(System.currentTimeMillis())) onDoubleTap()
            return true
        }
        return super.dispatchKeyEvent(event)
    }

    override fun onKeyUp(keyCode: Int, event: KeyEvent): Boolean {
        val pending = confirmInput.onConfirmation(controller.frame.value?.confirmation).let { confirmInput.hasPending }
        val now = System.currentTimeMillis()
        when (keyCode) {
            KeyEvent.KEYCODE_ENTER, KeyEvent.KEYCODE_DPAD_CENTER -> when {
                pending -> confirmInput.onTap()?.let { controller.send(it); ptt.stop() }
                closeConfirm.shown -> closeConfirm.onTap().let { (c, close) -> closeConfirm = c; if (close == true) closeApp() }
                else -> {
                    val tap = nav.onTap(controller.queue.value, now)
                    nav = tap.nav
                    tap.play?.let(controller::send) // music list: play the highlighted song
                    if (tap.talk && controller.connection.value != HudConnection.Outdated) ptt.toggle() // the hub ignores voice from a mismatched app
                }
            }
            // One swipe can emit several key events; debounce like the UPI app does.
            // Rokid swipes arrive as horizontal keys: RIGHT/DOWN = forward, LEFT/UP = back.
            KeyEvent.KEYCODE_DPAD_LEFT, KeyEvent.KEYCODE_DPAD_RIGHT, KeyEvent.KEYCODE_DPAD_UP, KeyEvent.KEYCODE_DPAD_DOWN -> {
                if (now - lastSwipe > 350) {
                    when {
                        pending -> { confirmInput.onSwipe(); highlightYes = confirmInput.highlightYes }
                        closeConfirm.shown -> closeConfirm = closeConfirm.onSwipe()
                        else -> nav = nav.onSwipe(
                            forward = keyCode == KeyEvent.KEYCODE_DPAD_RIGHT || keyCode == KeyEvent.KEYCODE_DPAD_DOWN,
                            inWorkout = inWorkout(), queue = controller.queue.value, nowMs = now,
                        )
                    }
                }
                lastSwipe = now
            }
            else -> return super.onKeyUp(keyCode, event)
        }
        return true
    }

    /** BACK is a double-tap on some firmware: same rules as the key-83 pair, never page navigation. */
    @Suppress("OVERRIDE_DEPRECATION", "DEPRECATION")
    override fun onBackPressed() {
        if (doubleTap.onBack(System.currentTimeMillis())) onDoubleTap()
    }

    /** Double-tap: hub prompt → No; our close prompt → stay; recording workout → ask; otherwise close the app. */
    private fun onDoubleTap() {
        confirmInput.onConfirmation(controller.frame.value?.confirmation)
        val (c, action) = closeConfirm.onDoubleTap(confirmInput.hasPending, controller.frame.value?.workout?.phase, System.currentTimeMillis())
        Log.d(TAG, "double-tap -> $action")
        closeConfirm = c
        when (action) {
            DoubleTapAction.AnswerNo -> confirmInput.onBack()?.let { controller.send(it); ptt.stop() }
            DoubleTapAction.Leave -> closeApp()
            DoubleTapAction.AskClose, DoubleTapAction.Stay -> {}
        }
    }

    /** The workout itself lives on the phone/watch; the glasses app just goes away. */
    private fun closeApp() {
        ptt.stop()
        closeConfirm = CloseConfirm()
        nav = HudNav(mode = nav.mode)
        moveTaskToBack(true)
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
