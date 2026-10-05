package com.debasish.livefit.phone.ui.list

import android.content.Context
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.KeyboardArrowRight
import androidx.compose.ui.graphics.vector.ImageVector
import com.debasish.livefit.phone.ServiceGraph
import com.debasish.livefit.phone.ui.list.sources.LanguageSource
import com.debasish.livefit.phone.ui.list.sources.PermissionSource
import com.debasish.livefit.phone.ui.list.sources.WorkoutHistorySource
import org.json.JSONObject

/** One row in the generic list screen. Sources map their domain objects onto this. */
data class ListItem(
    val id: String,
    val title: String,
    val subtitle: String? = null,
    /** Short glyph for the leading chip (emoji flag, monogram) when [icon] is null. */
    val glyph: String = "",
    val icon: ImageVector? = null,
    val status: ItemStatus = ItemStatus.None,
    /** Optional right-aligned value, e.g. "4.2 km". */
    val trailingText: String? = null,
)

/** Generic row state; each source names it (e.g. Done = "Downloaded" or "Granted"). */
enum class ItemStatus { None, Done, InProgress, ActionNeeded }

/**
 * Confirm-then-run action for a row. With [confirmTitle] null the action runs immediately;
 * with [blocking] the screen shows the progress overlay; [run] reports 0..1 or null (indeterminate).
 */
data class ItemAction(
    val confirmTitle: String? = null,
    val confirmMessage: String = "",
    val confirmLabel: String = "OK",
    val blocking: Boolean = false,
    val run: suspend (onProgress: (Float?) -> Unit) -> ActionResult,
)

sealed interface ActionResult {
    data object Done : ActionResult
    data object Silent : ActionResult
    data class Message(val text: String) : ActionResult
    data class Scheduled(val message: String) : ActionResult
    data class Failed(val message: String) : ActionResult
}

/**
 * Data provider behind the generic list screen. [filter] is the JSON passed in the route
 * (null when the caller doesn't pre-filter); sources read whatever keys they understand.
 */
interface ListSource {
    val title: String
    val searchHint: String
    /** Labels for the status filter chips and section headers. Empty = no grouping/filtering. */
    val statusLabels: Map<ItemStatus, String> get() = emptyMap()
    /** False keeps the source's own order (e.g. newest-first history) and hides the A–Z toggle. */
    val sortable: Boolean get() = true
    val doneSection: String get() = ""
    val actionSection: String get() = ""
    /** Trailing icon for [ItemStatus.ActionNeeded] rows. */
    val actionIcon: ImageVector get() = Icons.AutoMirrored.Rounded.KeyboardArrowRight
    suspend fun load(filter: JSONObject?): List<ListItem>
    fun actionFor(item: ListItem): ItemAction?
}

object ListSources {
    const val LANGUAGES = "languages"
    const val PERMISSIONS = "permissions"
    const val WORKOUTS = "workouts"

    fun create(id: String, context: Context, services: ServiceGraph, open: (String) -> Unit = {}): ListSource = when (id) {
        LANGUAGES -> LanguageSource(context.applicationContext, { services.settings.voiceLocale.value }, services.settings::setVoiceLocale, services::refreshVoicePacksAsync)
        PERMISSIONS -> PermissionSource(context)
        WORKOUTS -> WorkoutHistorySource(services.history, open)
        else -> error("Unknown list source: $id")
    }
}
