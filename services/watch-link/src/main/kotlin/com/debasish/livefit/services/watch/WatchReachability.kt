package com.debasish.livefit.services.watch

import com.debasish.livefit.model.LinkState
import com.debasish.livefit.services.Clock

/**
 * Whether the watch is reachable (F3). A message from the watch (delta, state, result, battery, command) proves it is
 * connected at once; the periodic node lookup can say Disconnected only when it lists no node **and** the watch has been
 * silent for [messageGraceMs] — an empty or failed lookup right after a message (seen after a phone process restart) used
 * to drop the HUD's watch ring to "offline" and stop frame pushes while live HR was flowing. Not thread-safe: hub main thread.
 */
class WatchReachability(private val clock: Clock, private val messageGraceMs: Long = 15_000) {
    var link: LinkState = LinkState.Connecting
        private set
    var nodeId: String? = null
        private set
    private var lastMessageMs: Long? = null

    fun onMessage(sourceNodeId: String) {
        nodeId = sourceNodeId
        lastMessageMs = clock.nowMs()
        link = LinkState.Connected
    }

    /** Result of a connected-nodes lookup; null = none listed (or the lookup failed). */
    fun onNodeLookup(listed: String?) {
        if (listed != null) { nodeId = listed; link = LinkState.Connected; return }
        val recent = lastMessageMs?.let { clock.nowMs() - it < messageGraceMs } == true
        if (recent) return
        nodeId = null
        link = LinkState.Disconnected
    }
}
