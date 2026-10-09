package com.debasish.livefit.watch

import com.debasish.livefit.model.NodeCandidate
import kotlin.test.Test
import kotlin.test.assertEquals

class PhoneNodeTest {
    @Test fun capabilityNodesWinNearbyFirst() {
        val cap = listOf(NodeCandidate("b", "Cloud", false), NodeCandidate("a", "Phone", true))
        assertEquals(listOf("a", "b"), PhoneNodes.targets(cap, connectedIds = listOf("z")))
    }

    @Test fun noCapabilityNodesFallBackToConnected() {
        assertEquals(listOf("z"), PhoneNodes.targets(emptyList(), connectedIds = listOf("z")))
    }

    @Test fun nothingAtAllIsEmpty() = assertEquals(emptyList(), PhoneNodes.targets(emptyList(), emptyList()))
}
