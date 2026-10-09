package com.debasish.livefit.phone

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.util.Log
import androidx.wear.remote.interactions.RemoteActivityHelper
import com.debasish.livefit.services.watch.watchNodes
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.guava.await
import kotlinx.coroutines.tasks.await

/**
 * Opens the watch app's MainActivity from the phone (livefit://workout). RemoteActivityHelper starts it even with the
 * watch screen off (spike-verified), which a watch app can't do for itself from the background.
 */
class WatchLauncher(context: Context) {
    private val app = context.applicationContext

    suspend fun open(reason: String) {
        try {
            val nodes = watchNodes(app)
            if (nodes.isEmpty()) { Log.i(TAG, "no watch node to open ($reason)"); return }
            val intent = Intent(Intent.ACTION_VIEW).addCategory(Intent.CATEGORY_BROWSABLE).setData(Uri.parse(URI))
            // the best node (nearby first) with the livefit_watch capability, not every connected node
            nodes.take(1).forEach { RemoteActivityHelper(app).startRemoteActivity(intent, it.id).await() }
            Log.i(TAG, "opened watch screen ($reason)")
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Log.w(TAG, "could not open watch screen ($reason)", e)
        }
    }

    private companion object {
        const val TAG = "LiveFitHub"
        const val URI = "livefit://workout"
    }
}
