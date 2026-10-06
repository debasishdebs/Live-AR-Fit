package com.debasish.livefit.sync

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals

/** F6: a slow or hung watch push must not hold back the glasses' frames (e.g. a pending confirmation). */
@OptIn(ExperimentalCoroutinesApi::class)
class LinkSenderTest {
    @Test fun aHungLinkDoesNotDelayTheOther() = runTest {
        val watchGate = CompletableDeferred<Unit>()
        val glasses = mutableListOf<Int>()
        val watch = mutableListOf<Int>()
        val g = LinkSender<Int>(backgroundScope, "glasses") { glasses += it }
        val w = LinkSender<Int>(backgroundScope, "watch", timeoutMs = 60_000) { watch += it; watchGate.await() }
        for (f in 1..3) { g.offer(f); w.offer(f); runCurrent() }
        assertEquals(listOf(1, 2, 3), glasses, "every glasses frame went out while the watch push hangs")
        assertEquals(listOf(1), watch)
        watchGate.complete(Unit); runCurrent()
        assertEquals(listOf(1, 3), watch, "the hung link then gets only the latest frame")
    }

    @Test fun aPushThatNeverCompletesTimesOutAndSendingContinues() = runTest {
        val sent = mutableListOf<Int>()
        val errors = mutableListOf<String>()
        val s = LinkSender<Int>(backgroundScope, "watch", timeoutMs = 2_000, onError = { name, _ -> errors += name }) {
            sent += it; if (it == 1) CompletableDeferred<Unit>().await()
        }
        s.offer(1); runCurrent(); s.offer(2); runCurrent()
        assertEquals(listOf(1), sent)
        advanceTimeBy(2_001); runCurrent()
        assertEquals(listOf(1, 2), sent)
        assertEquals(listOf("watch"), errors)
    }

    @Test fun aFailingPushDoesNotStopTheSender() = runTest {
        val sent = mutableListOf<Int>()
        val s = LinkSender<Int>(backgroundScope, "glasses") { sent += it; if (it == 1) error("boom") }
        s.offer(1); runCurrent(); s.offer(2); runCurrent()
        assertEquals(listOf(1, 2), sent)
    }
}
