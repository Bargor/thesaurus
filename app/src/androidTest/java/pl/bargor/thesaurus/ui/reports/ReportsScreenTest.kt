package pl.bargor.thesaurus.ui.reports

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.width
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.graphics.toPixelMap
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.SemanticsNodeInteraction
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.assertTextContains
import androidx.compose.ui.test.assertTextEquals
import androidx.compose.ui.test.assertContentDescriptionEquals
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.getUnclippedBoundsInRoot
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performSemanticsAction
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.text.TextLayoutResult
import java.math.BigInteger
import java.time.LocalDate
import java.time.Year
import java.time.YearMonth
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.Density
import pl.bargor.thesaurus.ThesaurusTheme
import pl.bargor.thesaurus.data.model.ReportAggregation
import pl.bargor.thesaurus.data.model.ReportCategoryValue
import pl.bargor.thesaurus.data.model.ReportTotals
import pl.bargor.thesaurus.data.model.ReportTrendValue
import pl.bargor.thesaurus.data.model.ReportTypeFilter
import pl.bargor.thesaurus.data.model.SyncState

class ReportsScreenTest {
    @get:Rule val composeRule = createComposeRule()

    @Test fun reportHeaderAndFilterButtonStayAtTopAtLargeFontScale() {
        composeRule.setContent {
            var state by remember { mutableStateOf(ReportsUiState(LocalDate.of(2026, 9, 30), YearMonth.of(2026, 9), Year.of(2026), isLoading = false)) }
            val density = LocalDensity.current
            CompositionLocalProvider(LocalDensity provides Density(density.density, 1.8f)) {
                ThesaurusTheme {
                    Box(Modifier.width(320.dp).fillMaxHeight().testTag("reports-fixture")) {
                        FilterScreen(state) { state = it }
                    }
                }
            }
        }
        composeRule.onAllNodesWithText("Raporty").assertCountEquals(1)
        val button = composeRule.onNodeWithTag("reports-open-filters").assertIsDisplayed().getUnclippedBoundsInRoot()
        val fixture = composeRule.onNodeWithTag("reports-fixture").getUnclippedBoundsInRoot()
        val topPadding = button.top - fixture.top
        assertTrue(topPadding >= 15.dp && topPadding <= 17.dp)
        assertTrue(button.right <= 320.dp && button.right - button.left >= 48.dp)
        composeRule.onNodeWithTag("reports-filter-category").assertDoesNotExist()
        composeRule.onNodeWithTag("reports-open-filters").performClick()
        composeRule.onNodeWithTag("reports-filter-dialog").assertIsDisplayed()
        composeRule.onNodeWithTag("reports-cancel-filters").assertIsDisplayed().performClick()
        composeRule.onNodeWithTag("reports-filter-dialog").assertDoesNotExist()
    }

    @Test fun monthlyReportExposesCategoryLegendAndEntryDrillDownWithoutTrend() {
        var opened: String? = null
        val entry = testEntry("e1", -1_250)
        val state = ReportsUiState(
            today = LocalDate.of(2026, 2, 15), month = YearMonth.of(2026, 2), year = Year.of(2026), isLoading = false,
            aggregation = ReportAggregation(
                ReportTotals(BigInteger.ZERO, BigInteger.valueOf(1_250), 1),
                listOf(ReportCategoryValue("food", BigInteger.valueOf(1_250))),
                listOf(ReportTrendValue(LocalDate.of(2026, 2, 2), BigInteger.valueOf(-1_250))),
            ),
            entries = listOf(ReportEntryItem(entry, "Jedzenie")), typeFilter = ReportTypeFilter.EXPENSE,
        )
        composeRule.setContent { ThesaurusTheme { ReportsScreen(state, {}, {}, {}, {}, {}, {}, {}, { opened = it }, {}) } }
        composeRule.onNodeWithContentDescription("Wykres pierścieniowy kategorii: Jedzenie: 12,50 zł (100%)").assertIsDisplayed()
        composeRule.onNodeWithTag("reports-category-name-food", useUnmergedTree = true).assertTextContains("Jedzenie", substring = true)
        composeRule.onNodeWithTag("reports-category-row-food")
            .assertContentDescriptionEquals("Segment wykresu kategorii: Jedzenie: 12,50 zł (100%)")
        composeRule.onNodeWithTag("reports-category-amount-food", useUnmergedTree = true).assertTextEquals("12,50 zł")
        composeRule.onNodeWithTag("reports-category-percentage-food", useUnmergedTree = true).assertTextEquals("100%")
        composeRule.onNodeWithTag("reports-trend-chart").assertDoesNotExist()
        composeRule.onNodeWithTag("reports-trend-summary").assertDoesNotExist()
        composeRule.onAllNodesWithText("Trend dzienny").assertCountEquals(0)
        composeRule.onNodeWithTag("reports-net", useUnmergedTree = true).assertTextContains("-12,50", substring = true)
        composeRule.waitForIdle()
        composeRule.onNodeWithTag("report-entry-e1").performScrollTo().performClick()
        assertEquals("e1", opened)
    }

