package com.debasish.livefit.phone

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.util.Log
import androidx.wear.remote.interactions.RemoteActivityHelper
import com.debasish.livefit.model.DeviceKind
import com.debasish.livefit.model.DiscoverableRequest
import com.debasish.livefit.model.Wire
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.guava.await
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull

/**
 * Pair (D2): the companion picker only lists discoverable devices, and bonded peers usually aren't discoverable.
 * So first ask the peer app to show the system "make discoverable" prompt, then open the picker (it keeps scanning).
 */
object PeerPairing {
    /** Time for the peer's prompt to appear and the user to tap Allow before the picker opens. */
    private const val PICKER_DELAY_MS = 2_000L

    fun pair(activity: Activity, services: ServiceGraph, kind: DeviceKind, onResult: (Boolean) -> Unit) {
        if (Build.VERSION.SDK_INT < 33) { CompanionLinker.associate(activity, kind, onResult); return }
        services.scope.launch {
            val asked = when (kind) {
                DeviceKind.Glasses -> services.glasses.requestDiscoverable()
                DeviceKind.Watch -> askWatch(activity, services)
                DeviceKind.Phone -> false
            }
            if (asked) delay(PICKER_DELAY_MS)
            if (activity.isFinishing || activity.isDestroyed) { reconnect(services, kind); onResult(false); return@launch }
            CompanionLinker.associate(activity, kind) { ok ->
                // The prompt took the HUD out of the foreground and CXR closed the session (GLASSES_EXIT), which the
                // policy doesn't retry on its own: reconnect manually whatever the outcome.
                reconnect(services, kind)
                onResult(ok)
            }
        }
    }

    private fun reconnect(services: ServiceGraph, kind: DeviceKind) { if (kind == DeviceKind.Glasses) services.glasses.connect() }

    /**
     * Data Layer message plus a remote activity launch carrying the same versioned request: a message alone may not be
     * allowed to start the prompt from the background on Wear OS. The watch shows one prompt for both.
     */
    private suspend fun askWatch(context: Context, services: ServiceGraph): Boolean = coroutineScope {
        val message = async { services.watch.requestDiscoverable() }
        val remote = async { launchOnWatch(context) }
        message.await() or remote.await()
    }

    private suspend fun launchOnWatch(context: Context): Boolean = try {
        val uri = Uri.Builder().scheme("livefit").authority("discoverable").appendQueryParameter("req", Wire.encode(DiscoverableRequest())).build()
        val intent = Intent(Intent.ACTION_VIEW).addCategory(Intent.CATEGORY_BROWSABLE).setData(uri)
        withTimeoutOrNull(3_000) { RemoteActivityHelper(context).startRemoteActivity(intent).await(); true } ?: false
    } catch (e: CancellationException) {
        throw e
    } catch (e: Exception) {
        Log.w("LiveFitPairing", "remote discoverable launch failed", e)
        false
    }
}
