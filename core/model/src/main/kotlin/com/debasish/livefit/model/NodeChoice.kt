package com.debasish.livefit.model

/** A Wear Data Layer node that advertises a LiveFit capability (livefit_watch / livefit_phone). */
data class NodeCandidate(val id: String, val displayName: String, val isNearby: Boolean)

const val CAPABILITY_WATCH = "livefit_watch"
const val CAPABILITY_PHONE = "livefit_phone"

/** Best-first: nearby nodes first, then by id (stable); duplicate ids collapse to the nearby entry. An empty input stays empty (caller falls back to connectedNodes). */
fun chooseNodes(nodes: List<NodeCandidate>): List<NodeCandidate> =
    nodes.groupBy { it.id }.map { (_, g) -> g.firstOrNull { it.isNearby } ?: g.first() }
        .sortedWith(compareByDescending<NodeCandidate> { it.isNearby }.thenBy { it.id })
