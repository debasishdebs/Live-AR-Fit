package com.debasish.livefit.sync

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull

/** Push-on-change with coalescing plus a heartbeat (spec §4.3). */
class StateBroadcaster(
    private val scope: CoroutineScope,
    private val coalesceMs: Long = 100,
    private val heartbeatMs: Long = 5_000,
    private val onError: (Throwable) -> Unit = {},
    private val send: suspend () -> Unit,
) {
    private val dirty = Channel<Unit>(Channel.CONFLATED)

    fun markDirty() { dirty.trySend(Unit) }

    fun start(): Job = scope.launch {
        while (isActive) {
            val changed = withTimeoutOrNull(heartbeatMs) { dirty.receive() } != null
            if (changed) {
                delay(coalesceMs)
                dirty.tryReceive() // merge changes that arrived during the window
            }
            try {
                send()
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                onError(e)
            }
        }
    }
}
