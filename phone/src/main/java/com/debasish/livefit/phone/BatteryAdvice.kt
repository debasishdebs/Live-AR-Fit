package com.debasish.livefit.phone

/** What the battery-optimisation banner shows (spec §4); null = LiveFit is exempt, no banner. */
data class BatteryBanner(val headline: String, val steps: List<String>, val samsung: Boolean)

/**
 * Spec §4: REQUEST_IGNORE_BATTERY_OPTIMIZATIONS is gone; while LiveFit is not exempt every app open shows a bold banner
 * with an Open settings button (ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS, no permission needed). Samsung gets its
 * own steps, other OEMs generic ones. Pure: the caller passes PowerManager.isIgnoringBatteryOptimizations and Build.MANUFACTURER.
 */
object BatteryAdvice {
    const val HEADLINE = "Battery optimisation is on — LiveFit may stop tracking or lose the glasses/watch link when the screen is off."
    val SAMSUNG_STEPS = listOf("Settings", "Battery", "Background usage limits", "Never sleeping apps", "Add Live AR Fit")
    val GENERIC_STEPS = listOf("Tap Open settings", "Show all apps and find Live AR Fit", "Choose \"Don't optimise\" (or \"Unrestricted\")")
    /** Tried in order by Open settings; the plain Settings screen is the fallback when an OEM hides the first. */
    val SETTINGS_ACTIONS = listOf("android.settings.IGNORE_BATTERY_OPTIMIZATION_SETTINGS", "android.settings.SETTINGS")

    fun banner(ignoringOptimizations: Boolean, manufacturer: String?): BatteryBanner? {
        if (ignoringOptimizations) return null
        val samsung = manufacturer?.trim().equals("samsung", ignoreCase = true)
        return BatteryBanner(HEADLINE, if (samsung) SAMSUNG_STEPS else GENERIC_STEPS, samsung)
    }
}
