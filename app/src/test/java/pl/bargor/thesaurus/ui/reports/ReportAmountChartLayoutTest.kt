package pl.bargor.thesaurus.ui.reports

import java.time.LocalDate
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ReportAmountChartLayoutTest {
    @Test fun collidingWidestTickDoesNotReserveEmptyAxisSpace() {
        val layout = reportAxisLayout(listOf(
            ReportAxisTickSize(40, 20, 90f), // Zero has priority.
            ReportAxisTickSize(120, 20, 91f),
            ReportAxisTickSize(64, 20, 8f),
            ReportAxisTickSize(56, 20, 172f),
        ), height = 180f, gap = 4f)

        assertEquals(64, layout.width)
        assertEquals(listOf(0, 2, 3), layout.ticks.map { it.index })
        assertEquals(0f, layout.ticks[1].top, 0f)
        assertEquals(160f, layout.ticks[2].top, 0f)
    }

    @Test fun labelsWithExactlyTheRequiredGapFitButOnePixelLessCollides() {
        fun layout(center: Float) = reportAxisLayout(listOf(
            ReportAxisTickSize(40, 20, 20f),
            ReportAxisTickSize(70, 20, center),
        ), height = 180f, gap = 4f)

        assertEquals(2, layout(44f).ticks.size)
        assertEquals(70, layout(44f).width)
        assertEquals(1, layout(43f).ticks.size)
        assertEquals(40, layout(43f).width)
    }

    @Test fun enlargedWrappedLabelFitsWithinAnExpandedChart() {
        val layout = reportAxisLayout(listOf(ReportAxisTickSize(100, 220, 8f)), height = 236f, gap = 4f)

        assertEquals(100, layout.width)
        assertEquals(0f, layout.ticks.single().top, 0f)
        assertTrue(layout.ticks.single().top + 220 <= 236f)
    }

    @Test fun dailyStepUsesEndOfDayAndSingleDayHasRoomForOpeningAndClosing() {
        val from = LocalDate.of(2026, 9, 1)
        val to = from.plusDays(2)
        assertEquals(1f / 3, reportDateFraction(from, from, to, step = true), .00001f)
        assertEquals(2f / 3, reportDateFraction(from.plusDays(1), from, to, step = true), .00001f)
        assertEquals(1f, reportDateFraction(to, from, to, step = true), 0f)
        assertEquals(1f, reportDateFraction(from, from, from, step = true), 0f)
        assertEquals(.5f, reportDateFraction(from, from, from, step = false), 0f)
    }

    @Test fun monthlyPointsUseCalendarDistanceIncludingUnequalMonthLengths() {
        val from = LocalDate.of(2026, 1, 1)
        val to = LocalDate.of(2026, 3, 31)
        assertEquals(30f / 89, reportDateFraction(LocalDate.of(2026, 1, 31), from, to, step = false), .00001f)
        assertEquals(58f / 89, reportDateFraction(LocalDate.of(2026, 2, 28), from, to, step = false), .00001f)
        assertEquals(1f, reportDateFraction(to, from, to, step = false), 0f)
    }
}
