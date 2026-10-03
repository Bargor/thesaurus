package pl.bargor.thesaurus.ui.reports

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toPixelMap
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.StateRestorationTester
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

class ReportsBalanceChartTest {
    @get:Rule val compose = createComposeRule()
    private val period = SummaryPeriod(LocalDate.of(2026, 9, 1), LocalDate.of(2026, 9, 3))

    @Test fun balanceAppearsBelowCategoryLegendAndBeforeExistingAnnualTrend() {
        val entries = listOf(entry("first", 100, 1), entry("last", -200, 3))
        val state = ReportsUiState(LocalDate.of(2026, 9, 30), YearMonth.of(2026, 9), Year.of(2026),
            mode = ReportPeriodMode.YEAR, isLoading = false,
            aggregation = aggregateReportEntries(entries, period, ReportTypeFilter.ALL),
            entries = entries.map { ReportEntryItem(it, "Jedzenie") }, balanceTrend = trend(entries))
        compose.setContent { ThesaurusTheme { ReportsScreen(state, {}, {}, {}, {}, {}, {}, {}, {}, {}) } }
        val legend = compose.onNodeWithTag("reports-category-legend", useUnmergedTree = true).getUnclippedBoundsInRoot()
        val balance = compose.onNodeWithTag("reports-balance-section", useUnmergedTree = true).getUnclippedBoundsInRoot()
        val annual = compose.onNodeWithTag("reports-trend-chart", useUnmergedTree = true).getUnclippedBoundsInRoot()
        assertTrue(legend.bottom <= balance.top)
        assertTrue(balance.bottom <= annual.top)
        compose.onNodeWithTag("reports-balance-chart").performScrollTo().assertIsDisplayed()
        assertRemovedControlsAbsent()
    }

    @Test fun chartSemanticsExposeExactOpeningDatesAndSignedDailyBalancesWithoutDetailsControls() {
        val entries = listOf(entry("history", 250, 1).copy(date = LocalDate.of(2026, 8, 31)),
            entry("income", 100, 1), entry("expense", -500, 3))
        compose.setContent { ThesaurusTheme { ReportsBalanceChart(trend(entries)) } }
        listOf(currency(250.toBigInteger()), currency(350.toBigInteger()), currency((-150).toBigInteger())).forEach(::assertChartDescriptionContains)
        assertChartDescriptionContains("1 wrz 2026")
        assertChartDescriptionContains("3 wrz 2026")
        compose.onNodeWithTag("reports-balance-total").assertTextEquals("Końcowe saldo: ${currency((-150).toBigInteger())}")
        assertRemovedControlsAbsent()
    }

    @Test fun trulyEmptyBeforeAnyHouseholdEntryHasExplicitMessageAndNoInventedLine() {
        compose.setContent { ThesaurusTheme { ReportsBalanceChart(trend(listOf(entry("future", 100, 4)))) } }
        compose.onNodeWithTag("reports-balance-empty").assertIsDisplayed()
        compose.onNodeWithTag("reports-balance-chart").assertDoesNotExist()
        assertRemovedControlsAbsent()
    }

    @Test fun inactiveViewportShowsFlatCarriedBalanceAndCancellingHistoryKeepsZeroChart() {
        val history = listOf(entry("income", 100, 1).copy(date = LocalDate.of(2026, 8, 31)),
            entry("expense", -100, 1).copy(date = LocalDate.of(2026, 8, 31)))
        compose.setContent { ThesaurusTheme { ReportsBalanceChart(trend(history)) } }
        compose.onNodeWithTag("reports-balance-chart").assertExists()
        compose.onNodeWithTag("reports-balance-empty").assertDoesNotExist()
        compose.onNodeWithTag("reports-balance-total").assertTextEquals("Końcowe saldo: ${currency(BigInteger.ZERO)}")
        assertChartDescriptionContains(currency(BigInteger.ZERO))
    }

    @Test fun restoredCompositionRetainsGlobalOpeningAndExactFinalBalance() {
        val history = listOf(entry("old", 100, 1).copy(date = LocalDate.of(2026, 8, 31)), entry("new", -25, 2))
        val restoration = StateRestorationTester(compose)
        restoration.setContent { ThesaurusTheme { ReportsBalanceChart(trend(history)) } }
        restoration.emulateSavedInstanceStateRestore()
        assertChartDescriptionContains(currency(100.toBigInteger()))
        assertChartDescriptionContains(currency(75.toBigInteger()))
        compose.onNodeWithTag("reports-balance-total").assertTextEquals("Końcowe saldo: ${currency(75.toBigInteger())}")
        assertRemovedControlsAbsent()
    }

    @Test fun narrowLightChartHasReadableDateAndSignedPlnAxesAtFontScale16() = assertNarrowLayout(false, 1.6f)
    @Test fun narrowDarkChartHasReadableDateAndSignedPlnAxesAtFontScale18() = assertNarrowLayout(true, 1.8f)

