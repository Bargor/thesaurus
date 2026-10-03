package pl.bargor.thesaurus.ui.reports

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsProperties
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

@OptIn(ExperimentalTestApi::class)
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
        assertTrue("Balance follows the entire category legend", legend.bottom <= balance.top)
        assertTrue("Existing annual trend remains after the balance", balance.bottom <= annual.top)
        compose.onNodeWithTag("reports-balance-details").performScrollTo().assertIsDisplayed()
    }

    @Test fun detailsExposeInitialZeroPositiveNegativeAndFinalExactBalanceWithKeyboardSelection() {
        compose.setContent { ThesaurusTheme { ReportsBalanceChart(trend(listOf(entry("income", 100, 1), entry("expense", -200, 3)))) } }
        compose.onNodeWithTag("reports-balance-details").performClick()
        compose.onNodeWithText("Saldo: ${currency(BigInteger.ZERO)} (zerowe)").assertIsDisplayed()
        compose.onNodeWithTag("reports-balance-previous").assertIsNotEnabled()
        val instrumentation = androidx.test.platform.app.InstrumentationRegistry.getInstrumentation()
        val next = compose.onNodeWithTag("reports-balance-next").performScrollTo()
        for (step in 0 until 20) {
            val config = next.fetchSemanticsNode().config
            if (SemanticsProperties.Focused in config && config[SemanticsProperties.Focused]) break
            instrumentation.sendKeyDownUpSync(android.view.KeyEvent.KEYCODE_TAB)
            compose.waitForIdle()
        }
        next.assertIsFocused()
        instrumentation.sendKeyDownUpSync(android.view.KeyEvent.KEYCODE_ENTER)
        compose.waitForIdle()
        compose.onNodeWithText("Saldo: ${currency(100.toBigInteger())} (dodatnie)").assertIsDisplayed()
        compose.onNodeWithText("Zmiana: ${currency(100.toBigInteger())}").assertIsDisplayed()
        compose.onNodeWithTag("reports-balance-end").performScrollTo().performClick()
        compose.onNodeWithText("Saldo: ${currency((-100).toBigInteger())} (ujemne)").assertIsDisplayed()
        compose.onNodeWithText("Zmiana: ${currency((-200).toBigInteger())}").assertIsDisplayed()
        compose.onNodeWithTag("reports-balance-next").assertIsNotEnabled()
        compose.onNodeWithTag("reports-balance-start").performScrollTo().performClick()
        compose.onNodeWithText("Saldo: ${currency(BigInteger.ZERO)} (zerowe)").assertIsDisplayed()
    }

    @Test fun emptyHasExplicitMessageAndNoInventedLineOrDetails() {
        val empty = trend(emptyList())
        compose.setContent { ThesaurusTheme { ReportsBalanceChart(empty) } }
        compose.onNodeWithTag("reports-balance-empty").assertIsDisplayed()
        compose.onNodeWithTag("reports-balance-chart").assertDoesNotExist()
        compose.onNodeWithTag("reports-balance-details").assertDoesNotExist()
    }

    @Test fun allZeroTransactionsKeepExactNeutralSelection() {
        compose.setContent { ThesaurusTheme { ReportsBalanceChart(trend(listOf(entry("income", 100, 2), entry("expense", -100, 2)))) } }
        compose.onNodeWithTag("reports-balance-chart").assertExists()
        compose.onNodeWithTag("reports-balance-details").performClick()
        compose.onNodeWithTag("reports-balance-end").performScrollTo().performClick()
        compose.onNodeWithText("Saldo: ${currency(BigInteger.ZERO)} (zerowe)").assertIsDisplayed()
    }

    @Test fun selectedExactDetailsSurviveSavedStateRestoration() {
        val restoration = StateRestorationTester(compose)
        restoration.setContent { ThesaurusTheme { ReportsBalanceChart(trend(listOf(entry("income", 12345, 1)))) } }
        compose.onNodeWithTag("reports-balance-details").performClick()
        compose.onNodeWithTag("reports-balance-end").performScrollTo().performClick()
        restoration.emulateSavedInstanceStateRestore()
        compose.onNodeWithText("Saldo: ${currency(12345.toBigInteger())} (dodatnie)").assertIsDisplayed()
        compose.onNodeWithTag("reports-balance-next").assertIsNotEnabled()
    }

    @Test fun narrowLightChartAndDetailsRemainReadableAtFontScale16() = assertNarrowLayout(false, 1.6f)
    @Test fun narrowDarkChartAndDetailsRemainReadableAtFontScale18() = assertNarrowLayout(true, 1.8f)

    private fun assertNarrowLayout(dark: Boolean, fontScale: Float) {
        val huge = BigInteger.valueOf(Long.MAX_VALUE).multiply(BigInteger.TEN)
        val series = ReportBalanceTrend(period, ReportBalanceGranularity.DAILY,
            listOf(ReportBalanceBucket(period.from, period.to, huge, huge)), 10)
        compose.setContent {
            val density = LocalDensity.current
            CompositionLocalProvider(LocalDensity provides Density(density.density, fontScale)) {
                ThesaurusTheme(darkTheme = dark) {
                    Box(Modifier.width(320.dp).fillMaxHeight().verticalScroll(rememberScrollState()).testTag("balance-fixture")) {
                        ReportsBalanceChart(series)
                    }
                }
            }
        }
        val fixture = compose.onNodeWithTag("balance-fixture", useUnmergedTree = true).getUnclippedBoundsInRoot()
        val chart = compose.onNodeWithTag("reports-balance-chart").performScrollTo().getUnclippedBoundsInRoot()
        assertTrue(chart.left >= fixture.left && chart.right <= fixture.right)
        assertReadableTexts()
        compose.onNodeWithTag("reports-balance-details").performScrollTo().performClick()
        compose.onNodeWithTag("reports-balance-end").performScrollTo().performClick()
        compose.onNodeWithText("Saldo: ${currency(huge)} (dodatnie)").assertExists()
        assertReadableTexts()
        compose.onNodeWithTag("reports-balance-close").performScrollTo().assertIsDisplayed().performClick()
    }

    private fun assertReadableTexts() {
        val matcher = SemanticsMatcher.keyIsDefined(SemanticsActions.GetTextLayoutResult)
        compose.onAllNodes(matcher, useUnmergedTree = true).fetchSemanticsNodes().forEach { node ->
            val results = mutableListOf<TextLayoutResult>()
            compose.onNode(SemanticsMatcher("text node ${node.id}") { it.id == node.id }, useUnmergedTree = true)
                .performSemanticsAction(SemanticsActions.GetTextLayoutResult) { action -> assertTrue(action(results)) }
            results.forEach { layout ->
                assertFalse("Text must not overflow horizontally: ${layout.layoutInput.text}; allocated=${layout.size.width}px, paragraph=${layout.multiParagraph.width}px, intrinsic=${layout.multiParagraph.maxIntrinsicWidth}px, constraints=${layout.layoutInput.constraints}", layout.didOverflowWidth)
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
