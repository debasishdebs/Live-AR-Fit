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
        val present = event.event == DevicePresenceEvent.EVENT_BLE_APPEARED || event.event == DevicePresenceEvent.EVENT_BT_CONNECTED
        handle(event.associationId, present)
    }

    @Deprecated("API < 36")
    override fun onDeviceAppeared(info: AssociationInfo) = handle(info.id, true)

    @Deprecated("API < 36")
    override fun onDeviceDisappeared(info: AssociationInfo) = handle(info.id, false)

    private fun handle(associationId: Int, present: Boolean) {
        val graph = (application as LiveFitApp).services // builds and starts the hub graph
        CompanionLinker.setPresent(this, associationId, present)
        if (present) LiveFitHubService.start(this)
        if (CompanionLinker.kindFor(this, associationId) == DeviceKind.Glasses) CxrGlassesLink.instance?.onDevicePresence(present)
        // Spec §5.2: stop the hub when no linked device is present and no workout is active.
        val idle = graph.workout.snapshot.value.phase.let { it == WorkoutPhase.Idle || it == WorkoutPhase.Summary }
        if (!present && idle && !CompanionLinker.anyPresent(this)) stopService(Intent(this, LiveFitHubService::class.java))
    }
}
