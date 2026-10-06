package com.debasish.livefit.phone.ui.history

import com.debasish.livefit.model.Provenance
import com.debasish.livefit.model.SessionStatus
import com.debasish.livefit.model.SessionSummary
import com.debasish.livefit.model.formatElapsed
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale
import kotlin.math.roundToInt

/** One local calendar day of finished workouts (Activity tab, level 1). [sessions] are newest first. */
data class DaySummary(
    val date: LocalDate,
    val sessions: List<SessionSummary>,
    val activeMs: Long,
    val kcal: Int,
    val steps: Int,
    val distanceKm: Double,
    /** Average of the sessions' avg HR weighted by active time; null when no session has HR. */
    val avgHr: Int?,
    val maxHr: Int?,
    val demoCount: Int,
    val incompleteCount: Int,
) {
    val workouts: Int get() = sessions.size
}

/** Pure grouping/aggregation behind the Activity tab: sessions belong to the local day they started on. */
object DailyActivity {
    fun dateOf(s: SessionSummary, zone: ZoneId): LocalDate = Instant.ofEpochMilli(s.startMs).atZone(zone).toLocalDate()

    fun group(sessions: List<SessionSummary>, zone: ZoneId): List<DaySummary> =
        sessions.groupBy { dateOf(it, zone) }
            .map { (date, list) -> summarize(date, list.sortedByDescending { it.startMs }) }
            .sortedByDescending { it.date }

    fun sessionsOn(sessions: List<SessionSummary>, date: LocalDate, zone: ZoneId): List<SessionSummary> =
        sessions.filter { dateOf(it, zone) == date }.sortedByDescending { it.startMs }

    private fun summarize(date: LocalDate, list: List<SessionSummary>): DaySummary {
        val withHr = list.filter { it.avgHr != null }
        val weight = withHr.sumOf { it.activeMs }
        val avgHr = when {
            withHr.isEmpty() -> null
            weight > 0 -> (withHr.sumOf { it.avgHr!!.toDouble() * it.activeMs } / weight).roundToInt()
            else -> withHr.map { it.avgHr!! }.average().roundToInt()
        }
        return DaySummary(
            date = date,
            sessions = list,
            activeMs = list.sumOf { it.activeMs },
            kcal = list.sumOf { it.kcal },
            steps = list.sumOf { it.steps },
            distanceKm = list.sumOf { it.distanceKm },
            avgHr = avgHr,
            maxHr = list.mapNotNull { it.maxHr }.maxOrNull(),
            demoCount = list.count { it.provenance is Provenance.Fake },
            incompleteCount = list.count { it.status == SessionStatus.Incomplete },
        )
    }

    fun dayLabel(date: LocalDate, today: LocalDate): String = when (date) {
        today -> "Today"
        today.minusDays(1) -> "Yesterday"
        else -> date.format(DateTimeFormatter.ofPattern(if (date.year == today.year) "EEE d MMM" else "EEE d MMM yyyy", Locale.getDefault()))
    }

    /** Row subtitle: count · time · kcal, then steps · HR, then Demo/Incomplete markers when present. */
    fun summary(d: DaySummary): String = buildList {
        add("${d.workouts} workout${if (d.workouts == 1) "" else "s"} · ${formatElapsed(d.activeMs)} · ${d.kcal} kcal")
        add(buildString {
            append("%,d steps".format(d.steps))
            d.avgHr?.let { append(" · ♥ $it avg") }
            d.maxHr?.let { append(" · $it max") }
        })
        val marks = listOfNotNull(d.demoCount.takeIf { it > 0 }?.let { "Demo $it" }, d.incompleteCount.takeIf { it > 0 }?.let { "Incomplete $it" })
        if (marks.isNotEmpty()) add(marks.joinToString(" · "))
    }.joinToString("\n")
}
