package com.debasish.livefit.phone.map

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Path
import android.util.Log
import androidx.core.content.ContextCompat
import androidx.core.graphics.drawable.toBitmap
import com.debasish.livefit.map.HttpTileFetcher
import com.debasish.livefit.map.HudPalette
import com.debasish.livefit.map.MapArrow
import com.debasish.livefit.map.MapAttribution
import com.debasish.livefit.map.MapScene
import com.debasish.livefit.map.TileLoader
import com.debasish.livefit.model.RouteState
import com.debasish.livefit.phone.R
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.ByteArrayOutputStream

/**
 * The glasses map image (spec §2.5): 480×480 PNG on black, tiles converted to the HUD palette, bright route, position
 * arrow (hollow while degraded), start marker, scale bar and the always-visible attribution. It never waits for a tile
 * (review #5): it draws what [tiles] holds now — or route only with "No map — route only" — and asks the loader for the
 * missing visible tiles, which then appear in a later image.
 */
class GlassesMapRenderer(
    private val tiles: TileLoader<Bitmap>,
    private val attribution: MapAttribution,
    private val logo: Bitmap?,
    private val sizePx: Int = SIZE_PX,
) {
    private val green = 0xFF000000.toInt() or HudPalette.HUD_GREEN
    private val dim = HudPalette.scaled(HudPalette.HUD_GREEN, 0.6f)
    private val routePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = green; style = Paint.Style.STROKE; strokeWidth = 6f; strokeCap = Paint.Cap.ROUND; strokeJoin = Paint.Join.ROUND }
    private val dimStroke = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = dim; style = Paint.Style.STROKE; strokeWidth = 3f }
    private val arrowFill = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = green; style = Paint.Style.FILL }
    private val arrowHollow = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = green; style = Paint.Style.STROKE; strokeWidth = 4f }
    private val caption = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = green; textSize = 28f; textAlign = Paint.Align.CENTER; isFakeBoldText = true }
    private val small = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = dim; textSize = 18f }
    private val attributionPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = dim; textSize = 18f; textAlign = Paint.Align.RIGHT; isFakeBoldText = true }
    private val logoPaint = Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG)

    suspend fun render(state: RouteState): ByteArray? {
        val generation = tiles.generation // on the caller's (Main) thread, before suspending: a later hide() wins (review r2 #1)
        return withContext(Dispatchers.Default) { draw(state, generation) }
    }

    private fun draw(state: RouteState, generation: Long): ByteArray {
        val plan = GlassesMapPlan.request(state, sizePx, tiles, generation) // returns at once; missing tiles load in the background
        val bmp = Bitmap.createBitmap(sizePx, sizePx, Bitmap.Config.ARGB_8888)
        val c = Canvas(bmp)
        c.drawColor(Color.BLACK)
        for ((pt, tile) in plan.drawn) c.drawBitmap(tile, pt.left, pt.top, null)
        drawOverlay(c, plan.scene, plan.captions)
        val out = ByteArrayOutputStream()
        bmp.compress(Bitmap.CompressFormat.PNG, 100, out)
        return out.toByteArray().also { if (it.size > MAX_PNG_BYTES) Log.w(TAG, "map PNG ${it.size} B exceeds the ${MAX_PNG_BYTES} B target") }
    }

    private fun drawOverlay(c: Canvas, scene: MapScene, captions: List<String>) {
        if (scene.route.size >= 2) {
            val path = Path().apply { moveTo(scene.route[0].x, scene.route[0].y); for (p in scene.route.drop(1)) lineTo(p.x, p.y) }
            c.drawPath(path, routePaint)
        }
        scene.start?.let { c.drawCircle(it.x, it.y, 9f, dimStroke) }
        scene.arrow?.let { drawArrow(c, it) }
        scene.scale?.let { s ->
            val y = sizePx - 46f
            c.drawLine(16f, y, 16f + s.lengthPx, y, dimStroke)
            c.drawText(s.label, 16f, y - 8f, small)
        }
        captions.forEachIndexed { i, line -> c.drawText(line, sizePx / 2f, 40f + i * 34f, caption) }
        // Spec §5: MapTiler logo + "© MapTiler © OpenStreetMap contributors" on every image; the logo keeps its original colours (black map background).
        val textY = sizePx - 12f
        c.drawText(attribution.text, sizePx - 12f, textY, attributionPaint)
        if (attribution.mapTilerLogo && logo != null) c.drawBitmap(logo, sizePx - 12f - logo.width, textY - 18f - logo.height, logoPaint)
    }

    private fun drawArrow(c: Canvas, a: MapArrow) {
        val paint = if (a.hollow) arrowHollow else arrowFill
        val bearing = a.bearingDeg
        if (bearing == null) { c.drawCircle(a.at.x, a.at.y, 10f, paint); return }
        val r = 16f
        val path = Path().apply { moveTo(0f, -r); lineTo(r * 0.7f, r * 0.8f); lineTo(0f, r * 0.4f); lineTo(-r * 0.7f, r * 0.8f); close() }
        c.save()
        c.translate(a.at.x, a.at.y)
        c.rotate(bearing)
        c.drawPath(path, paint)
        c.restore()
    }

    companion object {
        const val TAG = "LiveFitMap"
        const val SIZE_PX = 480
        const val MAX_PNG_BYTES = 40 * 1024
        const val LOGO_HEIGHT_PX = 24

        /** The MapTiler logo at HUD size (original colours, drawn untinted); null if the drawable is missing. */
        fun logo(context: Context): Bitmap? = ContextCompat.getDrawable(context, R.drawable.maptiler_logo)?.let { d ->
            d.toBitmap(width = LOGO_HEIGHT_PX * d.intrinsicWidth / d.intrinsicHeight.coerceAtLeast(1), height = LOGO_HEIGHT_PX)
        }

        /** Up to 4 tiles at once, missing visible tiles retried every 5 s (the fetcher's backoff limits real requests). */
        fun tileLoader(scope: CoroutineScope, fetcher: HttpTileFetcher): TileLoader<Bitmap> =
            TileLoader(scope, load = { t -> withContext(Dispatchers.IO) { fetcher.fetch(t)?.let(::toHud) } }, maxConcurrent = 4, retryEveryMs = 5_000, maxCached = 30)

        private fun toHud(bytes: ByteArray): Bitmap? {
            val src = BitmapFactory.decodeByteArray(bytes, 0, bytes.size) ?: return null
            val px = IntArray(src.width * src.height)
            src.getPixels(px, 0, src.width, 0, 0, src.width, src.height)
            HudPalette.convertAll(px)
            return Bitmap.createBitmap(px, src.width, src.height, Bitmap.Config.ARGB_8888)
        }
    }
}
