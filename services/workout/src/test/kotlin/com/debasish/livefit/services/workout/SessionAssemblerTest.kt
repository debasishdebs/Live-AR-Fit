package com.debasish.livefit.services.workout

import com.debasish.livefit.model.EndReason
import com.debasish.livefit.model.Provenance
import com.debasish.livefit.model.Sample
import com.debasish.livefit.model.SessionDelta
import com.debasish.livefit.model.SessionEvent
import com.debasish.livefit.model.SessionStatus
import com.debasish.livefit.model.WorkoutPhase
import com.debasish.livefit.model.WorkoutType
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class SessionAssemblerTest {
    private val live = Provenance.Live("galaxy-watch/health-services")
    private fun delta(seq: Long, events: List<SessionEvent> = emptyList(), samples: List<Sample> = emptyList(), final: Boolean = false, provenance: Provenance = live) =
        SessionDelta(sessionId = "s", seq = seq, events = events, samples = samples, provenance = provenance, final = final)

    @Test fun phaseFollowsEvents() {
        val a = SessionAssembler("s")
        assertEquals(WorkoutPhase.Starting, a.phase())
        a.add(delta(0, listOf(SessionEvent.Started(1_000, WorkoutType.Run))))
        assertEquals(WorkoutPhase.Active, a.phase())
        a.add(delta(1, listOf(SessionEvent.Paused(5_000))))
        assertEquals(WorkoutPhase.Paused, a.phase())
        a.add(delta(2, listOf(SessionEvent.Stopped(6_000, EndReason.User)), final = true))
        assertEquals(WorkoutPhase.Stopping, a.phase())
    }

    @Test fun activeTimeExcludesPauses() {
        val a = SessionAssembler("s")
        a.add(delta(0, listOf(SessionEvent.Started(0, WorkoutType.Walk))))
        a.add(delta(1, listOf(SessionEvent.Paused(10_000))))
        a.add(delta(2, listOf(SessionEvent.Resumed(40_000))))
        a.add(delta(3, listOf(SessionEvent.Stopped(45_000, EndReason.User))))
        assertEquals(15_000, a.activeMs())
    }

    /** Review Focus #2: watch timestamps only — the phone clock is never involved. */
    @Test fun activeTimeUsesEventTimestampsOnly() {
        val watchEpoch = 1_000_000_000L // watch clock far from the phone's
        val a = SessionAssembler("s")
        a.add(delta(0, listOf(SessionEvent.Started(watchEpoch, WorkoutType.Walk)), samples = listOf(Sample(watchEpoch))))
        a.add(delta(1, samples = listOf(Sample(watchEpoch + 30_000, hr = 100))))
        // still running: active time runs to the latest watch timestamp seen
        assertEquals(30_000, a.activeMs())
        assertEquals(30_000, a.snapshot().elapsedMs)
    }

    /** B1: the hub ticks the timer itself; [SessionAssembler.snapshot] takes the estimated watch-clock "now". */
    @Test fun snapshotAtRunsTheActiveSegmentToTheGivenWatchTime() {
        val a = SessionAssembler("s")
        a.add(delta(0, listOf(SessionEvent.Started(1_000, WorkoutType.Walk)), samples = listOf(Sample(3_000, hr = 100))))
        assertEquals(2_000, a.snapshot().elapsedMs)
        assertEquals(9_000, a.snapshot(atMs = 10_000).elapsedMs)
        assertEquals(2_000, a.snapshot(atMs = 2_500).elapsedMs, "never behind the newest recorded timestamp")
        a.add(delta(1, listOf(SessionEvent.Paused(12_000))))
        assertEquals(11_000, a.snapshot(atMs = 60_000).elapsedMs, "a paused session doesn't tick")
    }

    @Test fun duplicatesAreIgnoredAndOrderDoesNotMatter() {
        val a = SessionAssembler("s")
        assertTrue(a.add(delta(1, samples = listOf(Sample(2_000, hr = 110, stepsTotal = 5)))))
        assertTrue(a.add(delta(0, listOf(SessionEvent.Started(0, WorkoutType.Walk)), samples = listOf(Sample(1_000, hr = 100)))))
        assertFalse(a.add(delta(1)))
        assertEquals(1, a.contiguousSeq)
        assertEquals(110, a.snapshot().metrics.heartRate)
        assertEquals(5, a.snapshot().metrics.steps)
    }

    @Test fun completeOnlyWhenEverySeqThroughFinalIsPresent() {
        val a = SessionAssembler("s")
        a.add(delta(0, listOf(SessionEvent.Started(0, WorkoutType.Walk))))
        a.add(delta(2, listOf(SessionEvent.Stopped(3_000, EndReason.User)), final = true))
        assertEquals(2L, a.finalSeq)
        assertFalse(a.isComplete, "seq 1 is missing")
        a.add(delta(1))
        assertTrue(a.isComplete)
    }

    @Test fun totalsAndHeartRateStats() {
        val a = SessionAssembler("s")
        a.add(delta(0, listOf(SessionEvent.Started(0, WorkoutType.Run)), samples = listOf(
            Sample(1_000, hr = 100, stepsTotal = 10, distanceKmTotal = 0.01, kcalTotal = 1.0, speedKmh = 8.0),
            Sample(2_000, hr = 140, stepsTotal = 20, distanceKmTotal = 0.02, kcalTotal = 2.4, speedKmh = 9.0),
        )))
        val s = a.summary(SessionStatus.Active)
        assertEquals(120, s.avgHr)
        assertEquals(140, s.maxHr)
        assertEquals(20, s.steps)
        assertEquals(2, s.kcal)
        assertEquals(listOf(100, 140), a.hrHistory())
    }

    /** Codex plan round 2: a dipping total (e.g. watch reattach) must not lower history totals — same rule as the HC export. */
    @Test fun totalsNeverDropEvenWhenTheSessionEndsDuringTheDip() {
        val a = SessionAssembler("s")
        a.add(delta(0, listOf(SessionEvent.Started(0, WorkoutType.Walk)), samples = listOf(Sample(60_000, stepsTotal = 100, distanceKmTotal = 0.08, kcalTotal = 5.0))))
        a.add(delta(1, listOf(SessionEvent.Stopped(120_000, EndReason.User)), samples = listOf(Sample(120_000, stepsTotal = 50, distanceKmTotal = 0.04, kcalTotal = 2.0)), final = true))
        val s = a.summary(SessionStatus.Complete)
        assertEquals(100, s.steps)
        assertEquals(0.08, s.distanceKm, 1e-9)
        assertEquals(5, s.kcal)
        assertEquals(100, a.snapshot().metrics.steps)
    }

    @Test fun checkpointStatsGiveFullSessionHeartRateAverageAndMax() {
        val a = SessionAssembler("s")
        // checkpoint: folded 200 samples, of which only the last 2 are kept as samples
        a.addCheckpoint(delta(5, listOf(SessionEvent.Started(0, WorkoutType.Run)), samples = listOf(Sample(1_000, hr = 100), Sample(2_000, hr = 100))),
            HrStats(sum = 200L * 120, count = 200, max = 180))
        a.add(delta(6, samples = listOf(Sample(3_000, hr = 130))))
        assertEquals((200 * 120 + 130) / 201, a.snapshot().avgHeartRate)
        assertEquals(180, a.snapshot().maxHeartRate)
        assertEquals(listOf(100, 100, 130), a.hrHistory())
    }

    @Test fun provenanceIsFakeIfAnyDeltaIsFake() {
        val a = SessionAssembler("s")
        a.add(delta(0, listOf(SessionEvent.Started(0, WorkoutType.Walk))))
        assertEquals(live, a.provenance())
        a.add(delta(1, provenance = Provenance.Fake))
        assertEquals(Provenance.Fake, a.provenance())
    }

    @Test fun detectedTypeAndEndReasonInSummary() {
        val a = SessionAssembler("s")
        a.add(delta(0, listOf(SessionEvent.Started(0, WorkoutType.Auto), SessionEvent.TypeDetected(5_000, WorkoutType.Run))))
        a.add(delta(1, listOf(SessionEvent.Stopped(9_000, EndReason.OtherApp)), final = true))
        val s = a.summary(SessionStatus.Complete)
        assertEquals(WorkoutType.Auto, s.type)
        assertEquals(WorkoutType.Run, s.detectedType)
        assertEquals(EndReason.OtherApp, s.endReason)
        assertEquals(0L, s.startMs)
        assertEquals(9_000L, s.endMs)
    }

    @Test fun snapshotCarriesLatestSampleTimeForLatencyMeasurement() {
        val a = SessionAssembler("s")
        a.add(delta(0, listOf(SessionEvent.Started(0, WorkoutType.Walk)), samples = listOf(Sample(1_000), Sample(2_500))))
        assertEquals(2_500L, a.snapshot().latestSampleMs)
    }
}
