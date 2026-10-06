package com.debasish.livefit.phone

import com.debasish.livefit.model.Metrics
import com.debasish.livefit.model.WorkoutPhase
import com.debasish.livefit.model.WorkoutSnapshot
import com.debasish.livefit.model.WorkoutType
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals

/** F7: the ongoing hub notification is re-posted only when its text changes, at most once per second. */
@OptIn(ExperimentalCoroutinesApi::class)
class HubNotificationTest {
    private fun active(ms: Long, hr: Int?) = WorkoutSnapshot(phase = WorkoutPhase.Active, type = WorkoutType.Run, elapsedMs = ms, metrics = Metrics(heartRate = hr))

    @Test fun textShowsWhatTheUserSees() {
        assertEquals("LiveFit ready", HubNotification.text(WorkoutSnapshot()))
        assertEquals("Run · 01:05 · ♥ 142", HubNotification.text(active(65_400, 142)))
        assertEquals("Run · 01:05", HubNotification.text(active(65_400, null)))
        assertEquals("Saving workout…", HubNotification.text(WorkoutSnapshot(phase = WorkoutPhase.Stopping)))
    }

    @Test fun manySnapshotUpdatesPostAtMostOncePerSecondAndOnlyOnChange() = runTest {
        val snapshots = MutableStateFlow(WorkoutSnapshot())
        val posted = mutableListOf<Pair<Long, String>>()
        backgroundScope.launch { HubNotification.postChanges(snapshots, initial = "LiveFit ready") { posted += testScheduler.currentTime to it } }
        runCurrent()
        assertEquals(emptyList(), posted, "startForeground already shows the initial text")
        // 5 metric updates per second for 3 s, heart rate unchanged within each second.
        for (tick in 0 until 15) {
            snapshots.value = active(1_000L * (tick / 5), 140 + tick / 5).copy(metrics = Metrics(heartRate = 140 + tick / 5, steps = tick))
            advanceTimeBy(200); runCurrent()
        }
        assertEquals(listOf("Run · 00:00 · ♥ 140", "Run · 00:01 · ♥ 141", "Run · 00:02 · ♥ 142"), posted.map { it.second })
        posted.zipWithNext().forEach { (a, b) -> kotlin.test.assertTrue(b.first - a.first >= 1_000, "throttled: $posted") }
    }

    @Test fun burstWithinASecondPostsOnlyTheLatestText() = runTest {
        val snapshots = MutableStateFlow(WorkoutSnapshot())
        val posted = mutableListOf<String>()
        backgroundScope.launch { HubNotification.postChanges(snapshots, initial = "LiveFit ready") { posted += it } }
        snapshots.value = active(0, 120); runCurrent()
        snapshots.value = active(0, 121); advanceTimeBy(100); runCurrent()
        snapshots.value = active(0, 122); advanceTimeBy(100); runCurrent()
        snapshots.value = active(0, 120); advanceTimeBy(100); runCurrent() // back to what is already shown
        advanceTimeBy(1_000); runCurrent()
        assertEquals(listOf("Run · 00:00 · ♥ 120"), posted)
    }
}
