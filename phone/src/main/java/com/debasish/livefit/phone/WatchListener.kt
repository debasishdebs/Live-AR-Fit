package com.debasish.livefit.phone

import com.debasish.livefit.services.watch.DataLayerWatchLink
import kotlinx.coroutines.launch
import com.google.android.gms.wearable.MessageEvent
import com.google.android.gms.wearable.WearableListenerService

/** Wakes the phone for watch messages; building the graph starts the hub (spec §5.2). */
class WatchListener : WearableListenerService() {
    override fun onMessageReceived(event: MessageEvent) {
        val graph = (application as LiveFitApp).services
        LiveFitHubService.start(this)
        val link = DataLayerWatchLink.instance ?: return
        graph.scope.launch { link.onMessage(event.path, event.data, event.sourceNodeId) }
    }
}
