package com.debasish.livefit.sync

import com.debasish.livefit.model.FixSource
import com.debasish.livefit.model.GpsStatus
import com.debasish.livefit.model.HudPage
import com.debasish.livefit.model.LivePosition
import com.debasish.livefit.model.MapFrame
import com.debasish.livefit.model.MapFrameKind
import com.debasish.livefit.model.RouteState
import com.debasish.livefit.services.Clock
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

@OptIn(ExperimentalCoroutinesApi::class)
class MapStreamTest {
    private class Rig(scope: TestScope, render: suspend (RouteState) -> ByteArray? = { byteArrayOf(1, 2, 3) }) {
        val sent = mutableListOf<Pair<MapFrame, ByteArray?>>()
        var renders = 0
        var sendOk = true
        private var e = 0L
        val streamer = GlassesMapStreamer(
            scope.backgroundScope, Clock { scope.testScheduler.currentTime },
            render = { s -> renders++; render(s) },
            send = { f, png -> if (sendOk || f.kind == MapFrameKind.Epoch) { sent += f to png; true } else false },
            newEpoch = { ++e },
        )
        val images get() = sent.filter { it.first.kind == MapFrameKind.Image }.map { it.first }
        val epochs get() = sent.filter { it.first.kind == MapFrameKind.Epoch }.map { it.first.renderEpoch }
    }

    private fun at(northM: Double) = RouteState(
        sessionId = "s", live = LivePosition(12.9716 + northM / 111_195.0, 77.5946, null, FixSource.Watch, 0), status = GpsStatus.Live,
    )

    private fun TestScope.onMap(r: Rig, seq: Long = 1) {
        r.streamer.start(); r.streamer.onRoute(at(0.0)); r.streamer.onConnected(); r.streamer.onPageState(HudPage.Map, seq)
    }

    @Test fun announcesTheEpochBeforeAnyImage() = runTest {
        val r = Rig(this); onMap(r)
        advanceTimeBy(300); runCurrent()
        assertEquals(MapFrameKind.Epoch, r.sent.first().first.kind)
        assertNull(r.sent.first().second, "the epoch header carries no image")
        val img = r.images.single()
        assertEquals(r.sent.first().first.renderEpoch, img.renderEpoch)
        assertEquals(1L, img.renderSeq)
        assertEquals("s", img.sessionId)
    }

    /** Spec §2.5: when not on the Map page nothing image-related is sent. */
    @Test fun nothingImageRelatedWhenNotOnTheMapPage() = runTest {
        val r = Rig(this)
        r.streamer.start(); r.streamer.onRoute(at(0.0)); r.streamer.onConnected(); r.streamer.onPageState(HudPage.Workout, 1)
        advanceTimeBy(10_000); runCurrent()
        assertTrue(r.images.isEmpty()); assertEquals(0, r.renders)
        assertEquals(1, r.epochs.size)
    }

    /** Review Focus #5: leaving the Map page (page change or Map no longer eligible) stops the images. */
    @Test fun leavingMapStopsImages() = runTest {
        val r = Rig(this); onMap(r)
        advanceTimeBy(1_000); runCurrent()
        r.streamer.onPageState(HudPage.Workout, 2)
        advanceTimeBy(10_000); runCurrent()
        assertEquals(1, r.images.size)
        assertFalse(r.streamer.mapVisible)
    }

    @Test fun imagesFollowTheThreeSecondCadence() = runTest {
        val r = Rig(this); onMap(r)
        advanceTimeBy(10_000); runCurrent()
        assertEquals(listOf(1L, 2L, 3L, 4L), r.images.map { it.renderSeq }, "t = 0, 3, 6, 9 s")
    }

    @Test fun movingTwentyFiveMetresRendersEarly() = runTest {
        val r = Rig(this); onMap(r)
        advanceTimeBy(1_400); runCurrent()
        r.streamer.onRoute(at(30.0))
        advanceTimeBy(200); runCurrent()
        assertEquals(2, r.images.size)
    }

    @Test fun newEpochOnEveryReconnectAndTheSequenceRestarts() = runTest {
        val r = Rig(this); onMap(r)
        advanceTimeBy(300); runCurrent()
        r.streamer.onDisconnected(); r.streamer.onConnected(); r.streamer.onPageState(HudPage.Map, 1)
        advanceTimeBy(300); runCurrent()
        assertEquals(listOf(2L, 3L), r.epochs, "epoch 1 was the process-start epoch, never announced")
        assertEquals(listOf(2L to 1L, 3L to 1L), r.images.map { it.renderEpoch to it.renderSeq })
    }

