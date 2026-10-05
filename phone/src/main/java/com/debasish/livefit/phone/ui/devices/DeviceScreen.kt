package com.debasish.livefit.phone.ui.devices

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
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Battery5Bar
import androidx.compose.material.icons.rounded.BrightnessMedium
import androidx.compose.material.icons.rounded.Favorite
import androidx.compose.material.icons.rounded.Link
import androidx.compose.material.icons.rounded.Visibility
import androidx.compose.material.icons.rounded.Watch
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Slider
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.debasish.livefit.model.StateFrame
import com.debasish.livefit.model.LinkState
import com.debasish.livefit.model.formatElapsed
import com.debasish.livefit.phone.ServiceGraph
import com.debasish.livefit.phone.ui.components.ChipRow
import com.debasish.livefit.phone.ui.components.IconChip
import com.debasish.livefit.phone.ui.components.ScreenHeader
import com.debasish.livefit.phone.ui.components.SectionLabel
import com.debasish.livefit.phone.ui.components.SoftCard
import com.debasish.livefit.phone.ui.theme.LiveFitColors

enum class DeviceKind { Glasses, Watch }

@Composable
fun DeviceScreen(kind: DeviceKind, services: ServiceGraph, onBack: () -> Unit) {
    val status by (if (kind == DeviceKind.Glasses) services.glasses.status else services.watch.status).collectAsStateWithLifecycle()
    val colors = if (kind == DeviceKind.Glasses) LiveFitColors.ChipMint else LiveFitColors.ChipViolet
    Column(Modifier.fillMaxSize().background(LiveFitColors.SurfaceSoft).verticalScroll(rememberScrollState())) {
        ScreenHeader(if (kind == DeviceKind.Glasses) "Rokid glasses" else "Galaxy Watch", onBack)
        SoftCard(Modifier.padding(horizontal = 16.dp).fillMaxWidth()) {
            Row(Modifier.padding(20.dp), verticalAlignment = Alignment.CenterVertically) {
                IconChip(if (kind == DeviceKind.Glasses) Icons.Rounded.Visibility else Icons.Rounded.Watch, colors, size = 64.dp, shapeRadius = 20.dp)
                Column(Modifier.weight(1f).padding(start = 16.dp)) {
                    Text(status.name, style = MaterialTheme.typography.titleLarge)
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        val on = status.link == LinkState.Connected
                        Box(Modifier.size(8.dp).clip(CircleShape).background(if (on) LiveFitColors.Mint else Color(0xFFC5CBD3)))
                        Text("  ${status.link.name}", color = LiveFitColors.InkSoft)
                    }
                }
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    Icon(Icons.Rounded.Battery5Bar, contentDescription = "Battery", tint = LiveFitColors.MintDeep)
                    Text("${status.batteryPct ?: "--"}%", style = MaterialTheme.typography.titleMedium)
                }
            }
        }

        if (kind == DeviceKind.Glasses) {
            SectionLabel("HUD preview")
            val frame by services.lastFrame.collectAsStateWithLifecycle()
            if (status.link != LinkState.Connected) {
                SoftCard(Modifier.padding(horizontal = 16.dp).fillMaxWidth(), onClick = { services.glasses.connect() }) {
                    Text("Reconnect · ${status.detail ?: "not connected"}", modifier = Modifier.padding(16.dp), color = LiveFitColors.MintDeep)
                }
                Spacer(Modifier.height(8.dp))
            }
            HudPreview(frame)
            SectionLabel("Display")
            var brightness by remember { mutableFloatStateOf(0.4f) }
            SoftCard(Modifier.padding(horizontal = 16.dp).fillMaxWidth()) {
                Row(Modifier.padding(16.dp), verticalAlignment = Alignment.CenterVertically) {
                    IconChip(Icons.Rounded.BrightnessMedium, LiveFitColors.ChipAmber)
                    Slider(brightness, { brightness = it }, Modifier.weight(1f).padding(start = 12.dp))
                }
            }
        } else {
            SectionLabel("Tracking")
            SoftCard(Modifier.padding(horizontal = 16.dp).fillMaxWidth()) {
                Column {
                    ChipRow(Icons.Rounded.Favorite, LiveFitColors.ChipCoral, "Live sensors", "Health Services · ~1 Hz", {}, trailing = {})
                    HorizontalDivider(Modifier.padding(start = 70.dp), color = LiveFitColors.Line)
                    ChipRow(Icons.Rounded.Link, LiveFitColors.ChipSky, "Link", "Wearable Data Layer", {}, trailing = {})
                }
            }
        }
        Spacer(Modifier.height(32.dp))
    }
}

/** Phone-side miniature of the glasses HUD (green on black), fed by the same StateFrame the glasses get. */
@Composable
private fun HudPreview(frame: StateFrame?) {
    val green = Color(0xFF39FF6A)
    Box(
        Modifier.padding(horizontal = 16.dp).fillMaxWidth().height(240.dp).clip(RoundedCornerShape(20.dp)).background(Color.Black).border(1.dp, LiveFitColors.Line, RoundedCornerShape(20.dp)).padding(16.dp),
    ) {
        if (frame == null) {
            Text("Waiting for first frame…", color = green.copy(alpha = 0.5f), modifier = Modifier.align(Alignment.Center))
            return@Box
        }
        val w = frame.workout
        Column(Modifier.fillMaxSize(), verticalArrangement = Arrangement.SpaceBetween) {
            Row(Modifier.fillMaxWidth()) {
                Text(w.displayType.label.uppercase(), color = green, fontSize = 22.sp, fontWeight = FontWeight.Bold, modifier = Modifier.weight(1f))
                Text("⏱ ${formatElapsed(w.elapsedMs)}", color = green, fontSize = 22.sp, fontWeight = FontWeight.Bold)
            }
            Row(Modifier.fillMaxWidth()) {
                Text("♡ ${w.metrics.heartRate ?: "--"}", color = green, fontSize = 30.sp, fontWeight = FontWeight.Bold, modifier = Modifier.weight(1f))
                Text("🔥 ${w.metrics.calories}", color = green, fontSize = 30.sp, fontWeight = FontWeight.Bold)
            }
            Column {
                Text("%,d steps   %.2f km   %.1f km/h".format(w.metrics.steps, w.metrics.distanceKm, w.metrics.speedKmh), color = green.copy(alpha = 0.6f), fontSize = 13.sp)
                Spacer(Modifier.width(4.dp))
                Text("♪ ${frame.music?.title ?: "—"}", color = green.copy(alpha = 0.4f), fontSize = 13.sp, maxLines = 1)
            }
        }
    }
}
