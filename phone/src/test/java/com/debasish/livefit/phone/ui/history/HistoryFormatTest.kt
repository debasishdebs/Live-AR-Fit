package com.debasish.livefit.phone.ui.history

import com.debasish.livefit.model.Provenance
import com.debasish.livefit.model.SessionStatus
import com.debasish.livefit.model.SessionSummary
import com.debasish.livefit.model.WorkoutType
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class HistoryFormatTest {
    private fun s(status: SessionStatus = SessionStatus.Complete, prov: Provenance = Provenance.Live("galaxy-watch/health-services"), type: WorkoutType = WorkoutType.Run, detected: WorkoutType? = null) =
        SessionSummary(id = "x", type = type, detectedType = detected, startMs = 0, endMs = 1_752_000, activeMs = 1_720_000, avgHr = 151,
            distanceKm = 4.9, provenance = prov, status = status)

    @Test fun titleUsesDetectedTypeForAuto() {
        assertEquals("Run", HistoryFormat.title(s()))
        assertEquals("Auto · Cycle", HistoryFormat.title(s(type = WorkoutType.Auto, detected = WorkoutType.Cycle)))
    }

    @Test fun subtitleShowsDurationAndAvgHr() = assertEquals("28:40 · ♥ 151 avg", HistoryFormat.subtitle(s(), now = 0).substringAfter(" · "))

    @Test fun badges() {
        assertNull(HistoryFormat.badge(s()))
        assertEquals("Incomplete", HistoryFormat.badge(s(status = SessionStatus.Incomplete)))
        assertEquals("Demo", HistoryFormat.badge(s(prov = Provenance.Fake)))
    }
}
