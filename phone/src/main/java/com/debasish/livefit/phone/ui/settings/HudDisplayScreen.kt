package com.debasish.livefit.phone.ui.settings

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.DirectionsWalk
import androidx.compose.material.icons.rounded.Bolt
import androidx.compose.material.icons.rounded.Favorite
import androidx.compose.material.icons.rounded.LocalFireDepartment
import androidx.compose.material.icons.rounded.MusicNote
import androidx.compose.material.icons.rounded.Route
import androidx.compose.material.icons.rounded.ShowChart
import androidx.compose.material.icons.rounded.SignalCellularAlt
import androidx.compose.material.icons.rounded.Timer
import androidx.compose.material.icons.rounded.ZoomOutMap
import androidx.compose.material.icons.automirrored.rounded.DirectionsRun
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
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
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.debasish.livefit.model.HudItem
import com.debasish.livefit.model.HudPosition
import com.debasish.livefit.phone.SettingsStore
import com.debasish.livefit.phone.ui.components.ChipRow
import com.debasish.livefit.phone.ui.components.ScreenHeader
import com.debasish.livefit.phone.ui.components.SectionLabel
import com.debasish.livefit.phone.ui.components.SoftCard
import com.debasish.livefit.phone.ui.theme.LiveFitColors
import kotlin.math.roundToInt

private val HudItem.icon: ImageVector
    get() = when (this) {
        HudItem.WorkoutType -> Icons.AutoMirrored.Rounded.DirectionsRun
        HudItem.Timer -> Icons.Rounded.Timer
        HudItem.HeartRate -> Icons.Rounded.Favorite
        HudItem.Calories -> Icons.Rounded.LocalFireDepartment
        HudItem.HeartTrend -> Icons.Rounded.ShowChart
        HudItem.Steps -> Icons.AutoMirrored.Rounded.DirectionsWalk
        HudItem.Distance -> Icons.Rounded.Route
        HudItem.Speed -> Icons.Rounded.Bolt
        HudItem.Music -> Icons.Rounded.MusicNote
        HudItem.StatusBar -> Icons.Rounded.SignalCellularAlt
    }

private val HudItem.chip: Pair<Color, Color>
    get() = when (this) {
        HudItem.HeartRate, HudItem.HeartTrend -> LiveFitColors.ChipCoral
        HudItem.Calories -> LiveFitColors.ChipAmber
        HudItem.Steps, HudItem.WorkoutType -> LiveFitColors.ChipMint
        HudItem.Distance -> LiveFitColors.ChipViolet
        HudItem.Speed, HudItem.Timer -> LiveFitColors.ChipSky
        HudItem.Music -> LiveFitColors.ChipRose
        HudItem.StatusBar -> LiveFitColors.ChipSlate
    }

/**
 * Settings → Glasses display: workout-HUD size, placement and which elements are shown.
 * Edits are a draft until Apply (header) or Back, which auto-applies unsaved changes.
 */