    @Test fun reportEntryUsesItsPersistedCategoryColor() {
        val entry = testEntry("colored", -1_250)
        val state = ReportsUiState(
            today = LocalDate.of(2026, 2, 15),
            month = YearMonth.of(2026, 2),
            year = Year.of(2026),
            isLoading = false,
            aggregation = ReportAggregation(
                ReportTotals(BigInteger.ZERO, BigInteger.valueOf(1_250), 1),
                listOf(ReportCategoryValue("food", BigInteger.valueOf(1_250))),
                emptyList(),
            ),
            entries = listOf(ReportEntryItem(entry, "Jedzenie", "rose")),
            typeFilter = ReportTypeFilter.EXPENSE,
        )
        composeRule.setContent { ThesaurusTheme { ReportsScreen(state, {}, {}, {}, {}, {}, {}, {}, {}, {}) } }

        val image = composeRule.onNodeWithTag(
            "report-entry-category-color-colored",
            useUnmergedTree = true,
        )
            .performScrollTo()
            .captureToImage()
        assertEquals(Color(0xFFF43F5E), image.toPixelMap()[image.width / 2, image.height / 2])
    }

    @Test fun yearlyReportKeepsMonthlyTrendAndCustomRangeHasNoTrend() {
        var state by mutableStateOf(ReportsUiState(
            today = LocalDate.of(2026, 2, 15), month = YearMonth.of(2026, 2), year = Year.of(2026), isLoading = false,
            aggregation = ReportAggregation(
                ReportTotals(BigInteger.ZERO, BigInteger.valueOf(1_250), 1),
                listOf(ReportCategoryValue("food", BigInteger.valueOf(1_250))),
                listOf(ReportTrendValue(LocalDate.of(2026, 2, 1), BigInteger.valueOf(-1_250))),
            ),
            entries = listOf(ReportEntryItem(testEntry("e1", -1_250), "Jedzenie")),
            typeFilter = ReportTypeFilter.EXPENSE,
        ))
        composeRule.setContent {
            ThesaurusTheme {
                FilterScreen(state) { state = it }
            }
        }

        composeRule.onNodeWithTag("reports-trend-chart").assertDoesNotExist()
        chooseMode("YEAR")
        composeRule.onAllNodesWithText("Trend miesięczny").assertCountEquals(1)
        composeRule.onNodeWithContentDescription("Trend miesięczny: lut: +12,50 zł").performScrollTo().assertIsDisplayed()
        composeRule.onNodeWithTag("reports-trend-summary").assertTextContains("lut: +12,50", substring = true)
        chooseMode("CUSTOM")
        composeRule.onNodeWithTag("reports-trend-chart").assertDoesNotExist()
        composeRule.onNodeWithTag("reports-trend-summary").assertDoesNotExist()
    }

