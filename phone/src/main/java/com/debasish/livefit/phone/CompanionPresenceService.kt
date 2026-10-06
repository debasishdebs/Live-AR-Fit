package com.debasish.livefit.phone

import android.companion.AssociationInfo
import android.companion.CompanionDeviceService
import android.companion.DevicePresenceEvent
import android.content.Intent
import android.os.Build
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
        // Gone only when every transport (BLE + BT) has dropped it.
        val present = CompanionLinker.setPresent(associationId, source, reported)
        if (present) LiveFitHubService.ensureRunning(this)
        if (CompanionLinker.kindFor(this, associationId) == DeviceKind.Glasses) CxrGlassesLink.instance?.onDevicePresence(present)
        // Spec §5.2: stop the hub when no linked device is present and no workout is active.
        val idle = graph.workout.snapshot.value.phase.let { it == WorkoutPhase.Idle || it == WorkoutPhase.Summary }
        if (!present && idle && !CompanionLinker.anyPresent()) stopService(Intent(this, LiveFitHubService::class.java))
    }
}
