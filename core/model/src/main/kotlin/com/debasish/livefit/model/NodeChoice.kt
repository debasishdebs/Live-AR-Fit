package com.debasish.livefit.model

/** A Data Layer node offering a capability (id, display name, nearby = directly connected over Bluetooth). */
data class NodeCandidate(val id: String, val displayName: String, val isNearby: Boolean)

/** Best-first: nearby nodes first, then stable order by id. Empty in, empty out (callers fall back to connected nodes). */
fun chooseNodes(nodes: List<NodeCandidate>): List<NodeCandidate> =
    nodes.sortedWith(compareByDescending<NodeCandidate> { it.isNearby }.thenBy { it.id })
