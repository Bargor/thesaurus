package pl.bargor.thesaurus.ui.reports

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
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
import java.time.*
import java.util.Locale
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import pl.bargor.thesaurus.ThesaurusTheme
import pl.bargor.thesaurus.data.model.*

class ReportsTotalCardsTest {
    @get:Rule val compose = createComposeRule()

    @Test fun reproducedPositiveAndNegativeBalancesKeepCurrencyOnOneLineAtPhoneWidths() {
        var width by mutableStateOf(280)
        var totals by mutableStateOf(ReportTotals(1_500_000.toBigInteger(), 128_535.toBigInteger(), 2))
        compose.setContent { Viewport(width) { ReportsTotalCards(totals) } }
        for (viewport in listOf(280, 320)) {
            for (negative in listOf(false, true)) {
                compose.runOnIdle {
                    width = viewport
                    totals = if (negative) ReportTotals(128_535.toBigInteger(), 1_500_000.toBigInteger(), 2)
                        else ReportTotals(1_500_000.toBigInteger(), 128_535.toBigInteger(), 2)
                }
                assertAmounts(totals)
                compose.onNodeWithTag("reports-total-stack").assertExists()
                assertCardsDoNotOverlap()
            }
        }
    }

    @Test fun largeGroupedAmountsRemainCompleteAtLargeFontAndChangedDisplayDensity() {
        var width by mutableStateOf(280)
        var fontScale by mutableStateOf(1.8f)
        var displayDensity by mutableStateOf(1f)
        val totals = ReportTotals(123_456_789.toBigInteger(), 98_765_432.toBigInteger(), 2)
        compose.setContent { Viewport(width, fontScale, displayDensity) { ReportsTotalCards(totals) } }
        for (viewport in listOf(280, 320)) for (scale in listOf(1.8f, 2f)) for (density in listOf(1f, 2f)) {
            compose.runOnIdle { width = viewport; fontScale = scale; displayDensity = density }
            assertAmounts(totals)
            assertCardsDoNotOverlap()
            compose.onNodeWithTag("reports-total-stack").assertExists()
        }
    }

    @Test fun landscapeUsesMeasuredRowAndResizingReflowsWithoutLosingAmounts() {
        var width by mutableStateOf(720)
        var fontScale by mutableStateOf(1f)
        val totals = ReportTotals(1_500_000.toBigInteger(), 128_535.toBigInteger(), 2)
        compose.setContent { Viewport(width, fontScale) { ReportsTotalCards(totals) } }
        assertEquals("Landscape fixture must actually receive its 720dp width at density 1", 720f,
            compose.onNodeWithTag("reports-total-cards", true).fetchSemanticsNode().boundsInRoot.width, 1f)
        compose.onNodeWithTag("reports-total-row").assertExists()
        assertAmounts(totals); assertCardsDoNotOverlap()
        compose.runOnIdle { width = 280; fontScale = 2f }
        compose.onNodeWithTag("reports-total-stack").assertExists()
        assertAmounts(totals); assertCardsDoNotOverlap()
        compose.runOnIdle { width = 720; fontScale = 1f }
        compose.onNodeWithTag("reports-total-row").assertExists()
        assertAmounts(totals); assertCardsDoNotOverlap()
    }

    @Test fun realReportsScreenDisplaysAggregatedIncomeExpenseAndSignedNetWithoutWrapping() {
        val date = LocalDate.of(2026, 9, 15)
        fun entry(id: String, amount: Long) = LedgerEntry(id, "home", amount, date, categoryId = "food", authorId = "actor", updatedById = "actor")
        val aggregation = aggregateReportEntries(listOf(entry("income", 1_500_000), entry("expense", -128_535)),
            YearMonth.of(2026, 9).summaryPeriod(), ReportTypeFilter.ALL)
        compose.setContent { Viewport(320, scrollable = false) {
            ReportsScreen(ReportsUiState(date, YearMonth.of(2026, 9), Year.of(2026), aggregation = aggregation, isLoading = false),
                {}, {}, {}, {}, {}, {}, {}, {}, {})
        } }
        assertAmounts(aggregation.totals, scroll = true)
        assertCardsDoNotOverlap()
    }

