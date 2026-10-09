package com.debasish.livefit.phone.map

import com.debasish.livefit.map.MapSceneBuilder
import com.debasish.livefit.model.FixSource
import com.debasish.livefit.model.GpsStatus
import com.debasish.livefit.model.LivePosition
import com.debasish.livefit.model.RouteState
import kotlin.test.Test
import kotlin.test.assertEquals

class GlassesMapTextTest {
    private val live = LivePosition(12.97, 77.59, null, FixSource.Watch, 0)

    @Test fun waitingWithoutAPointShowsOnlyTheGpsCaption() =
        assertEquals(listOf("Waiting for GPS…"), GlassesMapText.captionLines(MapSceneBuilder.build(RouteState(sessionId = "s"), 18, 480, 480), drewTile = false))

    @Test fun liveWithTilesHasNoCaption() =
        assertEquals(emptyList(), GlassesMapText.captionLines(MapSceneBuilder.build(RouteState(sessionId = "s", live = live, status = GpsStatus.Live), 18, 480, 480), drewTile = true))

    /** Spec §2.5/§7: offline → route only on black with "No map — route only". */
    @Test fun offlineAndDelayedShowsBoth() = assertEquals(
        listOf("GPS delayed", "No map — route only"),
        GlassesMapText.captionLines(MapSceneBuilder.build(RouteState(sessionId = "s", live = live, status = GpsStatus.Delayed), 18, 480, 480), drewTile = false),
    )
}
