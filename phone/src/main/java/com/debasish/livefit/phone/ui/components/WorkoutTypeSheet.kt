package com.debasish.livefit.phone.ui.components

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import com.debasish.livefit.model.WorkoutType
import com.debasish.livefit.phone.ui.theme.LiveFitColors

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun WorkoutTypeSheet(onPick: (WorkoutType) -> Unit, onDismiss: () -> Unit) {
    ModalBottomSheet(onDismissRequest = onDismiss, containerColor = Color.White) {
        Column(Modifier.padding(horizontal = 20.dp).padding(bottom = 32.dp)) {
            Text("Choose workout", style = MaterialTheme.typography.titleLarge)
            Text("Or say \"start a run\" on the glasses", style = MaterialTheme.typography.bodyMedium, color = LiveFitColors.InkSoft)
            Spacer(Modifier.height(20.dp))
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                WorkoutType.entries.forEach { type ->
                    Column(Modifier.clickable { onPick(type) }.padding(8.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                        IconChip(type.icon, type.chip, size = 64.dp, shapeRadius = 20.dp)
                        Spacer(Modifier.height(8.dp))
                        Text(type.label, style = MaterialTheme.typography.titleMedium)
                    }
                }
            }
        }
    }
}
