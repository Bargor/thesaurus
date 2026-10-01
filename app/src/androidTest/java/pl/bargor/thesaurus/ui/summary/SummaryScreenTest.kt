package pl.bargor.thesaurus.ui.summary

import androidx.compose.foundation.layout.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toPixelMap
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.*
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import java.time.*
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import pl.bargor.thesaurus.ThesaurusTheme
import pl.bargor.thesaurus.data.model.*
import pl.bargor.thesaurus.ui.entries.EntryListItem

class SummaryScreenTest {
    @get:Rule val compose = createComposeRule()
    @Test fun compactMetricRowsAlignSymbolsWithExactAmountsAndKeepPolishAccessibilityInOverviewAndDetail() {
        val card = fixture().cards.last()
        var state by mutableStateOf(fixture().copy(cards = listOf(card)))
        compose.setContent { ThesaurusTheme { SummaryScreen(state, {}, {}, {}, onOpenPeriod = {
            state = state.copy(detailCard = card)
        }) } }
        assertCompactMetricRows("month-2026-9")
        val description = compose.onNodeWithTag("summary-card-month-2026-9")
            .fetchSemanticsNode().config[SemanticsProperties.ContentDescription].joinToString()
        listOf("Przychody: ${summaryCurrency(400.toBigInteger())}",
            "Wydatki: ${summaryCurrency((-100).toBigInteger())}",
            "Bilans: ${summaryCurrency(300.toBigInteger())}").forEach { meaning ->
            assertTrue("Overview must announce $meaning", description.contains(meaning))
        }
        assertNoStandaloneMetricCaptions()
        compose.onNodeWithText("Przychody: 80,0%", useUnmergedTree = true).assertIsDisplayed()
        compose.onNodeWithText("Wydatki: 20,0%", useUnmergedTree = true).assertIsDisplayed()
        compose.onNodeWithTag("summary-card-month-2026-9").performClick()
        compose.onNodeWithTag("summary-detail-period").assertIsDisplayed()
        assertCompactMetricRows("month-2026-9")
        assertNoStandaloneMetricCaptions()
        compose.onNodeWithText("Przychody: 80,0%", useUnmergedTree = true).assertIsDisplayed()
        compose.onNodeWithText("Wydatki: 20,0%", useUnmergedTree = true).assertIsDisplayed()
    }
    @Test fun overviewCardsHaveExactSignedTotalsChartDescriptionsAndFutureMonthsAreAbsent() {
        compose.setContent { ThesaurusTheme { SummaryScreen(fixture(), {}, {}, {}) } }
        compose.onNodeWithTag("summary-period").assertTextContains("2026")
        compose.onNodeWithTag("summary-next-period").assertIsNotEnabled()
        compose.onNodeWithTag("summary-list").performScrollToNode(hasTestTag("summary-card-month-2026-9"))
        val card = fixture().cards.last()
        compose.onNodeWithTag("summary-income-month-2026-9", true).assertTextEquals(summaryCurrency(400.toBigInteger()))
        compose.onNodeWithTag("summary-expense-month-2026-9", true).assertTextEquals(summaryCurrency((-100).toBigInteger()))
        compose.onNodeWithTag("summary-balance-month-2026-9", true).assertTextEquals(summaryCurrency(300.toBigInteger()))
        val chart = compose.onNodeWithTag("summary-chart-month-2026-9", true).assertIsDisplayed()
        assertTrue(chart.fetchSemanticsNode().config[SemanticsProperties.ContentDescription].joinToString().contains(summaryCurrency(card.totals.incomeGrosze)))
        assertImageContains("summary-chart-month-2026-9", Color(0xFF146C2E))
        assertImageContains("summary-chart-month-2026-9", Color(0xFFB3261E))
        compose.onNodeWithTag("summary-card-month-2026-10").assertDoesNotExist()
    }
    @Test fun tapCardOpensExactEntriesAndBackRestoresScrolledCard() {
        var state by mutableStateOf(fixture())
        compose.setContent { ThesaurusTheme { SummaryScreen(state, {}, {}, {}, onOpenPeriod = { key ->
            val card = state.cards.single { it.key == key }
            state = state.copy(detailCard = card, detailEntries = card.entries.map { EntryListItem(it, "Jedzenie", null, "Anna") })
        }, onClosePeriod = { state = state.copy(detailCard = null, detailEntries = emptyList()) }) } }
        compose.onNodeWithTag("summary-list").performScrollToNode(hasTestTag("summary-card-month-2026-9"))
        val before = compose.onNodeWithTag("summary-card-month-2026-9").fetchSemanticsNode().boundsInRoot
        compose.onNodeWithTag("summary-card-month-2026-9").performClick()
        compose.onNodeWithTag("summary-detail-period").assertIsDisplayed()
        compose.onNodeWithTag("summary-heading-month-2026-9", true).assertTextContains("Wrzesień 2026")
        compose.onNodeWithTag("summary-detail-list").performScrollToNode(hasTestTag("entry-expense"))
        compose.onNodeWithTag("entry-expense").assertIsDisplayed()
        compose.onNodeWithTag("summary-detail-list").performScrollToNode(hasTestTag("entry-income"))
        compose.onNodeWithTag("entry-income").assertIsDisplayed()
        compose.onNodeWithTag("summary-detail-back").performClick()
        compose.onNodeWithTag("summary-card-month-2026-9").assertIsDisplayed()
        assertEquals(before, compose.onNodeWithTag("summary-card-month-2026-9").fetchSemanticsNode().boundsInRoot)
    }
    @Test fun loadingEmptyRetryAndCachedCardsRemainVisibleInTheirRespectiveStates() {
        var state by mutableStateOf(fixture().copy(cards = emptyList(), isLoading = true))
        var retries = 0
        compose.setContent { ThesaurusTheme { SummaryScreen(state, {}, {}, {}, onRetry = { retries++ }) } }
        compose.onNodeWithTag("summary-loading").assertIsDisplayed().assertContentDescriptionEquals("Ładowanie danych")
        compose.onNodeWithTag("summary-empty").assertDoesNotExist()
        compose.runOnIdle { state = state.copy(isLoading = false) }
        compose.onNodeWithTag("summary-empty").assertIsDisplayed()
        compose.runOnIdle { state = state.copy(hasError = true, syncState = SyncState.ERROR) }
        compose.onNodeWithTag("summary-retry").assertIsDisplayed()
        compose.onNodeWithTag("summary-empty").assertDoesNotExist()
        compose.runOnIdle { state = fixture().copy(hasError = true, syncState = SyncState.PENDING) }
        compose.onNodeWithTag("summary-retry").performClick()
        assertEquals(1, retries)
        compose.onNodeWithTag("summary-list").performScrollToNode(hasTestTag("summary-card-month-2026-9"))
        compose.onNodeWithTag("summary-card-month-2026-9").assertIsDisplayed()
    }
    @Test fun openingDifferentPeriodResetsDetailScrollWhileReturningToSamePeriodRetainsIt() {
        val extra = (1..24).map { index -> entry("extra-$index", -100).copy(date = LocalDate.of(2026, 9, 12)) }
        val cards = prepareSummaryOverview(extra, "home", Year.of(2026), SummaryPeriodMode.MONTH, LocalDate.of(2026, 9, 15))
        var state by mutableStateOf(fixture().copy(cards = cards))
        compose.setContent { ThesaurusTheme { SummaryScreen(state, {}, {}, {}, onOpenPeriod = { key ->
            val card = state.cards.single { it.key == key }
            state = state.copy(detailCard = card, detailEntries = card.entries.map { EntryListItem(it, "Jedzenie", null, "Anna") })
        }, onClosePeriod = { state = state.copy(detailCard = null, detailEntries = emptyList()) }) } }
        compose.onNodeWithTag("summary-list").performScrollToNode(hasTestTag("summary-card-month-2026-9"))
        compose.onNodeWithTag("summary-card-month-2026-9").performClick()
        compose.onNodeWithTag("summary-detail-list").performScrollToNode(hasTestTag("entry-extra-9"))
        val scrolled = compose.onNodeWithTag("entry-extra-9").assertIsDisplayed().fetchSemanticsNode().boundsInRoot
        compose.onNodeWithTag("summary-detail-back").performClick()
        compose.onNodeWithTag("summary-card-month-2026-9").performClick()
        assertEquals(scrolled, compose.onNodeWithTag("entry-extra-9").assertIsDisplayed().fetchSemanticsNode().boundsInRoot)
        compose.onNodeWithTag("summary-detail-back").performClick()
        compose.onNodeWithTag("summary-list").performScrollToNode(hasTestTag("summary-card-month-2026-8"))
        compose.onNodeWithTag("summary-card-month-2026-8").performClick()
        compose.onNodeWithTag("summary-detail-period").assertIsDisplayed()
        compose.onNodeWithTag("summary-heading-month-2026-8", true).assertIsDisplayed().assertTextContains("Sierpień 2026")
        compose.onNodeWithTag("summary-income-month-2026-8", true).assertIsDisplayed().assertTextEquals(summaryCurrency(0.toBigInteger()))
    }
    @Test fun headingTogglesAnnualListAndMonthlyYearArrowsHaveAccessibleDescriptions() {
        var state by mutableStateOf(fixture())
        var previous = 0
        compose.setContent { ThesaurusTheme { SummaryScreen(state, { mode ->
            state = state.copy(mode = mode, cards = prepareSummaryOverview(
                listOf(entry("expense", -100), entry("income", 400)), "home", state.year,
                mode, LocalDate.of(2026, 9, 15)))
        }, { previous++ }, {}) } }
        val period = compose.onNodeWithTag("summary-period").assertHasClickAction()
        period.assert(SemanticsMatcher.expectValue(SemanticsProperties.Role, Role.Button))
        period.assert(SemanticsMatcher.expectValue(SemanticsProperties.StateDescription, "Podsumowanie miesięczne"))
        compose.onNodeWithTag("summary-previous-period").assertContentDescriptionEquals("Poprzedni rok").performClick()
        assertEquals(1, previous)
        period.performClick().assert(SemanticsMatcher.expectValue(SemanticsProperties.StateDescription, "Podsumowanie roczne"))
        compose.onNodeWithTag("summary-previous-period").assertDoesNotExist()
        compose.onNodeWithTag("summary-next-period").assertDoesNotExist()
        compose.onNodeWithTag("summary-card-year-2026").assertIsDisplayed().assertHasClickAction()
        compose.onNodeWithTag("summary-heading-year-2026", true).assertTextEquals("2026")
        compose.onNodeWithTag("summary-balance-year-2026", true).assertTextEquals(summaryCurrency(300.toBigInteger()))
        period.performClick().assertTextContains("2026")
    }
    @Test fun annualOverviewRendersRelevantYearsAndOpensTheTappedYear() {
        val entries = listOf(entry("old", -200).copy(date = LocalDate.of(2023, 12, 31)),
            entry("prior", 700).copy(date = LocalDate.of(2025, 2, 28)), entry("current", -100),
            entry("future", 999).copy(date = LocalDate.of(2027, 1, 1)))
        val cards = prepareSummaryOverview(entries, "home", Year.of(2026), SummaryPeriodMode.YEAR, LocalDate.of(2026, 9, 15))
        var selected: SummaryPeriodKey? = null
        compose.setContent { ThesaurusTheme { SummaryScreen(fixture().copy(mode = SummaryPeriodMode.YEAR, cards = cards), {}, {}, {}, onOpenPeriod = { selected = it }) } }
        compose.onNodeWithTag("summary-period").assertTextContains("Wszystkie lata")
        compose.onNodeWithTag("summary-card-year-2023").assertIsDisplayed()
        for ((year, amount) in listOf(2023 to -200, 2025 to 700, 2026 to -100)) {
            compose.onNodeWithTag("summary-list").performScrollToNode(hasTestTag("summary-card-year-$year"))
            compose.onNodeWithTag("summary-heading-year-$year", true).assertTextEquals(year.toString())
            compose.onNodeWithTag("summary-balance-year-$year", true).assertTextEquals(summaryCurrency(amount.toBigInteger()))
        }
        compose.onNodeWithTag("summary-card-year-2026").performClick()
        assertEquals(SummaryPeriodKey(SummaryPeriodMode.YEAR, 2026), selected)
        compose.onNodeWithTag("summary-card-year-2027").assertDoesNotExist()
    }
    @Test fun zeroIncomeOnlyAndExpenseOnlyChartsExposeExactAmountsAndSingleColorArcs() {
        val today = LocalDate.of(2026, 9, 15)
        fun card(entries: List<LedgerEntry>) = prepareSummaryOverview(entries, "home", Year.of(2026), SummaryPeriodMode.MONTH, today).last()
        var state by mutableStateOf(fixture().copy(cards = listOf(card(emptyList()))))
        var neutral = Color.Unspecified
        compose.setContent { ThesaurusTheme {
            neutral = androidx.compose.material3.MaterialTheme.colorScheme.outlineVariant
            SummaryScreen(state, {}, {}, {})
        } }
        val chartTag = "summary-chart-month-2026-9"
        compose.onNodeWithTag(chartTag, true).assertContentDescriptionEquals("Brak wpisów")
        compose.onNodeWithTag("summary-top-category-month-2026-9", true).assertTextEquals("Brak wydatków")
        compose.onNodeWithTag("summary-income-month-2026-9", true).assertTextEquals(summaryCurrency(0.toBigInteger()))
        compose.onNodeWithTag("summary-expense-month-2026-9", true).assertTextEquals(summaryCurrency(0.toBigInteger()))
        assertImageContains(chartTag, neutral)
        assertImageDoesNotContain(chartTag, Color(0xFF146C2E))
        assertImageDoesNotContain(chartTag, Color(0xFFB3261E))
        compose.runOnIdle { state = state.copy(cards = listOf(card(listOf(entry("income-only", 500))))) }
        compose.onNodeWithTag(chartTag, true).assertContentDescriptionEquals("Udział przychodów i wydatków. Przychody: ${summaryCurrency(500.toBigInteger())}. Wydatki: ${summaryCurrency(0.toBigInteger())}.")
        compose.onNodeWithTag("summary-top-category-month-2026-9", true).assertTextEquals("Brak wydatków")
        compose.onNodeWithText("Przychody: 100,0%", useUnmergedTree = true).assertIsDisplayed()
        compose.onNodeWithText("Wydatki: 0,0%", useUnmergedTree = true).assertIsDisplayed()
        assertImageContains(chartTag, Color(0xFF146C2E))
        assertImageDoesNotContain(chartTag, Color(0xFFB3261E))
        compose.runOnIdle { state = state.copy(cards = listOf(card(listOf(entry("expense-only", -500))))) }
        compose.onNodeWithTag(chartTag, true).assertContentDescriptionEquals("Udział przychodów i wydatków. Przychody: ${summaryCurrency(0.toBigInteger())}. Wydatki: ${summaryCurrency((-500).toBigInteger())}.")
        compose.onNodeWithText("Przychody: 0,0%", useUnmergedTree = true).assertIsDisplayed()
        compose.onNodeWithText("Wydatki: 100,0%", useUnmergedTree = true).assertIsDisplayed()
        assertImageContains(chartTag, Color(0xFFB3261E))
        assertImageDoesNotContain(chartTag, Color(0xFF146C2E))
    }
    @Test fun narrowLargeFontRetainsAllAmountDigitsAndCardTextDoesNotOverlapChart() {
        val entries = listOf(entry("big", Long.MIN_VALUE), entry("income", Long.MAX_VALUE))
        val card = prepareSummaryOverview(entries, "home", Year.of(2026), SummaryPeriodMode.MONTH, LocalDate.of(2026, 9, 15)).last().copy(
            highestExpenseCategory = Category("food", "home", "Bardzo długa nazwa kategorii obejmującej codzienne zakupy", authorId = "actor", updatedById = "actor"))
        compose.setContent {
            val density = LocalDensity.current
            CompositionLocalProvider(LocalDensity provides Density(density.density, 1.8f)) {
                ThesaurusTheme { Box(Modifier.width(320.dp).fillMaxHeight()) { SummaryScreen(fixture().copy(cards = listOf(card)), {}, {}, {}) } }
            }
        }
        listOf("summary-period", "summary-previous-period", "summary-next-period").forEach { tag ->
            val b = compose.onNodeWithTag(tag).assertIsDisplayed().getUnclippedBoundsInRoot()
            assertTrue(b.bottom - b.top >= 48.dp && b.right - b.left >= 48.dp)
            assertTrue(b.left >= 0.dp && b.right <= 320.dp)
        }
        for (kind in listOf("income", "expense", "balance")) {
            val tag = "summary-$kind-month-2026-9"
            // Card accessibility merges its children; layout assertions inspect their
            // individual text nodes and therefore scroll through the unmerged tree.
            compose.onNodeWithTag("summary-list", useUnmergedTree = true).performScrollToNode(hasTestTag(tag))
            compose.onNodeWithTag(tag, true).performSemanticsAction(SemanticsActions.GetTextLayoutResult) { action ->
                val results = mutableListOf<androidx.compose.ui.text.TextLayoutResult>()
                assertTrue(action(results))
                results.forEach { r ->
                    assertFalse(r.didOverflowHeight)
                    assertEquals(r.layoutInput.text.length, r.getLineEnd(r.lineCount - 1))
                    for (line in 0 until r.lineCount) assertTrue(r.getLineRight(line) <= r.size.width + 1f)
                }
            }
            val symbol = compose.onNodeWithTag("$tag-symbol", true).getUnclippedBoundsInRoot()
            val value = compose.onNodeWithTag(tag, true).getUnclippedBoundsInRoot()
            assertTrue("$kind symbol must remain beside the complete amount", symbol.right <= value.left)
        }
        val amount = compose.onNodeWithTag("summary-balance-month-2026-9", true).getUnclippedBoundsInRoot()
        val chart = compose.onNodeWithTag("summary-chart-month-2026-9", true).getUnclippedBoundsInRoot()
        assertTrue(amount.bottom <= chart.top)
    }
    private fun assertCompactMetricRows(periodTag: String) {
        var symbolLeft: Float? = null
        var amountLeft: Float? = null
        for ((kind, label, amount) in listOf(
            Triple("income", "Przychody", 400), Triple("expense", "Wydatki", -100),
            Triple("balance", "Bilans", 300),
        )) {
            val tag = "summary-$kind-$periodTag"
            val value = summaryCurrency(amount.toBigInteger())
            compose.onNodeWithTag("$tag-row", true).assertContentDescriptionEquals("$label: $value")
            val symbol = compose.onNodeWithTag("$tag-symbol", true).assertIsDisplayed().fetchSemanticsNode()
            val amountNode = compose.onNodeWithTag(tag, true).assertIsDisplayed().assertTextEquals(value).fetchSemanticsNode()
            assertNull("Decorative symbol must not replace its localized meaning", symbol.config.getOrNull(SemanticsProperties.ContentDescription))
            assertTrue("$kind symbol must precede amount on the same row", symbol.boundsInRoot.right <= amountNode.boundsInRoot.left)
            assertEquals("$kind symbol and amount must be vertically aligned", symbol.boundsInRoot.center.y, amountNode.boundsInRoot.center.y, 1f)
            symbolLeft?.let { assertEquals("Symbols must share a column", it, symbol.boundsInRoot.left, 1f) }
            amountLeft?.let { assertEquals("Amounts must share a column", it, amountNode.boundsInRoot.left, 1f) }
            symbolLeft = symbol.boundsInRoot.left
            amountLeft = amountNode.boundsInRoot.left
        }
    }
    private fun assertNoStandaloneMetricCaptions() {
        listOf("Przychody", "Wydatki", "Bilans").forEach {
            compose.onAllNodesWithText(it, useUnmergedTree = true).assertCountEquals(0)
        }
    }
    private fun fixture(): SummaryUiState = SummaryUiState(YearMonth.of(2026, 9), Year.of(2026), cards = prepareSummaryOverview(listOf(entry("expense", -100), entry("income", 400)), "home", Year.of(2026), SummaryPeriodMode.MONTH, LocalDate.of(2026, 9, 15)), isLoading = false)
    private fun entry(id: String, amount: Long) = LedgerEntry(id, "home", amount, LocalDate.of(2026, 9, 12), categoryId = "food", authorId = "actor", updatedById = "actor")
    private fun assertImageContains(tag: String, color: Color) {
        val image = compose.onNodeWithTag(tag, true).captureToImage()
        val pixels = image.toPixelMap()
        assertTrue("$tag must render $color", (0 until image.width).any { x -> (0 until image.height).any { y -> pixels[x, y] == color } })
    }
    private fun assertImageDoesNotContain(tag: String, color: Color) {
        val image = compose.onNodeWithTag(tag, true).captureToImage()
        val pixels = image.toPixelMap()
        assertFalse("$tag must not render $color", (0 until image.width).any { x -> (0 until image.height).any { y -> pixels[x, y] == color } })
    }
}
