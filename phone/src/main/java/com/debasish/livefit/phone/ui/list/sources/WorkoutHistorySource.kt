package com.debasish.livefit.phone.ui.list.sources

import com.debasish.livefit.model.WorkoutType
import com.debasish.livefit.phone.ui.components.icon
import com.debasish.livefit.phone.ui.history.HistoryFormat
import com.debasish.livefit.phone.ui.list.ActionResult
import com.debasish.livefit.phone.ui.list.ItemAction
import com.debasish.livefit.phone.ui.list.ListItem
import com.debasish.livefit.phone.ui.list.ListSource
import com.debasish.livefit.services.HistoryStore
import kotlinx.coroutines.flow.first
import org.json.JSONObject

/** Activity tab: LiveFit sessions only (owner decision), newest first. */
class WorkoutHistorySource(private val history: HistoryStore, private val open: (String) -> Unit) : ListSource {
    override val title = "Activity"
    override val searchHint = "Search workouts"
    override val sortable = false

    override suspend fun load(filter: JSONObject?): List<ListItem> {
        val now = System.currentTimeMillis()
        return history.sessions.first().map { s ->
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

    override fun actionFor(item: ListItem) = ItemAction { open(item.id); ActionResult.Silent }
}
