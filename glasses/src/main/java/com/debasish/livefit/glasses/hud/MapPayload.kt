package com.debasish.livefit.glasses.hud

import com.debasish.livefit.model.MapFrame
import java.util.Base64

/** A map image on lf_map: PNG in the CXR bytes argument, or (CxrGlassesLink.MAP_AS_BASE64) Base64 in the frame. */
object MapPayload {
    fun png(frame: MapFrame, bytes: ByteArray?): ByteArray? =
        bytes?.takeIf { it.isNotEmpty() }
            ?: frame.pngBase64?.let { runCatching { Base64.getDecoder().decode(it) }.getOrNull() }?.takeIf { it.isNotEmpty() }

    /** CRC-32 (hex) for the device check D1 arrival log; "-" without bytes. */
    fun crc(bytes: ByteArray?): String = bytes?.let { java.util.zip.CRC32().apply { update(it) }.value.toString(16) } ?: "-"
}

/** The newest accepted map image (spec §2.5). Not a data class: a new image must always replace the old one. */
class MapImage(val sessionId: String, val png: ByteArray, val seq: Long)
