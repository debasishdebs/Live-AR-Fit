package com.debasish.livefit.watch

import com.debasish.livefit.model.DeltaAck
import com.debasish.livefit.model.ExerciseOp
import com.debasish.livefit.model.ExerciseRequest
import com.debasish.livefit.model.PROTOCOL_VERSION
import com.debasish.livefit.model.WatchPaths
import com.debasish.livefit.model.Wire
import com.google.android.gms.wearable.MessageEvent
import com.google.android.gms.wearable.Wearable
import com.google.android.gms.wearable.WearableListenerService
import kotlinx.coroutines.launch
import kotlin.coroutines.cancellation.CancellationException

/** Wakes the watch app for phone messages, even after process death (verified in spikes). */
class PhoneCommandListener : WearableListenerService() {
    override fun onMessageReceived(event: MessageEvent) {
        WatchRuntime.init(this)
        val text = String(event.data)
        when (event.path) {
            WatchPaths.BATTERY_REQ -> {
                val pct = getSystemService(android.os.BatteryManager::class.java).getIntProperty(android.os.BatteryManager.BATTERY_PROPERTY_CAPACITY)
                Wearable.getMessageClient(this).sendMessage(event.sourceNodeId, WatchPaths.BATTERY, pct.toString().toByteArray())
                return
            }
        }
        if (Wire.versionOf(text) != PROTOCOL_VERSION) { WatchClient.onOutdated(); return }
        WatchRuntime.scope.launch {
            try {
                when (event.path) {
                    WatchPaths.EXERCISE_REQ -> {
                        val req = Wire.decode<ExerciseRequest>(text)
                        WatchRuntime.controller.handle(req)
                        if (req.op is ExerciseOp.Start) WatchFront.onHubStart(this@PhoneCommandListener) // A3: don't wait for the phone's delayed launch
                    }
                    WatchPaths.ACK -> WatchRuntime.recorder.onAck(Wire.decode<DeltaAck>(text))
                    WatchPaths.STATE -> WatchClient.onFrame(text)
                    WatchPaths.DISCOVERABLE -> DiscoverableActivity.start(this@PhoneCommandListener, text)
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                android.util.Log.e(WatchRuntime.TAG, "bad ${event.path} message", e)
            }
        }
    }
}