@Composable
fun HudDisplayScreen(store: SettingsStore, glassesConnected: Boolean, onApplied: (String) -> Unit, onBack: () -> Unit) {
    val saved by store.hud.collectAsStateWithLifecycle()
    var hud by remember { mutableStateOf(saved) }
    val dirty = hud != saved
    val apply = {
        store.updateHud { hud }
        onApplied(if (glassesConnected) "Sent to glasses" else "Saved · applies when glasses connect")
    }
    val leave = { if (dirty) apply(); onBack() }
    androidx.activity.compose.BackHandler(onBack = leave)

    Column(Modifier.fillMaxSize().background(LiveFitColors.SurfaceSoft).verticalScroll(rememberScrollState())) {
        ScreenHeader("Glasses display", leave) {
            Box(
                Modifier.padding(end = 8.dp).clip(RoundedCornerShape(20.dp))
                    .background(if (dirty) LiveFitColors.Mint else LiveFitColors.Line)
                    .clickable(enabled = dirty, onClick = apply)
                    .padding(horizontal = 16.dp, vertical = 8.dp),
            ) { Text("Apply", color = if (dirty) Color.White else LiveFitColors.InkSoft, style = MaterialTheme.typography.titleMedium) }
        }

        // Preview of the glasses view (3:4) with the HUD block at its size and position.
        SoftCard(Modifier.padding(horizontal = 16.dp).fillMaxWidth()) {
            Column(Modifier.padding(16.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                BoxWithConstraints(Modifier.fillMaxWidth(0.55f).aspectRatio(3f / 4f).clip(RoundedCornerShape(16.dp)).background(Color.Black)) {
                    val w = maxWidth * hud.scale
                    val h = maxHeight * hud.scale
                    val x = (maxWidth - w) * (hud.position.col / 2f)
                    val y = (maxHeight - h) * (hud.position.row / 2f)
                    Box(
                        Modifier.offset(x, y).size(w, h).border(2.dp, Color(0xFF3CFF6E), RoundedCornerShape(8.dp)).background(Color(0x223CFF6E), RoundedCornerShape(8.dp)),
                        contentAlignment = Alignment.Center,
                    ) { Text("HUD", color = Color(0xFF3CFF6E), style = MaterialTheme.typography.labelMedium) }
                }
                Spacer(Modifier.height(8.dp))
                Text("Black areas stay see-through", style = MaterialTheme.typography.labelMedium, color = LiveFitColors.InkSoft)
            }
        }

        SectionLabel("Size")
        SoftCard(Modifier.padding(horizontal = 16.dp).fillMaxWidth()) {
            Row(Modifier.padding(16.dp), verticalAlignment = Alignment.CenterVertically) {
                com.debasish.livefit.phone.ui.components.IconChip(Icons.Rounded.ZoomOutMap, LiveFitColors.ChipMint)
                Slider(
                    value = hud.scale, onValueChange = { v -> hud = hud.copy(scale = (v * 20).roundToInt() / 20f) },
                    valueRange = 0.3f..1f, modifier = Modifier.weight(1f).padding(horizontal = 12.dp),
                    colors = SliderDefaults.colors(thumbColor = LiveFitColors.Mint, activeTrackColor = LiveFitColors.Mint, inactiveTrackColor = LiveFitColors.ChipMint.first),
                )
                Text("${(hud.scale * 100).roundToInt()}%", style = MaterialTheme.typography.titleMedium)
            }
        }

        SectionLabel("Position")
        SoftCard(Modifier.padding(horizontal = 16.dp).fillMaxWidth()) {
            Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                for (row in 0..2) {
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        for (col in 0..2) {
                            val pos = HudPosition.entries.first { it.row == row && it.col == col }
                            val selected = pos == hud.position
                            Box(
                                Modifier.weight(1f).height(48.dp).clip(RoundedCornerShape(12.dp))
                                    .background(if (selected) LiveFitColors.ChipMint.first else LiveFitColors.SurfaceSoft)
                                    .border(if (selected) 2.dp else 1.dp, if (selected) LiveFitColors.Mint else LiveFitColors.Line, RoundedCornerShape(12.dp))
                                    .clickable { hud = hud.copy(position = pos) },
                                contentAlignment = when (pos) {
                                    HudPosition.TopLeft -> Alignment.TopStart; HudPosition.TopCenter -> Alignment.TopCenter; HudPosition.TopRight -> Alignment.TopEnd
                                    HudPosition.CenterLeft -> Alignment.CenterStart; HudPosition.Center -> Alignment.Center; HudPosition.CenterRight -> Alignment.CenterEnd
                                    HudPosition.BottomLeft -> Alignment.BottomStart; HudPosition.BottomCenter -> Alignment.BottomCenter; HudPosition.BottomRight -> Alignment.BottomEnd
                                },
                            ) {
                                Box(Modifier.padding(8.dp).size(18.dp, 12.dp).clip(RoundedCornerShape(3.dp)).background(if (selected) LiveFitColors.Mint else Color(0xFFC5CBD3)))
                            }
                        }
                    }
                }
                Text(hud.position.label, style = MaterialTheme.typography.labelMedium, color = LiveFitColors.InkSoft)
            }
        }

        SectionLabel("Show on glasses")
        SoftCard(Modifier.padding(horizontal = 16.dp).fillMaxWidth()) {
            Column {
                HudItem.entries.forEachIndexed { i, item ->
                    val on = item in hud.items
                    val toggle = { hud = hud.copy(items = if (on) hud.items - item else hud.items + item) }
                    ChipRow(item.icon, item.chip, item.label, null, onClick = toggle, trailing = {
                        Switch(checked = on, onCheckedChange = { toggle() }, colors = SwitchDefaults.colors(checkedTrackColor = LiveFitColors.Mint))
                    })
                    if (i < HudItem.entries.lastIndex) HorizontalDivider(Modifier.padding(start = 70.dp), color = LiveFitColors.Line)
                }
            }
        }
        Spacer(Modifier.height(32.dp))
    }
}
