package pl.bargor.thesaurus.ui.summary

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsNotSelected
import androidx.compose.ui.test.assertIsSelected
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.assertTextContains
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithContentDescription
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
import pl.bargor.thesaurus.data.model.SummaryTotals
import pl.bargor.thesaurus.data.model.SyncState
import pl.bargor.thesaurus.data.model.Category
import pl.bargor.thesaurus.data.model.LedgerEntry

class SummaryScreenTest {
    @get:Rule val composeRule = createComposeRule()

    @Test fun loadingEmptyOfflineErrorAndPendingStates() {
        var state by mutableStateOf(SummaryUiState(month = YearMonth.of(2026, 9), year = Year.of(2026)))
        var retries = 0
        composeRule.setContent {
            ThesaurusTheme {
                SummaryScreen(state, {}, {}, {}, { retries++ })
            }
        }
        composeRule.onNodeWithTag("summary-loading").assertIsDisplayed()
        composeRule.onNodeWithContentDescription("Ładowanie danych").assertIsDisplayed()
        composeRule.runOnIdle { state = state.copy(isLoading = false) }
        composeRule.onNodeWithTag("summary-empty").assertIsDisplayed()
        composeRule.onNodeWithTag("summary-income").assertIsDisplayed()
        composeRule.runOnIdle { state = state.copy(syncState = SyncState.OFFLINE) }
        composeRule.onNodeWithTag("summary-offline").performScrollTo().assertIsDisplayed()
        composeRule.runOnIdle { state = state.copy(syncState = SyncState.ERROR, hasError = true) }
        composeRule.onNodeWithTag("summary-error").performScrollTo().assertIsDisplayed()
        composeRule.onNodeWithTag("summary-empty").assertDoesNotExist()
        composeRule.onNodeWithTag("summary-retry").performClick()
        assertEquals(1, retries)
        composeRule.runOnIdle {
            state = state.copy(
                hasError = false, syncState = SyncState.PENDING,
                totals = SummaryTotals(BigInteger.valueOf(1_200), BigInteger.valueOf(500), 2),
            )
        }
        composeRule.onNodeWithTag("summary-pending").performScrollTo().assertIsDisplayed()
        composeRule.onNodeWithTag("summary-net").performScrollTo().assertIsDisplayed()
        composeRule.onNodeWithTag("summary-net").assertTextContains("7,00", substring = true)
        composeRule.onNodeWithContentDescription("Bilans: +7,00 zł").assertIsDisplayed()
    }

    @Test fun modeSelectorAndPeriodControlsNavigate() {
        var previous = 0
        var next = 0
        var state by mutableStateOf(
            SummaryUiState(month = YearMonth.of(2026, 9), year = Year.of(2026), isLoading = false),
        )
        composeRule.setContent {
            ThesaurusTheme {
                SummaryScreen(
                    state = state,
                    onSelectPeriodMode = { state = state.copy(mode = it) },
                    onPreviousPeriod = { previous++ },
                    onNextPeriod = { next++ },
                    onRetry = {},
                )
            }
        }
        composeRule.onNodeWithTag("summary-mode-month").assertIsSelected()
        composeRule.onNodeWithTag("summary-mode-year").assertIsNotSelected()
        composeRule.onNodeWithTag("summary-mode-year").performClick()
        composeRule.onNodeWithTag("summary-mode-year").assertIsSelected()
        composeRule.onNodeWithTag("summary-mode-month").assertIsNotSelected()
        composeRule.onNodeWithTag("summary-period").assertTextContains("2026")
        composeRule.onNodeWithTag("summary-previous-period").performClick()
        composeRule.onNodeWithTag("summary-next-period").performClick()
        assertEquals(1, previous)
        assertEquals(1, next)
        composeRule.onNodeWithTag("summary-mode-month").performClick()
        composeRule.onNodeWithTag("summary-mode-month").assertIsSelected()
        composeRule.onNodeWithTag("summary-period").assertTextContains("Wrzesień 2026")
    }

    @Test fun entriesShowTaxonomyPolishFormatsAndOpenEdit() {
        val entry = LedgerEntry(
            id = "entry-1", householdId = "home", amountGrosze = -12345,
            date = LocalDate.of(2026, 9, 2), title = "  ", categoryId = "food",
            tags = listOf(" Dom ", "pilne"), authorId = "anna", updatedById = "anna",
        )
        var opened: String? = null
        composeRule.setContent {
            ThesaurusTheme {
                SummaryScreen(
                    state = SummaryUiState(
                        month = YearMonth.of(2026, 9), year = Year.of(2026), isLoading = false,
                        totals = SummaryTotals(expenseGrosze = BigInteger.valueOf(12345), entryCount = 1),
                        entries = listOf(SummaryEntryItem(entry, "Żywność", null)),
                    ),
                    onSelectPeriodMode = {}, onPreviousPeriod = {}, onNextPeriod = {}, onRetry = {},
                    onOpenEntry = { opened = it },
                )
            }
        }
        composeRule.onNodeWithTag("summary-entry-count").performScrollTo().assertTextContains("1", substring = true)
        composeRule.onNodeWithTag("summary-entry-entry-1").performScrollTo().assertIsDisplayed()
        composeRule.onNodeWithTag("summary-entry-entry-1").assertTextContains("Żywność", substring = true)
        composeRule.onNodeWithTag("summary-entry-entry-1").assertTextContains("2 września 2026", substring = true)
        composeRule.onNodeWithTag("summary-entry-entry-1").assertTextContains("123,45", substring = true)
        composeRule.onNodeWithTag("summary-entry-entry-1").performClick()
        assertEquals("entry-1", opened)
    }

