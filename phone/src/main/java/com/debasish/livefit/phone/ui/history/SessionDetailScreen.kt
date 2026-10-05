package com.debasish.livefit.phone.ui.history

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.unit.dp
import com.debasish.livefit.model.Sample
import com.debasish.livefit.model.SessionSummary
import com.debasish.livefit.model.formatElapsed
import com.debasish.livefit.phone.ServiceGraph
import com.debasish.livefit.phone.ui.components.ScreenHeader
import com.debasish.livefit.phone.ui.components.SectionLabel
import com.debasish.livefit.phone.ui.components.SoftCard
import com.debasish.livefit.phone.ui.theme.LiveFitColors
import kotlinx.coroutines.flow.first

@Composable
fun SessionDetailScreen(services: ServiceGraph, sessionId: String, onBack: () -> Unit) {
    var summary by remember { mutableStateOf<SessionSummary?>(null) }
    var samples by remember { mutableStateOf<List<Sample>>(emptyList()) }
    var loaded by remember { mutableStateOf(false) }
    LaunchedEffect(sessionId) {
        runCatching {
            summary = services.history.sessions.first().firstOrNull { it.id == sessionId }
            samples = services.history.samples(sessionId)
        }
        loaded = true
    }
    val s = summary
    Column(Modifier.fillMaxSize().background(LiveFitColors.SurfaceSoft).verticalScroll(rememberScrollState())) {
        ScreenHeader(s?.let { HistoryFormat.title(it) } ?: "Workout", onBack)
        if (s == null) {
            if (loaded) Text("Workout not found", color = LiveFitColors.InkSoft, modifier = Modifier.padding(20.dp))
            return@Column
        }
        HistoryFormat.badge(s)?.let {
            val msg = buildList {
                if (it.startsWith("Demo")) add("Demo · not saved to Health Connect")
                if (it.endsWith("Incomplete")) add("Incomplete · some data could not be recovered")
            }.joinToString("\n")
            Text(msg, color = LiveFitColors.ChipCoral.second, modifier = Modifier.padding(horizontal = 20.dp))
        }
        s.endReason?.let { Text(HistoryFormat.endReason(it), color = LiveFitColors.InkSoft, modifier = Modifier.padding(horizontal = 20.dp, vertical = 4.dp)) }
        SoftCard(Modifier.padding(16.dp).fillMaxWidth()) {
            Column {
                Row(Modifier.padding(16.dp).fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                    Stat("Time", formatElapsed(s.activeMs)); Stat("Avg ♥", "${s.avgHr ?: "--"}"); Stat("Max ♥", "${s.maxHr ?: "--"}")
                }
                Row(Modifier.padding(start = 16.dp, end = 16.dp, bottom = 16.dp).fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                    Stat("kcal", "${s.kcal}"); Stat("Steps", "%,d".format(s.steps)); Stat("km", "%.2f".format(s.distanceKm))
                }
            }
        }
        SectionLabel("Heart rate")
        SoftCard(Modifier.padding(horizontal = 16.dp).fillMaxWidth().height(180.dp)) {
            val hrs = samples.mapNotNull { it.hr }
            if (hrs.size < 2) Text("No heart-rate data", color = LiveFitColors.InkSoft, modifier = Modifier.padding(16.dp))
            Canvas(Modifier.fillMaxSize().padding(16.dp)) {
                if (hrs.size < 2) return@Canvas
                val lo = hrs.min() - 5f; val hi = hrs.max() + 5f
                val path = Path()
                hrs.forEachIndexed { i, hr ->
                    val x = size.width * i / (hrs.size - 1)
                    val y = size.height - (hr - lo) / (hi - lo) * size.height
                    if (i == 0) path.moveTo(x, y) else path.lineTo(x, y)
                }
                drawPath(path, LiveFitColors.ChipCoral.second, style = Stroke(3.dp.toPx(), cap = StrokeCap.Round))
            }
        }
    }
}

@Composable
private fun Stat(label: String, value: String) {
    Column {
        Text(value, style = MaterialTheme.typography.titleLarge)
        Text(label, style = MaterialTheme.typography.labelMedium, color = LiveFitColors.InkSoft)
    }
}
