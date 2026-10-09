package com.debasish.livefit.watch

import com.debasish.livefit.map.TileId
import com.debasish.livefit.model.GpsStatus
import com.debasish.livefit.model.HudPage
import com.debasish.livefit.model.LocationFix
import com.debasish.livefit.model.PageSettings
import com.debasish.livefit.model.WorkoutPhase
import com.debasish.livefit.model.WorkoutSnapshot
import com.debasish.livefit.model.WorkoutType
import com.debasish.livefit.sync.WatchRouteFile.SessionRoute
import com.debasish.livefit.watch.map.WatchMapModel
import com.debasish.livefit.watch.map.WatchMapTracker
import com.debasish.livefit.watch.map.WatchTilePolicy
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

@OptIn(ExperimentalCoroutinesApi::class)
class WatchPagesTest {
    private fun fix(t: Long, northM: Double, acc: Float? = 5f) = LocationFix(12.9716 + northM / 111_195.0, 77.5946, acc, null, t)
    private val gpsRun = WorkoutSnapshot(phase = WorkoutPhase.Active, type = WorkoutType.Run, sessionId = "s", gps = true)

    /** Feeds route.bin growth to the tracker the way WatchClient does: each emission at its arrival time. */
    private class Feed {
        val tracker = WatchMapTracker()
        val fixes = mutableListOf<LocationFix>()
        init { tracker.onRoute(SessionRoute("s", emptyList()), 0) } // session opened (WatchRouteFile.open)
        fun arrive(nowMs: Long, vararg batch: LocationFix) { fixes += batch; tracker.onRoute(SessionRoute("s", fixes.toList()), nowMs) }
        fun state(nowMs: Long) = WatchMapModel.state(fixes, tracker.liveFor("s"), "s", WorkoutType.Walk, nowMs)
    }

    /** Spec §3.1: the same page set as the glasses; Glance and Playlist are new on the watch. */
    @Test fun pagesFollowSettingsAndMapEligibility() {
        assertEquals(HudPage.entries, WatchPageModel.pages(WatchUiState(snapshot = gpsRun)))
        val noGps = WatchPageModel.pages(WatchUiState(snapshot = gpsRun.copy(gps = false), pages = PageSettings(disabled = setOf(HudPage.Glance))))
        assertEquals(listOf(HudPage.Workout, HudPage.Stats, HudPage.Playlist, HudPage.MusicControls), noGps)
    }

    @Test fun aVanishedPageReopensOnWorkout() {
        val pages = listOf(HudPage.Glance, HudPage.Workout, HudPage.Playlist)
        assertEquals(1, WatchPageModel.initialIndex(pages, HudPage.Stats))
        assertEquals(2, WatchPageModel.initialIndex(pages, HudPage.Playlist))
    }

    /** Spec §2.6: the watch map draws its own fixes, start marker and live arrow. */
    @Test fun ownFixesBecomeTheRouteWithStartAndMarker() {
        val now = 100_000L
        val f = Feed()
        f.arrive(now - 3_000, fix(now - 3_000, 0.0)); f.arrive(now - 2_000, fix(now - 2_000, 10.0)); f.arrive(now - 1_000, fix(now - 1_000, 20.0))
        val s = f.state(now)
        assertEquals(3, s.route.size)
        assertEquals(s.route.first(), s.start)
        assertEquals(GpsStatus.Live, s.status)
        assertEquals(0f, assertNotNull(assertNotNull(s.live).bearingDeg), 0.5f, "bearing from the last two points")
    }

    @Test fun staleOwnFixIsDelayedThenLost() {
        val f = Feed()
        f.arrive(0, fix(0, 0.0))
        assertEquals(GpsStatus.Delayed, f.state(20_000).status)
        val lost = f.state(31_000)
        assertEquals(GpsStatus.Lost, lost.status)
        assertNotNull(lost.live, "last point kept")
    }

