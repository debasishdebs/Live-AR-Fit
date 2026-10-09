package com.debasish.livefit.model

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class PeerGateTest {
    private var now = 0L
    private var lookups = 0
    private var advertised: suspend () -> Set<String> = { setOf("phone-node") }
    private val gate = PeerGate(lookup = { lookups++; advertised() }, nowMs = { now })

    @Test fun anAdvertisedPeerIsAllowedAndCached() = runTest {
        assertFalse(gate.known("phone-node"), "nothing verified yet")
        assertTrue(gate.allows("phone-node"))
        assertTrue(gate.known("phone-node"))
        assertTrue(gate.allows("phone-node"))
        assertEquals(1, lookups)
    }

    @Test fun anUnknownSenderIsIgnoredAndCannotForceALookupStorm() = runTest {
        assertFalse(gate.allows("stranger"))
        now += 5_000
        assertFalse(gate.allows("stranger"))
        assertEquals(1, lookups, "rate limited")
        now += 5_001
        assertFalse(gate.allows("stranger"))
        assertEquals(2, lookups)
    }

    /** Review focus 4: the peer app was just (re)installed and its capability shows up after the first lookup. */
    @Test fun aNewlyAdvertisedPeerIsAcceptedAfterOneRefresh() = runTest {
        assertTrue(gate.allows("phone-node"))
        advertised = { setOf("phone-node", "new-phone") }
        now += 10_000
        assertTrue(gate.allows("new-phone"))
        assertEquals(2, lookups)
    }

    /** Play services failing once must not lock the genuine peer out for the whole refresh window. */
    @Test fun aFailedLookupRejectsCachesNothingAndRetriesAfterAShortBackoff() = runTest {
        var fail = true
        advertised = { if (fail) error("Wearable API unavailable") else setOf("phone-node") }
        assertFalse(gate.allows("phone-node"))
        fail = false
        assertFalse(gate.allows("phone-node"), "a failed lookup is rate limited too")
        assertEquals(1, lookups)
        now += PeerGate.FAILURE_BACKOFF_MS
        assertTrue(gate.allows("phone-node"), "retried after the short backoff")
        assertEquals(2, lookups)
    }

    /** Final review Important 3: every message from an unknown sender must not trigger a new failing lookup. */
    @Test fun failedLookupsAreRateLimitedPerUnknownNode() = runTest {
        advertised = { error("Wearable API unavailable") }
        assertFalse(gate.allows("stranger"))
        assertFalse(gate.allows("stranger"))
        assertFalse(gate.allows("stranger"))
        assertEquals(1, lookups, "one failing lookup per backoff window for this node")
        assertFalse(gate.allows("other"))
        assertEquals(2, lookups, "a different node gets its own attempt")
    }

    /** Final review Important 3: a hung lookup is abandoned after the timeout and counts as a failure. */
    @Test fun aHungLookupTimesOutAndRejects() = runTest {
        advertised = { awaitCancellation() }
        val result = async { gate.allows("phone-node") }
        advanceTimeBy(PeerGate.LOOKUP_TIMEOUT_MS + 1)
        runCurrent()
        assertTrue(result.isCompleted, "abandoned after the timeout")
        assertFalse(result.await())
    }

    /** Final review Important 3: the verified peer is never queued behind an unknown sender's slow lookup. */
    @Test fun theVerifiedPeerIsNotBlockedByAnotherSendersLookup() = runTest {
        assertTrue(gate.allows("phone-node"))
        now += 10_000
        val release = CompletableDeferred<Unit>()
        advertised = { release.await(); setOf("phone-node") }
        val stranger = async { gate.allows("stranger") }
        runCurrent()
        assertFalse(stranger.isCompleted, "stranger's lookup is in flight")
        val peer = async { gate.allows("phone-node") }
        runCurrent()
        assertTrue(peer.isCompleted, "verified peer answered without waiting for the mutex")
        assertTrue(peer.await())
        release.complete(Unit)
        assertFalse(stranger.await())
    }
}

class PeerNodesTest {
    @Test fun advertisedNodesWinAndConnectedNodesAreNotQueried() = runTest {
        var connectedAsked = false
        val ids = peerNodeIds(advertised = { listOf("a", "b") }, connected = { connectedAsked = true; listOf("c") })
        assertEquals(setOf("a", "b"), ids)
        assertFalse(connectedAsked)
    }

    @Test fun noAdvertisedNodeFallsBackToConnectedNodes() = runTest {
        assertEquals(setOf("c"), peerNodeIds(advertised = { emptyList() }, connected = { listOf("c", "c") }))
    }

    @Test fun nothingAdvertisedAndNothingConnectedIsEmpty() = runTest {
        assertEquals(emptySet(), peerNodeIds(advertised = { emptyList() }, connected = { emptyList() }))
    }
}