    @Test fun filterSortDirectionAndClearControlsAreAccessible() {
        var category: String? = null
        var tag: String? = null
        var sort: SummaryEntrySort? = null
        var toggles = 0
        var clears = 0
        composeRule.setContent {
            ThesaurusTheme {
                SummaryScreen(
                    state = SummaryUiState(
                        month = YearMonth.of(2026, 9), year = Year.of(2026), isLoading = false,
                        categories = listOf(Category("food", "home", "Żywność", authorId = "anna", updatedById = "anna")),
                        tags = listOf("dom"),
                    ),
                    onSelectPeriodMode = {}, onPreviousPeriod = {}, onNextPeriod = {}, onRetry = {},
                    onSelectCategory = { category = it },
                    onSelectTag = { tag = it },
                    onSelectSort = { sort = it },
                    onToggleSortDirection = { toggles++ },
                    onClearControls = { clears++ },
                )
            }
        }
        composeRule.onNodeWithTag("summary-filter-category").performScrollTo().performClick()
        composeRule.onNodeWithTag("summary-filter-category-food").performClick()
        assertEquals("food", category)
        composeRule.onNodeWithTag("summary-filter-subcategory").assertIsNotEnabled()
        composeRule.onNodeWithTag("summary-filter-tag").performScrollTo().performClick()
        composeRule.onNodeWithTag("summary-filter-tag-dom").performClick()
        assertEquals("dom", tag)
        composeRule.onNodeWithTag("summary-sort").performScrollTo().performClick()
        composeRule.onNodeWithTag("summary-sort-AMOUNT").performClick()
        assertEquals(SummaryEntrySort.AMOUNT, sort)
        composeRule.onNodeWithTag("summary-sort-direction").performScrollTo().performClick()
        composeRule.onNodeWithTag("summary-clear-controls").performScrollTo().performClick()
        assertEquals(1, toggles)
        assertEquals(1, clears)
    }

    @Test fun periodAndModeStateReplaceTotalsAndVisibleEntriesTogether() {
        val january = LedgerEntry(
            id = "january", householdId = "home", amountGrosze = 100,
            date = LocalDate.of(2026, 1, 1), categoryId = "food", authorId = "anna", updatedById = "anna",
        )
        val february = january.copy(id = "february", date = LocalDate.of(2026, 2, 1), amountGrosze = 200)
        var state by mutableStateOf(SummaryUiState(
            month = YearMonth.of(2026, 1), year = Year.of(2026), isLoading = false,
            totals = SummaryTotals(incomeGrosze = BigInteger.valueOf(100), entryCount = 1),
            entries = listOf(SummaryEntryItem(january, "Żywność", null)),
        ))
        composeRule.setContent {
            ThesaurusTheme {
                SummaryScreen(
                    state = state,
                    onSelectPeriodMode = { mode -> state = state.copy(
                        mode = mode,
                        totals = SummaryTotals(incomeGrosze = BigInteger.valueOf(300), entryCount = 2),
                        entries = listOf(SummaryEntryItem(february, "Żywność", null), SummaryEntryItem(january, "Żywność", null)),
                    ) },
                    onPreviousPeriod = { state = state.copy(
                        year = Year.of(2025), totals = SummaryTotals(), entries = emptyList(),
                    ) },
                    onNextPeriod = {}, onRetry = {},
                )
            }
        }
        composeRule.onNodeWithTag("summary-entry-january").performScrollTo().assertIsDisplayed()
        composeRule.onNodeWithTag("summary-mode-year").performScrollTo().performClick()
        composeRule.onNodeWithTag("summary-mode-year").assertIsSelected()
        composeRule.onNodeWithTag("summary-income").assertTextContains("3,00", substring = true)
        composeRule.onNodeWithTag("summary-entry-february").performScrollTo().assertIsDisplayed()
        composeRule.onNodeWithTag("summary-entry-count").performScrollTo().assertTextContains("2", substring = true)
        composeRule.onNodeWithTag("summary-previous-period").performScrollTo().performClick()
        composeRule.onNodeWithTag("summary-period").assertTextContains("2025", substring = true)
        composeRule.onNodeWithTag("summary-entry-february").assertDoesNotExist()
        composeRule.onNodeWithTag("summary-empty").assertExists()
    }
}
