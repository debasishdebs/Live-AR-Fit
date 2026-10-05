package com.debasish.livefit.phone.ui.components

import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.debasish.livefit.model.Command
import com.debasish.livefit.phone.ServiceGraph
import com.debasish.livefit.phone.ui.theme.LiveFitColors

/** The phone's copy of the cross-device confirmation; first answer on any device wins (spec §4.6). */
@Composable
fun HubConfirmationDialog(services: ServiceGraph) {
    val c by services.confirm.pending.collectAsStateWithLifecycle()
    val pending = c ?: return
    AlertDialog(
        onDismissRequest = {},
        title = { Text(pending.title) },
        text = { Text(pending.message) },
        confirmButton = {
            TextButton(onClick = { services.localCommand(Command.Answer(pending.id, yes = true)) }) {
                Text(pending.yesLabel, fontWeight = FontWeight.SemiBold, color = LiveFitColors.MintDeep)
            }
        },
        dismissButton = {
            TextButton(onClick = { services.localCommand(Command.Answer(pending.id, yes = false)) }) { Text(pending.noLabel, color = LiveFitColors.InkSoft) }
        },
        containerColor = Color.White,
    )
}
