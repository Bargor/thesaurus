package pl.bargor.thesaurus.ui.entries

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import java.time.LocalDate
import org.junit.Rule
import org.junit.Test
import pl.bargor.thesaurus.ThesaurusTheme
import pl.bargor.thesaurus.data.model.LedgerEntry
import pl.bargor.thesaurus.data.model.SyncState

class EntryListScreenTest {
    @get:Rule val composeRule = createComposeRule()

    @Test
    fun rowUsesTaxonomyShowsNormalizedTagsAndDerivesAmountStyleFromSign() {
        val income = item("income", 1200, title = null, tags = listOf(" Praca ", "PRACA"))
        val expense = item("expense", -500, title = "Zakupy")
        composeRule.setContent {
            ThesaurusTheme {
                EntryListScreen(
                    state = EntryListUiState(isLoading = false, entries = listOf(income, expense)),
                    onChangeSort = {}, onLoadNextPage = {}, onRetry = {}, onOpenSettings = {}, onAddEntry = {},
                )
            }
        }

        composeRule.onAllNodesWithText("Jedzenie › Sklep").assertCountEquals(2)
        composeRule.onNodeWithText("#praca").assertIsDisplayed()
        composeRule.onNodeWithText("Zakupy").assertIsDisplayed()
        composeRule.onNodeWithTag("entry-income-income").assertIsDisplayed()
        composeRule.onNodeWithTag("entry-expense-expense").assertIsDisplayed()
        composeRule.onNodeWithText("null").assertDoesNotExist()
    }

    @Test
    fun loadingEmptyAndErrorStatesRemainActionable() {
        var state by mutableStateOf(EntryListUiState(isLoading = true))
        composeRule.setContent {
            ThesaurusTheme {
                EntryListScreen(
                    state = state,
                    onChangeSort = {}, onLoadNextPage = {}, onRetry = {}, onOpenSettings = {}, onAddEntry = {},
                )
            }
        }
        composeRule.onNodeWithTag("entries-loading").assertIsDisplayed()

        composeRule.runOnIdle {
            state = EntryListUiState(isLoading = false, syncState = SyncState.PENDING)
        }
        composeRule.onNodeWithText("Nie ma jeszcze żadnych wpisów.").assertIsDisplayed()
        composeRule.onNodeWithText("Zmiany oczekują na synchronizację.").assertIsDisplayed()

        composeRule.runOnIdle {
            state = EntryListUiState(isLoading = false, error = EntryListError.LoadFailed)
        }
        composeRule.onNodeWithTag("entries-retry").performClick()
    }

    private fun item(id: String, amount: Long, title: String? = null, tags: List<String> = emptyList()) = EntryListItem(
        entry = LedgerEntry(
            id = id, householdId = "home", amountGrosze = amount, date = LocalDate.of(2026, 9, 16), title = title,
            categoryId = "food", subcategoryId = "shop", tags = tags, authorId = "anna", updatedById = "anna",
        ),
        categoryName = "Jedzenie", subcategoryName = "Sklep", authorName = "Anna",
    )
}
