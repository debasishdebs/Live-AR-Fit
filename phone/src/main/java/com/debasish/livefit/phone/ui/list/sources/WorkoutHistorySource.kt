package com.debasish.livefit.phone.ui.list.sources

import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.CalendarMonth
import com.debasish.livefit.model.WorkoutType
import com.debasish.livefit.phone.ui.AppActivity
import com.debasish.livefit.phone.ui.components.icon
import com.debasish.livefit.phone.ui.history.DailyActivity
import com.debasish.livefit.phone.ui.history.HistoryFormat
import com.debasish.livefit.phone.ui.list.ActionResult
import com.debasish.livefit.phone.ui.list.ItemAction
import com.debasish.livefit.phone.ui.list.ListItem
import com.debasish.livefit.phone.ui.list.ListSource
import com.debasish.livefit.phone.ui.list.ListSources
import com.debasish.livefit.services.HistoryStore
import kotlinx.coroutines.flow.first
import org.json.JSONObject
import java.time.LocalDate
import java.time.ZoneId

/** Activity tab level 1: one row per local calendar day, newest first, with the day's totals. */
class WorkoutDaysSource(private val history: HistoryStore, private val open: (String) -> Unit) : ListSource {
    override val title = "Activity"
    override val searchHint = "Search days"
    override val sortable = false

    override suspend fun load(filter: JSONObject?): List<ListItem> {
        val zone = ZoneId.systemDefault()
        val today = LocalDate.now(zone)
        return DailyActivity.group(history.sessions.first(), zone).map { d ->
            ListItem(
                id = d.date.toString(),
                title = DailyActivity.dayLabel(d.date, today),
                subtitle = DailyActivity.summary(d),
                icon = Icons.Rounded.CalendarMonth,
                trailingText = "%.1f km".format(d.distanceKm),
            )
        }
    }

    override fun actionFor(item: ListItem) = ItemAction {
        open(AppActivity.listRoute(ListSources.WORKOUTS, JSONObject().put("date", item.id).toString()))
        ActionResult.Silent
    }
}

/** Activity tab level 2: LiveFit sessions only (owner decision), newest first; `{"date":"yyyy-MM-dd"}` limits it to one local day. */
class WorkoutHistorySource(private val history: HistoryStore, private val open: (String) -> Unit) : ListSource {
    override val title = "Activity"
    override val searchHint = "Search workouts"
    override val sortable = false

    override fun titleFor(filter: JSONObject?): String =
        dateOf(filter)?.let { DailyActivity.dayLabel(it, LocalDate.now(ZoneId.systemDefault())) } ?: title

    override suspend fun load(filter: JSONObject?): List<ListItem> {
        val now = System.currentTimeMillis()
        val zone = ZoneId.systemDefault()
        val all = history.sessions.first()
        val sessions = dateOf(filter)?.let { DailyActivity.sessionsOn(all, it, zone) } ?: all
        return sessions.map { s ->
            val badge = HistoryFormat.badge(s)
            ListItem(
                id = s.id,
                title = HistoryFormat.title(s) + (badge?.let { " · $it" } ?: ""),
                subtitle = HistoryFormat.subtitle(s, now),
                icon = (s.detectedType ?: s.type).takeIf { it != WorkoutType.Auto }?.icon ?: s.type.icon,
                trailingText = "%.1f km".format(s.distanceKm),
            )
        }
    }

    override fun actionFor(item: ListItem) = ItemAction { open("session/${item.id}"); ActionResult.Silent }

    private fun dateOf(filter: JSONObject?): LocalDate? =
        filter?.optString("date")?.takeIf { it.isNotBlank() }?.let { runCatching { LocalDate.parse(it) }.getOrNull() }
}
