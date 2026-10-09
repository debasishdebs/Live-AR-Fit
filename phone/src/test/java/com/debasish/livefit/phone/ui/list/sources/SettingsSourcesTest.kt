package com.debasish.livefit.phone.ui.list.sources

import com.debasish.livefit.model.Gesture
import com.debasish.livefit.model.GestureAction
import com.debasish.livefit.model.GestureChange
import com.debasish.livefit.model.GestureRules
import com.debasish.livefit.model.GestureSettings
import com.debasish.livefit.model.HudPage
import com.debasish.livefit.model.PageSettings
import com.debasish.livefit.phone.ui.list.ActionResult
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class SettingsSourcesTest {
    // ---- Settings → Pages (spec §3.2) ----

    @Test fun oneSwitchPerPageWithWorkoutLockedOn() = runTest {
        var p = PageSettings(disabled = setOf(HudPage.Map))
        val s = PagesSource({ p }, { page, on -> p = PageSettings(if (on) p.disabled - page else p.disabled + page) })
        val items = s.load(null)
        assertEquals(HudPage.entries.map { it.label }, items.map { it.title })
        assertEquals("Glasses + Watch", items.first { it.id == "Glance" }.subtitle)
        assertEquals(false, items.first { it.id == "Map" }.toggle)
        assertNull(s.actionFor(items.first { it.id == "Workout" }), "Workout can't be turned off")
        s.actionFor(items.first { it.id == "Stats" })!!.run {}
        assertTrue(HudPage.Stats in p.disabled)
        s.actionFor(items.first { it.id == "Map" })!!.run {}
        assertFalse(HudPage.Map in p.disabled)
    }

    // ---- Settings → Glasses gestures (spec §4.4) ----

    private var g = GestureSettings()
    private val opened = mutableListOf<String>()
    private fun gestures() = GestureSource(
        current = { g },
        change = { m, p, ge, a -> GestureRules.change(g, m, p, ge, a).also { if (it is GestureChange.Applied) g = it.settings } },
        setIdle = { g = GestureRules.withIdleTimeout(g, it) },
        setAsk = { g = g.copy(askBeforeClose = it) },
        reset = { g = GestureSettings() },
        open = { opened += it },
        routeFor = { "menu=$it" },
    )

    @Test fun rootListsModesAndGeneral() {
        val items = gestures().items(null)
        assertEquals(listOf("Page mode", "Scroll mode", "Idle timeout", "Ask before closing during a workout", "Reset to defaults"), items.map { it.title })
        assertEquals("5 s · leaves scroll mode", items.first { it.id == "idle" }.subtitle)
        assertEquals(true, items.first { it.id == "ask" }.toggle)
    }

    @Test fun hierarchyPageModeThenPageThenGestureThenAction() = runTest {
        val s = gestures()
        s.actionFor(s.items(null).first { it.id == "page" })!!.run {}
        assertEquals("menu=pages:Page", opened.last())
        val pages = s.items("pages:Page")
        assertEquals(HudPage.entries.map { it.label }, pages.map { it.title })
        s.actionFor(pages.first { it.id == "Glance" })!!.run {}
        assertEquals("menu=gestures:Page:Glance", opened.last())
        val perGesture = s.items("gestures:Page:Glance")
        assertEquals(Gesture.entries.map { it.label }, perGesture.map { it.title })
        assertEquals("Talk (voice)", perGesture.first { it.id == "Tap" }.subtitle)
        s.actionFor(perGesture.first { it.id == "Tap" })!!.run {}
        assertEquals("menu=actions:Page:Glance:Tap", opened.last())
    }

    @Test fun scrollModeListsOnlyScrollPages() =
        assertEquals(listOf("Playlist", "Music controls"), gestures().items("pages:Scroll").map { it.title })

    @Test fun actionPickerOffersOnlyValidActionsAndMarksTheCurrentOne() {
        val items = gestures().items("actions:Scroll:MusicControls:Tap")
        val ids = items.map { it.id }
        assertTrue("PressSelected" in ids)
        assertFalse("PlayHighlighted" in ids)
        assertEquals(listOf("PressSelected"), items.filter { it.toggle == true }.map { it.id })
    }

    @Test fun unsafeChangeIsRefusedWithTheReason() = runTest {
        val s = gestures()
        val r = s.actionFor(s.items("actions:Page:Glance:DoubleTap").first { it.id == "Talk" })!!.run {}
        assertEquals(ActionResult.Failed("Glance needs a gesture for Close app"), r)
        assertEquals(GestureAction.CloseApp, g.page.getValue(HudPage.Glance).getValue(Gesture.DoubleTap))
    }

    @Test fun safeChangeApplies() = runTest {
        val s = gestures()
        assertEquals(ActionResult.Silent, s.actionFor(s.items("actions:Page:Glance:Tap").first { it.id == "NextSong" })!!.run {})
        assertEquals(GestureAction.NextSong, g.page.getValue(HudPage.Glance).getValue(Gesture.Tap))
    }

    @Test fun idleTimeoutPickerAndAskToggle() = runTest {
        val s = gestures()
        val idle = s.items("idle")
        assertEquals((3..15).map { "$it s" }, idle.map { it.title })
        s.actionFor(idle.first { it.id == "8" })!!.run {}
        assertEquals(8, g.idleTimeoutS)
        s.actionFor(s.items(null).first { it.id == "ask" })!!.run {}
        assertFalse(g.askBeforeClose)
    }

    @Test fun resetAsksFirstThenRestoresDefaults() = runTest {
        val s = gestures()
        g = GestureRules.withIdleTimeout(g, 12)
        val action = s.actionFor(s.items(null).first { it.id == "reset" })!!
        assertEquals("Reset glasses gestures?", action.confirmTitle)
        action.run {}
        assertEquals(GestureSettings(), g)
    }

    @Test fun unknownMenuFallsBackToRootAndTitlesFollowTheLevel() {
        assertEquals(GestureMenuLevel.Root, GestureMenu.decode("nonsense:1"))
        assertEquals("Map · page mode", GestureMenu.title(GestureMenu.decode("gestures:Page:Map")))
        assertEquals("actions:Scroll:Playlist:LongBack", GestureMenu.encode(GestureMenuLevel.Actions(com.debasish.livefit.model.GestureMode.Scroll, HudPage.Playlist, Gesture.LongBack)))
    }
}
