package com.debasish.livefit.services.watch

import android.content.Context
import com.debasish.livefit.model.CAPABILITY_WATCH
import com.debasish.livefit.model.NodeCandidate
import com.debasish.livefit.model.chooseNodes
import com.google.android.gms.wearable.CapabilityClient
import com.google.android.gms.wearable.Wearable
import kotlinx.coroutines.tasks.await

/** Watch nodes best-first: capability [CAPABILITY_WATCH] (reachable) via the shared rule, else every connected node (older builds). */
suspend fun watchNodes(context: Context): List<NodeCandidate> {
    val app = context.applicationContext
    val advertised = runCatching {
        Wearable.getCapabilityClient(app).getCapability(CAPABILITY_WATCH, CapabilityClient.FILTER_REACHABLE).await().nodes
            .map { NodeCandidate(it.id, it.displayName, it.isNearby) }
    }.getOrDefault(emptyList())
    val chosen = chooseNodes(advertised)
    if (chosen.isNotEmpty()) return chosen
    return chooseNodes(Wearable.getNodeClient(app).connectedNodes.await().map { NodeCandidate(it.id, it.displayName, it.isNearby) })
}
