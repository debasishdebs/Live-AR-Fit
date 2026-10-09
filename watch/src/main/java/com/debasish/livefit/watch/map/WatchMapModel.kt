package com.debasish.livefit.watch.map

import com.debasish.livefit.map.TileId
import com.debasish.livefit.map.TileLoader
import com.debasish.livefit.map.Viewport
import com.debasish.livefit.model.FixSource
import com.debasish.livefit.model.LivePosition
import com.debasish.livefit.model.LocationFix
import com.debasish.livefit.model.RouteState
import com.debasish.livefit.model.RouteTrack
import com.debasish.livefit.model.WorkoutType
import com.debasish.livefit.model.toRouteFix
import com.debasish.livefit.sync.Freshness
import com.debasish.livefit.sync.WatchRouteFile
import kotlinx.coroutines.CoroutineScope

/**
 * The watch marker (spec §2.1/§2.6, review #7). Fed every route.bin emission at its arrival time: only a fix that is
 * usable-live when it arrives ([Freshness.isUsableLive] on the watch's own clock — the phone's predicate) moves it; aged
 * batches still grow the route. A route restored after process death (or first seen mid-session) has no marker until
 * the next live fix.
 */
class WatchMapTracker {
    private var sessionId: String? = null
    private var seen = 0

    var live: LivePosition? = null
        private set

    fun onRoute(route: WatchRouteFile.SessionRoute?, nowMs: Long) {
        if (route == null || route.sessionId != sessionId || route.fixes.size < seen) {
            sessionId = route?.sessionId
            seen = route?.fixes?.size ?: 0
            live = null
            return
        }
        for (f in route.fixes.subList(seen, route.fixes.size)) {
            if (Freshness.isUsableLive(f.fixTimeMs, f.accuracyM, nowMs) && f.fixTimeMs > (live?.fixTimeMs ?: Long.MIN_VALUE)) {
                live = LivePosition(f.lat, f.lon, f.bearingDeg, FixSource.Watch, f.fixTimeMs)
            }
        }
        seen = route.fixes.size
    }

    fun liveFor(sessionId: String?): LivePosition? = live?.takeIf { sessionId != null && sessionId == this.sessionId }
}

/** The watch map from the watch's own fixes only (spec §2.6); its clock is its own, so no offset. */
object WatchMapModel {
    fun state(fixes: List<LocationFix>, live: LivePosition?, sessionId: String?, type: WorkoutType, nowMs: Long): RouteState {
        // Watch fix times are capped at their arrival (Task 11), so receipt = fix time on the watch's own clock.
        val track = RouteTrack.of(fixes.mapNotNull { it.toRouteFix(FixSource.Watch, phoneTimeMs = it.fixTimeMs, receivedAtMs = it.fixTimeMs)?.point() }, nowMs)
        val drawn = track.drawn()
        val marker = live?.let { it.copy(bearingDeg = it.bearingDeg ?: track.lastBearing()) }
        return RouteState(sessionId, type, drawn, drawn.firstOrNull(), marker, Freshness.status(live?.fixTimeMs, nowMs))
    }

    /** The on-screen +/− buttons (watches without a bezel): same 14–18 range. */
    fun zoomBy(zoom: Int, delta: Int): Int = (zoom + delta).coerceIn(Viewport.MIN_ZOOM, Viewport.MAX_ZOOM)

    /** One bezel detent = one zoom level, 14–18; the map always re-centres on the current position. */
    fun zoomStep(zoom: Int, scrollPixels: Float): Int =
        (zoom + when { scrollPixels > 0 -> 1; scrollPixels < 0 -> -1; else -> 0 }).coerceIn(Viewport.MIN_ZOOM, Viewport.MAX_ZOOM)
}

/** The watch's tile loading policy (review #6): 3 at a time, missing visible tiles retried every 5 s, 30 kept. */
object WatchTilePolicy {
    const val RETRY_MS = 5_000L
    const val MAX_TILES = 30
    const val MAX_CONCURRENT = 3

    fun <T : Any> loader(scope: CoroutineScope, load: suspend (TileId) -> T?): TileLoader<T> =
        TileLoader(scope, load, maxConcurrent = MAX_CONCURRENT, retryEveryMs = RETRY_MS, maxCached = MAX_TILES)
}
