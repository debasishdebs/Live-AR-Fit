package com.debasish.livefit.services.watch

import android.content.Context
import android.util.Log
import com.debasish.livefit.model.Command
import com.debasish.livefit.model.DeviceStatus
import com.debasish.livefit.model.HudFrame
import com.debasish.livefit.model.LinkState
import com.debasish.livefit.model.Protocol
import com.debasish.livefit.services.WatchLinkService
import com.google.android.gms.wearable.Wearable
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.tasks.await

/**
 * Live watch link over the Wearable Data Layer: link state from connected nodes, battery from
 * the watch app's replies to [PATH_BATTERY_REQUEST]. The phone's WearableListenerService
 * forwards incoming [PATH_BATTERY] messages to [onBattery].
 */
class DataLayerWatchLink(context: Context, private val scope: CoroutineScope) : WatchLinkService {
    private val app = context.applicationContext
    private val _status = MutableStateFlow(DeviceStatus("Galaxy Watch", LinkState.Connecting))
    override val status: StateFlow<DeviceStatus> = _status
    private val _commands = MutableSharedFlow<Command>(extraBufferCapacity = 8)
    override val commands: Flow<Command> = _commands
    @Volatile private var nodeId: String? = null

    init {
        instance = this
        scope.launch {
            while (true) {
                refresh()
                delay(60_000)
            }
        }
    }

    private suspend fun refresh() {
        val nodes = runCatching { Wearable.getNodeClient(app).connectedNodes.await() }.getOrElse { emptyList() }
        val node = nodes.firstOrNull()
        nodeId = node?.id
        if (node == null) {
            _status.update { it.copy(link = LinkState.Disconnected, detail = "Not reachable") }
            return
        }
        _status.update { it.copy(name = node.displayName.substringBefore(" (").ifBlank { "Galaxy Watch" }, link = LinkState.Connected, detail = null) }
        runCatching { Wearable.getMessageClient(app).sendMessage(node.id, PATH_BATTERY_REQUEST, ByteArray(0)).await() }
            .onFailure { Log.w(TAG, "battery request failed", it) }
    }

    override suspend fun push(frame: HudFrame) {
        val id = nodeId ?: return
        runCatching { Wearable.getMessageClient(app).sendMessage(id, Protocol.PATH_STATE, Protocol.encodeHud(frame).toByteArray()).await() }
    }

    /** Called by the phone's WearableListenerService for [Protocol.PATH_COMMAND]. */
    fun onCommand(json: String) {
        runCatching { Protocol.decodeCommand(json) }.onSuccess { _commands.tryEmit(it) }.onFailure { Log.w(TAG, "bad command $json", it) }
    }

    fun onBattery(pct: Int) {
        Log.i(TAG, "watch battery $pct%")
        _status.update { it.copy(batteryPct = pct, link = LinkState.Connected) }
    }

    companion object {
        const val TAG = "LiveFitWatchLink"
        const val PATH_BATTERY_REQUEST = "/rf/battery_req"
        const val PATH_BATTERY = "/rf/battery"

        /** Set by the live instance so the phone's WearableListenerService can deliver replies. */
        @Volatile var instance: DataLayerWatchLink? = null
    }
}
