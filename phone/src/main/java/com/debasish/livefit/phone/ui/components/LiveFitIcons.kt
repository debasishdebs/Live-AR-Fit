package com.debasish.livefit.phone.ui.components

import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.DirectionsBike
import androidx.compose.material.icons.automirrored.rounded.DirectionsRun
import androidx.compose.material.icons.automirrored.rounded.DirectionsWalk
import androidx.compose.material.icons.rounded.AutoMode
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import com.debasish.livefit.model.WorkoutType
import com.debasish.livefit.phone.ui.theme.LiveFitColors

val WorkoutType.icon: ImageVector
    get() = when (this) {
        WorkoutType.Walk -> Icons.AutoMirrored.Rounded.DirectionsWalk
        WorkoutType.Run -> Icons.AutoMirrored.Rounded.DirectionsRun
        WorkoutType.Cycle -> Icons.AutoMirrored.Rounded.DirectionsBike
        WorkoutType.Auto -> Icons.Rounded.AutoMode
    }

val WorkoutType.chip: Pair<Color, Color>
    get() = when (this) {
        WorkoutType.Walk -> LiveFitColors.ChipMint
        WorkoutType.Run -> LiveFitColors.ChipCoral
        WorkoutType.Cycle -> LiveFitColors.ChipSky
        WorkoutType.Auto -> LiveFitColors.ChipViolet
    }

/** Zone colours 0..5 (rest, warm-up, fat burn, cardio, hard, peak). */
fun zoneColor(zone: Int?): Color = when (zone) {
    1 -> Color(0xFF7FB8FF)
    2 -> Color(0xFF14C3A2)
    3 -> Color(0xFFF2B41B)
    4 -> Color(0xFFF07A3C)
    5 -> Color(0xFFE5443B)
    else -> Color(0xFFC5CBD3)
}
