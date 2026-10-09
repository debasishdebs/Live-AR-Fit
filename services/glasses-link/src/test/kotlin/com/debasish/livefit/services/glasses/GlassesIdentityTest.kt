package com.debasish.livefit.services.glasses

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse

/** Spec §2 (review P1-3): after the rename the CXR package and the activity class are two separate values. */
class GlassesIdentityTest {
    @Test fun packageAndActivityAreSeparateValues() {
        assertEquals("com.livear.fit.glasses", CxrGlassesLink.GLASSES_PKG)
        assertEquals("com.debasish.livefit.glasses.MainActivity", CxrGlassesLink.GLASSES_ACTIVITY)
        assertFalse(CxrGlassesLink.GLASSES_ACTIVITY.startsWith(CxrGlassesLink.GLASSES_PKG + "."), "the activity keeps the retained namespace")
    }
}
