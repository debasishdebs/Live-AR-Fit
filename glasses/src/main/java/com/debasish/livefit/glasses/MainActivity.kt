package com.debasish.livefit.glasses

import android.os.BatteryManager
import android.os.Bundle
import android.util.Log
import android.view.KeyEvent
import android.view.WindowManager
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.lifecycleScope
import com.debasish.livefit.glasses.hud.ConfirmInput
import com.debasish.livefit.glasses.hud.HudController
import com.debasish.livefit.glasses.hud.HudMode
import com.debasish.livefit.glasses.hud.HudOverlay
import com.debasish.livefit.glasses.hud.HudScreen
import com.debasish.livefit.glasses.voice.PushToTalk
import com.rokid.cxr.CXRServiceBridge

/** Glasses HUD. Touchpad: tap = talk, swipe = toggle full/glance, double-tap (back) = exit. */
class MainActivity : ComponentActivity() {

    private lateinit var controller: HudController
    private lateinit var ptt: PushToTalk
    private var mode by mutableStateOf(HudMode.Full)
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
        controller = HudController(lifecycleScope, bridge, getSharedPreferences("hud", 0)).also { it.start() }
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
            val frame by controller.frame.collectAsStateWithLifecycle()
            val settings by controller.settings.collectAsStateWithLifecycle()
            val connection by controller.connection.collectAsStateWithLifecycle()
            val history by controller.hrHistory.collectAsStateWithLifecycle()
            val listening by ptt.recording.collectAsStateWithLifecycle()
            androidx.compose.runtime.LaunchedEffect(frame?.confirmation?.id) {
                if (confirmInput.onConfirmation(frame?.confirmation)) ptt.start(maxMs = 6_000) // auto mic for a spoken answer
                highlightYes = confirmInput.highlightYes
            }
            androidx.compose.runtime.LaunchedEffect(localToast) {
                if (localToast != null) { kotlinx.coroutines.delay(3_000); localToast = null }
            }
            val confirmation = frame?.confirmation
            val toastText = localToast
            val overlay = when {
                confirmation != null -> HudOverlay.Confirm(confirmation, highlightYes, listening)
                toastText != null -> HudOverlay.LocalToast(toastText)
                listening -> HudOverlay.LocalListening
                else -> HudOverlay.None
            }
            HudScreen(frame, settings, connection, mode, battery, history, overlay = overlay)
        }
    }

    override fun onKeyUp(keyCode: Int, event: KeyEvent): Boolean {
        val pending = controller.frame.value?.confirmation != null
        when (keyCode) {
            KeyEvent.KEYCODE_ENTER, KeyEvent.KEYCODE_DPAD_CENTER ->
                if (pending) confirmInput.onTap()?.let { controller.send(it) } else ptt.toggle()
            // One swipe can emit several key events; debounce like the UPI app does.
            KeyEvent.KEYCODE_DPAD_LEFT, KeyEvent.KEYCODE_DPAD_RIGHT, KeyEvent.KEYCODE_DPAD_UP, KeyEvent.KEYCODE_DPAD_DOWN -> {
                val now = System.currentTimeMillis()
                if (now - lastSwipe > 350) {
                    if (pending) { confirmInput.onSwipe(); highlightYes = confirmInput.highlightYes }
                    else mode = if (mode == HudMode.Full) HudMode.Glance else HudMode.Full
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
        val answer = confirmInput.onBack()
        if (controller.frame.value?.confirmation != null && answer != null) controller.send(answer) else super.onBackPressed()
    }

    /** Never leave the mic open once the app is no longer in the foreground. */
    override fun onStop() {
        ptt.stop()
        super.onStop()
    }

    companion object { const val TAG = "LiveFitGlasses" }
}
