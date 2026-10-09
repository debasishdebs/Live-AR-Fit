package com.debasish.livefit.phone.ui.settings

import android.content.Intent
import android.net.Uri
import android.widget.Toast
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.BugReport
import androidx.compose.material.icons.rounded.CheckCircle
import androidx.compose.material.icons.rounded.Code
import androidx.compose.material.icons.rounded.Favorite
import androidx.compose.material.icons.rounded.Policy
import androidx.compose.material.icons.rounded.Straighten
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import com.debasish.livefit.model.Disclosures
import com.debasish.livefit.phone.PhoneCrash
import com.debasish.livefit.phone.ui.components.ChipRow
import com.debasish.livefit.phone.ui.components.IconChip
import com.debasish.livefit.phone.ui.components.ScreenHeader
import com.debasish.livefit.phone.ui.components.SoftCard
import com.debasish.livefit.phone.ui.theme.LiveFitColors
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

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
    val context = LocalContext.current
    // Spec §6: the crash file is read off the main thread; null = nothing recorded.
    val crash by produceState<String?>(null) { value = withContext(Dispatchers.IO) { PhoneCrash.log(context).last() } }
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
                HorizontalDivider(color = LiveFitColors.Line)
                ChipRow(Icons.Rounded.Policy, LiveFitColors.ChipMint, "Privacy policy", "Opens in your browser", {
                    runCatching { context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(Disclosures.PRIVACY_POLICY_URL)).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) }
                        .onFailure { Toast.makeText(context, "No browser found. The policy is at ${Disclosures.PRIVACY_POLICY_URL}", Toast.LENGTH_LONG).show() }
                })
                HorizontalDivider(color = LiveFitColors.Line)
                ChipRow(Icons.Rounded.BugReport, LiveFitColors.ChipSlate, "Share last crash",
                    if (crash == null) "No crash recorded" else "Opens the share sheet — nothing is sent automatically",
                    { crash?.let { context.startActivity(PhoneCrash.shareIntent(it)) } }, enabled = crash != null)
            }
        }
    }
}
