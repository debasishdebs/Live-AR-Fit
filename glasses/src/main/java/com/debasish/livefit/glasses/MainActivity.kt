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
import com.debasish.livefit.glasses.hud.HudController
import com.debasish.livefit.glasses.hud.HudMode
import com.debasish.livefit.glasses.hud.HudScreen
import com.debasish.livefit.model.GlassesChannels
import com.rokid.cxr.CXRServiceBridge

/** Glasses HUD. Touchpad: tap = talk, swipe = toggle full/glance, double-tap (back) = exit. */
class MainActivity : ComponentActivity() {

    private lateinit var controller: HudController
    private var mode by mutableStateOf(HudMode.Full)
    private var lastSwipe = 0L

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
            HudScreen(frame, settings, connection, mode, battery, history)
        }
    }

    override fun onKeyUp(keyCode: Int, event: KeyEvent): Boolean {
        when (keyCode) {
            KeyEvent.KEYCODE_ENTER, KeyEvent.KEYCODE_DPAD_CENTER -> controller.sendRaw(GlassesChannels.LISTEN, "{}") // Task 20 replaces this with push-to-talk
            // One swipe can emit several key events; debounce like the UPI app does.
            KeyEvent.KEYCODE_DPAD_LEFT, KeyEvent.KEYCODE_DPAD_RIGHT, KeyEvent.KEYCODE_DPAD_UP, KeyEvent.KEYCODE_DPAD_DOWN -> {
                val now = System.currentTimeMillis()
                if (now - lastSwipe > 350) mode = if (mode == HudMode.Full) HudMode.Glance else HudMode.Full
                lastSwipe = now
            }
            else -> return super.onKeyUp(keyCode, event)
        }
        return true
    }

    companion object { const val TAG = "LiveFitGlasses" }
}
