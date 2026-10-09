package com.debasish.livefit.model

import kotlinx.serialization.Serializable

/** Touchpad gestures the Rokid glasses sense (spec §4.1). Long press is the system's "Hi Rokid"; vertical swipes don't exist. */
@Serializable
enum class Gesture(val label: String) {
    Tap("Tap"),
    DoubleTap("Double tap"),
    ShortForward("Short swipe forward"),
    ShortBack("Short swipe back"),
    LongForward("Long swipe forward"),
    LongBack("Long swipe back"),
}

/** Page mode exists on every page; Scroll mode only on Playlist and Music controls (spec §4.2). */
@Serializable
enum class GestureMode { Page, Scroll }

/** The action catalogue (spec §4.4); which ones a context offers is decided by GestureRules.validActions. */
@Serializable
enum class GestureAction(val label: String) {
    None("None"),
    Talk("Talk (voice)"),
    NextPage("Next page"),
    PreviousPage("Previous page"),
    NextPage2("+2 pages"),
    PreviousPage2("−2 pages"),
    CloseApp("Close app"),
    EnterScroll("Enter scroll mode"),
    ExitScroll("Exit scroll mode"),
    HighlightNext("Highlight next row"),
    HighlightPrevious("Highlight previous row"),
    HighlightNext2("Highlight +2 rows"),
    HighlightPrevious2("Highlight −2 rows"),
    PlayHighlighted("Play highlighted"),
    SelectorNext("Selector next"),
    SelectorPrevious("Selector previous"),
    PressSelected("Press selected"),
    PlayPause("Play/pause"),
    NextSong("Next song"),
    PreviousSong("Previous song"),
    VolumeUp("Volume up"),
    VolumeDown("Volume down"),
    LikeSong("Like song"),
}

/**
 * Settings → Glasses gestures (spec §4.4), sent in the glasses settings frame as a compact name → name table.
 * [page] has one table per page (all six, including disabled ones); [scroll] one per scroll page.
 */
@Serializable
data class GestureSettings(
    val page: Map<HudPage, Map<Gesture, GestureAction>> = GestureDefaults.page,
    val scroll: Map<HudPage, Map<Gesture, GestureAction>> = GestureDefaults.scroll,
    val idleTimeoutS: Int = GestureDefaults.IDLE_DEFAULT_S,
    val askBeforeClose: Boolean = true,
)

/** Spec §4.3 defaults. */
object GestureDefaults {
    const val IDLE_DEFAULT_S = 5
    const val IDLE_MIN_S = 3
    const val IDLE_MAX_S = 15
    val SCROLL_PAGES: Set<HudPage> = setOf(HudPage.Playlist, HudPage.MusicControls)

    private val navigation: Map<Gesture, GestureAction> = mapOf(
        Gesture.DoubleTap to GestureAction.CloseApp,
        Gesture.ShortForward to GestureAction.NextPage,
        Gesture.ShortBack to GestureAction.PreviousPage,
        Gesture.LongForward to GestureAction.NextPage2,
        Gesture.LongBack to GestureAction.PreviousPage2,
    )

    fun pageTable(page: HudPage): Map<Gesture, GestureAction> =
        mapOf(Gesture.Tap to if (page in SCROLL_PAGES) GestureAction.EnterScroll else GestureAction.Talk) + navigation

    fun scrollTable(page: HudPage): Map<Gesture, GestureAction> = when (page) {
        HudPage.Playlist -> mapOf(
            Gesture.Tap to GestureAction.PlayHighlighted, Gesture.DoubleTap to GestureAction.CloseApp,
            Gesture.ShortForward to GestureAction.HighlightNext, Gesture.ShortBack to GestureAction.HighlightPrevious,
            Gesture.LongForward to GestureAction.HighlightNext2, Gesture.LongBack to GestureAction.HighlightPrevious2,
        )
        HudPage.MusicControls -> mapOf(
            Gesture.Tap to GestureAction.PressSelected, Gesture.DoubleTap to GestureAction.CloseApp,
            Gesture.ShortForward to GestureAction.SelectorNext, Gesture.ShortBack to GestureAction.SelectorPrevious,
            Gesture.LongForward to GestureAction.VolumeUp, Gesture.LongBack to GestureAction.VolumeDown,
        )
        else -> emptyMap()
    }

    val page: Map<HudPage, Map<Gesture, GestureAction>> = HudPage.entries.associateWith { pageTable(it) }
    val scroll: Map<HudPage, Map<Gesture, GestureAction>> = SCROLL_PAGES.associateWith { scrollTable(it) }
}
