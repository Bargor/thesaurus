package pl.bargor.thesaurus.ui.reports

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.width
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toPixelMap
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import java.math.BigDecimal
import java.math.BigInteger
import java.text.NumberFormat
import java.time.LocalDate
import java.time.Year
import java.time.YearMonth
import java.util.Locale
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import pl.bargor.thesaurus.ThesaurusTheme
import pl.bargor.thesaurus.data.model.*

class ReportsMonthlyTrendChartTest {
    @get:Rule val compose = createComposeRule()
    private var foreground = Color.Unspecified
    private var primary = Color.Unspecified

    @Test fun annualScreenRetainsChronologicalExactSignedMonthlyResultsWithoutVisibleJoinedLegend() {
        render(listOf(point(12, 300), point(1, -100), point(2, 200)))
        val chart = compose.onNodeWithTag("reports-trend-chart").performScrollTo().assertIsDisplayed()
        val description = chart.fetchSemanticsNode().config[SemanticsProperties.ContentDescription].joinToString()
        assertTrue(description.indexOf("sty 2026") < description.indexOf("lut 2026"))
        assertTrue(description.indexOf("lut 2026") < description.indexOf("gru 2026"))
        listOf("sty 2026", "lut 2026", "gru 2026", currency((-100).toBigInteger()), currency(200.toBigInteger()), currency(300.toBigInteger())).forEach {
            assertTrue("Exact monthly description must include $it", it in description)
        }
        val pixels = chart.captureToImage().toPixelMap()
        val plot = chart.fetchSemanticsNode().config[ReportAmountChartPlotBounds]
        val februaryX = plot.left + plot.width * (31f / 334f)
        // February is only 31 days after January but 303 days before December. At its
        // +2 PLN height it must be near January, rather than at an equal-index midpoint.
        assertTrue("Nonconsecutive monthly points must use elapsed calendar spacing", countColor(pixels, primary,
            (februaryX - plot.width * .03f).toInt(), (februaryX + plot.width * .03f).toInt(),
            (pixels.height * .18f).toInt(), (pixels.height * .36f).toInt()) >= 8)
        assertAxesAndNoLegend()
    }

    @Test fun expenseMonthlyTrendKeepsMagnitudesWhileSignedBalanceRemainsSeparate() {
        render(listOf(point(1, -100), point(3, -300)), ReportTypeFilter.EXPENSE)
        val description = compose.onNodeWithTag("reports-trend-chart").performScrollTo().fetchSemanticsNode().config[SemanticsProperties.ContentDescription].joinToString()
        assertTrue(currency(100.toBigInteger()) in description)
        assertTrue(currency(300.toBigInteger()) in description)
        assertFalse(currency((-100).toBigInteger()) in description)
        assertAxesAndNoLegend()
    }

    @Test fun singleMonthlyPointIsPaintedAndRetainsItsCalendarYearAndExactAmount() {
        render(listOf(point(2, 1250)))
        val chart = compose.onNodeWithTag("reports-trend-chart").performScrollTo()
        val description = chart.fetchSemanticsNode().config[SemanticsProperties.ContentDescription].joinToString()
        assertTrue("lut 2026" in description && currency(1250.toBigInteger()) in description)
        val pixels = chart.captureToImage().toPixelMap()
        val left = (pixels.width * .5f).toInt()
        val right = (pixels.width * .85f).toInt()
        assertTrue("Single point must be painted near the plot's center", countColor(pixels, primary, left, right, 0, pixels.height) >= 8)
        assertAxesAndNoLegend()
    }

    @Test fun zeroMonthlyResultStillPaintsPointAndProvidesZeroPlnAxis() {
        render(listOf(point(2, 0)))
        val chart = compose.onNodeWithTag("reports-trend-chart").performScrollTo()
        assertTrue(currency(BigInteger.ZERO) in chart.fetchSemanticsNode().config[SemanticsProperties.ContentDescription].joinToString())
        val axis = compose.onNodeWithTag("reports-trend-y-axis", useUnmergedTree = true)
        assertTrue(currency(BigInteger.ZERO) in axis.fetchSemanticsNode().config[SemanticsProperties.ContentDescription].joinToString())
        val pixels = chart.captureToImage().toPixelMap()
        assertTrue("Zero monthly result must remain visible", countColor(pixels, primary, 0, pixels.width, 0, pixels.height) >= 8)
        assertAxesAndNoLegend()
    }

    @Test fun monthlyAxesRemainPaintedReadableAndExactAt320DpLightFont16() = assertNarrowAxes(false, 1.6f)
    @Test fun monthlyAxesRemainPaintedReadableAndExactAt320DpDarkFont18() = assertNarrowAxes(true, 1.8f)

