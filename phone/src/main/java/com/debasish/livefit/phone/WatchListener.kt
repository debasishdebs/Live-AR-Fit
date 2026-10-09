package com.debasish.livefit.phone

import android.content.Context
import android.util.Log
import com.debasish.livefit.model.CAPABILITY_WATCH
import com.debasish.livefit.model.PeerGate
import com.debasish.livefit.services.watch.DataLayerWatchLink
import com.google.android.gms.wearable.CapabilityClient
import com.google.android.gms.wearable.MessageEvent
import com.google.android.gms.wearable.Wearable
import com.google.android.gms.wearable.WearableListenerService
import kotlinx.coroutines.launch
import kotlinx.coroutines.tasks.await

/** Wakes the phone for watch messages; building the graph starts the hub (spec §5.2). Only `livefit_watch` nodes count (spec §4). */
class WatchListener : WearableListenerService() {
    override fun onMessageReceived(event: MessageEvent) {
        val graph = (application as LiveFitApp).services
        LiveFitHubService.start(this)
        val link = DataLayerWatchLink.instance ?: return
        val path = event.path
        val data = event.data
        val source = event.sourceNodeId
        val gate = gate(applicationContext)
        graph.scope.launch {
            if (!gate.allows(source)) { Log.w("LiveFitWatchLink", "ignored $path from a node without $CAPABILITY_WATCH"); return@launch }
            link.onMessage(path, data, source)
        }
    }

    companion object {
        @Volatile private var gate: PeerGate? = null

        private fun gate(context: Context): PeerGate = gate ?: synchronized(this) {
            gate ?: PeerGate(
                lookup = {
                    // Same fallback as the sending side (WearNodes): an older watch build without the capability -> connected nodes.
                    Wearable.getCapabilityClient(context).getCapability(CAPABILITY_WATCH, CapabilityClient.FILTER_ALL).await()
                        .nodes.mapTo(HashSet()) { it.id }
                        .ifEmpty { Wearable.getNodeClient(context).connectedNodes.await().mapTo(HashSet()) { it.id } }
                },
                nowMs = System::currentTimeMillis,
            ).also { gate = it }
        }
    }
}
