package com.debasish.livefit.phone.ui.history

import com.debasish.livefit.model.Provenance
import com.debasish.livefit.model.SessionStatus
import com.debasish.livefit.model.SessionSummary
import com.debasish.livefit.model.WorkoutType
import java.time.LocalDate
import java.time.ZoneId
import java.time.ZonedDateTime
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class DailyActivityTest {
    private val zone = ZoneId.of("Asia/Kolkata")
    private fun at(y: Int, m: Int, d: Int, h: Int, min: Int = 0, z: ZoneId = zone) = ZonedDateTime.of(y, m, d, h, min, 0, 0, z).toInstant().toEpochMilli()

    private fun s(
        id: String, startMs: Long, activeMin: Long = 30, avgHr: Int? = 120, maxHr: Int? = 150,
        steps: Int = 1000, km: Double = 1.0, kcal: Int = 100,
        prov: Provenance = Provenance.Live("galaxy-watch/health-services"), status: SessionStatus = SessionStatus.Complete,
    ) = SessionSummary(
        id = id, type = WorkoutType.Run, startMs = startMs, endMs = startMs + activeMin * 60_000, activeMs = activeMin * 60_000,
        avgHr = avgHr, maxHr = maxHr, steps = steps, distanceKm = km, kcal = kcal, provenance = prov, status = status,
    )

    @Test fun emptyHistoryHasNoDays() = assertEquals(emptyList<DaySummary>(), DailyActivity.group(emptyList(), zone))

    @Test fun groupsByLocalDateNewestFirst() {
        val days = DailyActivity.group(
            listOf(s("a", at(2026, 10, 4, 7)), s("b", at(2026, 10, 5, 18)), s("c", at(2026, 10, 5, 6))),
            zone,
        )
        assertEquals(listOf(LocalDate.of(2026, 10, 5), LocalDate.of(2026, 10, 4)), days.map { it.date })
        assertEquals(listOf("b", "c"), days[0].sessions.map { it.id })
        assertEquals(2, days[0].workouts)
    }

    @Test fun sessionCrossingMidnightBelongsToStartDay() {
        val late = s("late", at(2026, 10, 4, 23, 40), activeMin = 60)
        val days = DailyActivity.group(listOf(late), zone)
        assertEquals(LocalDate.of(2026, 10, 4), days.single().date)
    }

    @Test fun zoneDecidesTheDay() {
        // 20:00 UTC on 4 Oct is 01:30 on 5 Oct in India.
        val start = at(2026, 10, 4, 20, z = ZoneId.of("UTC"))
        assertEquals(LocalDate.of(2026, 10, 4), DailyActivity.group(listOf(s("x", start)), ZoneId.of("UTC")).single().date)
        assertEquals(LocalDate.of(2026, 10, 5), DailyActivity.group(listOf(s("x", start)), zone).single().date)
    }

    @Test fun totalsSumAcrossTheDay() {
        val day = DailyActivity.group(
            listOf(
                s("a", at(2026, 10, 5, 7), activeMin = 30, steps = 3000, km = 2.5, kcal = 200),
                s("b", at(2026, 10, 5, 18), activeMin = 15, steps = 1500, km = 1.25, kcal = 90),
            ),
            zone,
        ).single()
        assertEquals(45 * 60_000L, day.activeMs)
        assertEquals(4500, day.steps)
        assertEquals(3.75, day.distanceKm, 1e-9)
        assertEquals(290, day.kcal)
    }

    @Test fun avgHrIsWeightedByActiveTimeAndMaxIsTheDayMax() {
        val day = DailyActivity.group(
            listOf(
                s("a", at(2026, 10, 5, 7), activeMin = 30, avgHr = 150, maxHr = 170),
                s("b", at(2026, 10, 5, 18), activeMin = 10, avgHr = 110, maxHr = 130),
            ),
            zone,
        ).single()
        assertEquals(140, day.avgHr) // (150*30 + 110*10) / 40
        assertEquals(170, day.maxHr)
    }

    @Test fun sessionsWithoutHrDoNotDiluteTheAverage() {
        val day = DailyActivity.group(
            listOf(s("a", at(2026, 10, 5, 7), avgHr = 130, maxHr = 160), s("b", at(2026, 10, 5, 9), avgHr = null, maxHr = null)),
            zone,
        ).single()
        assertEquals(130, day.avgHr)
        assertEquals(160, day.maxHr)
    }

    @Test fun noHrAtAllGivesNull() {
        val day = DailyActivity.group(listOf(s("a", at(2026, 10, 5, 7), avgHr = null, maxHr = null)), zone).single()
        assertNull(day.avgHr)
        assertNull(day.maxHr)
    }

    @Test fun zeroDurationSessionsFallBackToPlainMean() {
        val day = DailyActivity.group(
            listOf(s("a", at(2026, 10, 5, 7), activeMin = 0, avgHr = 100), s("b", at(2026, 10, 5, 8), activeMin = 0, avgHr = 120)),
            zone,
        ).single()
        assertEquals(110, day.avgHr)
    }

    @Test fun countsDemoAndIncompleteSessions() {
        val day = DailyActivity.group(
            listOf(
                s("a", at(2026, 10, 5, 7), prov = Provenance.Fake),
                s("b", at(2026, 10, 5, 8), status = SessionStatus.Incomplete),
                s("c", at(2026, 10, 5, 9), prov = Provenance.Fake, status = SessionStatus.Incomplete),
                s("d", at(2026, 10, 5, 10)),
            ),
            zone,
        ).single()
        assertEquals(2, day.demoCount)
        assertEquals(2, day.incompleteCount)
    }

    @Test fun dayLabelUsesTodayAndYesterday() {
        val today = LocalDate.of(2026, 10, 6)
        assertEquals("Today", DailyActivity.dayLabel(today, today))
        assertEquals("Yesterday", DailyActivity.dayLabel(today.minusDays(1), today))
        assertTrue(DailyActivity.dayLabel(LocalDate.of(2026, 10, 1), today).contains("1"))
    }

    @Test fun summaryLinesShowCountTimeKcalStepsAndHr() {
        val day = DailyActivity.group(
            listOf(s("a", at(2026, 10, 5, 7), activeMin = 30, avgHr = 150, maxHr = 170, steps = 3000, kcal = 200), s("b", at(2026, 10, 5, 18), activeMin = 10, avgHr = 110, maxHr = 130, steps = 1500, kcal = 90)),
            zone,
        ).single()
        assertEquals("2 workouts · 40:00 · 290 kcal\n4,500 steps · ♥ 140 avg · 170 max", DailyActivity.summary(day))
    }

    @Test fun summaryMarksDemoAndIncompleteAndSingularWorkout() {
        val day = DailyActivity.group(listOf(s("a", at(2026, 10, 5, 7), avgHr = null, maxHr = null, prov = Provenance.Fake, status = SessionStatus.Incomplete)), zone).single()
        assertEquals("1 workout · 30:00 · 100 kcal\n1,000 steps\nDemo 1 · Incomplete 1", DailyActivity.summary(day))
    }

    @Test fun findDayByIsoDate() {
        val sessions = listOf(s("a", at(2026, 10, 4, 7)), s("b", at(2026, 10, 5, 18)))
        assertEquals(listOf("a"), DailyActivity.sessionsOn(sessions, LocalDate.of(2026, 10, 4), zone).map { it.id })
    }
}
