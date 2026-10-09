package com.debasish.livefit.model

import kotlinx.serialization.Serializable

/** Settings → Pages (spec §3.2): stored on the phone, sent to glasses and watch. Workout can never be turned off. */
@Serializable
data class PageSettings(val disabled: Set<HudPage> = emptySet()) {
    fun isEnabled(page: HudPage): Boolean = page == HudPage.Workout || page !in disabled
}
