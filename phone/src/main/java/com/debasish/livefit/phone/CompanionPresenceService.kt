package com.debasish.livefit.phone

import android.companion.AssociationInfo
import android.companion.CompanionDeviceService
import android.companion.DevicePresenceEvent
import android.content.Intent
import android.os.Build
import android.util.Log
import com.debasish.livefit.model.DeviceKind
import com.debasish.livefit.model.WorkoutPhase
import com.debasish.livefit.services.glasses.CxrGlassesLink

/** Android calls this when an associated glasses/watch appears or disappears. */
class CompanionPresenceService : CompanionDeviceService() {
    override fun onDevicePresenceEvent(event: DevicePresenceEvent) {
        if (Build.VERSION.SDK_INT < 36) return
        val (source, present) = when (event.event) {
            DevicePresenceEvent.EVENT_BLE_APPEARED -> CompanionLinker.Source.Ble to true
            DevicePresenceEvent.EVENT_BLE_DISAPPEARED -> CompanionLinker.Source.Ble to false
            DevicePresenceEvent.EVENT_BT_CONNECTED -> CompanionLinker.Source.Bt to true
            DevicePresenceEvent.EVENT_BT_DISCONNECTED -> CompanionLinker.Source.Bt to false
            else -> return // self-managed and other event types are not ours
        }
        handle(event.associationId, source, present)
    }

    @Deprecated("API < 36")
    override fun onDeviceAppeared(info: AssociationInfo) = handle(info.id, CompanionLinker.Source.Legacy, true)

    @Deprecated("API < 36")
    override fun onDeviceDisappeared(info: AssociationInfo) = handle(info.id, CompanionLinker.Source.Legacy, false)

    private fun handle(associationId: Int, source: CompanionLinker.Source, reported: Boolean) {
        val graph = (application as LiveFitApp).services // builds and starts the hub graph
        val kind = CompanionLinker.kindFor(this, associationId)
        // Settings → Nearby devices (R2): a device with "Start LiveFit when nearby" off is ignored (late events too).
        val enabled = kind?.let(graph.settings::startWhenNearby) ?: true
        if (!enabled) { Log.i(TAG, "presence of $kind ignored: Start LiveFit when nearby is off"); return }
        // Gone only when every transport (BLE + BT) has dropped it.
        val present = CompanionLinker.setPresent(associationId, source, reported)
        // Spec §5.2: stop the hub when no linked device is present and no workout is active.
        val idle = graph.workout.snapshot.value.phase.let { it == WorkoutPhase.Idle || it == WorkoutPhase.Summary }
        val hub = NearbyPolicy.onPresence(enabled, present, idle, CompanionLinker.anyPresent())
        if (hub == NearbyPolicy.Hub.Start) LiveFitHubService.ensureRunning(this)
        if (kind == DeviceKind.Glasses) CxrGlassesLink.instance?.onDevicePresence(present)
        if (hub == NearbyPolicy.Hub.Stop) stopService(Intent(this, LiveFitHubService::class.java))
    }

    private companion object { const val TAG = "CompanionPresence" }
}
