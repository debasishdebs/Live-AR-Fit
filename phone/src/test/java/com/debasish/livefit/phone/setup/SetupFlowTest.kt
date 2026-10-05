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
        repeat(5) { f.next(); seen += f.step }
        assertEquals(SetupStep.entries.toList(), seen)
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
