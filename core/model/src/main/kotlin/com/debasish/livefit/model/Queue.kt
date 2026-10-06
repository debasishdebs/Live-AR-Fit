package com.debasish.livefit.model

import kotlinx.serialization.Serializable

/** One entry of YouTube Music's media-session queue ("Up next"); [queueId] is what `skipToQueueItem` takes. */
@Serializable
data class QueueItem(val queueId: Long, val title: String, val artist: String = "")

/**
 * The slice of the queue the glasses music screen lists (spec §5.5): history before the current item, the current item,
 * then upcoming items. [currentIndex] points into [items]; null when the session reports no (or an unknown) active item.
 */
@Serializable
data class QueueWindow(val items: List<QueueItem> = emptyList(), val currentIndex: Int? = null) {
    val current: QueueItem? get() = currentIndex?.let(items::getOrNull)
}

/** Phone → glasses on lf_queue: sent only when the window changes and on every (re)connect. */
@Serializable
data class QueueFrame(val protocolVersion: Int = PROTOCOL_VERSION, val window: QueueWindow)
