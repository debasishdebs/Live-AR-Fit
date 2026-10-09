package com.debasish.livefit.sync

import com.debasish.livefit.map.MapCadence
import com.debasish.livefit.model.HudPage
import com.debasish.livefit.model.MapFrame
import com.debasish.livefit.model.MapFrameKind
import com.debasish.livefit.model.RouteState
import com.debasish.livefit.services.Clock
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
import kotlin.random.Random

/**
 * Phone → glasses map images on lf_map (spec §2.5). A new random [renderEpoch] at construction (process start) and on
 * every glasses (re)connect, announced first with an image-less header. Images only while the glasses' latest
 * lf_page_state says Map (cleared on disconnect and on connect until they report again), every 3 s or after ≥ 25 m,
 * never more than 1/s ([MapCadence]). A failed render or send just waits for the next cadence. Single-threaded.
 */
class GlassesMapStreamer(
    private val scope: CoroutineScope,
    private val clock: Clock,
    private val render: suspend (RouteState) -> ByteArray?,
    private val send: suspend (MapFrame, ByteArray?) -> Boolean,
    private val newEpoch: () -> Long = { Random.nextLong() },
    private val tickMs: Long = 250,
    private val sendTimeoutMs: Long = 5_000,
    private val log: (String) -> Unit = {},
) {
    var renderEpoch: Long = newEpoch()
        private set
    private var connected = false
    private var announced = false
    private var visible = false
    private var lastPageSeq = Long.MIN_VALUE
    private var seq = 0L
    private var state = RouteState()
    private val cadence = MapCadence()

    val mapVisible: Boolean get() = visible

    fun onConnected() {
        connected = true
        renderEpoch = newEpoch()
        announced = false
        visible = false
        lastPageSeq = Long.MIN_VALUE
        seq = 0
        cadence.reset()
    }

    fun onDisconnected() { connected = false; announced = false; visible = false }

    /** lf_page_state; an older seq than the last one seen on this connection is ignored. */
    fun onPageState(page: HudPage, seq: Long) {
        if (seq <= lastPageSeq) return
        lastPageSeq = seq
        val v = page == HudPage.Map
        if (v && !visible) {
            cadence.reset() // arriving on the Map page renders at once
            announced = false // re-send the epoch header first: the glasses may have missed or not yet subscribed to it
        }
        visible = v
    }

    fun onRoute(s: RouteState) { state = s }

    fun start(): Job = scope.launch { while (isActive) { step(); delay(tickMs) } }

    suspend fun step() {
        if (!connected) return
        if (!announced) {
            announced = trySend(MapFrame(kind = MapFrameKind.Epoch, renderEpoch = renderEpoch), null)
            if (!announced) return
        }
        if (!visible) return
        val s = state
        val id = s.sessionId ?: return
        val lat = s.live?.lat ?: s.route.lastOrNull()?.lat
        val lon = s.live?.lon ?: s.route.lastOrNull()?.lon
        val now = clock.nowMs()
        if (!cadence.due(now, lat, lon)) return
        cadence.rendered(now, lat, lon)
        val epoch = renderEpoch
        val png = try { render(s) } catch (e: CancellationException) { throw e } catch (e: Exception) { log("render failed: $e"); null } ?: return
        if (!connected || !visible || epoch != renderEpoch) return // the link changed while rendering
        val frame = MapFrame(kind = MapFrameKind.Image, renderEpoch = epoch, sessionId = id, renderSeq = ++seq)
        if (trySend(frame, png)) log("sent seq=${frame.renderSeq} bytes=${png.size} epoch=$epoch")
    }

    private suspend fun trySend(frame: MapFrame, png: ByteArray?): Boolean = try {
        withTimeoutOrNull(sendTimeoutMs) { send(frame, png) } ?: false
    } catch (e: CancellationException) {
        throw e
    } catch (e: Exception) {
        log("send failed: $e"); false
    }
}
