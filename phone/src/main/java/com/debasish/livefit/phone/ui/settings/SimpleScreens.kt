package com.debasish.livefit.phone.ui.settings

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.CheckCircle
import androidx.compose.material.icons.rounded.Code
import androidx.compose.material.icons.rounded.Favorite
import androidx.compose.material.icons.rounded.Public
import androidx.compose.material.icons.rounded.Straighten
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.debasish.livefit.phone.ui.components.ChipRow
import com.debasish.livefit.phone.ui.components.IconChip
import com.debasish.livefit.phone.ui.components.ScreenHeader
import com.debasish.livefit.phone.ui.components.SoftCard
import com.debasish.livefit.phone.ui.theme.LiveFitColors

@Composable
fun UnitsScreen(onBack: () -> Unit) {
    var metric by remember { mutableStateOf(true) }
    Column(Modifier.fillMaxSize().background(LiveFitColors.SurfaceSoft)) {
        ScreenHeader("Units", onBack)
        SoftCard(Modifier.padding(16.dp).fillMaxWidth()) {
            Column {
                ChipRow(Icons.Rounded.Straighten, LiveFitColors.ChipAmber, "Metric", "km · kg · km/h", { metric = true }, trailing = { if (metric) Icon(Icons.Rounded.CheckCircle, null, tint = LiveFitColors.Mint) })
                HorizontalDivider(Modifier.padding(start = 70.dp), color = LiveFitColors.Line)
                ChipRow(Icons.Rounded.Public, LiveFitColors.ChipSky, "Imperial", "mi · lb · mph", { metric = false }, trailing = { if (!metric) Icon(Icons.Rounded.CheckCircle, null, tint = LiveFitColors.Mint) })
            }
        }
    }
}

@Composable
fun AboutScreen(onBack: () -> Unit) {
    Column(Modifier.fillMaxSize().background(LiveFitColors.SurfaceSoft), horizontalAlignment = Alignment.CenterHorizontally) {
        ScreenHeader("About", onBack)
        Spacer(Modifier.height(24.dp))
        IconChip(Icons.Rounded.Favorite, LiveFitColors.ChipMint, size = 88.dp, shapeRadius = 28.dp)
        Spacer(Modifier.height(12.dp))
        Text("Rokid LiveFit", style = MaterialTheme.typography.headlineMedium)
        Text("Version 0.1 · design preview", color = LiveFitColors.InkSoft)
        SoftCard(Modifier.padding(16.dp).fillMaxWidth()) {
            Column {
                ChipRow(Icons.Rounded.Code, LiveFitColors.ChipSlate, "Built on", "Rokid CXR-L · Wear Health Services", {}, trailing = {})
            }
        }
    }
}
