package com.debasish.livefit.phone.ui

import com.debasish.livefit.model.Disclosures
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** Spec §4: each disclosure screen shows the one shared wording (also quoted by the privacy policy). */
class DisclosureKindTest {
    @Test fun musicAndLocationUseTheSharedTexts() {
        assertEquals(Disclosures.MUSIC, DisclosureKind.Music.body)
        assertEquals(Disclosures.MUSIC_TITLE, DisclosureKind.Music.title)
        assertEquals(Disclosures.LOCATION, DisclosureKind.Location.body)
    }

    @Test fun musicSaysWhereContinueLeads() = assertTrue("notification access" in DisclosureKind.Music.continueLabel)
}
