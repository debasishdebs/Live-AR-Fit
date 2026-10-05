package com.debasish.livefit.phone.ui.history

import com.debasish.livefit.model.Provenance
import com.debasish.livefit.model.SessionStatus
import com.debasish.livefit.model.SessionSummary
import com.debasish.livefit.model.WorkoutType
import com.debasish.livefit.model.formatElapsed
import com.debasish.livefit.model.EndReason
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Date
import java.util.Locale

object HistoryFormat {
    fun title(s: SessionSummary): String =
        if (s.type == WorkoutType.Auto && s.detectedType != null) "Auto · ${s.detectedType!!.label}" else s.type.label

    fun subtitle(s: SessionSummary, now: Long): String {
        val day = if (sameDay(now, s.startMs)) "Today" else SimpleDateFormat("EEE d MMM", Locale.getDefault()).format(Date(s.startMs))
        return "$day · ${formatElapsed(s.activeMs)}" + (s.avgHr?.let { " · ♥ $it avg" } ?: "")
    }

    private fun sameDay(a: Long, b: Long): Boolean {
        val ca = Calendar.getInstance().apply { timeInMillis = a }
        val cb = Calendar.getInstance().apply { timeInMillis = b }
        return ca.get(Calendar.YEAR) == cb.get(Calendar.YEAR) && ca.get(Calendar.DAY_OF_YEAR) == cb.get(Calendar.DAY_OF_YEAR)
    }

    fun endReason(r: EndReason): String = when (r) {
        EndReason.User -> "Ended by you"
        EndReason.OtherApp -> "Ended by another app"
        EndReason.System -> "Ended by the watch"
        EndReason.Error -> "Ended by an error"
    }

    /** Spec §5.6: Demo = not all samples Live; Incomplete = completion rule unmet (§4.9). */
    fun badge(s: SessionSummary): String? = when {
        s.provenance is Provenance.Fake && s.status == SessionStatus.Incomplete -> "Demo · Incomplete"
        s.provenance is Provenance.Fake -> "Demo"
        s.status == SessionStatus.Incomplete -> "Incomplete"
        else -> null
    }
}
