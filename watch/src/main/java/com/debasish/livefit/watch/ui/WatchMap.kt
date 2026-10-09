package com.debasish.livefit.watch.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.focusable
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.ui.draw.clip
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.rotate
import androidx.compose.ui.graphics.drawscope.translate
import androidx.compose.ui.input.rotary.onRotaryScrollEvent
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.wear.compose.material.Text
import com.debasish.livefit.map.MapArrow
import com.debasish.livefit.map.MapSceneBuilder
import com.debasish.livefit.map.Viewport
import com.debasish.livefit.model.LivePosition
import com.debasish.livefit.model.LocationFix
import com.debasish.livefit.model.WorkoutType
import com.debasish.livefit.watch.map.WatchMapModel
import com.debasish.livefit.watch.map.WatchTiles
import kotlinx.coroutines.delay

/** Watch Map page (spec §2.6): own fixes over tiles, bezel/crown or on-screen +/− = zoom 14–18, offline = route only on black. */
@Composable
internal fun WatchMapPage(route: List<LocationFix>, live: LivePosition?, sessionId: String?, type: WorkoutType, tiles: WatchTiles) {
    var zoom by remember(type) { mutableIntStateOf(Viewport.zoomFor(type)) }
    var now by remember { mutableLongStateOf(System.currentTimeMillis()) }
    val focus = remember { FocusRequester() }
    LaunchedEffect(Unit) {
        runCatching { focus.requestFocus() }
        while (true) { delay(1_000); now = System.currentTimeMillis() } // status ages even without new fixes
    }
    val state = remember(route.size, route.lastOrNull(), live, sessionId, now / 1_000) { WatchMapModel.state(route, live, sessionId, type, now) }
    DisposableEffect(Unit) { onDispose { tiles.hide() } } // off the Map page: nothing visible, nothing retried or queued
    val bitmaps by tiles.bitmaps.collectAsState()
    val ins = rememberInsets()
    BoxWithConstraints(
        Modifier.fillMaxSize().background(Color.Black)
            .onRotaryScrollEvent { e -> zoom = WatchMapModel.zoomStep(zoom, e.verticalScrollPixels); true }
            .focusRequester(focus).focusable(),
    ) {
        val w = constraints.maxWidth
        val h = constraints.maxHeight
        val scene = remember(state, zoom, w, h) { MapSceneBuilder.build(state, zoom, w, h) }
        val visible = remember(scene.viewport) { scene.viewport?.tiles().orEmpty() }
        LaunchedEffect(visible) { tiles.show(visible.map { it.tile }) } // missing ones keep retrying while visible (review #6)
        val anyTile = visible.any { it.tile in bitmaps }
        Canvas(Modifier.fillMaxSize()) {
            for (t in visible) bitmaps[t.tile]?.let { drawImage(it, topLeft = Offset(t.left, t.top)) }
            if (scene.route.size >= 2) {
                val path = Path().apply {
                    moveTo(scene.route[0].x, scene.route[0].y)
                    for (p in scene.route.drop(1)) lineTo(p.x, p.y)
                }
                drawPath(path, W.Mint, style = Stroke(4.dp.toPx(), cap = StrokeCap.Round, join = StrokeJoin.Round))
            }
            scene.start?.let { drawCircle(W.Mint.copy(alpha = 0.6f), 6.dp.toPx(), Offset(it.x, it.y), style = Stroke(2.dp.toPx())) }
            scene.arrow?.let { drawArrow(it, Color.White) }
            scene.scale?.let { s ->
                val y = size.height * 0.80f
                val x0 = size.width / 2 - s.lengthPx / 2
                drawLine(Color.White.copy(alpha = 0.6f), Offset(x0, y), Offset(x0 + s.lengthPx, y), strokeWidth = 2.dp.toPx())
            }
        }
        // Watches without a bezel/crown: small on-screen zoom buttons at the middle edges (clear of a round screen's corners).
        ZoomButton("+", Modifier.align(Alignment.CenterEnd).padding(end = ins.x(6))) { zoom = WatchMapModel.zoomBy(zoom, +1) }
        ZoomButton("−", Modifier.align(Alignment.CenterStart).padding(start = ins.x(6))) { zoom = WatchMapModel.zoomBy(zoom, -1) }
        Column(Modifier.fillMaxSize().padding(top = ins.y(26), bottom = ins.y(14)), horizontalAlignment = Alignment.CenterHorizontally) {
            val caption = listOfNotNull(scene.caption, MapSceneBuilder.NO_TILES_CAPTION.takeIf { scene.viewport != null && !anyTile }).joinToString(" · ")
            if (caption.isNotEmpty()) Text(caption, fontSize = 12.sp, color = W.Amber, textAlign = TextAlign.Center)
            Box(Modifier.weight(1f))
            scene.scale?.let { Text(it.label, fontSize = 10.sp, color = W.Dim) }
            Text(scene.attribution, fontSize = 9.sp, color = W.Dim) // always visible (spec §2.4)
        }
    }
}

@Composable
private fun ZoomButton(label: String, modifier: Modifier, onClick: () -> Unit) {
    Box(
        modifier.size(32.dp).clip(CircleShape).background(Color(0x99000000)).clickable(onClick = onClick),
        contentAlignment = Alignment.Center,
    ) { Text(label, fontSize = 18.sp, color = Color.White) }
}

private fun DrawScope.drawArrow(a: MapArrow, color: Color) {
    val c = Offset(a.at.x, a.at.y)
    val r = 10.dp.toPx()
    val stroke = Stroke(3.dp.toPx())
    val bearing = a.bearingDeg
    if (bearing == null) {
        if (a.hollow) drawCircle(color, r * 0.7f, c, style = stroke) else drawCircle(color, r * 0.7f, c)
        return
    }
    val path = Path().apply { moveTo(0f, -r); lineTo(r * 0.7f, r * 0.8f); lineTo(0f, r * 0.4f); lineTo(-r * 0.7f, r * 0.8f); close() }
    rotate(bearing, pivot = c) { translate(c.x, c.y) { if (a.hollow) drawPath(path, color, style = stroke) else drawPath(path, color) } }
}
