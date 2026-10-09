package com.debasish.livefit.watch

import com.debasish.livefit.model.CAPABILITY_PHONE
import com.debasish.livefit.model.DeltaAck
import com.debasish.livefit.model.ExerciseOp
import com.debasish.livefit.model.ExerciseRequest
import com.debasish.livefit.model.PROTOCOL_VERSION
import com.debasish.livefit.model.PeerGate
import com.debasish.livefit.model.WatchPaths
import com.debasish.livefit.model.Wire
import com.google.android.gms.wearable.CapabilityClient
import com.google.android.gms.wearable.MessageEvent
import com.google.android.gms.wearable.Wearable
import com.google.android.gms.wearable.WearableListenerService
import kotlinx.coroutines.launch
import kotlinx.coroutines.tasks.await
import kotlin.coroutines.cancellation.CancellationException

/** Wakes the watch app for phone messages, even after process death (verified in spikes). */
class PhoneCommandListener : WearableListenerService() {
    override fun onMessageReceived(event: MessageEvent) {
        val path = event.path
        val data = event.data
        val source = event.sourceNodeId
        val gate = gate(this)
        // Time sync stays on the fast path (RTT <= 1 s) for an already-verified phone.
        if (path == WatchPaths.TIME_REQ && gate.known(source)) { replyTime(data, source); return }
        WatchRuntime.init(this)
        WatchRuntime.scope.launch {
            if (!gate.allows(source)) { WatchRuntime.log("ignored $path from a node without $CAPABILITY_PHONE"); return@launch }
            handle(path, data, source)
        }
    }

    private fun replyTime(data: ByteArray, source: String) {
        TimeSyncResponder.reply(String(data), System.currentTimeMillis())
            ?.let { Wearable.getMessageClient(this).sendMessage(source, WatchPaths.TIME_RES, it) }
    }

    private suspend fun handle(path: String, data: ByteArray, source: String) {
        when (path) {
            WatchPaths.TIME_REQ -> { replyTime(data, source); return }
            WatchPaths.BATTERY_REQ -> {
                val pct = getSystemService(android.os.BatteryManager::class.java).getIntProperty(android.os.BatteryManager.BATTERY_PROPERTY_CAPACITY)
                Wearable.getMessageClient(this).sendMessage(source, WatchPaths.BATTERY, pct.toString().toByteArray())
                return
            }
        }
        val text = String(data)
        if (Wire.versionOf(text) != PROTOCOL_VERSION) { WatchClient.onOutdated(); return }
        try {
            when (path) {
                WatchPaths.EXERCISE_REQ -> {
                    val req = Wire.decode<ExerciseRequest>(text)
                    WatchRuntime.controller.handle(req)
                    if (req.op is ExerciseOp.Start) WatchFront.onHubStart(this) // A3: don't wait for the phone's delayed launch
                }
                WatchPaths.ACK -> WatchRuntime.recorder.onAck(Wire.decode<DeltaAck>(text))
                WatchPaths.STATE -> WatchClient.onFrame(text)
                WatchPaths.SETTINGS -> WatchClient.onSettings(text)
                WatchPaths.QUEUE -> WatchClient.onQueue(text)
                WatchPaths.DISCOVERABLE -> DiscoverableActivity.start(this, text)
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            // Spec §3: no payload (track titles, coordinates) in release logs.
            android.util.Log.e(WatchRuntime.TAG, "bad $path message: ${e.javaClass.simpleName}")
        }
    }

    companion object {
        @Volatile private var gate: PeerGate? = null

        /** Spec §4: only nodes advertising `livefit_phone` may command the watch. */
        private fun gate(context: android.content.Context): PeerGate = gate ?: synchronized(this) {
            gate ?: PeerGate(
                lookup = {
                    val app = context.applicationContext
                    // Same fallback as the sending side (PhoneNodes): an older phone build without the capability -> connected nodes.
                    Wearable.getCapabilityClient(app).getCapability(CAPABILITY_PHONE, CapabilityClient.FILTER_ALL).await()
                        .nodes.mapTo(HashSet()) { it.id }
                        .ifEmpty { Wearable.getNodeClient(app).connectedNodes.await().mapTo(HashSet()) { it.id } }
                },
                nowMs = System::currentTimeMillis,
            ).also { gate = it }
        }
    }
}
