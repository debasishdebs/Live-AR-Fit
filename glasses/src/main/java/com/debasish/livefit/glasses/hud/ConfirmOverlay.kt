package com.debasish.livefit.glasses.hud

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Check
import androidx.compose.material.icons.outlined.Close
import androidx.compose.material.icons.outlined.Mic
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.debasish.livefit.model.Confirmation

/** Outlined ✓ Yes / ✕ No; highlight = brighter + thicker outline (monochrome rules, spec §6.3). */
@Composable
fun ConfirmOverlay(c: Confirmation, highlightYes: Boolean, listening: Boolean, modifier: Modifier = Modifier) {
    Column(
        modifier.background(Color.Black).border(2.dp, Hud.Green.copy(alpha = Hud.SECONDARY), RoundedCornerShape(16.dp)).padding(16.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Label(c.title, 28.sp, Hud.PRIMARY, FontWeight.Bold)
        Spacer(Modifier.height(10.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(16.dp)) {
            Choice(Icons.Outlined.Check, c.yesLabel, selected = highlightYes)
            Choice(Icons.Outlined.Close, c.noLabel, selected = !highlightYes)
        }
        if (listening) {
            Spacer(Modifier.height(8.dp))
            Row(verticalAlignment = Alignment.CenterVertically) {
                Glyph(Icons.Outlined.Mic, 22.dp, Hud.SECONDARY)
                Label(" say yes or no", 20.sp, Hud.TERTIARY)
            }
        }
    }
}

@Composable
private fun Choice(icon: ImageVector, label: String, selected: Boolean) {
    val level = if (selected) Hud.PRIMARY else Hud.TERTIARY
    Row(
        Modifier.border(if (selected) 4.dp else 2.dp, Hud.Green.copy(alpha = level), RoundedCornerShape(12.dp)).padding(horizontal = 14.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Glyph(icon, 28.dp, level)
        Label(" $label", 26.sp, level, FontWeight.Bold)
    }
}
