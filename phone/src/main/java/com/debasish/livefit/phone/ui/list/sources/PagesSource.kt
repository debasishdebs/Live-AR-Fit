package com.debasish.livefit.phone.ui.list.sources

import com.debasish.livefit.model.HudPage
import com.debasish.livefit.model.PageSettings
import com.debasish.livefit.phone.ui.list.ActionResult
import com.debasish.livefit.phone.ui.list.ItemAction
import com.debasish.livefit.phone.ui.list.ListItem
import com.debasish.livefit.phone.ui.list.ListSource
import org.json.JSONObject

/** Settings → Pages (spec §3.2): one switch per page, in cycle order; Workout shown locked on. */
class PagesSource(private val current: () -> PageSettings, private val setEnabled: (HudPage, Boolean) -> Unit) : ListSource {
    override val title = "Pages"
    override val searchHint = "Search pages"
    override val sortable = false // cycle order

    override suspend fun load(filter: JSONObject?): List<ListItem> {
        val p = current()
        return HudPage.entries.map { page ->
            ListItem(
                id = page.name,
                title = page.label,
                subtitle = if (page == HudPage.Workout) "Glasses + Watch · always on" else "Glasses + Watch",
                glyph = page.label.take(1),
                toggle = p.isEnabled(page),
            )
        }
    }

    override fun actionFor(item: ListItem): ItemAction? {
        val page = HudPage.entries.firstOrNull { it.name == item.id }?.takeIf { it != HudPage.Workout } ?: return null
        return ItemAction { setEnabled(page, item.toggle != true); ActionResult.Silent }
    }
}
