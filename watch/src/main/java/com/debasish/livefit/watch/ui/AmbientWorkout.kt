package com.debasish.livefit.watch.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.sp
import androidx.wear.compose.material.Text
import com.debasish.livefit.model.WorkoutPhase
import com.debasish.livefit.model.WorkoutSnapshot

/** Always-on elapsed time: whole minutes, since the ambient screen refreshes only about once a minute (B1). */
fun ambientElapsed(ms: Long): String {
    val min = ms / 60_000
    return if (min >= 60) "%d h %02d min".format(min / 60, min % 60) else "$min min"
}

/** Low-power workout screen for AOD: grey text on black, no controls, no seconds. Live values return on wake. */
@Composable
internal fun AmbientLive(s: WorkoutSnapshot) {
    val grey = Color(0xFFBDBDBD)
    Column(Modifier.fillMaxSize(), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.Center) {
        Text(s.displayType.label + if (s.phase == WorkoutPhase.Paused) " · paused" else "", fontSize = 14.sp, color = grey)
        Text(ambientElapsed(s.elapsedMs), fontSize = 30.sp, color = Color.White)
        Text("♥ ${s.metrics.heartRate ?: "--"}", fontSize = 20.sp, color = grey)
        Text("${s.metrics.steps} steps", fontSize = 14.sp, color = grey)
    }
}
