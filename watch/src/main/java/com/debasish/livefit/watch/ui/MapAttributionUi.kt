package com.debasish.livefit.watch.ui

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.wear.compose.foundation.lazy.ScalingLazyColumn
import androidx.wear.compose.material.Chip
import androidx.wear.compose.material.ChipDefaults
import androidx.wear.compose.material.CompactChip
import androidx.wear.compose.material.SwipeToDismissBox
import androidx.wear.compose.material.Text
import com.debasish.livefit.map.MapAttribution
import com.debasish.livefit.watch.OpenOnPhone
import com.debasish.livefit.watch.R
import kotlinx.coroutines.launch

/** "© MapTiler © OpenStreetMap contributors" → one line per credit, so it fits the narrow bottom of a round screen. */
internal fun attributionLines(text: String): List<String> = text.split(" © ").mapIndexed { i, s -> if (i == 0) s else "© $s" }

/** Spec §5: logo + text overlay and an "ⓘ Map data" tap target (≥ 48 dp) on the watch Map page. */
@Composable
internal fun MapAttributionBadge(attribution: MapAttribution, onInfo: () -> Unit) {
    val lines = attributionLines(attribution.text)
    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            if (attribution.mapTilerLogo) Image(painterResource(R.drawable.maptiler_logo), contentDescription = "MapTiler", modifier = Modifier.height(10.dp))
            Text(" ${lines.first()}", fontSize = 9.sp, color = W.Dim)
        }
        for (line in lines.drop(1)) Text(line, fontSize = 9.sp, color = W.Dim, textAlign = TextAlign.Center)
        Box(
            Modifier.clip(RoundedCornerShape(24.dp)).clickable(onClick = onInfo).defaultMinSize(minWidth = 48.dp, minHeight = 48.dp).padding(horizontal = 10.dp),
            contentAlignment = Alignment.Center,
        ) { Text("ⓘ Map data", fontSize = 10.sp, color = Color.White) }
    }
}

/** The attribution sheet: the full text and each copyright link, opened on the phone. Back or swipe right closes it. */
@Composable
internal fun MapAttributionSheet(attribution: MapAttribution, onClose: () -> Unit) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var note by remember { mutableStateOf<String?>(null) }
    BackHandler(onBack = onClose)
    SwipeToDismissBox(onDismissed = onClose) { isBackground ->
        if (isBackground) return@SwipeToDismissBox // the Map page shows through while swiping
        ScalingLazyColumn(Modifier.fillMaxSize().background(Color.Black), horizontalAlignment = Alignment.CenterHorizontally) {
            item { Text("Map data", fontSize = 15.sp, fontWeight = FontWeight.SemiBold) }
            item { Text(attribution.text, fontSize = 11.sp, color = W.Dim, textAlign = TextAlign.Center) }
            items(attribution.links.size) { i ->
                val link = attribution.links[i]
                Chip(
                    onClick = { scope.launch { note = if (OpenOnPhone.open(context, link.url)) "Opened on your phone" else "Couldn't reach the phone" } },
                    label = { Text("© ${link.label}") }, secondaryLabel = { Text("Open on phone") }, colors = ChipDefaults.secondaryChipColors(),
                )
            }
            note?.let { item { Text(it, fontSize = 11.sp, color = W.Mint) } }
            item { CompactChip(onClick = onClose, label = { Text("Close") }) }
        }
    }
}
