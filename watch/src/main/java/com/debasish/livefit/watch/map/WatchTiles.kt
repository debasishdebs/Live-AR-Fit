package com.debasish.livefit.watch.map

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import com.debasish.livefit.map.HttpTileFetcher
import com.debasish.livefit.map.HudPalette
import com.debasish.livefit.map.MapAttribution
import com.debasish.livefit.map.TileDiskCache
import com.debasish.livefit.map.TileId
import com.debasish.livefit.map.TileSources
import com.debasish.livefit.watch.BuildConfig
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.withContext
import java.io.File

/**
 * Watch tiles over the watch's own connection (spec §2.4/§2.6): 20 MB LRU disk cache, palette-converted with the
 * watch mint so it matches the glasses look; only the visible tiles are ever requested. A visible tile that failed is
 * retried every 5 s while it stays visible (the fetcher's 30 s backoff limits real requests), so a fixed viewport fills
 * in when the network returns (review #6).
 */
class WatchTiles(context: Context, scope: CoroutineScope, private val tint: Int = 0x14C3A2) {
    private val source = TileSources.select(BuildConfig.TILES_KEY, BuildConfig.DEBUG, BuildConfig.VERSION_NAME)
    /** What the Map page must show for these tiles (spec §5). */
    val attribution: MapAttribution get() = source.attribution
    private val fetcher = HttpTileFetcher(source, TileDiskCache(File(context.cacheDir, "tiles"), TileDiskCache.WATCH_MAX_BYTES))
    private val loader = WatchTilePolicy.loader(scope) { t -> withContext(Dispatchers.IO) { fetcher.fetch(t)?.let(::decode) } }
        .also { it.start() }
    val bitmaps: StateFlow<Map<TileId, ImageBitmap>> = loader.tiles

    /** The tiles of the current viewport, on every viewport change (Main thread, from composition). */
    fun show(tiles: Collection<TileId>) = loader.show(tiles)

    /** The Map page left the screen: nothing visible, queued loads dropped (review r2 #1). */
    fun hide() = loader.hide()

    private fun decode(bytes: ByteArray): ImageBitmap? {
        val src = BitmapFactory.decodeByteArray(bytes, 0, bytes.size) ?: return null
        val px = IntArray(src.width * src.height)
        src.getPixels(px, 0, src.width, 0, 0, src.width, src.height)
        HudPalette.convertAll(px, tint)
        return Bitmap.createBitmap(px, src.width, src.height, Bitmap.Config.ARGB_8888).asImageBitmap()
    }
}
