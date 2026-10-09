package com.debasish.livefit.watch.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.focusable
import androidx.compose.foundation.gestures.scrollBy
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Favorite
import androidx.compose.material.icons.rounded.PlayArrow
import androidx.compose.material.icons.rounded.Timer
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.rotary.onRotaryScrollEvent
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.wear.compose.foundation.lazy.ScalingLazyColumn
import androidx.wear.compose.foundation.lazy.itemsIndexed
import androidx.wear.compose.foundation.lazy.rememberScalingLazyListState
import androidx.wear.compose.material.Icon
import androidx.wear.compose.material.Text
import com.debasish.livefit.model.Command
import com.debasish.livefit.model.QueueWindow
import com.debasish.livefit.model.WorkoutSnapshot
import com.debasish.livefit.model.formatElapsed
import kotlinx.coroutines.launch

/** Glance: timer + heart rate, large (spec §3.1). */
@Composable
internal fun GlancePage(s: WorkoutSnapshot) {
    Column(Modifier.fillMaxSize(), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.Center) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Icon(Icons.Rounded.Timer, contentDescription = "Timer", tint = W.Dim, modifier = Modifier.size(22.dp))
            Text(" ${formatElapsed(s.elapsedMs)}", fontSize = 34.sp, fontWeight = FontWeight.Bold)
        }
        Spacer(Modifier.size(6.dp))
        Row(verticalAlignment = Alignment.CenterVertically) {
            Icon(Icons.Rounded.Favorite, contentDescription = "Heart rate", tint = W.Coral, modifier = Modifier.size(30.dp))
            Text(" ${s.metrics.heartRate ?: "--"}", fontSize = 56.sp, fontWeight = FontWeight.Bold)
        }
    }
}

/**
 * Playlist: the YouTube Music queue window from the phone (spec §6). Tap = play that song (play/pause on the current
 * one, PlayQueueItem otherwise); the bezel scrolls. Empty → "Nothing queued — start music on the phone".
 */
@Composable
internal fun PlaylistPage(queue: QueueWindow, onCommand: (Command) -> Unit) {
    if (queue.items.isEmpty()) {
        Box(Modifier.fillMaxSize().padding(28.dp), contentAlignment = Alignment.Center) {
            Text("Nothing queued — start music on the phone", fontSize = 14.sp, color = W.Dim, textAlign = TextAlign.Center)
        }
        return
    }
    val listState = rememberScalingLazyListState(initialCenterItemIndex = queue.currentIndex ?: 0)
    val focus = remember { FocusRequester() }
    val scope = rememberCoroutineScope()
    LaunchedEffect(Unit) { runCatching { focus.requestFocus() } }
    ScalingLazyColumn(
        Modifier.fillMaxSize()
            .onRotaryScrollEvent { e -> scope.launch { listState.scrollBy(e.verticalScrollPixels) }; true }
            .focusRequester(focus).focusable(),
        state = listState,
    ) {
        itemsIndexed(queue.items) { i, item ->
            val current = i == queue.currentIndex
            Row(
                Modifier.fillMaxWidth().clip(RoundedCornerShape(16.dp)).background(if (current) W.Pill else Color.Transparent)
                    .clickable { onCommand(if (current) Command.PlayPause else Command.PlayQueueItem(item.queueId)) }
                    .padding(horizontal = 12.dp, vertical = 6.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                if (current) { Icon(Icons.Rounded.PlayArrow, contentDescription = "Playing", tint = W.Rose, modifier = Modifier.size(16.dp)); Spacer(Modifier.width(4.dp)) }
                Column {
                    Text(item.title, fontSize = 14.sp, maxLines = 1, fontWeight = if (current) FontWeight.Bold else FontWeight.Normal)
                    if (item.artist.isNotEmpty()) Text(item.artist, fontSize = 11.sp, color = W.Dim, maxLines = 1)
                }
            }
        }
    }
}