    /** Review #7: live A, then an aged batch B → the route grows, the marker stays on A and the status degrades; live C moves it. */
    @Test fun agedBatchGrowsTheRouteButNeverMovesTheMarker() {
        val f = Feed()
        f.arrive(1_000, fix(1_000, 0.0))                                                   // A, live on arrival
        f.arrive(25_000, fix(2_000, 10.0), fix(3_000, 20.0), fix(4_000, 30.0))             // B, measured 21–23 s ago
        val s = f.state(25_000)
        assertEquals(4, s.route.size, "B is drawn")
        assertEquals(1_000L, assertNotNull(s.live).fixTimeMs, "the marker stays on A")
        assertEquals(GpsStatus.Delayed, s.status, "from A's age, not B's")
        f.arrive(26_000, fix(26_000, 40.0))                                                 // C, live
        assertEquals(26_000L, f.state(26_000).live?.fixTimeMs)
        assertEquals(GpsStatus.Live, f.state(26_000).status)
    }

    /** Review #7: a route reloaded from route.bin after process death is drawn but has no marker until a live fix arrives. */
    @Test fun restoredRouteHasNoMarkerUntilALiveFix() {
        val t = WatchMapTracker()
        val cached = listOf(fix(1_000, 0.0), fix(2_000, 10.0))
        t.onRoute(SessionRoute("s", cached), 2_500) // process restarted: route.bin reopened
        assertNull(t.liveFor("s"))
        val s = WatchMapModel.state(cached, t.liveFor("s"), "s", WorkoutType.Walk, 2_500)
        assertEquals(2, s.route.size)
        assertEquals(GpsStatus.Waiting, s.status)
        t.onRoute(SessionRoute("s", cached + fix(3_000, 20.0)), 3_000)
        assertEquals(3_000L, t.liveFor("s")?.fixTimeMs)
        assertNull(t.liveFor("other"), "another session never shows this marker")
    }

    @Test fun inaccurateFixesNeverBecomeTheMarker() {
        val f = Feed()
        f.arrive(1_000, fix(1_000, 0.0, acc = 80f))
        val s = f.state(1_000)
        assertNull(s.live)
        assertTrue(s.route.isEmpty())
        assertEquals(GpsStatus.Waiting, s.status)
    }

    /** Review #8: fresh watch fixes without accuracy neither draw nor move the marker. */
    @Test fun unknownAccuracyFixesNeverBecomeTheMarker() {
        val f = Feed()
        f.arrive(1_000, fix(1_000, 0.0, acc = null))
        f.arrive(2_000, fix(2_000, 10.0, acc = null))
        val s = f.state(2_000)
        assertNull(s.live)
        assertTrue(s.route.isEmpty())
        assertEquals(GpsStatus.Waiting, s.status)
    }

    /** Review #6: the Map page's viewport does not move; the first fetch fails, the network returns, tiles appear. */
    @Test fun fixedViewportGetsItsTilesOnceTheNetworkReturns() = runTest {
        var online = false
        val loader = WatchTilePolicy.loader(backgroundScope) { t: TileId -> if (online) "tile ${t.x}" else null }
        loader.start()
        val visible = listOf(TileId(18, 5, 5), TileId(18, 6, 5))
        loader.show(visible); runCurrent()
        assertTrue(loader.tiles.value.isEmpty())
        online = true
        advanceTimeBy(WatchTilePolicy.RETRY_MS + 1); runCurrent()
        assertEquals(visible.toSet(), loader.tiles.value.keys, "no navigation or zoom needed")
    }

    /** Spec §2.6: bezel zoom 14–18. */
    @Test fun bezelZoomIsClamped() {
        assertEquals(18, WatchMapModel.zoomStep(18, 12f))
        assertEquals(17, WatchMapModel.zoomStep(18, -12f))
        assertEquals(14, WatchMapModel.zoomStep(14, -3f))
        assertEquals(15, WatchMapModel.zoomStep(14, 3f))
        assertEquals(16, WatchMapModel.zoomStep(16, 0f))
    }
}
