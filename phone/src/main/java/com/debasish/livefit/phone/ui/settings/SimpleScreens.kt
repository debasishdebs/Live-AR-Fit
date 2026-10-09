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
import androidx.compose.material.icons.rounded.Straighten
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.debasish.livefit.phone.ui.components.ChipRow
import com.debasish.livefit.phone.ui.components.IconChip
import com.debasish.livefit.phone.ui.components.ScreenHeader
import com.debasish.livefit.phone.ui.components.SoftCard
import com.debasish.livefit.phone.ui.theme.LiveFitColors

/** V1 shows metric values only, so Metric is information, not a choice (review #15). */
@Composable
fun UnitsScreen(onBack: () -> Unit) {
    Column(Modifier.fillMaxSize().background(LiveFitColors.SurfaceSoft)) {
        ScreenHeader("Units", onBack)
        SoftCard(Modifier.padding(16.dp).fillMaxWidth()) {
            Column {
                ChipRow(Icons.Rounded.Straighten, LiveFitColors.ChipAmber, "Metric", "km · kg · km/h", {}, trailing = { Icon(Icons.Rounded.CheckCircle, null, tint = LiveFitColors.Mint) })
            }
        }
        Text("LiveFit shows workouts in metric units.", style = MaterialTheme.typography.bodySmall, color = LiveFitColors.InkSoft,
            modifier = Modifier.padding(horizontal = 24.dp))
    }
}

@Composable
fun AboutScreen(onBack: () -> Unit) {
    Column(Modifier.fillMaxSize().background(LiveFitColors.SurfaceSoft), horizontalAlignment = Alignment.CenterHorizontally) {
        ScreenHeader("About", onBack)
        Spacer(Modifier.height(24.dp))
        IconChip(Icons.Rounded.Favorite, LiveFitColors.ChipMint, size = 88.dp, shapeRadius = 28.dp)
        Spacer(Modifier.height(12.dp))
        Text("Live AR Fit", style = MaterialTheme.typography.headlineMedium)
        Text("Version ${com.debasish.livefit.phone.BuildConfig.VERSION_NAME}", color = LiveFitColors.InkSoft)
        SoftCard(Modifier.padding(16.dp).fillMaxWidth()) {
            Column {
                ChipRow(Icons.Rounded.Code, LiveFitColors.ChipSlate, "Built on", "Rokid CXR-L · Wear Health Services", {}, trailing = {})
            }
        }
    }
}
