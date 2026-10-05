package com.debasish.livefit.phone.ui.history

import com.debasish.livefit.model.Provenance
import com.debasish.livefit.model.SessionStatus
import com.debasish.livefit.model.SessionSummary
import com.debasish.livefit.model.WorkoutType
import com.debasish.livefit.model.formatElapsed
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

object HistoryFormat {
    fun title(s: SessionSummary): String =
        if (s.type == WorkoutType.Auto && s.detectedType != null) "Auto · ${s.detectedType!!.label}" else s.type.label

    fun subtitle(s: SessionSummary, now: Long): String {
        val day = if (now - s.startMs < 86_400_000L) "Today" else SimpleDateFormat("EEE d MMM", Locale.getDefault()).format(Date(s.startMs))
        return "$day · ${formatElapsed(s.activeMs)}" + (s.avgHr?.let { " · ♥ $it avg" } ?: "")
    }

    /** Spec §5.6: Demo = not all samples Live; Incomplete = completion rule unmet (§4.9). */
    fun badge(s: SessionSummary): String? = when {
        s.provenance is Provenance.Fake -> "Demo"
        s.status == SessionStatus.Incomplete -> "Incomplete"
        else -> null
    }
}