    /** Review Focus #3: the link drops and returns while the glasses stay on Map; images resume once they re-report. */
    @Test fun reconnectWithoutPageChangeResumesAfterPageReport() = runTest {
        val r = Rig(this); onMap(r, seq = 4)
        advanceTimeBy(300); runCurrent()
        r.streamer.onDisconnected(); r.streamer.onConnected()
        advanceTimeBy(5_000); runCurrent()
        assertEquals(1, r.images.size, "visibility is cleared on disconnect and not assumed after reconnect")
        r.streamer.onPageState(HudPage.Map, 5) // the glasses answer the new epoch announcement with their page
        advanceTimeBy(300); runCurrent()
        assertEquals(r.epochs.last(), r.images.last().renderEpoch)
        assertEquals(2, r.images.size)
    }

    @Test fun olderPageStateIsIgnored() = runTest {
        val r = Rig(this); onMap(r, seq = 5)
        r.streamer.onPageState(HudPage.Workout, 4)
        assertTrue(r.streamer.mapVisible)
    }

    /** Spec §7: a failed image send is retried by the next cadence and never blocks anything else. */
    @Test fun failedSendRetriesOnTheNextCadence() = runTest {
        val r = Rig(this); r.sendOk = false; onMap(r)
        advanceTimeBy(1_000); runCurrent()
        assertTrue(r.images.isEmpty())
        r.sendOk = true
        advanceTimeBy(2_500); runCurrent()
        assertEquals(listOf(2L), r.images.map { it.renderSeq })
    }

    @Test fun renderFailureDoesNotStopTheLoop() = runTest {
        var n = 0
        val r = Rig(this, render = { if (n++ == 0) error("tiles down") else byteArrayOf(9) }); onMap(r)
        advanceTimeBy(3_500); runCurrent()
        assertEquals(1, r.images.size)
    }

    @Test fun noSessionNoImages() = runTest {
        val r = Rig(this)
        r.streamer.start(); r.streamer.onRoute(RouteState()); r.streamer.onConnected(); r.streamer.onPageState(HudPage.Map, 1)
        advanceTimeBy(5_000); runCurrent()
        assertTrue(r.images.isEmpty())
    }

    // ---- Glasses side ----

    private fun epoch(e: Long) = MapFrame(kind = MapFrameKind.Epoch, renderEpoch = e)
    private fun image(e: Long, seq: Long, session: String = "s") = MapFrame(kind = MapFrameKind.Image, renderEpoch = e, sessionId = session, renderSeq = seq)

    @Test fun acceptsOnlyTheNewestAnnouncedEpoch() {
        val g = MapImageGate()
        assertFalse(g.accept(epoch(10), "s"))
        assertTrue(g.accept(image(10, 1), "s"))
        g.accept(epoch(20), "s")
        assertFalse(g.accept(image(10, 2), "s"), "older epoch")
        assertTrue(g.accept(image(20, 1), "s"))
        assertEquals(20L, g.currentEpoch)
    }

    @Test fun rejectsOlderOrRepeatedSequenceNumbers() {
        val g = MapImageGate().apply { accept(epoch(1), "s") }
        assertTrue(g.accept(image(1, 3), "s"))
        assertFalse(g.accept(image(1, 3), "s"))
        assertFalse(g.accept(image(1, 2), "s"))
        assertTrue(g.accept(image(1, 4), "s"))
    }

    @Test fun rejectsAnotherSession() {
        val g = MapImageGate().apply { accept(epoch(1), "s") }
        assertFalse(g.accept(image(1, 1, session = "old"), "s"))
        assertFalse(g.accept(image(1, 1), null), "no workout on the glasses")
    }

    @Test fun imageBeforeAnyEpochIsRejected() = assertFalse(MapImageGate().accept(image(1, 1), "s"))

    /** Review Focus #3: a restarted phone's new epoch starts again at seq 1 although the old one reached 50. */
    @Test fun restartedPhoneNewEpochResetsSequence() {
        val g = MapImageGate().apply { accept(epoch(1), "s") }
        assertTrue(g.accept(image(1, 50), "s"))
        g.accept(epoch(-7), "s")
        assertTrue(g.accept(image(-7, 1), "s"))
        assertFalse(g.accept(image(1, 51), "s"), "a late image of the old epoch")
    }
}
