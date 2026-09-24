package pl.bargor.thesaurus.ui.reports

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.width
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.toPixelMap
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.assertTextContains
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.getUnclippedBoundsInRoot
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import java.math.BigInteger
import java.time.LocalDate
import java.time.Year
import java.time.YearMonth
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import androidx.compose.ui.unit.dp
import pl.bargor.thesaurus.ThesaurusTheme
import pl.bargor.thesaurus.data.model.ReportAggregation
import pl.bargor.thesaurus.data.model.ReportCategoryValue
import pl.bargor.thesaurus.data.model.ReportTotals
import pl.bargor.thesaurus.data.model.ReportTrendValue
import pl.bargor.thesaurus.data.model.ReportTypeFilter
import pl.bargor.thesaurus.data.model.SyncState

class ReportsScreenTest {
    @get:Rule val composeRule = createComposeRule()

    @Test fun chartsExposePolishDescriptionsVisibleLegendAndEntryDrillDown() {
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
        composeRule.onNodeWithTag("reports-category-legend").assertTextContains("Jedzenie", substring = true)
        // Expense-only chart uses a positive magnitude even though the total net is negative.
        composeRule.onNodeWithContentDescription("Trend dzienny: 2 lut: +12,50 zł").performScrollTo().assertIsDisplayed()
        composeRule.onNodeWithTag("reports-net").performScrollTo().assertTextContains("-12,50", substring = true)
        composeRule.onNodeWithTag("report-entry-e1").performScrollTo().performClick()
        assertEquals("e1", opened)
    }

    @Test fun dateErrorSyncStatesAndPolishPeriodArrowDescriptionsAreExposed() {
        var state by mutableStateOf(ReportsUiState(
            today = LocalDate.of(2026, 2, 15), month = YearMonth.of(2026, 2), year = Year.of(2026), isLoading = false,
            mode = ReportPeriodMode.CUSTOM, customDateError = true, syncState = SyncState.OFFLINE,
        ))
        composeRule.setContent {
            ThesaurusTheme {
                ReportsScreen(state, { state = state.copy(mode = it) }, {}, {}, {}, {}, {}, {}, {}, {})
            }
        }
        composeRule.onNodeWithTag("reports-offline").performScrollTo().assertIsDisplayed()
        composeRule.onAllNodesWithText("Podaj daty w formacie RRRR-MM-DD. Data „od” nie może być późniejsza od daty „do”.").assertCountEquals(1)
        composeRule.onNodeWithTag("reports-mode-month").performScrollTo().performClick()
        composeRule.onNodeWithContentDescription("Poprzedni miesiąc").performScrollTo().assertIsDisplayed()
        composeRule.onNodeWithContentDescription("Następny miesiąc").assertIsDisplayed()
        composeRule.onNodeWithTag("reports-mode-year").performClick()
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
        categoryNames.forEach { legend.assertTextContains(it, substring = true) }
        val chartBounds = chart.getUnclippedBoundsInRoot()
        val legendBounds = legend.getUnclippedBoundsInRoot()
        assertTrue("Legenda zachodzi na wykres", legendBounds.top > chartBounds.bottom)
        assertTrue("Legenda wychodzi poza szerokość wykresu", legendBounds.left >= chartBounds.left && legendBounds.right <= chartBounds.right)
        assertTrue("Długie nazwy kategorii powinny zawijać się w legendzie", legendBounds.bottom - legendBounds.top > 40.dp)
    }

    private fun testEntry(id: String, amount: Long, categoryId: String = "food") = pl.bargor.thesaurus.data.model.LedgerEntry(
        id = id, householdId = "home", amountGrosze = amount, date = LocalDate.of(2026, 2, 2),
        categoryId = categoryId, authorId = "anna", updatedById = "anna", title = "Zakupy",
    )
}
