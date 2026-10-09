package com.debasish.livefit.phone.setup

import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class SetupFlowTest {
    @Test fun walksAllSteps() {
        val f = SetupFlow()
        val seen = mutableListOf(f.step)
        repeat(6) { f.next(); seen += f.step }
        assertEquals(SetupStep.entries.toList(), seen)
    }

    /** Spec §2.1: "Map fallback" is an optional setup step; skipping it leads on to Voice. */
    @Test fun mapFallbackStepIsOptional() {
        val f = SetupFlow(SetupStep.Map); f.skip()
        assertEquals(SetupStep.Voice, f.step)
        assertEquals(SetupStep.Map, SetupFlow(SetupStep.Music).also { it.next() }.step)
    }

    @Test fun backStopsAtWelcome() {
        val f = SetupFlow(); f.back()
        assertEquals(SetupStep.Welcome, f.step)
    }

    @Test fun skippingVoiceLeavesVoiceOff() {
        val f = SetupFlow(SetupStep.Voice); f.skip()
        assertEquals(SetupStep.Done, f.step)
        assertFalse(f.voiceEnabled)
    }

    @Test fun installingPackEnablesVoice() {
        val f = SetupFlow(SetupStep.Voice); f.voicePackInstalled(); f.next()
        assertTrue(f.voiceEnabled)
    }

    @Test fun nextOnVoiceWithoutPackActsAsSkip() {
        val f = SetupFlow(SetupStep.Voice); f.next()
        assertFalse(f.voiceEnabled)
        assertEquals(SetupStep.Done, f.step)
    }

    @Test fun permissionsIncludeNotificationsOnlyOn33Plus() {
        assertContentEquals(arrayOf("android.permission.RECORD_AUDIO", "android.permission.BLUETOOTH_CONNECT"), setupPermissions(32))
        assertContentEquals(
            arrayOf("android.permission.RECORD_AUDIO", "android.permission.BLUETOOTH_CONNECT", "android.permission.POST_NOTIFICATIONS"),
            setupPermissions(35),
        )
        assertContentEquals(arrayOf("android.permission.RECORD_AUDIO"), setupPermissions(30))
    }
}

class AutoAdvanceTest {
    @Test fun grantedAdvances() {
        assertTrue(shouldAutoAdvance(StepOutcome.Granted, disclosureRequired = false, disclosureShown = false))
        assertTrue(shouldAutoAdvance(StepOutcome.Granted, disclosureRequired = true, disclosureShown = true))
    }
    @Test fun deniedOrFailedNeverAdvance() {
        for (o in listOf(StepOutcome.Denied, StepOutcome.Failed))
            for (req in listOf(false, true)) for (shown in listOf(false, true)) assertFalse(shouldAutoAdvance(o, req, shown))
    }
    @Test fun alreadySatisfiedAdvances() {
        assertTrue(shouldAutoAdvance(StepOutcome.AlreadySatisfied, disclosureRequired = false, disclosureShown = false))
        assertTrue(shouldAutoAdvance(StepOutcome.AlreadySatisfied, disclosureRequired = true, disclosureShown = true))
    }
    @Test fun disclosureRequiredNotYetShownNeverAdvances() {
        assertFalse(shouldAutoAdvance(StepOutcome.AlreadySatisfied, disclosureRequired = true, disclosureShown = false))
        assertFalse(shouldAutoAdvance(StepOutcome.Granted, disclosureRequired = true, disclosureShown = false))
    }
}
