package com.debasish.livefit.glasses.hud

import com.debasish.livefit.model.HudPage
import com.debasish.livefit.model.PageState
import com.debasish.livefit.model.Wire
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class PageReporterTest {
    /** Spec §2.5: lf_page_state on every page change and on every connect/reconnect. */
    @Test fun reportsEveryChangeAndEveryResendWithAGrowingSeq() {
        val sent = mutableListOf<PageState>()
        val r = PageReporter { sent += Wire.decode<PageState>(it); true }
        r.onPage(HudPage.Workout)
        r.onPage(HudPage.Workout)
        r.onPage(HudPage.Map)
        r.resend()
        assertEquals(listOf(HudPage.Workout to 1L, HudPage.Map to 2L, HudPage.Map to 3L), sent.map { it.page to it.seq })
        assertEquals(HudPage.Map, r.page)
    }

    @Test fun aFailingSendNeverThrows() {
        val r = PageReporter { error("not connected") }
        r.onPage(HudPage.Map)
        assertTrue(r.hasPending, "a throwing send counts as unsent")
    }

    /** Review #10: only the latest unsent state is retried (older pending ones are obsolete), and one success clears it. */
    @Test fun onlyTheLatestUnsentPageIsRetried() {
        val sent = mutableListOf<PageState>()
        var up = false
        val r = PageReporter { if (up) { sent += Wire.decode<PageState>(it); true } else false }
        r.onPage(HudPage.Map); r.onPage(HudPage.Stats); r.onPage(HudPage.Glance)
        up = true
        r.retryPending(); r.retryPending()
        assertEquals(listOf(HudPage.Glance), sent.map { it.page })
        assertFalse(r.hasPending)
    }

    /** Review #10: a failed report of the same page is re-sent even though the page did not change. */
    @Test fun samePageIsReReportedWhileItsLastSendFailed() {
        var ok = false
        val sent = mutableListOf<HudPage>()
        val r = PageReporter { if (ok) { sent += Wire.decode<PageState>(it).page; true } else false }
        r.onPage(HudPage.Map)
        ok = true
        r.onPage(HudPage.Map)
        assertEquals(listOf(HudPage.Map), sent)
        r.onPage(HudPage.Map)
        assertEquals(listOf(HudPage.Map), sent, "once sent, an unchanged page is not repeated")
    }
}
