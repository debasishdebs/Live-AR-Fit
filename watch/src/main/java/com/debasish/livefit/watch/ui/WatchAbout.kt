package com.debasish.livefit.watch.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.sp
import androidx.wear.compose.foundation.lazy.ScalingLazyColumn
import androidx.wear.compose.material.Chip
import androidx.wear.compose.material.ChipDefaults
import androidx.wear.compose.material.CompactChip
import androidx.wear.compose.material.Text
import com.debasish.livefit.model.Disclosures
import com.debasish.livefit.watch.BuildConfig
import com.debasish.livefit.watch.OpenOnPhone
import kotlinx.coroutines.launch

/** Spec §7 (review P2-8): Privacy policy opens on the paired phone; the short summary is always shown as the fallback. */
@Composable
internal fun AboutPage(onClose: () -> Unit) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var note by remember { mutableStateOf<String?>(null) }
    ScalingLazyColumn(Modifier.fillMaxSize().background(Color.Black), horizontalAlignment = Alignment.CenterHorizontally) {
        item { Text("Live AR Fit", fontSize = 16.sp, fontWeight = FontWeight.SemiBold) }
        item { Text("Version ${BuildConfig.VERSION_NAME}", fontSize = 11.sp, color = W.Dim) }
        item {
            Chip(
                onClick = { scope.launch { note = if (OpenOnPhone.open(context, Disclosures.PRIVACY_POLICY_URL)) "Opened on your phone" else "Couldn't reach the phone" } },
                label = { Text("Privacy policy") }, secondaryLabel = { Text("Open on phone") }, colors = ChipDefaults.secondaryChipColors(),
            )
        }
        note?.let { item { Text(it, fontSize = 11.sp, color = W.Mint) } }
        item { Text(Disclosures.WATCH_SUMMARY, fontSize = 11.sp, color = W.Dim, textAlign = TextAlign.Center) }
        item { CompactChip(onClick = onClose, label = { Text("Close") }) }
    }
}
