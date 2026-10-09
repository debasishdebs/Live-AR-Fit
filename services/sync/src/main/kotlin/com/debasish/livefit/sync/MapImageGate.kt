package com.debasish.livefit.sync

import com.debasish.livefit.model.MapFrame
import com.debasish.livefit.model.MapFrameKind

/**
 * Glasses side of lf_map (spec §2.5): accept the newest announced epoch, reset its last-seen renderSeq, and drop images
 * from any older epoch, another session, or an older/repeated renderSeq within the epoch.
 */
class MapImageGate {
    private var epoch: Long? = null
    private var lastSeq = 0L

    val currentEpoch: Long? get() = epoch

    /** True when [frame] is an image to show while the glasses' workout is [sessionId]. */
    @Synchronized
    fun accept(frame: MapFrame, sessionId: String?): Boolean = when (frame.kind) {
        MapFrameKind.Epoch -> { epoch = frame.renderEpoch; lastSeq = 0; false }
        MapFrameKind.Image -> {
            val ok = epoch == frame.renderEpoch && sessionId != null && frame.sessionId == sessionId && frame.renderSeq > lastSeq
            if (ok) lastSeq = frame.renderSeq
            ok
        }
    }
}
