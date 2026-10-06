package com.debasish.livefit.sync

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeout

/**
 * Sends the latest frame to one link on its own coroutine (F6). Frames used to go out to the glasses and then the watch
 * in one loop, so a slow or hung watch push (Data Layer) held back the glasses — including a pending confirmation.
 * Conflated: a link that falls behind gets only the newest frame; each push is bounded by [timeoutMs].
 */
class LinkSender<T>(
    scope: CoroutineScope,
    private val name: String,
    private val timeoutMs: Long = 5_000,
    private val onError: (String, Throwable) -> Unit = { _, _ -> },
    private val push: suspend (T) -> Unit,
) {
    private val latest = Channel<T>(Channel.CONFLATED)

    init {
        scope.launch {
            for (frame in latest) {
                try {
                    withTimeout(timeoutMs) { push(frame) }
                } catch (e: TimeoutCancellationException) {
                    onError(name, e)
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    onError(name, e)
                }
            }
        }
    }

    fun offer(frame: T) { latest.trySend(frame) }
}
