package com.debasish.livefit.phone.ui.list.sources

import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Dashboard
import androidx.compose.material.icons.rounded.Favorite
import androidx.compose.material.icons.rounded.Pause
import androidx.compose.material.icons.rounded.PlayArrow
import androidx.compose.material.icons.rounded.QuestionAnswer
import androidx.compose.material.icons.rounded.SkipNext
import androidx.compose.material.icons.rounded.Stop
import androidx.compose.material.icons.rounded.VolumeUp
import androidx.compose.material.icons.rounded.MusicNote
import com.debasish.livefit.phone.ui.list.ActionResult
import com.debasish.livefit.phone.ui.list.ItemAction
import com.debasish.livefit.phone.ui.list.ListItem
import com.debasish.livefit.phone.ui.list.ListSource
import com.debasish.livefit.services.voice.VoiceCommandGroup
import org.json.JSONObject

/** Settings → Voice → Voice commands: one toggle per [VoiceCommandGroup]; yes/no answers are shown but always on. */
class VoiceCommandSource(
    private val disabled: () -> Set<VoiceCommandGroup>,
    private val setEnabled: (VoiceCommandGroup, Boolean) -> Unit,
) : ListSource {
    override val title = "Voice commands"
    override val searchHint = "Search voice commands"
    override val sortable = false // parser order: workout, music, views, answers

    override suspend fun load(filter: JSONObject?): List<ListItem> {
        val off = disabled()
        return VoiceCommandGroup.entries.map { g ->
            ListItem(
                id = g.name,
                title = g.label,
                subtitle = if (g.toggleable) g.examples else "${g.examples} · always on, needed to answer confirmations",
                icon = when (g) {
                    VoiceCommandGroup.StartWorkout -> Icons.Rounded.PlayArrow
                    VoiceCommandGroup.PauseResume -> Icons.Rounded.Pause
                    VoiceCommandGroup.StopWorkout -> Icons.Rounded.Stop
                    VoiceCommandGroup.MusicPlayPause -> Icons.Rounded.MusicNote
                    VoiceCommandGroup.NextPrevious -> Icons.Rounded.SkipNext
                    VoiceCommandGroup.Volume -> @Suppress("DEPRECATION") Icons.Rounded.VolumeUp
                    VoiceCommandGroup.Like -> Icons.Rounded.Favorite
                    VoiceCommandGroup.PageViews -> Icons.Rounded.Dashboard
                    VoiceCommandGroup.YesNo -> Icons.Rounded.QuestionAnswer
                },
                toggle = g !in off,
            )
        }
    }

    override fun actionFor(item: ListItem): ItemAction? {
        val g = VoiceCommandGroup.entries.firstOrNull { it.name == item.id }?.takeIf { it.toggleable } ?: return null
        return ItemAction { setEnabled(g, item.toggle != true); ActionResult.Silent } // the list reloads after each action
    }
}
