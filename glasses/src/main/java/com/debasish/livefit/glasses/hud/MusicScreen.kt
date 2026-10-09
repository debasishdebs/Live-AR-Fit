package com.debasish.livefit.glasses.hud

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.MusicNote
import androidx.compose.material.icons.outlined.Pause
import androidx.compose.material.icons.outlined.PlayArrow
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.debasish.livefit.model.NowPlaying
import com.debasish.livefit.model.QueueItem
import com.debasish.livefit.model.QueueWindow

/** List rows that fit under the now-playing header at full HUD size. */
private const val MUSIC_ROWS = 7

/**
 * Glasses music screen (M1, spec §6.3): now playing on top, then the YouTube Music queue window. Played songs are
 * dim (35 %), upcoming 60 %, the current song full brightness with a ▶ mark; the highlight is an outline (like the
 * ✓/✕ choices), never a fill, always drawn while the queue has songs ([highlight], on the current song unless moved). A dim hint line sits at the bottom.
 */
@Composable
fun MusicScreen(np: NowPlaying?, queue: QueueWindow, highlight: Int?, clock: String, scrolling: Boolean = false) {
    Column(Modifier.fillMaxSize()) {
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Glyph(Icons.Outlined.MusicNote, 26.dp, Hud.TERTIARY)
            Label(" MUSIC", 22.sp, Hud.TERTIARY, FontWeight.Bold)
            val position = highlight?.takeIf { it < queue.items.size } ?: queue.currentIndex
            if (queue.items.isNotEmpty() && position != null) Label("  ${position + 1}/${queue.items.size}", 22.sp, Hud.TERTIARY)
            Spacer(Modifier.weight(1f))
            if (clock.isNotEmpty()) Label(clock, 24.sp, Hud.SECONDARY, FontWeight.Bold)
        }
        Spacer(Modifier.height(10.dp))
        if (np == null) {
            Label("Nothing playing", 30.sp, Hud.SECONDARY, FontWeight.Bold)
        } else {
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Glyph(if (np.isPlaying) Icons.Outlined.PlayArrow else Icons.Outlined.Pause, 34.dp, Hud.PRIMARY)
                Spacer(Modifier.width(6.dp))
                Label(np.title, 30.sp, Hud.PRIMARY, FontWeight.Bold, modifier = Modifier.weight(1f), overflow = TextOverflow.Ellipsis)
            }
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Label(np.artist, 24.sp, Hud.SECONDARY, modifier = Modifier.weight(1f).padding(start = 40.dp), overflow = TextOverflow.Ellipsis)
                Label(if (np.isPlaying) "playing" else "paused", 20.sp, Hud.TERTIARY)
            }
        }
        Spacer(Modifier.height(12.dp))
        Box(Modifier.fillMaxWidth().height(2.dp).background(Hud.Green.copy(alpha = Hud.TERTIARY))) // hairline rule
        Spacer(Modifier.height(8.dp))
        Box(Modifier.fillMaxWidth().weight(1f).clipToBounds()) {
            val rows = queue.items.size + if (scrolling) 1 else 0 // scroll mode adds the ✕ Back row
            Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                if (queue.items.isEmpty()) Label("No queue from YouTube Music", 24.sp, Hud.TERTIARY)
                for (i in visibleRows(rows, highlight ?: queue.currentIndex, MUSIC_ROWS)) {
                    if (i == queue.items.size) BackRow(highlighted = i == highlight)
                    else QueueRow(queue.items[i], played = queue.currentIndex != null && i < queue.currentIndex!!, current = i == queue.currentIndex, highlighted = i == highlight)
                }
            }
        }
        Spacer(Modifier.height(6.dp))
        val hint = when {
            !scrolling -> "tap: choose songs · swipe: pages"
            highlight == queue.items.size -> "tap: leave song list"
            highlight == queue.currentIndex -> "swipe: songs · tap: play/pause · ✕ Back: leave"
            else -> "swipe: songs · tap: play song · ✕ Back: leave"
        }
        Label(hint, 18.sp, Hud.TERTIARY, maxLines = 2) // the HUD block is narrow at 40 % size
    }
}

@Composable
private fun QueueRow(item: QueueItem, played: Boolean, current: Boolean, highlighted: Boolean) {
    val level = when { current || highlighted -> Hud.PRIMARY; played -> Hud.TERTIARY; else -> Hud.SECONDARY }
    val shape = RoundedCornerShape(10.dp)
    val outline = if (highlighted) Modifier.border(3.dp, Hud.Green.copy(alpha = Hud.PRIMARY), shape) else Modifier
    Row(outline.fillMaxWidth().padding(horizontal = 8.dp, vertical = 4.dp), verticalAlignment = Alignment.CenterVertically) {
        Box(Modifier.size(26.dp), contentAlignment = Alignment.Center) { if (current) Glyph(Icons.Outlined.PlayArrow, 26.dp, Hud.PRIMARY) }
        Spacer(Modifier.width(6.dp))
        val text = if (item.artist.isEmpty()) item.title else "${item.title} · ${item.artist}"
        Label(text, 24.sp, level, if (current) FontWeight.Bold else FontWeight.Medium, modifier = Modifier.weight(1f), overflow = TextOverflow.Ellipsis)
    }
}

@Composable
private fun BackRow(highlighted: Boolean) {
    val shape = RoundedCornerShape(10.dp)
    val outline = if (highlighted) Modifier.border(3.dp, Hud.Green.copy(alpha = Hud.PRIMARY), shape) else Modifier
    Row(outline.fillMaxWidth().padding(horizontal = 8.dp, vertical = 4.dp), verticalAlignment = Alignment.CenterVertically) {
        Label("✕ Back", 24.sp, if (highlighted) Hud.PRIMARY else Hud.SECONDARY, FontWeight.Bold)
    }
}
