package com.debasish.livefit.phone.ui.linked

import android.app.Activity
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Favorite
import androidx.compose.material.icons.rounded.Link
import androidx.compose.material.icons.rounded.Watch
import androidx.compose.material3.HorizontalDivider
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.debasish.livefit.model.DeviceKind
import com.debasish.livefit.model.LinkState
import com.debasish.livefit.phone.CompanionLinker
import com.debasish.livefit.phone.ServiceGraph
import com.debasish.livefit.phone.ui.components.ChipRow
import com.debasish.livefit.phone.ui.components.ScreenHeader
import com.debasish.livefit.phone.ui.components.SectionLabel
import com.debasish.livefit.phone.ui.components.SoftCard
import com.debasish.livefit.phone.ui.theme.LiveFitColors

@Composable
fun LinkedWatchScreen(services: ServiceGraph, onBack: () -> Unit, toast: (String) -> Unit) {
    val activity = LocalContext.current as Activity
    val st by services.watch.status.collectAsStateWithLifecycle()
    Column(Modifier.fillMaxSize().background(LiveFitColors.SurfaceSoft).verticalScroll(rememberScrollState())) {
        ScreenHeader("Galaxy Watch", onBack)
        SoftCard(Modifier.padding(horizontal = 16.dp).fillMaxWidth()) {
            ChipRow(Icons.Rounded.Watch, LiveFitColors.ChipViolet, st.name, "${st.link.name}${st.batteryPct?.let { " · $it%" } ?: ""}${st.detail?.let { " · $it" } ?: ""}", {}, trailing = {})
        }
        val reachable = st.link == LinkState.Connected
        val installed = st.batteryPct != null
        SoftCard(Modifier.padding(horizontal = 16.dp).fillMaxWidth().padding(top = 12.dp)) {
            Column {
                ChipRow(Icons.Rounded.Link, LiveFitColors.ChipSky, if (reachable) "Reachable" else "Unreachable", if (reachable) "Watch is connected" else (st.detail ?: "Watch is not connected"), {}, trailing = {})
                HorizontalDivider(Modifier.padding(start = 70.dp), color = LiveFitColors.Line)
                ChipRow(Icons.Rounded.Watch, LiveFitColors.ChipViolet, if (installed) "Watch app installed" else "Watch app not found",
                    if (installed) "Rokid LiveFit on the watch is responding" else "Install Rokid LiveFit on the watch, then open it once", {}, trailing = {})
            }
        }
        SectionLabel("Link")
        SoftCard(Modifier.padding(horizontal = 16.dp).fillMaxWidth()) {
            Column {
                ChipRow(Icons.Rounded.Link, LiveFitColors.ChipSky, "Pair / re-pair", "Lets Android wake LiveFit when the watch is near",
                    { CompanionLinker.associate(activity, DeviceKind.Watch) { ok -> toast(if (ok) "Watch paired" else "Pairing cancelled") } })
                HorizontalDivider(Modifier.padding(start = 70.dp), color = LiveFitColors.Line)
                ChipRow(Icons.Rounded.Favorite, LiveFitColors.ChipCoral, "Sensor permissions", "Granted on the watch: open Rokid LiveFit on the watch and tap Allow", {}, trailing = {})
            }
        }
    }
}
