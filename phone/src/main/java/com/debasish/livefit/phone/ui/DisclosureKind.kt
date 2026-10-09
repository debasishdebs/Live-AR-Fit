package com.debasish.livefit.phone.ui

import com.debasish.livefit.model.Disclosures

/** The prominent disclosures shown before LiveFit sends the user to a system permission (spec §4). */
enum class DisclosureKind(val title: String, val body: String, val continueLabel: String) {
    Music(Disclosures.MUSIC_TITLE, Disclosures.MUSIC, "Continue to notification access"),
    Location(Disclosures.LOCATION_TITLE, Disclosures.LOCATION, "Continue"),
    /** From the Permissions list: the mic is granted in app settings (Setup shows the same text before its prompt). */
    Mic(Disclosures.MIC_TITLE, Disclosures.MIC, "Continue to app settings"),
}
