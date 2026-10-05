package com.debasish.livefit.phone.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.debasish.livefit.phone.ServiceGraph

/** Coordinated-upgrade banner (spec §4.7). */
@Composable
fun OutdatedBanner(services: ServiceGraph, modifier: Modifier = Modifier) {
    val outdated by services.router.outdated.collectAsStateWithLifecycle()
    val device = outdated ?: return
    Text("Update LiveFit on your ${device.name.lowercase()} — its commands are ignored until then",
        color = Color.White, modifier = modifier.fillMaxWidth().background(Color(0xFFE5583B)).statusBarsPadding().padding(12.dp))
}
