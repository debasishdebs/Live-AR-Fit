package com.debasish.livefit.glasses

import android.bluetooth.BluetoothAdapter
import android.content.Intent
import android.os.BatteryManager
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
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
import com.debasish.livefit.glasses.hud.IdleGate
import com.debasish.livefit.glasses.hud.NavContext
import com.debasish.livefit.glasses.hud.gesture
import com.debasish.livefit.model.Gesture
import com.debasish.livefit.model.GestureMode
import com.debasish.livefit.model.GestureSettings
import com.debasish.livefit.model.HudPage
import com.debasish.livefit.model.PageSet
import com.debasish.livefit.model.PageSettings
import com.debasish.livefit.model.WorkoutSnapshot
import com.debasish.livefit.glasses.hud.Swipe
import com.debasish.livefit.glasses.hud.SwipeClassifier
import com.debasish.livefit.glasses.hud.SwipeKey
import com.debasish.livefit.glasses.hud.HudOverlay
import com.debasish.livefit.glasses.hud.HudScreen
import com.debasish.livefit.glasses.voice.PushToTalk
import com.rokid.cxr.CXRServiceBridge

/**
 * Glasses HUD. Pages glance / workout / playlist, starting on workout; voice switches them via lf_page ([HudNav.show]).
 * Touchpad (see [HudNav]): each swipe's key burst becomes one [Swipe] ([SwipeClassifier]). Glance and workout pages:
 * tap = talk, any swipe = next/previous page; playlist page short swipe = move the highlight, long swipe = pages, tap =
 * play/pause (highlight on the current song) or play the highlighted song; 6 s idle = highlight back on the current song.
 * Double-tap (two KEYCODE_NOTIFICATION or BACK, [DoubleTapDetector]) on any page = close the app, asking first while a
 * workout records ([CloseConfirm]). A pending hub confirmation overrides all of these ([ConfirmInput]), then our close prompt.
 */
class MainActivity : ComponentActivity() {

    private lateinit var controller: HudController
    private lateinit var ptt: PushToTalk
    private var nav by mutableStateOf(HudNav())
    private val swipes = SwipeClassifier()
    private val swipeTimer = Handler(Looper.getMainLooper())
    private val closeSwipe = Runnable { swipes.onTimer(android.os.SystemClock.uptimeMillis())?.let(::onSwipe); scheduleSwipeClose() }
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
        controller = HudController(lifecycleScope, bridge, getSharedPreferences("hud", 0), onDiscoverable = { s -> runOnUiThread { requestDiscoverable(s) } },
            onPage = { p -> runOnUiThread { updateNav(nav.show(p, availablePages())) } }).also { it.start() }
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
            // Scroll-mode idle timeout (spec §4.2/§3.3), one ordered path (review #9): an overlay change updates the gate
            // (a dismissal restarts the timer) before the timer effect — keyed on the gate, the input time and the
            // timeout — computes its deadline. Task 22 makes idleMs collected state, so a new timeout re-keys the effect.
            val overlayUp = frame?.confirmation != null || closeConfirm.shown
            val idleMs = gestures().idleTimeoutS * 1_000L
            androidx.compose.runtime.LaunchedEffect(overlayUp) {
                val (gate, resumed) = idleGate.onOverlay(overlayUp, nav, System.currentTimeMillis())
                idleGate = gate
                updateNav(resumed)
            }
            androidx.compose.runtime.LaunchedEffect(nav.mode, nav.lastInputMs, idleGate, idleMs) {
                val deadline = idleGate.deadlineMs(nav, idleMs) ?: return@LaunchedEffect
                kotlinx.coroutines.delay((deadline - System.currentTimeMillis()).coerceAtLeast(0))
                updateNav(idleGate.tick(nav, idleMs, System.currentTimeMillis()))
            }
            // The visible page disappears (disabled, GPS workout ended) → Workout at once (spec §3.3).
            val available = PageSet.available(pageSettings(), PageSet.mapEligible(frame?.workout ?: WorkoutSnapshot()))
            androidx.compose.runtime.LaunchedEffect(available) { updateNav(nav.reconcile(available)) }
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
            HudScreen(frame, settings, connection, battery, history, overlay = overlay, clock = clock,
                page = nav.page, queue = queue, musicHighlight = nav.highlightRow(queue))
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

