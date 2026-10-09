package com.debasish.livefit.map

import com.debasish.livefit.model.RoutePoint

/** The whole route fitted into a box (history detail, spec §2.2); route only, no tiles. */
object RouteThumbnail {
    fun project(points: List<RoutePoint>, widthPx: Int, heightPx: Int, paddingPx: Int = 8): List<Px> {
        val vp = Viewport.fit(points.map { it.lat to it.lon }, widthPx, heightPx, paddingPx) ?: return emptyList()
        return MapSceneBuilder.decimate(points.map { vp.project(it.lat, it.lon) })
    }
}
