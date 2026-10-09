package com.debasish.livefit.phone.ui

import com.debasish.livefit.model.Disclosures
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** Spec §4: each disclosure screen shows the one shared wording (also quoted by the privacy policy). */
class DisclosureKindTest {
    @Test fun musicAndLocationUseTheSharedTexts() {
        assertEquals(Disclosures.MUSIC, DisclosureKind.Music.body)
        assertEquals(Disclosures.MUSIC_TITLE, DisclosureKind.Music.title)
        assertEquals(Disclosures.LOCATION, DisclosureKind.Location.body)
        assertEquals(Disclosures.LOCATION_TITLE, DisclosureKind.Location.title)
    }

    @Test fun micUsesTheSharedTextAndEndsInAppSettings() {
        assertEquals(Disclosures.MIC, DisclosureKind.Mic.body)
        assertEquals(Disclosures.MIC_TITLE, DisclosureKind.Mic.title)
        assertTrue("settings" in DisclosureKind.Mic.continueLabel)
    }

    @Test fun musicSaysWhereContinueLeads() = assertTrue("notification access" in DisclosureKind.Music.continueLabel)

    /** After "Don't ask again" the system prompt returns denied at once; only app settings can still grant it. */
    @Test fun appSettingsOnlyWhenDeniedWithoutRationale() {
        assertTrue(DisclosureActivity.needsAppSettings(fineGranted = false, coarseGranted = false, showRationale = false))
        assertFalse(DisclosureActivity.needsAppSettings(fineGranted = false, coarseGranted = false, showRationale = true))
        assertFalse(DisclosureActivity.needsAppSettings(fineGranted = true, coarseGranted = true, showRationale = false))
    }

    /** Task 6 deferred: the user chose "Approximate" — a real choice, so don't bounce them to app settings. */
    @Test fun approximateOnlyGrantDoesNotOpenAppSettings() {
        assertFalse(DisclosureActivity.needsAppSettings(fineGranted = false, coarseGranted = true, showRationale = false))
    }
}
