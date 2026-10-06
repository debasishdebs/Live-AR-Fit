package com.debasish.livefit.services.watch

import com.debasish.livefit.model.LinkState
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

/** F3: any message from the watch proves it is connected; an empty node lookup must not override that. */
class WatchReachabilityTest {
    private var now = 0L
    private val r = WatchReachability({ now }, messageGraceMs = 15_000)

    @Test fun startsConnectingAndAnyMessageMarksConnected() {
        assertEquals(LinkState.Connecting, r.link)
        r.onMessage("n1")
        assertEquals(LinkState.Connected, r.link)
        assertEquals("n1", r.nodeId)
    }

    @Test fun emptyNodeLookupRightAfterAMessageKeepsConnectedAndTheNode() {
        r.onMessage("n1")
        now = 5_000
        r.onNodeLookup(null)
        assertEquals(LinkState.Connected, r.link)
        assertEquals("n1", r.nodeId, "frames must still be pushed to the node that just talked to us")
    }

    @Test fun silentAndNotListedMeansDisconnected() {
        r.onMessage("n1")
        now = 16_000
        r.onNodeLookup(null)
        assertEquals(LinkState.Disconnected, r.link)
        assertNull(r.nodeId)
    }

    @Test fun listedNodeIsConnected() {
        r.onNodeLookup("n2")
        assertEquals(LinkState.Connected, r.link)
        assertEquals("n2", r.nodeId)
        now = 60_000
        r.onNodeLookup(null)
        assertEquals(LinkState.Disconnected, r.link)
    }
}
