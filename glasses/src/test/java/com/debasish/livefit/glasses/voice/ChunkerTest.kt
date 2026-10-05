package com.debasish.livefit.glasses.voice

import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals

class ChunkerTest {
    @Test fun splitsIntoHundredMillisecondChunks() {
        val pcm = ByteArray(8_000) { it.toByte() }
        val chunks = AudioChunks.split(pcm)
        assertEquals(listOf(3_200, 3_200, 1_600), chunks.map { it.size })
    }

    @Test fun base64RoundTrips() {
        val pcm = ByteArray(3_200) { (it * 7).toByte() }
        assertContentEquals(pcm, java.util.Base64.getDecoder().decode(AudioChunks.encode(pcm)))
    }
}
