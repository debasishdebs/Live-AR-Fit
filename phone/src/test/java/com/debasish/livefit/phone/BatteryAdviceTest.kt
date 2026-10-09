package com.debasish.livefit.phone

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class BatteryAdviceTest {
    @Test fun exemptMeansNoBanner() {
        assertNull(BatteryAdvice.banner(ignoringOptimizations = true, manufacturer = "samsung"))
        assertNull(BatteryAdvice.banner(ignoringOptimizations = true, manufacturer = "Google"))
    }

    @Test fun notExemptShowsTheSpecHeadline() {
        val b = BatteryAdvice.banner(ignoringOptimizations = false, manufacturer = "Google")!!
        assertEquals("Battery optimisation is on — LiveFit may stop tracking or lose the glasses/watch link when the screen is off.", b.headline)
        assertFalse(b.samsung)
        assertEquals(BatteryAdvice.GENERIC_STEPS, b.steps)
    }

    @Test fun samsungGetsNeverSleepingAppsStepsWhateverTheCase() {
        for (m in listOf("samsung", "SAMSUNG", " Samsung ")) {
            val b = BatteryAdvice.banner(false, m)!!
            assertTrue(b.samsung, m)
            assertEquals(listOf("Settings", "Battery", "Background usage limits", "Never sleeping apps", "Add Live AR Fit"), b.steps)
        }
    }

    @Test fun unknownManufacturerIsGeneric() {
        assertFalse(BatteryAdvice.banner(false, null)!!.samsung)
        assertFalse(BatteryAdvice.banner(false, "samsungish")!!.samsung)
    }

    @Test fun openSettingsNeedsNoPermission() =
        assertEquals("android.settings.IGNORE_BATTERY_OPTIMIZATION_SETTINGS", BatteryAdvice.SETTINGS_ACTIONS.first())
}
