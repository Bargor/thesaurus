package pl.bargor.thesaurus.ui.entries

import androidx.compose.runtime.getValue
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toPixelMap
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.getUnclippedBoundsInRoot
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performSemanticsAction
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import java.time.LocalDate
import org.junit.Rule
import org.junit.Test
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
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
    fun rowUsesItsPersistedCategoryColor() {
        composeRule.setContent {
            ThesaurusTheme {
                EntryListScreen(
                    state = EntryListUiState(
                        isLoading = false,
                        entries = listOf(item("colored", -500, categoryColor = "rose")),
                    ),
                    onChangeSort = {}, onLoadNextPage = {}, onRetry = {}, onOpenSettings = {}, onAddEntry = {},
                )
            }
        }

        val image = composeRule.onNodeWithTag("entry-category-color-colored").captureToImage()
        assertEquals(Color(0xFFF43F5E), image.toPixelMap()[image.width / 2, image.height / 2])
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

    @Test
    fun regularTapEditsWithoutOpeningActionsAndRowsStayCompact() {
        val manageable = item("managed", -500, title = "Zakupy").copy(canManage = true)
        var editedId: String? = null
        composeRule.setContent {
            ThesaurusTheme {
                EntryListScreen(
                    state = EntryListUiState(isLoading = false, entries = listOf(manageable)),
                    onChangeSort = {}, onLoadNextPage = {}, onRetry = {}, onOpenSettings = {}, onAddEntry = {},
                    onEditEntry = { editedId = it },
                )
            }
        }

        composeRule.onNodeWithTag("entry-managed").assertIsDisplayed()
        val cardBounds = composeRule.onNodeWithTag("entry-managed").getUnclippedBoundsInRoot()
        assertTrue(cardBounds.bottom - cardBounds.top < 120.dp)
        composeRule.onNodeWithTag("entry-managed").performClick()
        assertEquals("managed", editedId)
        composeRule.onNodeWithText("Działania dla wpisu").assertDoesNotExist()
        composeRule.onNodeWithText("Edytuj wpis").assertDoesNotExist()
        composeRule.onNodeWithText("Usuń wpis").assertDoesNotExist()
    }

    @Test
    fun longPressTargetsSpecificEntryAndDismissOrEditWorks() {
        val first = item("first", -500, title = "Zakupy").copy(canManage = true)
        val second = item("second", -700, title = "Książki").copy(canManage = true)
        var editedId: String? = null
        composeRule.setContent {
            ThesaurusTheme {
                EntryListScreen(
                    state = EntryListUiState(isLoading = false, entries = listOf(first, second)),
                    onChangeSort = {}, onLoadNextPage = {}, onRetry = {}, onOpenSettings = {}, onAddEntry = {},
                    onEditEntry = { editedId = it },
                )
            }
        }

        composeRule.onNodeWithTag("entry-second").performSemanticsAction(SemanticsActions.OnLongClick)
        composeRule.onNodeWithText("Działania dla wpisu").assertIsDisplayed()
        composeRule.onNodeWithTag("entry-action-dismiss").assertIsDisplayed().performClick()
        composeRule.onNodeWithText("Działania dla wpisu").assertDoesNotExist()
        assertEquals(null, editedId)

        composeRule.onNodeWithTag("entry-second").performSemanticsAction(SemanticsActions.OnLongClick)
        composeRule.onNodeWithTag("entry-action-edit").assertIsDisplayed().performClick()
        assertEquals("second", editedId)
        composeRule.onNodeWithText("Działania dla wpisu").assertDoesNotExist()
    }

    @Test
    fun longPressRemovalConfirmsExactEntryAndSnackbarOffersUndo() {
        val first = item("first", -300, title = "Bilet").copy(canManage = true)
        val manageable = item("managed", -500, title = "Zakupy").copy(canManage = true)
        var state by mutableStateOf(EntryListUiState(isLoading = false, entries = listOf(first, manageable)))
        var confirmedId: String? = null
        var undoCalled = false
        composeRule.setContent {
            ThesaurusTheme {
                EntryListScreen(
                    state = state,
                    onChangeSort = {},
                    onLoadNextPage = {},
                    onRetry = {},
                    onOpenSettings = {},
                    onAddEntry = {},
                    onConfirmDelete = { item ->
                        confirmedId = item.entry.id
                        state = state.copy(entries = emptyList(), pendingDeletion = item)
                    },
                    onUndoDelete = { undoCalled = true },
                )
            }
        }

        composeRule.onNodeWithTag("entry-managed").performSemanticsAction(SemanticsActions.OnLongClick)
        composeRule.onNodeWithTag("entry-action-delete").performClick()
        composeRule.onNodeWithText("Usunąć wpis?").assertIsDisplayed()
        assertEquals(null, confirmedId)
        composeRule.onNodeWithText("Usuń").performClick()
        assertEquals("managed", confirmedId)
        composeRule.onNodeWithText("Cofnij").assertIsDisplayed().performClick()
        assertTrue(undoCalled)
    }

    @Test
    fun longContentAndLargeFontRemainReadableWithActionsAvailable() {
        val title = "Bardzo długi opis zakupu, który powinien zawijać się na wiele wierszy bez obcinania zawartości karty"
        val manageable = item("long", -500, title = title, tags = listOf("bardzo-długi-tag", "kolejny-tag"))
            .copy(canManage = true)
        composeRule.setContent {
            val density = LocalDensity.current
            CompositionLocalProvider(LocalDensity provides Density(density.density, 1.8f)) {
                ThesaurusTheme {
                    EntryListScreen(
                        state = EntryListUiState(isLoading = false, entries = listOf(manageable)),
                        onChangeSort = {}, onLoadNextPage = {}, onRetry = {}, onOpenSettings = {}, onAddEntry = {},
                    )
                }
            }
        }

        val card = composeRule.onNodeWithTag("entry-long").fetchSemanticsNode().boundsInRoot
        val titleBounds = composeRule.onNodeWithText(title, useUnmergedTree = true).fetchSemanticsNode().boundsInRoot
        assertTrue(titleBounds.right <= card.right)
        assertTrue(titleBounds.bottom <= card.bottom)
        composeRule.onNodeWithTag("entry-long").performSemanticsAction(SemanticsActions.OnLongClick)
        composeRule.onNodeWithTag("entry-action-edit").assertIsDisplayed()
        composeRule.onNodeWithTag("entry-action-delete").assertIsDisplayed()
        composeRule.onNodeWithTag("entry-action-dismiss").assertIsDisplayed()
    }

    private fun item(
        id: String,
        amount: Long,
        title: String? = null,
        tags: List<String> = emptyList(),
        categoryColor: String? = null,
    ) = EntryListItem(
        entry = LedgerEntry(
            id = id, householdId = "home", amountGrosze = amount, date = LocalDate.of(2026, 9, 16), title = title,
            categoryId = "food", subcategoryId = "shop", tags = tags, authorId = "anna", updatedById = "anna",
        ),
        categoryName = "Jedzenie", subcategoryName = "Sklep", authorName = "Anna", categoryColor = categoryColor,
    )
}
