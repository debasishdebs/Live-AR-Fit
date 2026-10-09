package com.debasish.livefit.glasses.hud

import com.debasish.livefit.model.MapFrame
import com.debasish.livefit.model.MapFrameKind
import java.util.Base64
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertNull

class MapPayloadTest {
    private val frame = MapFrame(kind = MapFrameKind.Image, renderEpoch = 1, sessionId = "s", renderSeq = 1)

    @Test fun pngFromTheBytesArgument() = assertContentEquals(byteArrayOf(1, 2), MapPayload.png(frame, byteArrayOf(1, 2)))

    @Test fun base64FallbackWhenNoBytes() = assertContentEquals(
        byteArrayOf(3, 4), MapPayload.png(frame.copy(pngBase64 = Base64.getEncoder().encodeToString(byteArrayOf(3, 4))), ByteArray(0)),
    )

    @Test fun nothingWhenNeitherCarriesAnImage() {
        assertNull(MapPayload.png(frame, null))
        assertNull(MapPayload.png(frame.copy(pngBase64 = "!!not base64"), null))
    }
}
