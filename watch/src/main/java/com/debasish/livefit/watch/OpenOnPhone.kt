package com.debasish.livefit.watch

import android.content.Context
import android.content.Intent
import android.net.Uri
import androidx.wear.remote.interactions.RemoteActivityHelper
import kotlinx.coroutines.guava.await
import kotlinx.coroutines.withTimeoutOrNull
import kotlin.coroutines.cancellation.CancellationException

/** Opens a web page in the paired phone's browser (spec §5 map links, §7 privacy policy). True if the phone accepted. */
object OpenOnPhone {
    suspend fun open(context: Context, url: String): Boolean = try {
        val intent = Intent(Intent.ACTION_VIEW, Uri.parse(url)).addCategory(Intent.CATEGORY_BROWSABLE)
        withTimeoutOrNull(5_000) { RemoteActivityHelper(context.applicationContext).startRemoteActivity(intent).await(); true } ?: false
    } catch (e: CancellationException) {
        throw e
    } catch (_: Exception) {
        false
    }
}
