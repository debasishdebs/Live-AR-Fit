package com.debasish.livefit.phone

import android.content.Context
import android.content.Intent
import android.net.Uri
import androidx.wear.remote.interactions.RemoteActivityHelper
import com.google.android.gms.wearable.Wearable
import kotlinx.coroutines.guava.await
import kotlinx.coroutines.tasks.await

/** Two ways to start the watch workout, so the spike can compare them. */
object WatchControl {
    const val PATH_START = "/rf/start"
    const val PATH_STOP = "/rf/stop"
    const val PATH_METRICS = "/rf/metrics"

    /** Path A: open the watch activity remotely (official cross-device launch). */
    suspend fun launchActivity(context: Context, action: String, force: Boolean) {
        val node = watchNode(context) ?: return
        val uri = Uri.parse("livefit://workout/$action" + if (force) "?force=1" else "")
        val intent = Intent(Intent.ACTION_VIEW).addCategory(Intent.CATEGORY_BROWSABLE).setData(uri)
        runCatching { RemoteActivityHelper(context).startRemoteActivity(intent, node).await() }
            .onSuccess { SpikeLog.i("remote activity $uri sent to $node") }
            .onFailure { SpikeLog.e("remote activity failed", it) }
    }

    /** Path B: Data Layer message handled by the watch's WearableListenerService. */
    suspend fun message(context: Context, path: String, force: Boolean) {
        val node = watchNode(context) ?: return
        runCatching {
            Wearable.getMessageClient(context).sendMessage(node, path, """{"force":$force}""".toByteArray()).await()
        }.onSuccess { SpikeLog.i("message $path sent") }.onFailure { SpikeLog.e("message failed", it) }
    }

    private suspend fun watchNode(context: Context): String? {
        val nodes = runCatching { Wearable.getNodeClient(context).connectedNodes.await() }.getOrElse {
            SpikeLog.e("node lookup failed", it); return null
        }
        SpikeLog.i("watch nodes: ${nodes.joinToString { "${it.displayName}/${it.id}" }}")
        return nodes.firstOrNull()?.id ?: run { SpikeLog.i("no watch connected"); null }
    }
}
