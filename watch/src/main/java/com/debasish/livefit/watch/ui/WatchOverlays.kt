package com.debasish.livefit.watch.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.focusable
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.scrollBy
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Check
import androidx.compose.material.icons.rounded.Close
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.input.rotary.onRotaryScrollEvent
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.runtime.rememberCoroutineScope
import androidx.wear.compose.foundation.lazy.ScalingLazyColumn
import androidx.wear.compose.foundation.lazy.rememberScalingLazyListState
import androidx.wear.compose.material.Icon
import kotlinx.coroutines.launch
import androidx.wear.compose.material.Text
import com.debasish.livefit.model.Confirmation
import kotlin.math.atan2

/**
 * Edge volume arc (bottom 120°). Drag along the edge or turn the bezel (5 % per detent).
 * [level] is the phone's real volume from the frame; [onChange] sends SetVolume.
 */
@Composable
fun VolumeArc(level: Float, onChange: (Float) -> Unit, modifier: Modifier = Modifier) {
    var local by remember(level) { mutableFloatStateOf(level) }
    val focus = remember { FocusRequester() }
    LaunchedEffect(Unit) { focus.requestFocus() }
    Canvas(
        modifier.fillMaxSize().padding(rememberInsets().x(4))
            .onRotaryScrollEvent { e -> local = (local + if (e.verticalScrollPixels > 0) 0.05f else -0.05f).coerceIn(0f, 1f); onChange(local); true }
            .focusRequester(focus).focusable()
            .pointerInput(Unit) {
                // Only drags that start in the outer ring band are ours; others fall through so the pager still pages.
                awaitEachGesture {
                    val down = awaitFirstDown(requireUnconsumed = false)
                    val c = Offset(size.width / 2f, size.height / 2f)
                    fun degOf(o: Offset) = Math.toDegrees(atan2((o.y - c.y).toDouble(), (o.x - c.x).toDouble())).toFloat()
                    val dist = (down.position - c).getDistance()
                    if (dist < 0.8f * minOf(size.width, size.height) / 2f || degOf(down.position) !in 30f..150f) return@awaitEachGesture
                    down.consume()
                    while (true) {
                        val ev = awaitPointerEvent()
                        val ch = ev.changes.firstOrNull() ?: break
                        if (!ch.pressed) break
                        // Arc spans 150° (left) → 30° (right) through 90° (bottom).
                        val deg = degOf(ch.position)
                        if (deg in 30f..150f) { local = ((150f - deg) / 120f).coerceIn(0f, 1f); onChange(local) }
                        ch.consume()
                    }
                }
            },
    ) {
        val stroke = Stroke(width = 8.dp.toPx(), cap = StrokeCap.Round)
        drawArc(Color(0xFF26292D), 150f, -120f, false, style = stroke)
        drawArc(Color(0xFFFF6F9C), 150f, -120f * local, false, style = stroke)
    }
}

@Composable
fun ConfirmOverlay(c: Confirmation, onAnswer: (Boolean) -> Unit) {
    Box(Modifier.fillMaxSize().background(Color(0xEE000000)), contentAlignment = Alignment.Center) {
        Column(horizontalAlignment = Alignment.CenterHorizontally, modifier = Modifier.padding(rememberInsets().x(24))) {
            Text(c.title, fontSize = 16.sp, fontWeight = FontWeight.Bold, textAlign = TextAlign.Center)
            Text(c.message, fontSize = 12.sp, color = Color(0xFF9AA0A6), textAlign = TextAlign.Center, maxLines = 3)
            Spacer(Modifier.height(10.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                Box(Modifier.size(52.dp).clip(CircleShape).background(Color(0x33FF6B4F)).clickable { onAnswer(false) }, contentAlignment = Alignment.Center) {
                    Icon(Icons.Rounded.Close, contentDescription = c.noLabel, tint = Color(0xFFFF6B4F))
                }
                Box(Modifier.size(52.dp).clip(CircleShape).background(Color(0x3314C3A2)).clickable { onAnswer(true) }, contentAlignment = Alignment.Center) {
                    Icon(Icons.Rounded.Check, contentDescription = c.yesLabel, tint = Color(0xFF14C3A2))
                }
            }
        }
    }
}

@Composable
fun OfflineBadge(modifier: Modifier = Modifier) {
    Text("Phone offline", fontSize = 11.sp, color = Color(0xFFFFB627),
        modifier = modifier.clip(RoundedCornerShape(10.dp)).background(Color(0x33FFB627)).padding(horizontal = 8.dp, vertical = 2.dp))
}

@Composable
fun PermissionCard(perms: List<String>, onGrant: () -> Unit) {
    Box(Modifier.fillMaxSize().background(Color.Black), contentAlignment = Alignment.Center) {
        Column(horizontalAlignment = Alignment.CenterHorizontally, modifier = Modifier.padding(rememberInsets().x(24))) {
            Text("LiveFit needs sensor access", fontSize = 15.sp, fontWeight = FontWeight.Bold, textAlign = TextAlign.Center)
            Text(perms.joinToString { it.substringAfterLast('.') }, fontSize = 11.sp, color = Color(0xFF9AA0A6), textAlign = TextAlign.Center)
            Spacer(Modifier.height(10.dp))
            Text("Allow", fontSize = 15.sp, color = Color.Black, modifier = Modifier.clip(RoundedCornerShape(20.dp)).background(Color(0xFF14C3A2)).clickable(onClick = onGrant).padding(horizontal = 20.dp, vertical = 8.dp))
        }
    }
}

/** Spec §4: prominent disclosure before the watch's location prompt. Scrolls (touch or crown) so small round screens don't clip it. */
@Composable
fun LocationDisclosureCard(onAnswer: (Boolean) -> Unit) {
    val listState = rememberScalingLazyListState(initialCenterItemIndex = 0)
    val focus = remember { FocusRequester() }
    val scope = rememberCoroutineScope()
    LaunchedEffect(Unit) { runCatching { focus.requestFocus() } }
    ScalingLazyColumn(
        Modifier.fillMaxSize().background(Color.Black)
            .onRotaryScrollEvent { e -> scope.launch { listState.scrollBy(e.verticalScrollPixels) }; true }
            .focusRequester(focus).focusable(),
        state = listState,
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        item { Text(com.debasish.livefit.model.Disclosures.LOCATION_TITLE, fontSize = 15.sp, fontWeight = FontWeight.Bold, textAlign = TextAlign.Center) }
        item { Text(com.debasish.livefit.model.Disclosures.WATCH_LOCATION, fontSize = 11.sp, color = Color(0xFF9AA0A6), textAlign = TextAlign.Center) }
        item {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Text("Not now", fontSize = 14.sp, color = Color.White, modifier = Modifier.clip(RoundedCornerShape(20.dp)).background(Color(0xFF202327)).clickable { onAnswer(false) }.padding(horizontal = 14.dp, vertical = 8.dp))
                Text("Continue", fontSize = 14.sp, color = Color.Black, modifier = Modifier.clip(RoundedCornerShape(20.dp)).background(Color(0xFF14C3A2)).clickable { onAnswer(true) }.padding(horizontal = 14.dp, vertical = 8.dp))
            }
        }
    }
}
