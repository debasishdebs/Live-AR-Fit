package com.debasish.livefit.services.voice

import com.debasish.livefit.model.Command
import com.debasish.livefit.model.WorkoutType
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull

class LanguageRegistryTest {
    @Test fun defaultLocaleIsEnglishIndia() {
        assertEquals("en-IN", LanguageRegistry.DEFAULT_LOCALE)
        assertNotNull(LanguageRegistry.forLocale("en-IN"))
    }

    @Test fun englishPacksParseCommandsAndYesNo() {
        for (locale in listOf("en-IN", "en-US", "en-GB")) {
            val pack = LanguageRegistry.forLocale(locale)!!
            assertEquals(Command.StartWorkout(WorkoutType.Walk), pack.parseCommand("start workout"))
            assertEquals(true, pack.parseYesNo("yes"))
        }
    }

    @Test fun unknownLocaleHasNoPack() = assertNull(LanguageRegistry.forLocale("hi-IN"))
}
