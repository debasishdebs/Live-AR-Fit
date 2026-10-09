package com.debasish.livefit.phone.ui.list.sources

import com.debasish.livefit.model.Gesture
import com.debasish.livefit.model.GestureAction
import com.debasish.livefit.model.GestureChange
import com.debasish.livefit.model.GestureMode
import com.debasish.livefit.model.GestureSettings
import com.debasish.livefit.model.HudPage
import com.debasish.livefit.phone.ui.list.ActionResult
import com.debasish.livefit.phone.ui.list.ItemAction
import com.debasish.livefit.phone.ui.list.ItemStatus
import com.debasish.livefit.phone.ui.list.ListItem
import com.debasish.livefit.phone.ui.list.ListSource
import org.json.JSONObject

/**
 * Settings → Glasses gestures (spec §4.4) on the generic list screen; each level is the same source with
 * filter {"menu": "<level>"}. A change that would leave a page without Close app or without a page move is refused
 * with the reason (the list shows it as a message); applied changes reach the glasses in the next settings frame.
 */
class GestureSource(
    private val current: () -> GestureSettings,
    private val change: (GestureMode, HudPage, Gesture, GestureAction) -> GestureChange,
    private val setIdle: (Int) -> Unit,
    private val setAsk: (Boolean) -> Unit,
    private val reset: () -> Unit,
    private val open: (String) -> Unit,
    private val routeFor: (menu: String) -> String,
) : ListSource {
    override val title = "Glasses gestures"
    override val searchHint = "Search gestures"
    override val sortable = false
    private var level: GestureMenuLevel = GestureMenuLevel.Root

    override fun titleFor(filter: JSONObject?): String = GestureMenu.title(GestureMenu.decode(filter?.optString("menu")))

    override suspend fun load(filter: JSONObject?): List<ListItem> = items(filter?.optString("menu"))

    fun items(menu: String?): List<ListItem> {
        level = GestureMenu.decode(menu)
        return GestureMenu.rows(level, current()).map {
            ListItem(id = it.id, title = it.title, subtitle = it.subtitle, glyph = it.title.take(1), toggle = it.toggle,
                status = if (it.target != null) ItemStatus.ActionNeeded else ItemStatus.None)
        }
    }

    override fun actionFor(item: ListItem): ItemAction? {
        val l = level
        val row = GestureMenu.rows(l, current()).firstOrNull { it.id == item.id } ?: return null
        row.target?.let { t -> return ItemAction { open(routeFor(GestureMenu.encode(t))); ActionResult.Silent } }
        return when (l) {
            GestureMenuLevel.Root -> when (row.id) {
                "ask" -> ItemAction { setAsk(item.toggle != true); ActionResult.Silent }
                "reset" -> ItemAction(confirmTitle = "Reset glasses gestures?", confirmMessage = "Every page and mode goes back to the default gestures.", confirmLabel = "Reset") {
                    reset(); ActionResult.Message("Gestures reset")
                }
                else -> null
            }
            is GestureMenuLevel.Actions -> ItemAction {
                when (val r = change(l.mode, l.page, l.gesture, GestureAction.valueOf(row.id))) {
                    is GestureChange.Applied -> ActionResult.Silent
                    is GestureChange.Refused -> ActionResult.Failed(r.reason)
                }
            }
            GestureMenuLevel.Idle -> ItemAction { setIdle(row.id.toInt()); ActionResult.Silent }
            else -> null
        }
    }
}
