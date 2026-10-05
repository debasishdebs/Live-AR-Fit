package com.debasish.livefit.phone.ui.list.sources

import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.DirectionsBike
import androidx.compose.material.icons.automirrored.rounded.DirectionsRun
import androidx.compose.material.icons.automirrored.rounded.DirectionsWalk
import com.debasish.livefit.phone.ui.list.ItemAction
import com.debasish.livefit.phone.ui.list.ListItem
import com.debasish.livefit.phone.ui.list.ListSource
import org.json.JSONObject

/** Mock-up history. The live version will read finished sessions from the workout store. */
class WorkoutHistorySource : ListSource {
    override val title = "Activity"
    override val searchHint = "Search workouts"

    override suspend fun load(filter: JSONObject?): List<ListItem> = listOf(
        ListItem("w1", "Evening walk", "Today · 34:12 · ♥ 112 avg", icon = Icons.AutoMirrored.Rounded.DirectionsWalk, trailingText = "3.1 km"),
        ListItem("w2", "Morning run", "Yesterday · 28:40 · ♥ 151 avg", icon = Icons.AutoMirrored.Rounded.DirectionsRun, trailingText = "4.9 km"),
        ListItem("w3", "Cycle commute", "Fri · 41:05 · ♥ 128 avg", icon = Icons.AutoMirrored.Rounded.DirectionsBike, trailingText = "14.2 km"),
        ListItem("w4", "Lunch walk", "Thu · 22:18 · ♥ 104 avg", icon = Icons.AutoMirrored.Rounded.DirectionsWalk, trailingText = "1.9 km"),
        ListItem("w5", "Interval run", "Tue · 31:55 · ♥ 162 avg", icon = Icons.AutoMirrored.Rounded.DirectionsRun, trailingText = "5.6 km"),
    )

    override fun actionFor(item: ListItem): ItemAction? = null
}
