package com.debasish.livefit.watch

import com.debasish.livefit.model.NodeCandidate
import com.debasish.livefit.model.chooseNodes

/** Where the watch sends to the phone: `livefit_phone` nodes (nearby first); an older phone build without it → every connected node. */
object PhoneNodes {
    const val CAPABILITY = "livefit_phone"

    fun targets(capabilityNodes: List<NodeCandidate>, connectedIds: List<String>): List<String> =
        chooseNodes(capabilityNodes).map { it.id }.ifEmpty { connectedIds }
}
