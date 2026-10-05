package com.debasish.livefit.services.glasses

import com.debasish.livefit.sync.Backoff

sealed interface LinkEvent {
    data object DevicePresent : LinkEvent
    data object DeviceGone : LinkEvent
    data object Started : LinkEvent
    data object Paused : LinkEvent
    data object Resumed : LinkEvent
    data object Closed : LinkEvent
    data object ConnectFailed : LinkEvent
    data object RetryTimer : LinkEvent
}

sealed interface LinkAction {
    data object Connect : LinkAction
    data object SendSettings : LinkAction
    data class ScheduleRetry(val delayMs: Long) : LinkAction
    data object MarkConnected : LinkAction
    data object MarkConnecting : LinkAction
    data object MarkDisconnected : LinkAction
}

/** Pure session lifecycle rules for the CXR-L link (spec §5.3, Review Focus #3). */
class GlassesSessionPolicy(private val backoff: Backoff = Backoff()) {
    private enum class S { Idle, Connecting, Open, Paused, WaitingRetry }
    private var state = S.Idle
    private var present = false

    fun onEvent(e: LinkEvent): List<LinkAction> = when (e) {
        LinkEvent.DevicePresent -> {
            present = true
            if (state == S.Idle || state == S.WaitingRetry) { state = S.Connecting; listOf(LinkAction.MarkConnecting, LinkAction.Connect) } else emptyList()
        }
        LinkEvent.DeviceGone -> { present = false; state = S.Idle; listOf(LinkAction.MarkDisconnected) }
        LinkEvent.Started -> { state = S.Open; backoff.reset(); listOf(LinkAction.MarkConnected, LinkAction.SendSettings) }
        LinkEvent.Paused -> { state = S.Paused; listOf(LinkAction.MarkConnecting) }
        LinkEvent.Resumed -> { state = S.Open; listOf(LinkAction.MarkConnected, LinkAction.SendSettings) }
        LinkEvent.Closed, LinkEvent.ConnectFailed -> {
            if (present) { state = S.WaitingRetry; listOf(LinkAction.MarkDisconnected, LinkAction.ScheduleRetry(backoff.next())) }
            else { state = S.Idle; listOf(LinkAction.MarkDisconnected) }
        }
        LinkEvent.RetryTimer -> if (present && state == S.WaitingRetry) { state = S.Connecting; listOf(LinkAction.Connect) } else emptyList()
    }

    /** Manual connect (app opened / reconnect button) is allowed only when no session is in flight. */
    fun manualConnect(): List<LinkAction> {
        present = true
        return if (state == S.Idle || state == S.WaitingRetry) { state = S.Connecting; listOf(LinkAction.MarkConnecting, LinkAction.Connect) } else emptyList()
    }
}
