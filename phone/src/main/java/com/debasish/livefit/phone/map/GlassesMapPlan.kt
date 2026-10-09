package com.debasish.livefit.phone.map

import com.debasish.livefit.map.MapScene
import com.debasish.livefit.map.MapSceneBuilder
import com.debasish.livefit.map.PlacedTile
import com.debasish.livefit.map.TileId
import com.debasish.livefit.map.TileLoader
import com.debasish.livefit.map.Viewport
import com.debasish.livefit.model.RouteState

/**
 * What one glasses image shows (pure, review #5): the scene for the latest state, the visible tiles it [wanted], the ones
 * already [loaded] that get [drawn], and the caption lines. Nothing here waits for a tile.
 */
data class GlassesMapPlan<T>(val scene: MapScene, val wanted: List<TileId>, val drawn: List<Pair<PlacedTile, T>>, val captions: List<String>) {
    companion object {
        fun <T> of(state: RouteState, sizePx: Int, loaded: Map<TileId, T>): GlassesMapPlan<T> {
            val scene = MapSceneBuilder.build(state, Viewport.zoomFor(state.type), sizePx, sizePx)
            val visible = scene.viewport?.tiles().orEmpty()
            val drawn = visible.mapNotNull { pt -> loaded[pt.tile]?.let { pt to it } }
            return GlassesMapPlan(scene, visible.map { it.tile }, drawn, GlassesMapText.captionLines(scene, drewTile = drawn.isNotEmpty()))
        }

        /** [of] plus asking [loader] for the wanted tiles — ignored by the loader if the map was hidden since [generation] was captured. */
        fun <T : Any> request(state: RouteState, sizePx: Int, loader: TileLoader<T>, generation: Long): GlassesMapPlan<T> =
            of(state, sizePx, loader.tiles.value).also { loader.show(it.wanted, generation) }
    }
}
