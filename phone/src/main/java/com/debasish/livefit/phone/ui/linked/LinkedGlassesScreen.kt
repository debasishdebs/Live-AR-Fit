package com.debasish.livefit.phone.ui.linked

import android.app.Activity
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
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Dashboard
import androidx.compose.material.icons.rounded.Key
import androidx.compose.material.icons.rounded.Link
import androidx.compose.material.icons.rounded.Refresh
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.debasish.livefit.model.DeviceKind
import com.debasish.livefit.model.StateFrame
import com.debasish.livefit.model.formatElapsed
import com.debasish.livefit.phone.CompanionLinker
import com.debasish.livefit.phone.ServiceGraph
import com.debasish.livefit.phone.ui.components.ChipRow
import com.debasish.livefit.phone.ui.components.GlassesIcon
import com.debasish.livefit.phone.ui.components.ScreenHeader
import com.debasish.livefit.phone.ui.components.SectionLabel
import com.debasish.livefit.phone.ui.components.SoftCard
import com.debasish.livefit.phone.ui.theme.LiveFitColors
import com.debasish.livefit.services.glasses.AuthActivity

@Composable
fun LinkedGlassesScreen(services: ServiceGraph, onBack: () -> Unit, onDisplay: () -> Unit, toast: (String) -> Unit) {
    val activity = LocalContext.current as Activity
    val st by services.glasses.status.collectAsStateWithLifecycle()
    val frame by services.lastFrame.collectAsStateWithLifecycle()
    Column(Modifier.fillMaxSize().background(LiveFitColors.SurfaceSoft).verticalScroll(rememberScrollState())) {
        ScreenHeader("Rokid glasses", onBack)
        SoftCard(Modifier.padding(horizontal = 16.dp).fillMaxWidth()) {
            ChipRow(GlassesIcon, LiveFitColors.ChipMint, st.name, "${st.link.name}${st.batteryPct?.let { " · $it%" } ?: ""}${st.detail?.let { " · $it" } ?: ""}", {}, trailing = {})
        }
        SectionLabel("HUD preview")
        HudPreview(frame)
        SectionLabel("Link")
        SoftCard(Modifier.padding(horizontal = 16.dp).fillMaxWidth()) {
            Column {
                ChipRow(Icons.Rounded.Link, LiveFitColors.ChipSky, "Pair / re-pair", "Lets Android wake LiveFit when the glasses are near",
                    { CompanionLinker.associate(activity, DeviceKind.Glasses) { ok -> toast(if (ok) "Glasses paired" else "Pairing cancelled") } })
                HorizontalDivider(Modifier.padding(start = 70.dp), color = LiveFitColors.Line)
                ChipRow(Icons.Rounded.Key, LiveFitColors.ChipAmber, "Re-authorize in Hi Rokid", "Microphone, device and media access", { AuthActivity.launch(activity) })
                HorizontalDivider(Modifier.padding(start = 70.dp), color = LiveFitColors.Line)
                ChipRow(Icons.Rounded.Refresh, LiveFitColors.ChipViolet, "Reconnect", "Opens LiveFit on the glasses", { services.glasses.connect() })
            }
        }
        SectionLabel("Display")
        SoftCard(Modifier.padding(horizontal = 16.dp).fillMaxWidth()) {
            ChipRow(Icons.Rounded.Dashboard, LiveFitColors.ChipSky, "Glasses display", "Size, position, metrics", onDisplay)
        }
        Text("Unpair: Android Settings → Connected devices → Rokid glasses → remove LiveFit's companion access.",
            color = LiveFitColors.InkSoft, modifier = Modifier.padding(20.dp))
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
