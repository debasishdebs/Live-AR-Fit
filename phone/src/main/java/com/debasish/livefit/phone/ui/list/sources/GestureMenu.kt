package com.debasish.livefit.phone.ui.list.sources

import com.debasish.livefit.model.Gesture
import com.debasish.livefit.model.GestureDefaults
import com.debasish.livefit.model.GestureMode
import com.debasish.livefit.model.GestureRules
import com.debasish.livefit.model.GestureSettings
import com.debasish.livefit.model.HudPage

/** Levels of Settings → Glasses gestures (spec §4.4): Page/Scroll mode → page → gesture → action; General rows on the root. */
sealed interface GestureMenuLevel {
    data object Root : GestureMenuLevel
    data class Pages(val mode: GestureMode) : GestureMenuLevel
    data class Gestures(val mode: GestureMode, val page: HudPage) : GestureMenuLevel
    data class Actions(val mode: GestureMode, val page: HudPage, val gesture: Gesture) : GestureMenuLevel
    data object Idle : GestureMenuLevel
}

data class MenuRow(val id: String, val title: String, val subtitle: String? = null, val toggle: Boolean? = null, val target: GestureMenuLevel? = null)

object GestureMenu {
    fun encode(l: GestureMenuLevel): String = when (l) {
        GestureMenuLevel.Root -> "root"
        is GestureMenuLevel.Pages -> "pages:${l.mode}"
        is GestureMenuLevel.Gestures -> "gestures:${l.mode}:${l.page}"
        is GestureMenuLevel.Actions -> "actions:${l.mode}:${l.page}:${l.gesture}"
        GestureMenuLevel.Idle -> "idle"
    }

    fun decode(s: String?): GestureMenuLevel = runCatching {
        val p = s.orEmpty().split(":")
        when (p[0]) {
            "pages" -> GestureMenuLevel.Pages(GestureMode.valueOf(p[1]))
            "gestures" -> GestureMenuLevel.Gestures(GestureMode.valueOf(p[1]), HudPage.valueOf(p[2]))
            "actions" -> GestureMenuLevel.Actions(GestureMode.valueOf(p[1]), HudPage.valueOf(p[2]), Gesture.valueOf(p[3]))
            "idle" -> GestureMenuLevel.Idle
            else -> GestureMenuLevel.Root
        }
    }.getOrDefault(GestureMenuLevel.Root)

    private fun modeName(m: GestureMode) = if (m == GestureMode.Page) "page mode" else "scroll mode"

    fun title(l: GestureMenuLevel): String = when (l) {
        GestureMenuLevel.Root -> "Glasses gestures"
        is GestureMenuLevel.Pages -> if (l.mode == GestureMode.Page) "Page mode" else "Scroll mode"
        is GestureMenuLevel.Gestures -> "${l.page.label} · ${modeName(l.mode)}"
        is GestureMenuLevel.Actions -> l.gesture.label
        GestureMenuLevel.Idle -> "Idle timeout"
    }

    fun rows(l: GestureMenuLevel, s: GestureSettings): List<MenuRow> = when (l) {
        GestureMenuLevel.Root -> listOf(
            MenuRow("page", "Page mode", "Per page: Glance, Workout, Stats, Playlist, Map, Music controls", target = GestureMenuLevel.Pages(GestureMode.Page)),
            MenuRow("scroll", "Scroll mode", "Playlist, Music controls", target = GestureMenuLevel.Pages(GestureMode.Scroll)),
            MenuRow("idle", "Idle timeout", "${s.idleTimeoutS} s · leaves scroll mode", target = GestureMenuLevel.Idle),
            MenuRow("ask", "Ask before closing during a workout", toggle = s.askBeforeClose),
            MenuRow("reset", "Reset to defaults", "All pages and modes"),
        )
        is GestureMenuLevel.Pages -> HudPage.entries.filter { l.mode == GestureMode.Page || it in GestureDefaults.SCROLL_PAGES }.map { p ->
            val t = GestureRules.table(s, l.mode, p)
            MenuRow(p.name, p.label, "Tap: ${t.getValue(Gesture.Tap).label} · Double tap: ${t.getValue(Gesture.DoubleTap).label}", target = GestureMenuLevel.Gestures(l.mode, p))
        }
        is GestureMenuLevel.Gestures -> {
            val t = GestureRules.table(s, l.mode, l.page)
            Gesture.entries.map { g -> MenuRow(g.name, g.label, t.getValue(g).label, target = GestureMenuLevel.Actions(l.mode, l.page, g)) }
        }
        is GestureMenuLevel.Actions -> {
            val current = GestureRules.table(s, l.mode, l.page).getValue(l.gesture)
            GestureRules.validActions(l.mode, l.page).map { a -> MenuRow(a.name, a.label, toggle = a == current) }
        }
        GestureMenuLevel.Idle -> (3..15).map { MenuRow("$it", "$it s", toggle = it == s.idleTimeoutS) }
    }
}
