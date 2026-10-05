package com.debasish.livefit.phone.ui.settings

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Code
import androidx.compose.material.icons.rounded.Dashboard
import androidx.compose.material.icons.rounded.Info
import androidx.compose.material.icons.rounded.Language
import androidx.compose.material.icons.rounded.Shield
import androidx.compose.material.icons.rounded.Straighten
import androidx.compose.material.icons.rounded.Visibility
import androidx.compose.material.icons.rounded.Watch
import androidx.compose.material3.HorizontalDivider
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.debasish.livefit.phone.ui.components.ChipRow
import com.debasish.livefit.phone.ui.components.ScreenHeader
import com.debasish.livefit.phone.ui.components.SectionLabel
import com.debasish.livefit.phone.ui.components.SoftCard
import com.debasish.livefit.phone.ui.theme.LiveFitColors

@Composable
fun SettingsScreen(
    onBack: () -> Unit,
    onLanguages: () -> Unit,
    onDeveloper: () -> Unit,
    onNavigate: (String) -> Unit,
) {
    Column(Modifier.fillMaxSize().background(LiveFitColors.SurfaceSoft).verticalScroll(rememberScrollState())) {
        ScreenHeader("Settings", onBack)

        SectionLabel("General")
        Group {
            ChipRow(Icons.Rounded.Language, LiveFitColors.ChipSky, "Languages", "Offline voice packs", onLanguages)
            Divider()
            ChipRow(Icons.Rounded.Straighten, LiveFitColors.ChipAmber, "Units", "Metric", { onNavigate("units") })
        }

        SectionLabel("Devices")
        Group {
            ChipRow(Icons.Rounded.Visibility, LiveFitColors.ChipMint, "Rokid glasses", "Via Hi Rokid", { onNavigate("device/glasses") })
            Divider()
            ChipRow(Icons.Rounded.Dashboard, LiveFitColors.ChipSky, "Glasses display", "Size, position, metrics", { onNavigate("hud") })
            Divider()
            ChipRow(Icons.Rounded.Watch, LiveFitColors.ChipViolet, "Galaxy Watch", "Health Services", { onNavigate("device/watch") })
            Divider()
            ChipRow(Icons.Rounded.Shield, LiveFitColors.ChipCoral, "Permissions", null, { onNavigate("permissions") })
        }

        SectionLabel("Advanced")
        Group {
            ChipRow(Icons.Rounded.Code, LiveFitColors.ChipSlate, "Developer tools", "Spike console", onDeveloper)
            Divider()
            ChipRow(Icons.Rounded.Info, LiveFitColors.ChipRose, "About", "Rokid LiveFit 0.1", { onNavigate("about") })
        }
    }
}

@Composable
private fun Group(content: @Composable () -> Unit) {
    SoftCard(Modifier.padding(horizontal = 16.dp).fillMaxWidth()) { Column { content() } }
}

@Composable
private fun Divider() = HorizontalDivider(Modifier.padding(start = 70.dp), color = LiveFitColors.Line)
