package com.debasish.livefit.map

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import java.io.IOException
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

@OptIn(ExperimentalCoroutinesApi::class)
class TileLoaderTest {
    private val a = TileId(18, 1, 1)
    private val b = TileId(18, 1, 2)

    /** Review #5: show() never waits for the network, and one slow tile never holds back another. */
    @Test fun showNeverBlocksAndTilesArriveIndependently() = runTest {
        val gates = mapOf(a to CompletableDeferred<String?>(), b to CompletableDeferred<String?>())
        val loader = TileLoader(backgroundScope, load = { t: TileId -> gates.getValue(t).await() })
        loader.show(listOf(a, b)); runCurrent()
        assertTrue(loader.tiles.value.isEmpty())
        gates.getValue(b).complete("B"); runCurrent()
        assertEquals(setOf(b), loader.tiles.value.keys, "b does not wait for a")
        gates.getValue(a).complete("A"); runCurrent()
        assertEquals(mapOf(a to "A", b to "B"), loader.tiles.value)
    }

    @Test fun atMostMaxConcurrentLoadsRunAtOnce() = runTest {
        var running = 0
        var peak = 0
        val release = CompletableDeferred<Unit>()
        val loader = TileLoader(backgroundScope, load = { _: TileId -> running++; peak = maxOf(peak, running); release.await(); running--; "x" }, maxConcurrent = 3)
        loader.show((0 until 9).map { TileId(18, it, 0) }); runCurrent()
        assertEquals(3, peak)
        release.complete(Unit); runCurrent()
        assertEquals(9, loader.tiles.value.size)
        assertEquals(3, peak)
    }

    /** Review #6: a fixed viewport whose tiles failed is retried while visible; nothing else has to change. */
    @Test fun missingVisibleTilesAreRetriedWithoutAViewportChange() = runTest {
        var online = false
        var calls = 0
        val loader = TileLoader(backgroundScope, load = { _: TileId -> calls++; if (online) "t" else null }, retryEveryMs = 5_000)
        loader.start()
        loader.show(listOf(a)); runCurrent()
        assertTrue(loader.tiles.value.isEmpty())
        assertEquals(1, calls)
        online = true
        advanceTimeBy(5_001); runCurrent()
        assertEquals("t", loader.tiles.value[a])
        val n = calls
        advanceTimeBy(20_000); runCurrent()
        assertEquals(n, calls, "a loaded tile is not loaded again")
    }

    @Test fun tilesThatLeftTheViewAreNotRetried() = runTest {
        var calls = 0
        val loader = TileLoader(backgroundScope, load = { _: TileId -> calls++; null as String? }, retryEveryMs = 5_000)
        loader.start()
        loader.show(listOf(a)); runCurrent()
        loader.show(emptyList())
        advanceTimeBy(30_000); runCurrent()
        assertEquals(1, calls)
    }

    /** A load that throws releases its slot and in-flight mark (finally), so the next retry runs. */
    @Test fun aThrowingLoadIsRetried() = runTest {
        var calls = 0
        val loader = TileLoader(backgroundScope, load = { _: TileId -> if (calls++ == 0) throw IOException("reset") else "ok" }, maxConcurrent = 1, retryEveryMs = 1_000)
        loader.start()
        loader.show(listOf(a)); runCurrent()
        advanceTimeBy(1_001); runCurrent()
        assertEquals("ok", loader.tiles.value[a])
    }

    /** Review r2 #1: loads queued behind a slow one never start once the map is closed. */
    @Test fun queuedLoadsDoNotStartAfterTheMapCloses() = runTest {
        val started = mutableListOf<TileId>()
        val release = CompletableDeferred<Unit>()
        val loader = TileLoader(backgroundScope, load = { t: TileId -> started += t; release.await(); "x" }, maxConcurrent = 1)
        val tiles = (0 until 9).map { TileId(18, it, 0) }
        loader.show(tiles); runCurrent()
        assertEquals(listOf(tiles[0]), started)
        loader.hide()
        release.complete(Unit); runCurrent()
        assertEquals(listOf(tiles[0]), started, "the 8 queued loads were dropped")
        assertTrue(loader.visibleTiles.isEmpty())
    }

    /** Review r2 #1: after several viewport changes the free slots go to the newest viewport only. */
    @Test fun theNewestViewportGetsTheSlots() = runTest {
        val started = mutableListOf<TileId>()
        val release = CompletableDeferred<Unit>()
        val loader = TileLoader(backgroundScope, load = { t: TileId -> started += t; release.await(); "x" }, maxConcurrent = 2)
        val first = (0 until 6).map { TileId(18, it, 0) }
        val second = (0 until 3).map { TileId(18, it, 1) }
        val newest = (0 until 3).map { TileId(18, it, 2) }
        loader.show(first); runCurrent()
        loader.show(second); runCurrent()
        loader.show(newest); runCurrent()
        assertEquals(first.take(2), started)
        release.complete(Unit); runCurrent()
        assertEquals(first.take(2) + newest, started, "queued tiles of the older viewports never load")
        assertTrue(loader.tiles.value.keys.containsAll(newest))
    }

    /** Review r2 #1: a show() from a render that captured the generation before hide() cannot make tiles visible again. */
    @Test fun aStaleGenerationCannotReinstateVisibility() = runTest {
        var calls = 0
        val loader = TileLoader(backgroundScope, load = { _: TileId -> calls++; "x" })
        val captured = loader.generation
        loader.hide()
        loader.show(listOf(TileId(18, 0, 0)), captured); runCurrent()
        assertTrue(loader.visibleTiles.isEmpty())
        assertEquals(0, calls)
        loader.show(listOf(TileId(18, 0, 0)), loader.generation); runCurrent()
        assertEquals(1, calls, "the current generation still works")
    }

    @Test fun visibleTilesAreNeverEvicted() = runTest {
        val loader = TileLoader(backgroundScope, load = { t: TileId -> "t${t.x}" }, maxCached = 2)
        loader.show(listOf(TileId(18, 0, 0))); runCurrent()
        loader.show(listOf(TileId(18, 1, 0))); runCurrent()
        loader.show(listOf(TileId(18, 2, 0), TileId(18, 3, 0), TileId(18, 4, 0))); runCurrent()
        assertEquals(setOf(2, 3, 4), loader.tiles.value.keys.map { it.x }.toSet(), "over the cap only invisible tiles go")
    }
}
