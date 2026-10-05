package com.debasish.livefit.phone

import com.google.android.gms.wearable.MessageEvent
import com.google.android.gms.wearable.WearableListenerService

/** Receives watch status/metrics and forwards metrics straight to the glasses. */
class WatchListener : WearableListenerService() {
    override fun onMessageReceived(event: MessageEvent) {
        val json = String(event.data)
        when (event.path) {
            WatchControl.PATH_METRICS -> {
                val sent = RokidLink.send("rf_metrics", json)
                SpikeLog.i("watch metrics $json -> glasses=$sent")
            }
            com.debasish.livefit.model.Protocol.PATH_COMMAND -> {
                // Touching the app instance builds the ServiceGraph even if the phone app wasn't open.
                (application as LiveFitApp).services
                com.debasish.livefit.services.watch.DataLayerWatchLink.instance?.onCommand(json)
            }
            com.debasish.livefit.services.watch.DataLayerWatchLink.PATH_BATTERY ->
                json.toIntOrNull()?.let { com.debasish.livefit.services.watch.DataLayerWatchLink.instance?.onBattery(it) }
            else -> SpikeLog.i("watch ${event.path} $json")
        }
    }
}
