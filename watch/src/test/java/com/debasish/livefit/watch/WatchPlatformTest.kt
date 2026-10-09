package com.debasish.livefit.watch

import android.content.pm.ServiceInfo
import com.debasish.livefit.model.HudPage
import com.debasish.livefit.model.PageSettings
import com.debasish.livefit.model.TimeSyncRequest
import com.debasish.livefit.model.TimeSyncResponse
import com.debasish.livefit.model.Wire
import java.io.File
import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull

class WatchPlatformTest {
    /** Spec §2.1: health|location during GPS workouts; health only without ACCESS_FINE_LOCATION (never crash). */
    @Test fun fgsAddsLocationOnlyForAGpsWorkoutWithPermission() {
        val health = ServiceInfo.FOREGROUND_SERVICE_TYPE_HEALTH
        val location = ServiceInfo.FOREGROUND_SERVICE_TYPE_LOCATION
        assertEquals(health or location, WatchFgs.types(gpsWorkout = true, fineLocationGranted = true))
        assertEquals(health, WatchFgs.types(gpsWorkout = true, fineLocationGranted = false))
        assertEquals(health, WatchFgs.types(gpsWorkout = false, fineLocationGranted = true))
    }

    @Test fun timeSyncReplyEchoesIdAndT0WithTheWatchClock() {
        val bytes = assertNotNull(TimeSyncResponder.reply(Wire.encode(TimeSyncRequest(id = 7, t0 = 1_000)), nowMs = 6_100))
        assertEquals(TimeSyncResponse(id = 7, t0 = 1_000, tw = 6_100), Wire.decode<TimeSyncResponse>(String(bytes)))
    }

    @Test fun timeSyncIgnoresOtherVersions() {
        assertNull(TimeSyncResponder.reply("""{"protocolVersion":3,"id":1,"t0":1}""", 5))
        assertNull(TimeSyncResponder.reply("garbage", 5))
    }

    @Test fun pageSettingsPersistAcrossRestarts() {
        val dir = Files.createTempDirectory("pages").toFile()
        val f = File(dir, "pages.json")
        PageSettingsFile(f).save(PageSettings(disabled = setOf(HudPage.Map)))
        assertEquals(setOf(HudPage.Map), PageSettingsFile(f).load().disabled)
        assertEquals(PageSettings(), PageSettingsFile(File(dir, "missing.json")).load())
    }
}