    @Test fun dateErrorSyncStatesAndPolishPeriodArrowDescriptionsAreExposed() {
        var state by mutableStateOf(ReportsUiState(
            today = LocalDate.of(2026, 2, 15), month = YearMonth.of(2026, 2), year = Year.of(2026), isLoading = false,
            mode = ReportPeriodMode.CUSTOM, customDateError = true, syncState = SyncState.OFFLINE,
        ))
        composeRule.setContent {
            ThesaurusTheme {
                FilterScreen(state) { state = it }
            }
        }
        composeRule.onNodeWithTag("reports-offline").assertDoesNotExist()
        composeRule.onNodeWithTag("reports-open-filters").performClick()
        composeRule.onAllNodesWithText("Podaj daty w formacie RRRR-MM-DD, nie późniejsze niż dzisiaj. Data „od” nie może być późniejsza od daty „do”.").assertCountEquals(1)
        composeRule.onNodeWithTag("reports-cancel-filters").performClick()
        composeRule.onNodeWithTag("reports-open-filters").performClick()
        chooseDialogMode("MONTH")
        composeRule.onNodeWithContentDescription("Poprzedni miesiąc").performScrollTo().assertIsDisplayed()
        composeRule.onNodeWithContentDescription("Następny miesiąc").assertIsDisplayed()
        chooseDialogMode("YEAR")
        composeRule.onNodeWithContentDescription("Poprzedni rok").performScrollTo().assertIsDisplayed()
        composeRule.onNodeWithContentDescription("Następny rok").assertIsDisplayed()
    }

    @Test fun doughnutStrokeAndLongLegendStayApartOnCompactWidth() {
        val categoryNames = listOf(
            "Zakupy spożywcze na cały tydzień dla rodziny",
            "Rachunki za energię elektryczną i ogrzewanie",
            "Przejazdy komunikacją miejską oraz pociągami",
        )
        val amounts = listOf(10_000L, 20_000L, 30_000L)
        val state = ReportsUiState(
            today = LocalDate.of(2026, 2, 15), month = YearMonth.of(2026, 2), year = Year.of(2026), isLoading = false,
            aggregation = ReportAggregation(
                ReportTotals(BigInteger.ZERO, BigInteger.valueOf(60_000), 3),
                amounts.mapIndexed { index, amount -> ReportCategoryValue("category-$index", BigInteger.valueOf(amount)) },
                emptyList(),
            ),
            entries = categoryNames.mapIndexed { index, name ->
                ReportEntryItem(testEntry("e$index", -amounts[index], "category-$index"), name)
            },
            typeFilter = ReportTypeFilter.EXPENSE,
        )
        composeRule.setContent {
            ThesaurusTheme {
                Box(Modifier.fillMaxSize()) {
                    Box(Modifier.width(280.dp).fillMaxHeight()) {
                        ReportsScreen(state, {}, {}, {}, {}, {}, {}, {}, {}, {})
                    }
                }
            }
        }

        val chart = composeRule.onNodeWithTag("reports-category-chart").performScrollTo()
        val image = chart.captureToImage()
        val pixels = image.toPixelMap()
        val background = pixels[0, 0]
        for (y in 0 until 4) {
            assertTrue("Pierścień dotyka górnej krawędzi wykresu", (0 until image.width).all { x -> pixels[x, y] == background })
            assertTrue("Pierścień wychodzi pod wykres", (0 until image.width).all { x -> pixels[x, image.height - 1 - y] == background })
        }

        val legend = composeRule.onNodeWithTag("reports-category-legend").performScrollTo()
        categoryNames.forEachIndexed { index, name ->
            val text = composeRule.onNodeWithTag("reports-category-name-category-$index", useUnmergedTree = true)
            text.revealInReport().assertTextEquals(name).assertIsDisplayed()
            text.performSemanticsAction(SemanticsActions.GetTextLayoutResult) { action ->
                val results = mutableListOf<TextLayoutResult>()
                assertTrue(action(results))
                assertEquals("Every long category remains a single horizontal line", 1, results.single().lineCount)
                assertTrue("Horizontal scrolling must preserve the full category", !results.single().didOverflowWidth)
                assertTrue("Long names must not be ellipsized", !results.single().isLineEllipsized(0))
            }
        }
        val chartBounds = chart.getUnclippedBoundsInRoot()
        val legendBounds = legend.getUnclippedBoundsInRoot()
        assertTrue("Legenda zachodzi na wykres", legendBounds.top > chartBounds.bottom)
        assertTrue("Legenda wychodzi poza szerokość wykresu", legendBounds.left >= chartBounds.left && legendBounds.right <= chartBounds.right)
        val viewport = composeRule.onNodeWithTag("reports-category-legend-table", useUnmergedTree = true)
        val content = composeRule.onNodeWithTag("reports-category-legend-content", useUnmergedTree = true)
        val contentBounds = content.getUnclippedBoundsInRoot()
        val viewportBounds = viewport.getUnclippedBoundsInRoot()
        assertTrue("Długie nazwy powinny być dostępne po przewinięciu w poziomie", contentBounds.right - contentBounds.left > viewportBounds.right - viewportBounds.left)
        assertTrue(viewport.fetchSemanticsNode().config[SemanticsProperties.HorizontalScrollAxisRange].maxValue() > 0f)
    }