    /**
     * Every key is logged (debug) so touchpad codes can be checked on device; the touch/double-tap key (83) never
     * reaches the system. Swipe keys are consumed here (down and up) and classified on key-down by event time.
     */
    override fun dispatchKeyEvent(event: KeyEvent): Boolean {
        Log.d(TAG, "key action=${event.action} code=${event.keyCode} scan=${event.scanCode} repeat=${event.repeatCount} t=${event.eventTime}")
        val firstDown = event.action == KeyEvent.ACTION_DOWN && event.repeatCount == 0
        if (event.keyCode == KeyEvent.KEYCODE_NOTIFICATION) {
            // Consumed (down and up) so the Rokid system does not move our task to the back on its own.
            if (firstDown) {
                swipes.onTouch(event.eventTime)?.let(::onSwipe) // a new touch closes the previous swipe
                scheduleSwipeClose()
                if (doubleTap.onNotificationKey(System.currentTimeMillis())) onDoubleTap()
            }
            return true
        }
        val key = when (event.keyCode) {
            KeyEvent.KEYCODE_DPAD_RIGHT -> SwipeKey.Right
            KeyEvent.KEYCODE_DPAD_LEFT -> SwipeKey.Left
            KeyEvent.KEYCODE_DPAD_DOWN -> SwipeKey.Down
            KeyEvent.KEYCODE_DPAD_UP -> SwipeKey.Up
            else -> null
        }
        if (key != null) {
            if (firstDown) {
                doubleTap.onGestureKey()
                swipes.onKey(key, event.eventTime)?.let(::onSwipe)
                scheduleSwipeClose()
            }
            return true
        }
        return super.dispatchKeyEvent(event)
    }

    private fun scheduleSwipeClose() {
        swipeTimer.removeCallbacks(closeSwipe)
        swipes.deadlineMs?.let { swipeTimer.postAtTime(closeSwipe, it) } // eventTime and postAtTime share the uptime clock
    }

    private fun gestures(): GestureSettings = GestureSettings()
    private fun pageSettings(): PageSettings = PageSettings()
    /** Scroll idle timer pause state (review #9); changed only through IdleGate.onOverlay. */
    private var idleGate by mutableStateOf(IdleGate())
    private fun availablePages(): List<HudPage> =
        PageSet.available(pageSettings(), PageSet.mapEligible(controller.frame.value?.workout ?: WorkoutSnapshot()))
    private fun navContext() = NavContext(controller.queue.value, gestures(), availablePages())
    private fun updateNav(next: HudNav) { nav = next }

    /** Priority (spec §4.2): hub confirmation, then our close prompt, then the configurable table. */
    private fun onGesture(g: Gesture) {
        Log.d(TAG, "gesture $g")
        val now = System.currentTimeMillis()
        val pending = confirmInput.onConfirmation(controller.frame.value?.confirmation).let { confirmInput.hasPending }
        when {
            pending -> when (g) {
                Gesture.Tap -> confirmInput.onTap()?.let { controller.send(it); ptt.stop() }
                Gesture.DoubleTap -> confirmInput.onBack()?.let { controller.send(it); ptt.stop() }
                else -> { confirmInput.onSwipe(); highlightYes = confirmInput.highlightYes }
            }
            closeConfirm.shown -> when (g) {
                Gesture.Tap -> closeConfirm.onTap().let { (c, close) -> closeConfirm = c; if (close == true) closeApp() }
                Gesture.DoubleTap -> closeConfirm = CloseConfirm() // double-tap = stay
                else -> closeConfirm = closeConfirm.onSwipe()
            }
            else -> {
                val out = nav.onGesture(g, navContext(), now)
                updateNav(out.nav)
                out.command?.let(controller::send)
                if (out.talk && controller.connection.value != HudConnection.Outdated) ptt.toggle() // the hub ignores voice from a mismatched app
                if (out.close) {
                    val (c, action) = closeConfirm.onClose(controller.frame.value?.workout?.phase, gestures().askBeforeClose, now)
                    closeConfirm = c
                    if (action == DoubleTapAction.Leave) closeApp()
                }
            }
        }
    }

    private fun onSwipe(swipe: Swipe) = onGesture(swipe.gesture())

    override fun onKeyUp(keyCode: Int, event: KeyEvent): Boolean = when (keyCode) {
        KeyEvent.KEYCODE_ENTER, KeyEvent.KEYCODE_DPAD_CENTER -> { onGesture(Gesture.Tap); true }
        else -> super.onKeyUp(keyCode, event)
    }

    /** BACK is a double-tap on some firmware: same rules as the key-83 pair, never page navigation. */
    @Suppress("OVERRIDE_DEPRECATION", "DEPRECATION")
    override fun onBackPressed() {
        if (doubleTap.onBack(System.currentTimeMillis())) onDoubleTap()
    }

    /** Double-tap (two key-83 presses or BACK) is a configurable gesture like any other (spec §4.4). */
    private fun onDoubleTap() = onGesture(Gesture.DoubleTap)

    /** The workout itself lives on the phone/watch; the glasses app just goes away. */
    private fun closeApp() {
        ptt.stop()
        closeConfirm = CloseConfirm()
        updateNav(HudNav())
        swipeTimer.removeCallbacks(closeSwipe)
        moveTaskToBack(true)
    }

    /** Never leave the mic open once the app is no longer in the foreground. */
    override fun onStop() {
        ptt.stop()
        super.onStop()
    }

    companion object { const val TAG = "LiveFitGlasses" }
}
