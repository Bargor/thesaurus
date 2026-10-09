package pl.bargor.thesaurus.ui.reports

import java.time.Clock
import java.time.Instant
import java.time.ZoneId
import java.time.ZoneOffset
import org.junit.Assert.assertEquals
import org.junit.Test

class ReportsCalendarTest {
    @Test fun delayTargetsNextLocalMidnightIncludingLeapMonthAndYearBoundaries() {
        for (instant in listOf("2024-02-28T23:59:59Z", "2024-02-29T23:59:59Z",
            "2026-09-30T23:59:59Z", "2026-12-31T23:59:59Z")) {
            assertEquals(instant, 1_000L,
                nextReportMidnightDelayMillis(Clock.fixed(Instant.parse(instant), ZoneOffset.UTC)))
        }
    }

    @Test fun delayUsesZoneInsteadOfUtcDate() {
        val instant = Instant.parse("2024-02-29T23:30:00Z")
        assertEquals(23 * 3_600_000L + 30 * 60_000L,
            nextReportMidnightDelayMillis(Clock.fixed(instant, ZoneId.of("Europe/Warsaw"))))
        assertEquals(8 * 3_600_000L + 30 * 60_000L,
            nextReportMidnightDelayMillis(Clock.fixed(instant, ZoneId.of("America/Los_Angeles"))))
    }

    @Test fun daylightSavingDaysLastTwentyThreeAndTwentyFiveHours() {
        val warsaw = ZoneId.of("Europe/Warsaw")
        assertEquals(23 * 3_600_000L,
            nextReportMidnightDelayMillis(Clock.fixed(Instant.parse("2024-03-30T23:00:00Z"), warsaw)))
        assertEquals(25 * 3_600_000L,
            nextReportMidnightDelayMillis(Clock.fixed(Instant.parse("2024-10-26T22:00:00Z"), warsaw)))
    }

    @Test fun subMillisecondDelayRoundsUpSoTimerCannotWakeBeforeMidnight() {
        assertEquals(1L, nextReportMidnightDelayMillis(
            Clock.fixed(Instant.parse("2026-09-30T23:59:59.999999999Z"), ZoneOffset.UTC)))
        assertEquals(86_400_000L, nextReportMidnightDelayMillis(
            Clock.fixed(Instant.parse("2026-10-01T00:00:00Z"), ZoneOffset.UTC)))
    }
}
