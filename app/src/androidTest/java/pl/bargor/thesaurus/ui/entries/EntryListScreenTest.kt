package pl.bargor.thesaurus.ui.entries

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.width
import androidx.compose.ui.test.assertHeightIsAtLeast
import androidx.compose.runtime.getValue
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
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
import androidx.compose.ui.test.performScrollToNode
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.hasAnyAncestor
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
    fun dateHeadingsFollowVisibleRunsAndRecomputeAfterListChanges() {
        val a = item("a", -100).copy(authorName = "creator@example.test")
        val b = item("b", -200).let { it.copy(entry = it.entry.copy(date = LocalDate.of(2026, 9, 15))) }
        val c = item("c", -300)
        var state by mutableStateOf(EntryListUiState(isLoading = false, entries = listOf(a, b, c), sort = EntryListSort.CREATION_ORDER))
        composeRule.setContent {
            ThesaurusTheme {
                EntryListScreen(
                    state = state, onChangeSort = {}, onLoadNextPage = {}, onRetry = {},
                    onOpenSettings = {}, onAddEntry = {},
                )
            }
        }
        composeRule.onAllNodesWithText("16 września 2026").assertCountEquals(2)
        composeRule.onNodeWithTag("date-a").assertIsDisplayed()
        composeRule.onNodeWithTag("date-b").assertIsDisplayed()
        composeRule.onNodeWithTag("date-c").assertIsDisplayed()
        composeRule.onNodeWithText("creator@example.test").assertDoesNotExist()
        composeRule.onNodeWithText("Anna").assertDoesNotExist()
        composeRule.onNode(hasText("16 września 2026") and hasAnyAncestor(hasTestTag("entry-a")), useUnmergedTree = true)
            .assertDoesNotExist()

        // Filtering/deleting the middle run makes equal dates adjacent.
        composeRule.runOnIdle { state = state.copy(entries = listOf(a, c)) }
        composeRule.onAllNodesWithText("16 września 2026").assertCountEquals(1)
        composeRule.onNodeWithTag("date-c").assertDoesNotExist()
        // Refreshing a subset gives its first entry a heading.
        composeRule.runOnIdle { state = state.copy(entries = listOf(c)) }
        composeRule.onNodeWithTag("date-c").assertIsDisplayed()
        composeRule.onNodeWithTag("date-a").assertDoesNotExist()
        composeRule.runOnIdle { state = state.copy(entries = listOf(b, c)) }
        composeRule.onNodeWithTag("date-b").assertIsDisplayed()
        // Editing the accounting date coalesces both runs.
        composeRule.runOnIdle { state = state.copy(entries = listOf(b.copy(entry = b.entry.copy(date = c.entry.date)), c)) }
        composeRule.onAllNodesWithText("16 września 2026").assertCountEquals(1)
        composeRule.onNodeWithTag("date-c").assertDoesNotExist()
    }

    @Test
    fun addEntryStaysFixedWhenBrowsingAndLoadingAnotherPage() {
        val entries = (1..25).map { item("scroll-$it", -100, title = "Zakup $it") }
        var state by mutableStateOf(EntryListUiState(isLoading = false, entries = entries))
        var added = 0
        composeRule.setContent {
            ThesaurusTheme {
                EntryListScreen(
                    state = state, onChangeSort = {},
                    onLoadNextPage = { state = state.copy(visibleCount = entries.size) },
                    onRetry = {}, onOpenSettings = {}, onAddEntry = { added++ },
                )
            }
        }
        val before = composeRule.onNodeWithTag("add-entry").assertIsDisplayed().getUnclippedBoundsInRoot()
        assertTrue(before.bottom - before.top >= 48.dp)
        composeRule.onNodeWithTag("entries-list").performScrollToNode(hasTestTag("entries-load-more"))
        composeRule.onNodeWithTag("add-entry").assertIsDisplayed().performClick()
        assertEquals(before, composeRule.onNodeWithTag("add-entry").getUnclippedBoundsInRoot())
        composeRule.onNodeWithTag("entries-load-more").performClick()
        composeRule.onNodeWithTag("entries-list").performScrollToNode(hasTestTag("entry-scroll-25"))
        composeRule.onNodeWithTag("add-entry").assertIsDisplayed().performClick()
        assertEquals(2, added)
        assertEquals(before, composeRule.onNodeWithTag("add-entry").getUnclippedBoundsInRoot())
        composeRule.onNodeWithTag("entries-list").performScrollToNode(hasTestTag("date-scroll-1"))
        composeRule.onAllNodesWithText("16 września 2026").assertCountEquals(1)
    }

    @Test
    fun fiveRepresentativeEntriesFitAboveThePersistentAction() {
        val entries = (1..5).map { index ->
            item(
                "dense-$index", -13972,
                title = if (index == 2) null else "Zakupy spożywcze",
                tags = listOf("codzienne"),
            ).let { it.copy(entry = it.entry.copy(date = LocalDate.of(2026, 9, 30 - (index - 1) / 2))) }
        }
        composeRule.setContent {
            ThesaurusTheme {
                // Representative route height above app navigation and the DEV account action.
                Box(Modifier.width(411.dp).height(700.dp)) {
                    EntryListScreen(
                        state = EntryListUiState(isLoading = false, entries = entries, syncState = SyncState.OFFLINE),
                        onChangeSort = {}, onLoadNextPage = {}, onRetry = {}, onOpenSettings = {}, onAddEntry = {},
                    )
                }
            }
        }
        val list = composeRule.onNodeWithTag("entries-list").getUnclippedBoundsInRoot()
        entries.forEach { item ->
            val card = composeRule.onNodeWithTag("entry-${item.entry.id}").assertIsDisplayed().getUnclippedBoundsInRoot()
            assertTrue("Every representative card fits completely: ${item.entry.id} card=$card list=$list", card.top >= list.top && card.bottom <= list.bottom)
        }
        composeRule.onNodeWithTag("add-entry").assertIsDisplayed().assertHeightIsAtLeast(48.dp)
    }

    @Test
    fun actionsAndSortUseTheTopOfTheScreenAtLargeFontScale() {
        composeRule.setContent {
            val density = LocalDensity.current
            CompositionLocalProvider(LocalDensity provides Density(density.density, 1.8f)) {
                ThesaurusTheme {
                    Box(Modifier.width(320.dp).fillMaxHeight()) {
                        EntryListScreen(
                            state = EntryListUiState(isLoading = false),
                            onChangeSort = {}, onLoadNextPage = {}, onRetry = {},
                            onOpenSettings = {}, onAddEntry = {},
                        )
                    }
                }
            }
        }

        composeRule.onNodeWithText("Wpisy").assertDoesNotExist()
        val settings = composeRule.onNodeWithTag("open-taxonomy-settings").assertIsDisplayed().getUnclippedBoundsInRoot()
        val family = composeRule.onNodeWithTag("open-family").assertIsDisplayed().getUnclippedBoundsInRoot()
        val sort = composeRule.onNodeWithTag("entries-sort-date").assertIsDisplayed().getUnclippedBoundsInRoot()
        val createdSort = composeRule.onNodeWithTag("entries-sort-created").assertIsDisplayed().getUnclippedBoundsInRoot()
        assertTrue("Actions should start at the top without an empty title row", settings.top <= 24.dp)
        assertTrue("Settings must fit on a compact screen", settings.left >= 0.dp && settings.right <= 320.dp)
        assertTrue("Family action must fit on a compact screen", family.left >= 0.dp && family.right <= 320.dp)
        assertTrue("Actions must not overlap", settings.bottom <= family.top || settings.right <= family.left)
        assertTrue("Sort controls must follow the actions", sort.top >= minOf(settings.bottom, family.bottom))
        assertTrue("Sort controls must fit on a compact screen", sort.right <= 320.dp && createdSort.right <= 320.dp)
    }

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
        val incomeBounds = composeRule.onNodeWithTag("entry-income-income").getUnclippedBoundsInRoot()
        assertTrue("Income receives usable layout space: $incomeBounds", incomeBounds.right > incomeBounds.left && incomeBounds.bottom > incomeBounds.top)
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
        var added = 0
        composeRule.setContent {
            ThesaurusTheme {
                EntryListScreen(
                    state = state,
                    onChangeSort = {}, onLoadNextPage = {}, onRetry = {}, onOpenSettings = {}, onAddEntry = { added++ },
                )
            }
        }
        composeRule.onNodeWithTag("entries-loading").assertIsDisplayed()
        composeRule.onNodeWithTag("add-entry").assertIsDisplayed().performClick()

        composeRule.runOnIdle {
            state = EntryListUiState(isLoading = false, syncState = SyncState.PENDING)
        }
        composeRule.onNodeWithText("Nie ma jeszcze żadnych wpisów.").assertIsDisplayed()
        composeRule.onNodeWithText("Zmiany oczekują na synchronizację.").assertIsDisplayed()
        composeRule.onNodeWithTag("add-entry").assertIsDisplayed().performClick()

        composeRule.runOnIdle {
            state = EntryListUiState(isLoading = false, error = EntryListError.LoadFailed)
        }
        composeRule.onNodeWithTag("entries-retry").performClick()
        composeRule.onNodeWithTag("add-entry").assertIsDisplayed().performClick()
        assertEquals(3, added)
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
        assertTrue("Typical title card should stay compact", cardBounds.bottom - cardBounds.top <= 80.dp)
        composeRule.onNodeWithTag("entry-managed").assertHeightIsAtLeast(48.dp)
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
        val undo = composeRule.onNodeWithText("Cofnij").assertIsDisplayed().getUnclippedBoundsInRoot()
        val add = composeRule.onNodeWithTag("add-entry").assertIsDisplayed().getUnclippedBoundsInRoot()
        assertTrue("Undo stays above the persistent add action", undo.bottom <= add.top)
        composeRule.onNodeWithText("Cofnij").performClick()
        assertTrue(undoCalled)
    }

    @Test
    fun longContentAndLargeFontRemainReadableWithActionsAvailable() {
        val title = "Bardzo długi opis zakupu, który powinien zawijać się na wiele wierszy bez obcinania zawartości karty"
        val taxonomy = "Bardzo długa nazwa kategorii › Bardzo długa nazwa podkategorii"
        val manageable = item("long", -987654321012, title = title, tags = listOf("bardzo-długi-tag", "kolejny-tag"))
            .copy(canManage = true, categoryName = "Bardzo długa nazwa kategorii", subcategoryName = "Bardzo długa nazwa podkategorii")
        composeRule.setContent {
            val density = LocalDensity.current
            CompositionLocalProvider(LocalDensity provides Density(density.density * 1.1f, 1.8f)) {
                ThesaurusTheme {
                    Box(Modifier.width(320.dp).fillMaxHeight()) {
                        EntryListScreen(
                            state = EntryListUiState(isLoading = false, entries = listOf(manageable)),
                            onChangeSort = {}, onLoadNextPage = {}, onRetry = {}, onOpenSettings = {}, onAddEntry = {},
                        )
                    }
                }
            }
        }

        composeRule.onNodeWithTag("add-entry").assertIsDisplayed().assertHeightIsAtLeast(48.dp)
        // The full card can exceed the viewport at accessibility sizes; compare its unclipped layout.
        val card = composeRule.onNodeWithTag("entry-long").getUnclippedBoundsInRoot()
        listOf(
            composeRule.onNodeWithText(title, useUnmergedTree = true),
            composeRule.onNodeWithText(taxonomy, useUnmergedTree = true),
            composeRule.onNodeWithText("#bardzo-długi-tag #kolejny-tag", useUnmergedTree = true),
            composeRule.onNodeWithTag("entry-expense-long", useUnmergedTree = true),
        ).forEach { node ->
            val bounds = node.getUnclippedBoundsInRoot()
            assertTrue("Every content field receives usable layout space", bounds.right > bounds.left && bounds.bottom > bounds.top)
            assertTrue("Card content fits its horizontal bounds", bounds.left >= card.left && bounds.right <= card.right)
            assertTrue("Card height accommodates the complete content", bounds.bottom <= card.bottom)
        }
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
