package com.debasish.livefit.services.voice

import kotlin.test.Test
import kotlin.test.assertEquals

class YesNoParserTest {
    private fun assertAll(expected: Boolean?, vararg utterances: String) {
        for (u in utterances) assertEquals(expected, YesNoParser.parse(u), "utterance: \"$u\"")
    }

    @Test fun yes() = assertAll(true, "yes", "Yes.", "yeah", "yep", "ok", "okay", "sure", "confirm", "take over", "do it", "yes please", "go ahead")
    @Test fun no() = assertAll(false, "no", "No!", "nope", "cancel", "stop", "don't", "dont", "leave it", "not now", "no thanks")
    @Test fun negationWins() = assertAll(false, "don't take over", "no, don't do it", "ok no")
    @Test fun unclear() = assertAll(null, "", "maybe", "what", "next song")
}
