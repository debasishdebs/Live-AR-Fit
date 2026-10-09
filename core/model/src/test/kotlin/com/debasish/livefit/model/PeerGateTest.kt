package com.debasish.livefit.model

import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class PeerGateTest {
    private var now = 0L
    private var lookups = 0
    private var advertised: () -> Set<String> = { setOf("phone-node") }
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

    /** Review focus 4: Play services failing once must not lock the genuine peer out until the rate limit ends. */
    @Test fun aFailedLookupRejectsButCachesNothing() = runTest {
        var fail = true
        advertised = { if (fail) error("Wearable API unavailable") else setOf("phone-node") }
        assertFalse(gate.allows("phone-node"))
        fail = false
        assertTrue(gate.allows("phone-node"), "retried at once")
        assertEquals(2, lookups)
    }
}