    @Test fun exceptionalArbitraryPrecisionAmountUsesReadableSingleLineHorizontalScrolling() {
        val totals = ReportTotals(BigInteger.TEN.pow(80), BigInteger.ZERO, 1)
        compose.setContent { Viewport(280, 2f) { ReportsTotalCards(totals) } }
        val value = currency(totals.incomeGrosze)
        assertAmountLayout("reports-income", value, requireContained = false)
        val scroll = compose.onNodeWithTag("reports-income-scroll", true).assertExists()
        val range = scroll.fetchSemanticsNode().config[SemanticsProperties.HorizontalScrollAxisRange]
        assertTrue("Exception must expose a usable horizontal range", range.maxValue() > 0f)
        scroll.performSemanticsAction(SemanticsActions.ScrollBy) { action -> action(100_000f, 0f) }
        compose.waitForIdle()
        val finalRange = scroll.fetchSemanticsNode().config[SemanticsProperties.HorizontalScrollAxisRange]
        assertEquals(finalRange.maxValue(), finalRange.value(), 1f)
        assertCardMeaning("reports-income", "Przychody", value)
    }

    @Composable private fun Viewport(width: Int, fontScale: Float = 1f, density: Float = 1f, scrollable: Boolean = true, content: @Composable () -> Unit) {
        CompositionLocalProvider(LocalDensity provides Density(density, fontScale)) {
            ThesaurusTheme {
                val viewport = Modifier.requiredWidth(width.dp).height(600.dp)
                Box(if (scrollable) viewport.verticalScroll(rememberScrollState()) else viewport) { content() }
            }
        }
    }

    private fun assertAmounts(totals: ReportTotals, scroll: Boolean = false) {
        for ((tag, label, value) in listOf(
            Triple("reports-income", "Przychody", currency(totals.incomeGrosze)),
            Triple("reports-expense", "Wydatki", currency(totals.expenseGrosze)),
            Triple("reports-net", "Bilans", (if (totals.netGrosze.signum() > 0) "+" else "") + currency(totals.netGrosze)),
        )) {
            if (scroll) compose.onNodeWithTag(tag, true).performScrollTo()
            assertAmountLayout(tag, value)
            assertCardMeaning(tag, label, value)
            compose.onNode(hasText(label) and hasAnyAncestor(hasTestTag("$tag-card")), useUnmergedTree = true).assertExists()
            compose.onNodeWithTag("$tag-scroll", true).assertDoesNotExist()
        }
    }

    private fun assertAmountLayout(tag: String, expected: String, requireContained: Boolean = true) {
        val amount = compose.onNodeWithTag(tag, true).assertTextEquals(expected)
        amount.performSemanticsAction(SemanticsActions.GetTextLayoutResult) { action ->
            val results = mutableListOf<TextLayoutResult>()
            assertTrue(action(results)); assertTrue(results.isNotEmpty())
            results.forEach { layout ->
                assertEquals("$tag must retain the currency on the amount line", 1, layout.lineCount)
                assertFalse("$tag must not clip horizontally: size=${layout.size}, paragraphWidth=${layout.multiParagraph.width}, " +
                    "lineRight=${layout.getLineRight(0)}, constraints=${layout.layoutInput.constraints}, density=${layout.layoutInput.density}",
                    layout.didOverflowWidth)
                assertFalse("$tag must not clip vertically", layout.didOverflowHeight)
                assertFalse("$tag must not use ellipsis", layout.isLineEllipsized(0))
                assertEquals(expected.length, layout.getLineEnd(0))
                assertTrue(layout.getBoundingBox(expected.lastIndex).right <= layout.size.width + 1f)
                assertTrue("$tag must retain a readable body font", layout.layoutInput.style.fontSize.value >= 14f)
            }
        }
        if (requireContained) {
            val text = amount.getUnclippedBoundsInRoot()
            val card = compose.onNodeWithTag("$tag-card", true).getUnclippedBoundsInRoot()
            assertTrue("$tag amount must fit inside its card", text.left >= card.left && text.right <= card.right && text.top >= card.top && text.bottom <= card.bottom)
        }
    }

    private fun assertCardMeaning(tag: String, label: String, value: String) {
        val descriptions = compose.onNodeWithTag("$tag-card", true).fetchSemanticsNode()
            .config[SemanticsProperties.ContentDescription].joinToString()
        assertTrue("TalkBack must identify $label", descriptions.contains(label))
        assertTrue("TalkBack must read the full $tag value", descriptions.contains(value))
    }

    private fun assertCardsDoNotOverlap() {
        val cards = listOf("reports-income", "reports-expense", "reports-net").map {
            compose.onNodeWithTag("$it-card", true).getUnclippedBoundsInRoot()
        }
        for (first in cards.indices) for (second in first + 1 until cards.size) {
            val a = cards[first]; val b = cards[second]
            assertTrue("Total cards must not intersect", a.right <= b.left || b.right <= a.left || a.bottom <= b.top || b.bottom <= a.top)
        }
    }

    private fun currency(value: BigInteger) = NumberFormat.getCurrencyInstance(Locale.forLanguageTag("pl-PL")).format(BigDecimal(value, 2))
}
