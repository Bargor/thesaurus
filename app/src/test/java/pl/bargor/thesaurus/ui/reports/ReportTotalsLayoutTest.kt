package pl.bargor.thesaurus.ui.reports

import org.junit.Assert.assertEquals
import org.junit.Test

class ReportTotalsLayoutTest {
    @Test fun exactMeasuredBoundaryKeepsRowAndOnePixelLessRequiresStack() {
        assertEquals(ReportTotalsLayout.ROW, chooseReportTotalsLayout(316, listOf(100, 75, 20), 8))
        assertEquals(ReportTotalsLayout.STACK, chooseReportTotalsLayout(315, listOf(100, 75, 20), 8))
    }

    @Test fun equalColumnsMustAllFitTheWidestMetricNotOnlyTheSumOfWidths() {
        assertEquals(ReportTotalsLayout.STACK, chooseReportTotalsLayout(250, listOf(100, 75, 20), 8))
        assertEquals(ReportTotalsLayout.ROW, chooseReportTotalsLayout(400, listOf(100, 75, 20), 8))
    }

    @Test fun exceptionallyWideValuesCannotOverflowTheMeasuredFitDecision() {
        assertEquals(ReportTotalsLayout.STACK,
            chooseReportTotalsLayout(Int.MAX_VALUE, listOf(Int.MAX_VALUE, Int.MAX_VALUE, Int.MAX_VALUE), 8))
    }
}
