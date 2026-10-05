package com.debasish.livefit.watch

import android.content.Context
import android.util.Log
import com.google.android.gms.wearable.Wearable
import kotlinx.coroutines.tasks.await

/** Sends small JSON messages to every connected phone node over the Wearable Data Layer. */
object PhoneLink {
    const val PATH_START = "/rf/start"
    const val PATH_STOP = "/rf/stop"
    const val PATH_STATUS = "/rf/status"
    const val PATH_METRICS = "/rf/metrics"
    const val PATH_BATTERY_REQUEST = "/rf/battery_req"
    const val PATH_BATTERY = "/rf/battery"

    suspend fun send(context: Context, path: String, json: String) {
        runCatching {
            val nodes = Wearable.getNodeClient(context).connectedNodes.await()
            for (node in nodes) {
                Wearable.getMessageClient(context).sendMessage(node.id, path, json.toByteArray()).await()
            }
        }.onFailure { Log.w(TAG, "send $path failed", it) }
    }

    const val TAG = "LiveFitWatch"
}
