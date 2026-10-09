package com.debasish.livefit.phone.ui.components

import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.PowerManager
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.KeyboardArrowRight
import androidx.compose.material.icons.rounded.Add
import androidx.compose.material3.Button
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
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.LifecycleResumeEffect
import com.debasish.livefit.phone.BatteryAdvice
import com.debasish.livefit.phone.ui.theme.LiveFitColors

/** Spec §4: bold banner on every app open while LiveFit is not exempt from battery optimisation. */
@Composable
fun BatteryBannerCard(modifier: Modifier = Modifier) {
    val context = LocalContext.current
    var exempt by remember { mutableStateOf(isExempt(context)) }
    LifecycleResumeEffect(Unit) {
        exempt = isExempt(context) // back from Settings: the banner disappears once exempt
        onPauseOrDispose { }
    }
    val banner = BatteryAdvice.banner(exempt, Build.MANUFACTURER) ?: return
    Column(modifier.fillMaxWidth().clip(RoundedCornerShape(20.dp)).background(Color(0xFFFFF1D6)).padding(16.dp)) {
        Text(banner.headline, style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.Bold, color = LiveFitColors.Ink)
        Spacer(Modifier.height(10.dp))
        if (banner.samsung) SamsungSteps(banner.steps)
        else banner.steps.forEachIndexed { i, s -> Text("${i + 1}. $s", style = MaterialTheme.typography.bodySmall, color = LiveFitColors.InkSoft) }
        Spacer(Modifier.height(10.dp))
        Button(onClick = { openBatterySettings(context) }) { Text("Open settings") }
    }
}

private fun isExempt(context: Context): Boolean =
    context.getSystemService(PowerManager::class.java).isIgnoringBatteryOptimizations(context.packageName)

fun openBatterySettings(context: Context) {
    for (action in BatteryAdvice.SETTINGS_ACTIONS) {
        try { context.startActivity(Intent(action).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)); return } catch (_: ActivityNotFoundException) { }
    }
}

/** Screenshot-style illustration of Samsung's path: settings rows with chevrons, the final "Add" row highlighted. */
@Composable
private fun SamsungSteps(steps: List<String>) {
    Column(Modifier.fillMaxWidth().clip(RoundedCornerShape(14.dp)).background(Color.White)) {
        steps.forEachIndexed { i, s ->
            val last = i == steps.lastIndex
            Row(
                Modifier.fillMaxWidth().background(if (last) LiveFitColors.Mint.copy(alpha = 0.18f) else Color.Transparent).padding(horizontal = 14.dp, vertical = 10.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(s, Modifier.weight(1f), style = MaterialTheme.typography.bodyMedium, fontWeight = if (last) FontWeight.Bold else FontWeight.Normal, color = LiveFitColors.Ink)
                Icon(if (last) Icons.Rounded.Add else Icons.AutoMirrored.Rounded.KeyboardArrowRight, contentDescription = null, tint = LiveFitColors.InkSoft)
            }
            if (!last) HorizontalDivider(color = LiveFitColors.SurfaceSoft)
        }
    }
}