    private fun assertNarrowAxes(dark: Boolean, fontScale: Float) {
        val huge = BigInteger.valueOf(Long.MAX_VALUE).multiply(BigInteger.TEN)
        render(listOf(ReportTrendValue(LocalDate.of(2026, 1, 1), huge), ReportTrendValue(LocalDate.of(2026, 12, 1), -huge)),
            dark = dark, fontScale = fontScale)
        val chart = compose.onNodeWithTag("reports-trend-chart").performScrollTo()
        val description = chart.fetchSemanticsNode().config[SemanticsProperties.ContentDescription].joinToString()
        assertTrue(currency(huge) in description && currency(-huge) in description)
        assertAxesAndNoLegend()
        val fixture = compose.onNodeWithTag("monthly-fixture", useUnmergedTree = true).getUnclippedBoundsInRoot()
        for (tag in listOf("reports-trend-chart", "reports-trend-y-axis", "reports-trend-x-axis")) {
            val bounds = compose.onNodeWithTag(tag, useUnmergedTree = true).getUnclippedBoundsInRoot()
            assertTrue("$tag must remain inside 320 dp viewport", bounds.left >= fixture.left && bounds.right <= fixture.right)
        }
        val pixels = chart.captureToImage().toPixelMap()
        for (band in 0 until 3) {
            assertTrue("Positive, zero and negative monthly Y labels must be painted", countColor(pixels, foreground,
                0, (pixels.width * .38f).toInt(), pixels.height * band / 3, pixels.height * (band + 1) / 3) >= 8)
        }
        val layoutNodes = compose.onAllNodes(SemanticsMatcher.keyIsDefined(SemanticsActions.GetTextLayoutResult) and
            (hasTestTag("reports-trend-chart") or hasAnyAncestor(hasTestTag("reports-trend-x-axis"))), useUnmergedTree = true).fetchSemanticsNodes()
        assertTrue("Actual painted ticks and date text must expose measured layouts", layoutNodes.size >= 3)
        layoutNodes.forEach { node ->
            val results = mutableListOf<TextLayoutResult>()
            compose.onNode(SemanticsMatcher("monthly layout ${node.id}") { it.id == node.id }, useUnmergedTree = true)
                .performSemanticsAction(SemanticsActions.GetTextLayoutResult) { assertTrue(it(results)) }
            results.forEach { result ->
                assertFalse("Monthly axis clips ${result.layoutInput.text}", result.didOverflowWidth)
                assertFalse(result.didOverflowHeight)
                assertTrue((0 until result.lineCount).none(result::isLineEllipsized))
            }
        }
    }

    private fun assertAxesAndNoLegend() {
        compose.onNodeWithTag("reports-trend-y-axis", useUnmergedTree = true).assertExists()
        compose.onNodeWithTag("reports-trend-x-axis", useUnmergedTree = true).assertExists()
        compose.onNodeWithTag("reports-trend-summary").assertDoesNotExist()
    }

    private fun render(values: List<ReportTrendValue>, type: ReportTypeFilter = ReportTypeFilter.ALL,
        dark: Boolean = false, fontScale: Float = 1f) {
        val entry = LedgerEntry("fixture", "home", -100, LocalDate.of(2026, 2, 1), categoryId = "food", authorId = "actor", updatedById = "actor")
        val state = ReportsUiState(LocalDate.of(2026, 12, 31), YearMonth.of(2026, 12), Year.of(2026),
            mode = ReportPeriodMode.YEAR, typeFilter = type, isLoading = false,
            aggregation = ReportAggregation(ReportTotals(BigInteger.ZERO, 100.toBigInteger(), 1), emptyList(), values),
            entries = listOf(ReportEntryItem(entry, "Jedzenie")))
        compose.setContent {
            val density = LocalDensity.current
            CompositionLocalProvider(LocalDensity provides Density(density.density, fontScale)) {
                ThesaurusTheme(darkTheme = dark) {
                    foreground = MaterialTheme.colorScheme.onSurface
                    primary = MaterialTheme.colorScheme.primary
                    Box(Modifier.width(320.dp).fillMaxHeight().background(MaterialTheme.colorScheme.surface).testTag("monthly-fixture")) {
                        ReportsScreen(state, {}, {}, {}, {}, {}, {}, {}, {}, {})
                    }
                }
            }
        }
    }

    private fun countColor(pixels: androidx.compose.ui.graphics.PixelMap, color: Color,
        left: Int, right: Int, top: Int, bottom: Int): Int {
        var count = 0
        for (y in top until bottom) for (x in left until right) {
            val pixel = pixels[x, y]
            if (kotlin.math.abs(pixel.red - color.red) < .03f && kotlin.math.abs(pixel.green - color.green) < .03f &&
                kotlin.math.abs(pixel.blue - color.blue) < .03f) count++
        }
        return count
    }

    private fun point(month: Int, amount: Long) = ReportTrendValue(LocalDate.of(2026, month, 1), BigInteger.valueOf(amount))
    private fun currency(amount: BigInteger) = (if (amount.signum() > 0) "+" else "") +
        NumberFormat.getCurrencyInstance(Locale.forLanguageTag("pl-PL")).format(BigDecimal(amount, 2))
}
