package com.debasish.livefit.history

import androidx.test.core.app.ApplicationProvider
import com.debasish.livefit.model.EndReason
import com.debasish.livefit.model.Provenance
import com.debasish.livefit.model.Sample
import com.debasish.livefit.model.SessionDelta
import com.debasish.livefit.model.SessionEvent
import com.debasish.livefit.model.SessionStatus
import com.debasish.livefit.model.SessionSummary
import com.debasish.livefit.model.WorkoutType
import com.debasish.livefit.services.SessionLifecycle
import com.debasish.livefit.services.StoredSessionState
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class RoomSessionStoreTest {
    private val live = Provenance.Live("galaxy-watch/health-services")
    private fun store() = RoomSessionStore(HistoryDatabase.create(ApplicationProvider.getApplicationContext(), inMemory = true))
    private fun d(seq: Long, samples: List<Sample> = emptyList(), events: List<SessionEvent> = emptyList()) =
        SessionDelta(sessionId = "s", seq = seq, events = events, samples = samples, provenance = live)

    @Test fun storeDeltaIsIdempotentAndReturnsContiguousSeq() = runTest {
        val s = store()
        assertEquals(0L, s.storeDelta(d(0, events = listOf(SessionEvent.Started(0, WorkoutType.Run)))))
        assertEquals(0L, s.storeDelta(d(2)))
        assertEquals(0L, s.storeDelta(d(2)))
        assertEquals(2L, s.storeDelta(d(1)))
        assertEquals(listOf(0L, 1L, 2L), s.deltas("s").map { it.seq })
        assertEquals(listOf("s"), s.openSessionIds())
    }

    @Test fun samplesAreExtractedForCharts() = runTest {
        val s = store()
        s.storeDelta(d(0, samples = listOf(Sample(1_000, hr = 100), Sample(2_000, hr = 110))))
        assertEquals(listOf(100, 110), s.samples("s").map { it.hr })
    }

    @Test fun finalizeMovesSessionToHistory() = runTest {
        val s = store()
        s.storeDelta(d(0))
        s.finalize(SessionSummary(id = "s", type = WorkoutType.Run, startMs = 0, endMs = 9_000, activeMs = 9_000, provenance = live,
            status = SessionStatus.Complete, endReason = EndReason.User))
        assertTrue(s.openSessionIds().isEmpty())
        assertEquals(SessionStatus.Complete, s.sessions.first().single().status)
    }

    @Test fun discardAndClearAll() = runTest {
        val s = store()
        s.storeDelta(d(0)); s.discard("s")
        assertTrue(s.deltas("s").isEmpty())
        assertTrue(s.openSessionIds().isEmpty())
        s.clearAll()
        assertTrue(s.sessions.first().isEmpty())
    }

    /** The hub's restart memory (Codex P1): tombstones, end time and reason survive in the database. */
    @Test fun lifecycleIsPersisted() = runTest {
        val s = store()
        assertEquals(null, s.lifecycle("s"))
        s.storeDelta(d(0, events = listOf(SessionEvent.Started(0, WorkoutType.Walk))))
        s.markEnded("s", null, 5_000)
        s.markEnded("s", EndReason.OtherApp, 9_000) // first time kept, missing reason filled
        assertEquals(SessionLifecycle(StoredSessionState.Open, EndReason.OtherApp, 5_000), s.lifecycle("s"))
        s.finalize(SessionSummary(id = "s", type = WorkoutType.Walk, startMs = 0, endMs = 5_000, activeMs = 5_000, provenance = live, status = SessionStatus.Complete))
        assertEquals(StoredSessionState.Finalized, s.lifecycle("s")!!.state)
        s.discard("never-started") // abandoned start: no deltas, still remembered
        assertEquals(StoredSessionState.Discarded, s.lifecycle("never-started")!!.state)
        assertTrue("never-started" !in s.openSessionIds())
    }

    @Test fun tombstoneSurvivesLateDataAndFinalize() = runTest {
        val s = store()
        s.discard("s")
        s.storeDelta(d(0)); s.markEnded("s", EndReason.User, 1_000)
        s.finalize(SessionSummary(id = "s", type = WorkoutType.Run, startMs = 0, endMs = 1, activeMs = 1, provenance = live, status = SessionStatus.Complete))
        assertEquals(StoredSessionState.Discarded, s.lifecycle("s")!!.state)
        assertTrue(s.openSessionIds().isEmpty())
        assertTrue(s.sessions.first().isEmpty())
    }

    @Test fun markEndedOnUnseenIdGivesOpenLifecycleButNoOpenSessionId() = runTest {
        val s = store()
        s.markEnded("e", EndReason.User, 7_000)
        assertEquals(SessionLifecycle(StoredSessionState.Open, EndReason.User, 7_000), s.lifecycle("e"))
        assertTrue(s.openSessionIds().isEmpty())
    }
}
