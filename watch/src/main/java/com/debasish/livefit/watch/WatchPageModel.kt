package com.debasish.livefit.watch

import com.debasish.livefit.model.HudPage
import com.debasish.livefit.model.PageSet

/** Watch pager pages (spec §3): the shared page set, Map only during a recording GPS workout. */
object WatchPageModel {
    fun pages(state: WatchUiState): List<HudPage> = PageSet.available(state.pages, PageSet.mapEligible(state.snapshot))

    /** Where the pager opens: the shown page while it still exists, else Workout (spec §3.3). */
    fun initialIndex(pages: List<HudPage>, shown: HudPage): Int = pages.indexOf(PageSet.resolve(shown, pages)).coerceAtLeast(0)
}