    private fun chooseDialogMode(mode: String) {
        composeRule.onNodeWithTag("reports-period-selector").performScrollTo().performClick()
        composeRule.onNodeWithTag("reports-period-selector-option-$mode").performClick()
    }
    private fun chooseMode(mode: String) {
        composeRule.onNodeWithTag("reports-open-filters").performClick()
        chooseDialogMode(mode)
        composeRule.onNodeWithTag("reports-apply-filters").performClick()
    }
    @androidx.compose.runtime.Composable
    private fun FilterScreen(state: ReportsUiState, update: (ReportsUiState) -> Unit) {
        ReportsScreen(state, { mode -> update(state.copy(filterDraft = state.filterDraft?.copy(mode = mode))) },
            {}, {}, {}, {}, {}, {}, {}, {},
            onOpenFilters = { update(state.copy(filterDraft = ReportFilterDraft(state.mode, state.month, state.year,
                state.typeFilter, state.customFromInput, state.customToInput, customDateError = state.customDateError))) },
            onDismissFilters = { update(state.copy(filterDraft = null)) },
            onApplyFilters = { state.filterDraft?.let { update(state.copy(mode = it.mode, filterDraft = null)) } })
    }

    private fun SemanticsNodeInteraction.revealInReport(): SemanticsNodeInteraction {
        // The nearest scroll ancestor is the horizontal legend. Reveal the row in the
        // outer report first, then bring its text into the shared horizontal viewport.
        val report = composeRule.onNode(SemanticsMatcher("vertical report scroller") {
            it.config.contains(SemanticsProperties.VerticalScrollAxisRange)
        }, useUnmergedTree = true)
        val verticalDelta = fetchSemanticsNode().positionInRoot.y - report.fetchSemanticsNode().positionInRoot.y
        report.performSemanticsAction(SemanticsActions.ScrollBy) { it(0f, verticalDelta) }
        composeRule.waitForIdle()
        val viewport = composeRule.onNodeWithTag("reports-category-legend-table", useUnmergedTree = true)
        val viewportNode = viewport.fetchSemanticsNode()
        val target = fetchSemanticsNode()
        val leftDelta = target.positionInRoot.x - viewportNode.positionInRoot.x
        val rightDelta = leftDelta + target.size.width - viewportNode.size.width
        val horizontalDelta = when {
            target.size.width >= viewportNode.size.width -> leftDelta
            leftDelta < 0f -> leftDelta
            rightDelta > 0f -> rightDelta
            else -> 0f
        }
        viewport.performSemanticsAction(SemanticsActions.ScrollBy) { it(horizontalDelta, 0f) }
        composeRule.waitForIdle()
        return this
    }

    private fun testEntry(id: String, amount: Long, categoryId: String = "food") = pl.bargor.thesaurus.data.model.LedgerEntry(
        id = id, householdId = "home", amountGrosze = amount, date = LocalDate.of(2026, 2, 2),
        categoryId = categoryId, authorId = "anna", updatedById = "anna", title = "Zakupy",
    )
}
