package com.debasish.livefit.model

sealed interface GestureChange {
    data class Applied(val settings: GestureSettings) : GestureChange
    data class Refused(val reason: String) : GestureChange
}

/**
 * Spec §4.4 rules, shared by the phone UI (refuses unsafe changes with a reason) and the glasses (validate what they
 * receive, per page). Safety: every page's page-mode table, disabled pages included, needs ≥ 1 Close app and ≥ 1
 * Next page or Previous page. Scroll tables only need valid actions (✕ Back and the idle timeout always exit).
 */
object GestureRules {
    private val COMMON: List<GestureAction> = listOf(
        GestureAction.None, GestureAction.Talk, GestureAction.NextPage, GestureAction.PreviousPage, GestureAction.NextPage2,
        GestureAction.PreviousPage2, GestureAction.CloseApp, GestureAction.PlayPause, GestureAction.NextSong,
        GestureAction.PreviousSong, GestureAction.VolumeUp, GestureAction.VolumeDown, GestureAction.LikeSong,
    )

    /** Only actions valid for the context are offered (and accepted). */
    fun validActions(mode: GestureMode, page: HudPage): List<GestureAction> = when (mode) {
        GestureMode.Page -> if (page in GestureDefaults.SCROLL_PAGES) COMMON + GestureAction.EnterScroll else COMMON
        GestureMode.Scroll -> when (page) {
            HudPage.Playlist -> COMMON + listOf(
                GestureAction.ExitScroll, GestureAction.HighlightNext, GestureAction.HighlightPrevious,
                GestureAction.HighlightNext2, GestureAction.HighlightPrevious2, GestureAction.PlayHighlighted,
            )
            HudPage.MusicControls -> COMMON + listOf(
                GestureAction.ExitScroll, GestureAction.SelectorNext, GestureAction.SelectorPrevious, GestureAction.PressSelected,
            )
            else -> emptyList()
        }
    }

    fun defaults(mode: GestureMode, page: HudPage): Map<Gesture, GestureAction> =
        if (mode == GestureMode.Page) GestureDefaults.pageTable(page) else GestureDefaults.scrollTable(page)

    /** All six gestures of a context; gestures missing from [settings] take their default. */
    fun table(settings: GestureSettings, mode: GestureMode, page: HudPage): Map<Gesture, GestureAction> {
        val stored = if (mode == GestureMode.Page) settings.page[page] else settings.scroll[page]
        val d = defaults(mode, page)
        return Gesture.entries.associateWith { stored?.get(it) ?: d[it] ?: GestureAction.None }
    }

    /** Why [table] is unacceptable for the context, or null. */
    fun problem(table: Map<Gesture, GestureAction>, mode: GestureMode, page: HudPage): String? {
        val valid = validActions(mode, page)
        table.values.firstOrNull { it !in valid }?.let { return "${it.label} isn't available on ${page.label}" + if (mode == GestureMode.Scroll) " in scroll mode" else "" }
        if (mode == GestureMode.Page) {
            if (GestureAction.CloseApp !in table.values) return "${page.label} needs a gesture for Close app"
            if (GestureAction.NextPage !in table.values && GestureAction.PreviousPage !in table.values) return "${page.label} needs a gesture for Next or Previous page"
        }
        return null
    }

    fun problems(settings: GestureSettings): List<String> =
        HudPage.entries.mapNotNull { problem(table(settings, GestureMode.Page, it), GestureMode.Page, it) } +
            GestureDefaults.SCROLL_PAGES.mapNotNull { problem(table(settings, GestureMode.Scroll, it), GestureMode.Scroll, it) }

    /** Glasses side: per context, an invalid received table keeps [lastValid]'s (defaults if that is invalid too). */
    fun sanitized(received: GestureSettings, lastValid: GestureSettings = GestureSettings()): GestureSettings {
        fun pick(mode: GestureMode, page: HudPage): Map<Gesture, GestureAction> {
            val t = table(received, mode, page)
            if (problem(t, mode, page) == null) return t
            val l = table(lastValid, mode, page)
            return if (problem(l, mode, page) == null) l else defaults(mode, page)
        }
        return GestureSettings(
            page = HudPage.entries.associateWith { pick(GestureMode.Page, it) },
            scroll = GestureDefaults.SCROLL_PAGES.associateWith { pick(GestureMode.Scroll, it) },
            idleTimeoutS = received.idleTimeoutS.coerceIn(GestureDefaults.IDLE_MIN_S, GestureDefaults.IDLE_MAX_S),
            askBeforeClose = received.askBeforeClose,
        )
    }

    /** Phone side: applies one gesture → action change unless it breaks the rules for that page. */
    fun change(settings: GestureSettings, mode: GestureMode, page: HudPage, gesture: Gesture, action: GestureAction): GestureChange {
        if (mode == GestureMode.Scroll && page !in GestureDefaults.SCROLL_PAGES) return GestureChange.Refused("${page.label} has no scroll mode")
        val next = table(settings, mode, page) + (gesture to action)
        problem(next, mode, page)?.let { return GestureChange.Refused(it) }
        return GestureChange.Applied(
            if (mode == GestureMode.Page) settings.copy(page = settings.page + (page to next))
            else settings.copy(scroll = settings.scroll + (page to next)),
        )
    }

    fun withIdleTimeout(settings: GestureSettings, seconds: Int): GestureSettings =
        settings.copy(idleTimeoutS = seconds.coerceIn(GestureDefaults.IDLE_MIN_S, GestureDefaults.IDLE_MAX_S))
}
