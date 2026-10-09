package com.debasish.livefit.glasses.hud

import com.debasish.livefit.model.FixSource
import com.debasish.livefit.model.GpsStatus
import com.debasish.livefit.model.HudPage
import com.debasish.livefit.model.LivePosition
import com.debasish.livefit.model.MapFrame
import com.debasish.livefit.model.MapFrameKind
import com.debasish.livefit.model.PageState
import com.debasish.livefit.model.RouteState
import com.debasish.livefit.model.Wire
import com.debasish.livefit.services.Clock
import com.debasish.livefit.sync.GlassesMapStreamer
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

@OptIn(ExperimentalCoroutinesApi::class)
class PageReportRetryTest {
    private class Link(scope: TestScope) {
        /** False = the CXR bridge refuses lf_page_state (sendMessage != 0). */
        var up = true
        val images = mutableListOf<MapFrame>()
        val streamer = GlassesMapStreamer(
            scope.backgroundScope, Clock { scope.testScheduler.currentTime },
            render = { byteArrayOf(1) },
            send = { f, _ -> if (f.kind == MapFrameKind.Image) images += f; true },
            newEpoch = { 7L },
        )
        val reporter = PageReporter { json -> if (!up) false else { Wire.decode<PageState>(json).let { streamer.onPageState(it.page, it.seq) }; true } }

        fun connect() {
            streamer.start()
            streamer.onRoute(RouteState(sessionId = "s", live = LivePosition(12.97, 77.59, null, FixSource.Watch, 0), status = GpsStatus.Live))
            streamer.onConnected()
        }
    }

    /** Review #10: the first Map report fails; the retry on the same connection makes the images begin. */
    @Test fun failedMapReportIsRetriedAndImagesBegin() = runTest {
        val l = Link(this)
        l.connect()
        l.reporter.onPage(HudPage.Workout)
        l.up = false
        l.reporter.onPage(HudPage.Map)
        advanceTimeBy(5_000); runCurrent()
        assertTrue(l.images.isEmpty(), "the phone never heard about Map")
        assertTrue(l.reporter.hasPending)
        l.up = true
        l.reporter.retryPending() // HudController calls this every second
        advanceTimeBy(500); runCurrent()
        assertEquals(1, l.images.size)
        assertFalse(l.reporter.hasPending)
    }

    /** Review #10: the report of leaving Map fails; the retry makes the images stop. */
    @Test fun failedLeaveReportIsRetriedAndImagesStop() = runTest {
        val l = Link(this)
        l.connect()
        l.reporter.onPage(HudPage.Map)
        advanceTimeBy(500); runCurrent()
        assertEquals(1, l.images.size)
        l.up = false
        l.reporter.onPage(HudPage.Workout)
        advanceTimeBy(3_500); runCurrent()
        assertEquals(2, l.images.size, "the phone still believes Map is visible")
        l.up = true
        l.reporter.retryPending()
        advanceTimeBy(10_000); runCurrent()
        assertEquals(2, l.images.size, "no images after the retried report")
    }
}