    private fun assertNarrowLayout(dark: Boolean, fontScale: Float) {
        val huge = BigInteger.valueOf(Long.MAX_VALUE).multiply(BigInteger.TEN)
        var axisForeground = Color.Unspecified
        val series = ReportBalanceTrend(period, ReportBalanceGranularity.DAILY,
            listOf(ReportBalanceBucket(period.from, period.to, -huge * BigInteger.valueOf(2), -huge)), 10,
            startBalanceGrosze = huge)
        compose.setContent {
            val density = LocalDensity.current
            CompositionLocalProvider(LocalDensity provides Density(density.density, fontScale)) {
                ThesaurusTheme(darkTheme = dark) {
                    axisForeground = MaterialTheme.colorScheme.onSurface
                    Box(Modifier.width(320.dp).fillMaxHeight().background(MaterialTheme.colorScheme.surface)
                        .verticalScroll(rememberScrollState()).testTag("balance-fixture")) {
                        ReportsBalanceChart(series)
                    }
                }
            }
        }
        val fixture = compose.onNodeWithTag("balance-fixture", useUnmergedTree = true).getUnclippedBoundsInRoot()
        val chart = compose.onNodeWithTag("reports-balance-chart").performScrollTo().getUnclippedBoundsInRoot()
        assertTrue(chart.left >= fixture.left && chart.right <= fixture.right)
        val chartPixels = compose.onNodeWithTag("reports-balance-chart").captureToImage().toPixelMap()
        // The Y labels use onSurface; baseline/ticks use onSurfaceVariant and the balance
        // uses primary. Matching foreground glyph pixels proves all three labels were painted.
        val axisRight = (chartPixels.width * .38f).toInt()
        for (band in 0 until 3) {
            var foregroundPixels = 0
            for (y in chartPixels.height * band / 3 until chartPixels.height * (band + 1) / 3) {
                for (x in 0 until axisRight) {
                    val pixel = chartPixels[x, y]
                    if (kotlin.math.abs(pixel.red - axisForeground.red) < .03f &&
                        kotlin.math.abs(pixel.green - axisForeground.green) < .03f &&
                        kotlin.math.abs(pixel.blue - axisForeground.blue) < .03f) foregroundPixels++
                }
            }
            assertTrue("Visible Y label glyphs are required in axis band $band ($foregroundPixels pixels)", foregroundPixels >= 8)
        }
        listOf("reports-balance-y-axis", "reports-balance-x-axis").forEach { tag ->
            compose.onNodeWithTag(tag, useUnmergedTree = true).assertExists()
            val bounds = compose.onNodeWithTag(tag, useUnmergedTree = true).getUnclippedBoundsInRoot()
            assertTrue("Axis $tag must stay inside chart width", bounds.left >= fixture.left && bounds.right <= fixture.right)
        }
        compose.onNodeWithTag("reports-balance-y-axis", useUnmergedTree = true).assertContentDescriptionEquals(
            "${currency(huge)}; ${currency(BigInteger.ZERO)}; ${currency(-huge)}")
        val dateNodes = compose.onAllNodes(hasAnyAncestor(hasTestTag("reports-balance-x-axis")) and
            SemanticsMatcher.keyIsDefined(SemanticsProperties.Text), useUnmergedTree = true).fetchSemanticsNodes()
        assertEquals("Narrow axis must retain both endpoint dates", 2, dateNodes.size)
        assertEquals(listOf("1 wrz 2026", "3 wrz 2026"), dateNodes.map { it.config[SemanticsProperties.Text].single().text })
        assertTrue("Date label columns must not overlap", dateNodes[0].positionInRoot.x + dateNodes[0].size.width <= dateNodes[1].positionInRoot.x + 1f)
        assertChartDescriptionContains(currency(huge))
        assertChartDescriptionContains(currency(-huge))
        compose.onNodeWithTag("reports-balance-total").performScrollTo().assertTextEquals("Końcowe saldo: ${currency(-huge)}")
        assertReadableTexts()
        assertRemovedControlsAbsent()
    }

    private fun assertRemovedControlsAbsent() {
        listOf("reports-balance-details", "reports-balance-bucket", "reports-balance-previous", "reports-balance-next", "reports-balance-start", "reports-balance-end", "reports-balance-close").forEach {
            compose.onNodeWithTag(it).assertDoesNotExist()
        }
        compose.onNodeWithText("Linia odniesienia: 0,00 zł", substring = true).assertDoesNotExist()
    }

    private fun assertChartDescriptionContains(value: String) {
        compose.onNodeWithTag("reports-balance-chart").assert(SemanticsMatcher("chart describes $value") {
            it.config.getOrNull(SemanticsProperties.ContentDescription).orEmpty().any { description -> value in description }
        })
    }

    private fun assertReadableTexts() {
        val matcher = SemanticsMatcher.keyIsDefined(SemanticsActions.GetTextLayoutResult)
        compose.onAllNodes(matcher, useUnmergedTree = true).fetchSemanticsNodes().forEach { node ->
            val results = mutableListOf<TextLayoutResult>()
            compose.onNode(SemanticsMatcher("text node ${node.id}") { it.id == node.id }, useUnmergedTree = true)
                .performSemanticsAction(SemanticsActions.GetTextLayoutResult) { action -> assertTrue(action(results)) }
            results.forEach { layout ->
                assertFalse("Text overflow: ${layout.layoutInput.text}; allocated=${layout.size.width}px, paragraph=${layout.multiParagraph.width}px", layout.didOverflowWidth)
                assertFalse("Text must not overflow vertically: ${layout.layoutInput.text}", layout.didOverflowHeight)
                assertTrue((0 until layout.lineCount).none(layout::isLineEllipsized))
            }
        }
    }

    private fun trend(entries: List<LedgerEntry>) = buildReportBalanceTrend(entries, period, ReportBalanceGranularity.DAILY)
    private fun entry(id: String, amount: Long, day: Int) = LedgerEntry(id, "home", amount,
        LocalDate.of(2026, 9, day), categoryId = "food", authorId = "actor", updatedById = "actor")
    private fun currency(amount: BigInteger) = (if (amount.signum() > 0) "+" else "") +
        NumberFormat.getCurrencyInstance(Locale.forLanguageTag("pl-PL")).format(BigDecimal(amount, 2))
}
