package com.debasish.livefit.services.watch

import android.content.Context
import android.util.Log
import com.debasish.livefit.model.CommandEnvelope
import com.debasish.livefit.model.DeltaAck
import com.debasish.livefit.model.DeviceStatus
import com.debasish.livefit.model.DiscoverableRequest
import com.debasish.livefit.model.ExerciseRequest
import com.debasish.livefit.model.ExerciseResult
import com.debasish.livefit.model.ExerciseStateReport
import com.debasish.livefit.model.LinkState
import com.debasish.livefit.model.SessionClaim
import com.debasish.livefit.model.SessionDelta
import com.debasish.livefit.model.StateFrame
import com.debasish.livefit.model.WatchPaths
import com.debasish.livefit.model.Wire
import com.debasish.livefit.services.WatchExerciseGateway
import com.debasish.livefit.services.WatchLinkService
import com.google.android.gms.wearable.Wearable
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.tasks.await

/** Phone ↔ watch over the Wearable Data Layer: gateway for the hub + frames + battery. */
class DataLayerWatchLink(context: Context, private val scope: CoroutineScope) : WatchLinkService, WatchExerciseGateway {
    private val app = context.applicationContext
    private val messages = Wearable.getMessageClient(app)
    @Volatile private var nodeId: String? = null

    private val _status = MutableStateFlow(DeviceStatus("Galaxy Watch", LinkState.Connecting))
    override val status: StateFlow<DeviceStatus> = _status
    override val commands = MutableSharedFlow<CommandEnvelope>(extraBufferCapacity = 32)
    override val results = MutableSharedFlow<ExerciseResult>(extraBufferCapacity = 32)
    override val stateReports = MutableSharedFlow<ExerciseStateReport>(extraBufferCapacity = 32)
    override val deltas = MutableSharedFlow<SessionDelta>(extraBufferCapacity = 8_192)
    override val claims = MutableSharedFlow<SessionClaim>(extraBufferCapacity = 16)
    private val _outdated = MutableSharedFlow<Int?>(extraBufferCapacity = 4)
    val outdated: SharedFlow<Int?> = _outdated

    init {
        instance = this
        scope.launch { while (true) { refreshNode(); delay(5_000) } }
        scope.launch { while (true) { requestBattery(); delay(60_000) } }
    }

    private suspend fun refreshNode() {
        val node = try {
            Wearable.getNodeClient(app).connectedNodes.await().firstOrNull()
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Log.w(TAG, "node lookup failed", e)
            null
        }
        nodeId = node?.id
        _status.update {
            if (node == null) it.copy(link = LinkState.Disconnected, detail = "Not reachable")
            else it.copy(name = node.displayName.substringBefore(" (").ifBlank { "Galaxy Watch" }, link = LinkState.Connected, detail = null)
        }
    }

    private suspend fun requestBattery() { sendRaw(WatchPaths.BATTERY_REQ, ByteArray(0)) }

    private suspend fun sendRaw(path: String, bytes: ByteArray): Boolean {
        val id = nodeId ?: return false
        return try {
            messages.sendMessage(id, path, bytes).await()
            true
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Log.w(TAG, "send $path failed", e)
            false
        }
    }

    override suspend fun send(request: ExerciseRequest) { sendRaw(WatchPaths.EXERCISE_REQ, Wire.encode(request).toByteArray()) }
    override suspend fun ack(ack: DeltaAck) { sendRaw(WatchPaths.ACK, Wire.encode(ack).toByteArray()) }
    /** Pairing (D2): the watch app shows the system discoverable prompt. */
    override suspend fun requestDiscoverable(): Boolean {
        if (nodeId == null) refreshNode()
        return sendRaw(WatchPaths.DISCOVERABLE, Wire.encode(DiscoverableRequest()).toByteArray())
    }
    override suspend fun push(frame: StateFrame) {
        if (_status.value.link == LinkState.Connected) { sendRaw(WatchPaths.STATE, Wire.encode(frame).toByteArray()) }
    }

    /** Called from the phone's WearableListenerService for every /lf message. */
    fun onMessage(path: String, bytes: ByteArray, sourceNodeId: String) {
        nodeId = sourceNodeId
        _status.update { it.copy(link = LinkState.Connected, detail = null) }
        when (val m = WatchMessageCodec.decode(path, bytes)) {
            is WatchInbound.Delta -> deltas.tryEmit(m.delta)
            is WatchInbound.Result -> results.tryEmit(m.result)
            is WatchInbound.State -> stateReports.tryEmit(m.report)
            is WatchInbound.Claim -> claims.tryEmit(m.claim)
            is WatchInbound.Cmd -> commands.tryEmit(m.envelope)
            is WatchInbound.Battery -> _status.update { it.copy(batteryPct = m.pct) }
            is WatchInbound.Outdated -> _outdated.tryEmit(m.version)
            null -> Log.w(TAG, "ignored $path")
        }
    }

    companion object {
        const val TAG = "LiveFitWatchLink"
        @Volatile var instance: DataLayerWatchLink? = null
    }
}
