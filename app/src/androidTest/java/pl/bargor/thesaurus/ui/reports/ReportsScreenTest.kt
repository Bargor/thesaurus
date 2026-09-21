package pl.bargor.thesaurus.ui.reports

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.assertTextContains
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
import org.junit.Rule
import org.junit.Test
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
        composeRule.onNodeWithContentDescription("Trend dzienny: 2 lut: +12,50 zł").assertIsDisplayed()
        composeRule.onNodeWithTag("reports-net").assertTextContains("-12,50", substring = true)
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
        composeRule.onNodeWithTag("reports-offline").assertIsDisplayed()
        composeRule.onAllNodesWithText("Podaj daty w formacie RRRR-MM-DD. Data „od” nie może być późniejsza od daty „do”.").assertCountEquals(2)
        composeRule.onNodeWithTag("reports-mode-month").performClick()
        composeRule.onNodeWithContentDescription("Poprzedni miesiąc").assertIsDisplayed()
        composeRule.onNodeWithContentDescription("Następny miesiąc").assertIsDisplayed()
        composeRule.onNodeWithTag("reports-mode-year").performClick()
        composeRule.onNodeWithContentDescription("Poprzedni rok").assertIsDisplayed()
        composeRule.onNodeWithContentDescription("Następny rok").assertIsDisplayed()
    }

    private fun testEntry(id: String, amount: Long) = pl.bargor.thesaurus.data.model.LedgerEntry(
        id = id, householdId = "home", amountGrosze = amount, date = LocalDate.of(2026, 2, 2),
        categoryId = "food", authorId = "anna", updatedById = "anna", title = "Zakupy",
    )
}
