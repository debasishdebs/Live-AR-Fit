package com.debasish.livefit.glasses.hud

import android.content.SharedPreferences
import android.util.Log
import com.debasish.livefit.model.Command
import com.debasish.livefit.model.CommandEnvelope
import com.debasish.livefit.model.DeviceKind
import com.debasish.livefit.model.DiscoverableRequest
import com.debasish.livefit.model.GlassesChannels
import com.debasish.livefit.model.HudSettings
import com.debasish.livefit.model.HudSettingsFrame
import com.debasish.livefit.model.PROTOCOL_VERSION
import com.debasish.livefit.model.QueueFrame
import com.debasish.livefit.model.QueueWindow
import com.debasish.livefit.model.StateFrame
import com.debasish.livefit.model.Wire
import com.debasish.livefit.services.Clock
import com.debasish.livefit.sync.LivenessMonitor
import com.rokid.cxr.CXRServiceBridge
import com.rokid.cxr.Caps
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import java.util.UUID

enum class HudConnection { Connecting, OpenPhoneApp, Live, Outdated }

/** Pure: what the HUD shows about the phone link (spec §4.3 liveness, §5.3). */
fun connectionFor(hasEverReceived: Boolean, online: Boolean, outdated: Boolean, sinceStartMs: Long): HudConnection = when {
    outdated -> HudConnection.Outdated
    online -> HudConnection.Live
    !hasEverReceived && sinceStartMs >= 12_000 -> HudConnection.OpenPhoneApp
    else -> HudConnection.Connecting
}

/** Glasses side of the CXR link: renders hub frames; sends commands and push-to-talk audio. */
class HudController(
    private val scope: CoroutineScope,
    private val bridge: CXRServiceBridge,
    private val prefs: SharedPreferences,
    /** Pairing (D2): the phone asks to be shown the glasses in its companion picker; called on a bridge thread with seconds. */
    private val onDiscoverable: (Int) -> Unit = {},
) {
    private val clock = Clock { System.currentTimeMillis() }
    private val liveness = LivenessMonitor(clock)
    private val startedAt = clock.nowMs()
    // Written from CXR bridge callback threads, read from the coroutine refresh loop.
    @Volatile private var everReceived = false
    @Volatile private var outdated = false

    private val _frame = MutableStateFlow<StateFrame?>(null)
    val frame: StateFlow<StateFrame?> = _frame
    private val _settings = MutableStateFlow(loadSettings())
    val settings: StateFlow<HudSettings> = _settings
    private val _connection = MutableStateFlow(HudConnection.Connecting)
    val connection: StateFlow<HudConnection> = _connection
    private val _queue = MutableStateFlow(QueueWindow())
    /** YouTube Music queue window for the music screen (lf_queue). */
    val queue: StateFlow<QueueWindow> = _queue
    private val _hrHistory = MutableStateFlow<List<Int>>(emptyList())
    val hrHistory: StateFlow<List<Int>> = _hrHistory
    @Volatile private var lastSampleSecond = -1L
    @Volatile private var lastLatencySample: Long? = null

    fun start() {
        bridge.subscribe(GlassesChannels.STATE, CXRServiceBridge.MsgCallback { _, caps, _ -> onState(caps) })
        bridge.subscribe(GlassesChannels.SETTINGS, CXRServiceBridge.MsgCallback { _, caps, _ -> onSettings(caps) })
        bridge.subscribe(GlassesChannels.QUEUE, CXRServiceBridge.MsgCallback { _, caps, _ -> onQueue(caps) })
        bridge.subscribe(GlassesChannels.DISCOVERABLE, CXRServiceBridge.MsgCallback { _, caps, _ ->
            text(caps)?.let(DiscoverableRequest::parse)?.let(onDiscoverable) // an outdated phone's request is ignored
        })
        scope.launch { while (true) { refreshConnection(); delay(1_000) } }
    }

    private fun text(caps: Caps?) = runCatching { caps?.at(0)?.string }.getOrNull()

    private fun onState(caps: Caps?) {
        val t = text(caps) ?: return
        if (Wire.versionOf(t) != PROTOCOL_VERSION) { outdated = true; refreshConnection(); return }
        val f = runCatching { Wire.decode<StateFrame>(t) }.getOrElse { Log.w(TAG, "bad frame", it); return }
        everReceived = true
        liveness.onFrame()
        _frame.value = f
        f.workout.latestSampleMs?.takeIf { it != lastLatencySample }?.let {
            lastLatencySample = it
            Log.i("LiveFitLatency", "sample=$it render=${System.currentTimeMillis()}")
        }
        val sec = f.workout.elapsedMs / 1_000
        val hr = f.workout.metrics.heartRate
        if (f.workout.elapsedMs == 0L) _hrHistory.value = emptyList()
        if (hr != null && sec != lastSampleSecond) { lastSampleSecond = sec; _hrHistory.value = (_hrHistory.value + hr).takeLast(HR_HISTORY) }
        refreshConnection()
    }

    private fun onSettings(caps: Caps?) {
        val t = text(caps) ?: return
        if (Wire.versionOf(t) != PROTOCOL_VERSION) return
        val s = runCatching { Wire.decode<HudSettingsFrame>(t).settings }.getOrNull() ?: return
        _settings.value = s
        prefs.edit().putString(KEY_SETTINGS, Wire.encode(s)).apply() // keep layout across restarts
    }

    private fun onQueue(caps: Caps?) {
        val t = text(caps) ?: return
        if (Wire.versionOf(t) != PROTOCOL_VERSION) return // lf_state already reports the mismatch
        _queue.value = runCatching { Wire.decode<QueueFrame>(t).window }.getOrElse { Log.w(TAG, "bad queue", it); return }
    }

    private fun loadSettings(): HudSettings =
        prefs.getString(KEY_SETTINGS, null)?.let { runCatching { Wire.decode<HudSettings>(it) }.getOrNull() } ?: HudSettings()

    private fun refreshConnection() {
        _connection.value = connectionFor(everReceived, liveness.isOnline(), outdated, clock.nowMs() - startedAt)
    }

    fun send(command: Command) =
        sendRaw(GlassesChannels.COMMAND, Wire.encode(CommandEnvelope(id = UUID.randomUUID().toString(), origin = DeviceKind.Glasses, command = command)))

    fun sendRaw(channel: String, text: String) { bridge.sendMessage(channel, Caps().apply { write(text) }) }

    companion object {
        const val TAG = "LiveFitGlasses"
        private const val KEY_SETTINGS = "hudSettings"
    }
}
