package com.debasish.livefit.phone.map

import com.debasish.livefit.map.TileId
import com.debasish.livefit.map.TileLoader
import com.debasish.livefit.model.FixSource
import com.debasish.livefit.model.GpsStatus
import com.debasish.livefit.model.HudPage
import com.debasish.livefit.model.LivePosition
import com.debasish.livefit.model.MapFrameKind
import com.debasish.livefit.model.RouteState
import com.debasish.livefit.services.Clock
import com.debasish.livefit.sync.GlassesMapStreamer
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

@OptIn(ExperimentalCoroutinesApi::class)
class GlassesMapPlanTest {
    private val live = LivePosition(12.9716, 77.5946, null, FixSource.Watch, 0)

    /**
     * Review #5: every tile response hangs. Images still go out on the 3 s cadence with route, marker and captions
     * ("GPS delayed", "No map — route only"); once the tiles are released they appear in the next image, no restart.
     */
    @Test fun blockedTilesNeverHoldBackTheImage() = runTest {
        val gate = CompletableDeferred<Unit>()
        val loader = TileLoader(backgroundScope, load = { t: TileId -> gate.await(); "tile ${t.x}/${t.y}" }, maxConcurrent = 4)
        val plans = mutableListOf<GlassesMapPlan<String>>()
        var images = 0
        val streamer = GlassesMapStreamer(
            backgroundScope, Clock { testScheduler.currentTime },
            render = { s -> GlassesMapPlan.request(s, 480, loader, loader.generation).also { plans += it }; byteArrayOf(1) },
            send = { f, _ -> if (f.kind == MapFrameKind.Image) images++; true },
            newEpoch = { 1L },
        )
        streamer.start()
        streamer.onRoute(RouteState(sessionId = "s", live = live, status = GpsStatus.Delayed))
        streamer.onConnected()
        streamer.onPageState(HudPage.Map, 1)
        advanceTimeBy(6_500); runCurrent()
        assertEquals(3, images, "t = 0, 3, 6 s although no tile has arrived")
        assertTrue(plans.all { it.drawn.isEmpty() && it.scene.arrow != null }, "marker drawn without tiles")
        assertEquals(listOf("GPS delayed", "No map — route only"), plans.last().captions)
        gate.complete(Unit); runCurrent()
        advanceTimeBy(3_000); runCurrent()
        assertEquals(4, images)
        assertEquals(plans.last().wanted.size, plans.last().drawn.size, "every visible tile drawn once loaded")
        assertEquals(listOf("GPS delayed"), plans.last().captions)
    }

    /** Review r2 #1: a render suspended before it asks for tiles; the wearer leaves Map; the resumed render must not re-show them. */
    @Test fun aRenderResumedAfterLeavingMapCannotReShowTiles() = runTest {
        var loads = 0
        val loader = TileLoader(backgroundScope, load = { _: TileId -> loads++; "t" })
        val paused = CompletableDeferred<Unit>()
        val streamer = GlassesMapStreamer(
            backgroundScope, Clock { testScheduler.currentTime },
            // Same order as GlassesMapRenderer.render: capture the generation, suspend (Dispatchers.Default), then request.
            render = { s -> val gen = loader.generation; paused.await(); GlassesMapPlan.request(s, 480, loader, gen); byteArrayOf(1) },
            send = { _, _ -> true },
            newEpoch = { 1L },
        )
        streamer.start()
        streamer.onRoute(RouteState(sessionId = "s", live = live, status = GpsStatus.Live))
        streamer.onConnected()
        streamer.onPageState(HudPage.Map, 1)
        runCurrent() // the first render is now suspended
        streamer.onPageState(HudPage.Workout, 2); loader.hide() // what ServiceGraph's page-state collector does on Main
        paused.complete(Unit); runCurrent()
        assertTrue(loader.visibleTiles.isEmpty(), "the obsolete render did not reinstate the tiles")
        assertEquals(0, loads)
    }

    @Test fun planOnlyUsesLoadedTilesOfTheViewport() {
        val state = RouteState(sessionId = "s", live = live, status = GpsStatus.Live)
        val first = GlassesMapPlan.of(state, 480, emptyMap<TileId, String>())
        assertTrue(first.wanted.size in 4..9)
        val one = first.wanted.first()
        val p = GlassesMapPlan.of(state, 480, mapOf(one to "a", TileId(3, 0, 0) to "elsewhere"))
        assertEquals(listOf(one to "a"), p.drawn.map { it.first.tile to it.second })
        assertEquals(emptyList(), p.captions)
    }
}
