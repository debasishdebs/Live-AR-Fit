package com.debasish.livefit.phone.map

import com.debasish.livefit.map.MapScene
import com.debasish.livefit.map.MapSceneBuilder

/** Text lines at the top of the glasses map image. */
object GlassesMapText {
    fun captionLines(scene: MapScene, drewTile: Boolean): List<String> =
        listOfNotNull(scene.caption, MapSceneBuilder.NO_TILES_CAPTION.takeIf { scene.viewport != null && !drewTile })
}
