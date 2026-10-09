package com.debasish.livefit.glasses.hud

import android.content.SharedPreferences
import android.util.Log
import com.debasish.livefit.model.Command
import com.debasish.livefit.model.CommandEnvelope
import com.debasish.livefit.model.DeviceKind
import com.debasish.livefit.model.DiscoverableRequest
import com.debasish.livefit.model.GestureRules
import com.debasish.livefit.model.GestureSettings
import com.debasish.livefit.model.GlassesChannels
import com.debasish.livefit.model.HudSettings
import com.debasish.livefit.model.HudSettingsFrame
import com.debasish.livefit.model.HudPage
import com.debasish.livefit.model.MapFrame
import com.debasish.livefit.model.MapFrameKind
import com.debasish.livefit.model.PageSettings
import com.debasish.livefit.model.PageRequest
import com.debasish.livefit.model.PROTOCOL_VERSION
import com.debasish.livefit.model.QueueFrame
import com.debasish.livefit.model.QueueWindow
import com.debasish.livefit.model.StateFrame
import com.debasish.livefit.model.Wire
import com.debasish.livefit.services.Clock
import com.debasish.livefit.sync.LivenessMonitor
import com.debasish.livefit.sync.MapImageGate
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
    /** Voice page switch (lf_page, e.g. "playlist view"); called on a bridge thread. */
    private val onPage: (HudPage) -> Unit = {},
) {
    private val clock = Clock { System.currentTimeMillis() }
    private val liveness = LivenessMonitor(clock)
    private val startedAt = clock.nowMs()
    // Written from CXR bridge callback threads, read from the coroutine refresh loop.
    @Volatile private var everReceived = false
    @Volatile private var outdated = false

    private val _frame = MutableStateFlow<StateFrame?>(null)
    val frame: StateFlow<StateFrame?> = _frame
    /** Last settings frame (layout + pages + last valid gesture table), kept across restarts. */
    private val saved: HudSettingsFrame? = prefs.getString(KEY_FRAME, null)?.let { runCatching { Wire.decode<HudSettingsFrame>(it) }.getOrNull() }
    private val _settings = MutableStateFlow(saved?.settings ?: loadSettings())
    private val _pages = MutableStateFlow(saved?.pages ?: PageSettings())
    /** Settings → Pages from the phone (spec §3.2). */
    val pages: StateFlow<PageSettings> = _pages
    private val _gestures = MutableStateFlow(GestureRules.sanitized(saved?.gestures ?: GestureSettings()))
    /** The gesture table, validated per page (spec §4.4); an invalid page keeps its last valid table. */
    val gestures: StateFlow<GestureSettings> = _gestures
    private val gate = MapImageGate()
    private val _mapImage = MutableStateFlow<MapImage?>(null)
    /** Newest accepted lf_map image (newest epoch, this session, newer seq). */
    val mapImage: StateFlow<MapImage?> = _mapImage
    private val reporter = PageReporter { json -> sendRaw(GlassesChannels.PAGE_STATE, json) }
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
        bridge.subscribe(GlassesChannels.PAGE, CXRServiceBridge.MsgCallback { _, caps, _ ->
            text(caps)?.let(PageRequest::parse)?.let(onPage) // another version's request is ignored (lf_state reports it)
        })
        bridge.subscribe(GlassesChannels.MAP, CXRServiceBridge.MsgCallback { _, caps, bytes -> onMap(caps, bytes) })
        scope.launch { while (true) { refreshConnection(); reporter.retryPending(); delay(1_000) } } // review #10: unsent page state
    }

    private fun text(caps: Caps?) = runCatching { caps?.at(0)?.string }.getOrNull()

    private fun onState(caps: Caps?) {
        val t = text(caps) ?: return
        if (Wire.versionOf(t) != PROTOCOL_VERSION) { outdated = true; refreshConnection(); return }
        val f = runCatching { Wire.decode<StateFrame>(t) }.getOrElse { Log.w(TAG, "bad frame: ${it.javaClass.simpleName}"); return }
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
        val f = runCatching { Wire.decode<HudSettingsFrame>(t) }.getOrNull() ?: return
        GestureRules.problems(f.gestures).forEach { Log.w(TAG, "gesture table: $it — keeping the last valid one") }
        val gestures = GestureRules.sanitized(f.gestures, _gestures.value)
        _settings.value = f.settings
        _pages.value = f.pages
        _gestures.value = gestures
        prefs.edit()
            .putString(KEY_FRAME, Wire.encode(f.copy(gestures = gestures)))
            .putString(KEY_SETTINGS, Wire.encode(f.settings)) // keep layout across restarts
            .apply()
    }

    /** lf_map (spec §2.5): an epoch header resets the gate and re-reports our page; images pass the gate first. */
    private fun onMap(caps: Caps?, bytes: ByteArray?) {
        val frame = text(caps)?.let(MapFrame::parse) ?: return
        // Arrival, before the gate (device check D1): what CXR actually delivered.
        Log.i(MAP_TAG, "lf_map in kind=${frame.kind} epoch=${frame.renderEpoch} seq=${frame.renderSeq} bytes=${bytes?.size ?: -1} crc=${MapPayload.crc(bytes?.takeIf { it.isNotEmpty() })} b64=${frame.pngBase64?.length ?: 0} b64crc=${MapPayload.crc(MapPayload.png(frame, null))}")
        val session = _frame.value?.workout?.sessionId
        if (frame.kind == MapFrameKind.Epoch) {
            gate.accept(frame, session)
            reporter.resend() // a (re)connected or restarted phone learns our page without a page change
            return
        }
        if (!gate.accept(frame, session)) { Log.d(MAP_TAG, "dropped epoch=${frame.renderEpoch} seq=${frame.renderSeq} session=${frame.sessionId}"); return }
        val png = MapPayload.png(frame, bytes) ?: run { Log.w(MAP_TAG, "image without payload (bytes=${bytes?.size})"); return }
        _mapImage.value = MapImage(frame.sessionId ?: return, png, frame.renderSeq)
        Log.i(MAP_TAG, "shown epoch=${frame.renderEpoch} seq=${frame.renderSeq} bytes=${png.size}")
    }

    fun reportPage(page: HudPage) = reporter.onPage(page)

    /** CXR bridge connected: report the visible page again (spec §2.5). */
    fun onPhoneConnected() = reporter.resend()

    private fun onQueue(caps: Caps?) {
        val t = text(caps) ?: return
        if (Wire.versionOf(t) != PROTOCOL_VERSION) return // lf_state already reports the mismatch
        _queue.value = runCatching { Wire.decode<QueueFrame>(t).window }.getOrElse { Log.w(TAG, "bad queue: ${it.javaClass.simpleName}"); return }
    }

    private fun loadSettings(): HudSettings =
        prefs.getString(KEY_SETTINGS, null)?.let { runCatching { Wire.decode<HudSettings>(it) }.getOrNull() } ?: HudSettings()

    private fun refreshConnection() {
        _connection.value = connectionFor(everReceived, liveness.isOnline(), outdated, clock.nowMs() - startedAt)
    }

    fun send(command: Command) =
        sendRaw(GlassesChannels.COMMAND, Wire.encode(CommandEnvelope(id = UUID.randomUUID().toString(), origin = DeviceKind.Glasses, command = command)))

    /** True when the bridge accepted the message (CXR-S `sendMessage`: 0 = sent, -1 parameter error, -3 internal error). */
    fun sendRaw(channel: String, text: String): Boolean = bridge.sendMessage(channel, Caps().apply { write(text) }) == 0

    companion object {
        const val TAG = "LiveFitGlasses"
        private const val KEY_SETTINGS = "hudSettings"
        private const val KEY_FRAME = "hudSettingsFrame"
        const val MAP_TAG = "LiveFitMap"
    }
}
