package pl.bargor.thesaurus.ui.summary

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.width
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertContentDescriptionEquals
import androidx.compose.ui.test.assertHasClickAction
import androidx.compose.ui.test.assert
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.Role
import androidx.lifecycle.ViewModelStore
import androidx.lifecycle.SavedStateHandle
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.assertTextContains
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.getUnclippedBoundsInRoot
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import java.math.BigInteger
import java.time.LocalDate
import java.time.Year
import java.time.YearMonth
import java.time.Clock
import java.time.Instant
import java.time.ZoneOffset
import kotlinx.coroutines.flow.flowOf
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import androidx.test.platform.app.InstrumentationRegistry
import pl.bargor.thesaurus.ThesaurusTheme
import pl.bargor.thesaurus.data.model.SummaryTotals
import pl.bargor.thesaurus.data.model.SyncState
import pl.bargor.thesaurus.data.model.Category
import pl.bargor.thesaurus.data.model.LedgerEntry
import pl.bargor.thesaurus.data.model.Subcategory
import pl.bargor.thesaurus.data.model.SyncObservation
import pl.bargor.thesaurus.data.firebase.LedgerRepository
import pl.bargor.thesaurus.data.firebase.TaxonomyRepository

class SummaryScreenTest {
    @get:Rule val composeRule = createComposeRule()

    @Test fun headingToggleRecalculatesRealViewModelTotalsFilteredTotalsAndList() {
        val january = LedgerEntry(
            id = "jan", householdId = "home", amountGrosze = 100, date = LocalDate.of(2026, 1, 1),
            categoryId = "food", tags = listOf("dom"), authorId = "anna", updatedById = "anna",
        )
        val entries = listOf(
            january,
            january.copy(id = "feb", amountGrosze = 200, date = LocalDate.of(2026, 2, 1)),
            january.copy(id = "other", amountGrosze = 300, tags = emptyList()),
            january.copy(id = "previous", amountGrosze = 400, date = LocalDate.of(2025, 1, 1)),
        )
        val ledger = object : LedgerRepository {
            override fun observeEntries(householdId: String, includeDeleted: Boolean) = flowOf(SyncObservation(entries, SyncState.SYNCED))
            override suspend fun save(entry: LedgerEntry) = Unit
            override suspend fun tombstone(householdId: String, entryId: String, actorId: String) = Unit
        }
        val taxonomy = object : TaxonomyRepository {
            override fun observeCategories(householdId: String) = flowOf(SyncObservation<List<Category>>(emptyList(), SyncState.SYNCED))
            override fun observeSubcategories(householdId: String, categoryId: String) = flowOf(SyncObservation<List<Subcategory>>(emptyList(), SyncState.SYNCED))
            override suspend fun save(category: Category) = Unit
            override suspend fun save(subcategory: Subcategory) = Unit
        }
        lateinit var vm: SummaryViewModel
        composeRule.setContent {
            val model = androidx.compose.runtime.remember {
                SummaryViewModel(ledger, taxonomy, Clock.fixed(Instant.parse("2026-01-15T12:00:00Z"), ZoneOffset.UTC), SavedStateHandle())
                    .also { vm = it; it.selectTag("dom"); it.selectSort(SummaryEntrySort.AMOUNT); it.toggleSortDirection() }
            }
            val store = androidx.compose.runtime.remember { ViewModelStore().also { it.put("summary", model) } }
            DisposableEffect(store) { onDispose { store.clear() } }
            LaunchedEffect(Unit) { model.start("home") }
            val state by model.state.collectAsState()
            ThesaurusTheme {
                SummaryScreen(
                    state, model::selectPeriodMode,
                    { if (state.mode == SummaryPeriodMode.MONTH) model.previousMonth() else model.previousYear() },
                    { if (state.mode == SummaryPeriodMode.MONTH) model.nextMonth() else model.nextYear() }, {},
                )
            }
        }
        composeRule.onNodeWithTag("summary-income").assertTextContains("4,00", substring = true)
        composeRule.onNodeWithTag("summary-filtered-income").performScrollTo().assertTextContains("1,00", substring = true)
        composeRule.onNodeWithTag("summary-entry-jan").performScrollTo().assertIsDisplayed()
        composeRule.onNodeWithTag("summary-entry-feb").assertDoesNotExist()
        composeRule.onNodeWithTag("summary-period").performScrollTo().performClick()
        composeRule.onNodeWithTag("summary-income").assertTextContains("6,00", substring = true)
        composeRule.onNodeWithTag("summary-filtered-income").performScrollTo().assertTextContains("3,00", substring = true)
        composeRule.onNodeWithTag("summary-entry-feb").performScrollTo().assertIsDisplayed()
        composeRule.runOnIdle {
            assertEquals("dom", vm.state.value.selectedTag)
            assertEquals(SummaryEntrySort.AMOUNT, vm.state.value.sort)
            assertEquals(SummarySortDirection.ASCENDING, vm.state.value.direction)
            assertEquals(listOf("jan", "feb"), vm.state.value.entries.map { it.entry.id })
        }
        composeRule.onNodeWithTag("summary-previous-period").performScrollTo().performClick()
        composeRule.onNodeWithTag("summary-filtered-income").performScrollTo().assertTextContains("4,00", substring = true)
        composeRule.onNodeWithTag("summary-entry-previous").performScrollTo().assertIsDisplayed()
        composeRule.onNodeWithTag("summary-entry-jan").assertDoesNotExist()
        composeRule.onNodeWithTag("summary-period").performScrollTo().performClick()
        composeRule.onNodeWithTag("summary-period").assertTextContains("Styczeń 2025")
        composeRule.onNodeWithTag("summary-filtered-income").performScrollTo().assertTextContains("4,00", substring = true)
        composeRule.onNodeWithTag("summary-next-period").performScrollTo().performClick()
        composeRule.onNodeWithTag("summary-filtered-income").performScrollTo().assertTextContains("0,00", substring = true)
        composeRule.onNodeWithTag("summary-entry-previous").assertDoesNotExist()
    }

    @Test fun periodControlsStartAtTheTopAtLargeFontScale() {
        composeRule.setContent {
            val density = LocalDensity.current
            CompositionLocalProvider(LocalDensity provides Density(density.density, 1.8f)) {
                ThesaurusTheme {
                    Box(Modifier.width(320.dp).fillMaxHeight()) {
                        SummaryScreen(
                            state = SummaryUiState(month = YearMonth.of(2026, 9), year = Year.of(2026), isLoading = false),
                            onSelectPeriodMode = {}, onPreviousPeriod = {}, onNextPeriod = {}, onRetry = {},
                        )
                    }
                }
            }
        }

        composeRule.onNodeWithText("Podsumowanie").assertDoesNotExist()
        composeRule.onNodeWithTag("summary-mode-month").assertDoesNotExist()
        composeRule.onNodeWithTag("summary-mode-year").assertDoesNotExist()
        val period = composeRule.onNodeWithTag("summary-period").assertIsDisplayed().getUnclippedBoundsInRoot()
        assertTrue("Period heading must start without title-sized empty space", period.top <= 24.dp)
        assertTrue("Period heading must fit on a compact screen", period.left >= 0.dp && period.right <= 320.dp)
        assertTrue("Period heading must have a 48dp touch target", period.bottom - period.top >= 48.dp)
    }

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
        composeRule.onNodeWithTag("summary-offline").assertDoesNotExist()
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

    @Test fun headingAndPeriodControlsNavigateWithAccessibleScopeAndAction() {
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
        composeRule.onNodeWithTag("summary-mode-month").assertDoesNotExist()
        composeRule.onNodeWithTag("summary-mode-year").assertDoesNotExist()
        val heading = composeRule.onNodeWithTag("summary-period").assertHasClickAction()
        heading.assert(SemanticsMatcher.expectValue(SemanticsProperties.Role, Role.Button))
            .assert(SemanticsMatcher.expectValue(SemanticsProperties.StateDescription, "Podsumowanie miesięczne"))
        assertEquals("Pokaż podsumowanie roczne", heading.fetchSemanticsNode().config[SemanticsActions.OnClick].label)
        heading.performClick()
        heading.assert(SemanticsMatcher.expectValue(SemanticsProperties.StateDescription, "Podsumowanie roczne"))
        assertEquals("Pokaż podsumowanie miesięczne", heading.fetchSemanticsNode().config[SemanticsActions.OnClick].label)
        composeRule.onNodeWithTag("summary-period").assertTextContains("2026")
        composeRule.onNodeWithTag("summary-previous-period").performClick()
        composeRule.onNodeWithTag("summary-next-period").performClick()
        assertEquals(1, previous)
        assertEquals(1, next)
        heading.performClick()
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
        composeRule.onNodeWithTag("summary-sort-TAGS").assertDoesNotExist()
        composeRule.onNodeWithTag("summary-sort-AMOUNT").performClick()
        assertEquals(SummaryEntrySort.AMOUNT, sort)
        composeRule.onNodeWithTag("summary-sort-direction").performScrollTo().performClick()
        composeRule.onNodeWithTag("summary-clear-controls").performScrollTo().performClick()
        assertEquals(1, toggles)
        assertEquals(1, clears)
    }

    @Test fun categorySubcategoryAndTagFiltersAreCompactHorizontalRows() {
        composeRule.setContent {
            ThesaurusTheme {
                SummaryScreen(
                    state = SummaryUiState(month = YearMonth.of(2026, 9), year = Year.of(2026), isLoading = false),
                    onSelectPeriodMode = {}, onPreviousPeriod = {}, onNextPeriod = {}, onRetry = {},
                )
            }
        }
        val density = InstrumentationRegistry.getInstrumentation().targetContext.resources.displayMetrics.density
        listOf(
            Triple("summary-filter-category", "Kategoria", "Kategoria: Wszystkie kategorie"),
            Triple("summary-filter-subcategory", "Podkategoria", "Podkategoria: Wszystkie podkategorie"),
            Triple("summary-filter-tag", "Tag", "Tag: Wszystkie tagi"),
        ).forEach { (tag, _, description) ->
            val row = composeRule.onNodeWithTag("$tag-row").performScrollTo()
            val label = composeRule.onNodeWithTag("$tag-label")
            val button = composeRule.onNodeWithTag(tag).assertContentDescriptionEquals(description)
            val rowBounds = row.fetchSemanticsNode().boundsInRoot
            val labelBounds = label.fetchSemanticsNode().boundsInRoot
            val buttonBounds = button.fetchSemanticsNode().boundsInRoot
            assertTrue("$tag label must be left of its dropdown", labelBounds.right < buttonBounds.left)
            assertTrue("$tag label and dropdown must share a row",
                labelBounds.top < buttonBounds.bottom && buttonBounds.top < labelBounds.bottom)
            assertTrue("$tag row must be compact: $rowBounds", rowBounds.height <= 56f * density + 1f)
        }
        composeRule.onNodeWithTag("summary-filter-subcategory").assertIsNotEnabled()
    }

    @Test fun filteredResultShowsSignedTotalsAndDisappearsAfterReset() {
        var state by mutableStateOf(SummaryUiState(
            month = YearMonth.of(2026, 9), year = Year.of(2026), isLoading = false,
            totals = SummaryTotals(BigInteger.valueOf(30_000), BigInteger.valueOf(10_000), 3),
            filteredTotals = SummaryTotals(BigInteger.valueOf(10_000), BigInteger.valueOf(5_000), 2),
            selectedTag = "dom", tags = listOf("dom"),
        ))
        composeRule.setContent {
            ThesaurusTheme {
                SummaryScreen(
                    state = state,
                    onSelectPeriodMode = { state = state.copy(mode = it) },
                    onPreviousPeriod = {}, onNextPeriod = {}, onRetry = {},
                    onToggleSortDirection = { state = state.copy(direction = SummarySortDirection.ASCENDING) },
                    onClearControls = { state = state.copy(
                        selectedCategoryId = null, selectedSubcategoryId = null, selectedTag = null,
                        sort = SummaryEntrySort.DATE, direction = SummarySortDirection.DESCENDING,
                    ) },
                )
            }
        }
        composeRule.onNodeWithTag("summary-income").assertTextContains("300,00", substring = true)
        composeRule.onNodeWithTag("summary-expense").assertTextContains("100,00", substring = true)
        composeRule.onNodeWithTag("summary-net").assertTextContains("200,00", substring = true)
        composeRule.onNodeWithTag("summary-filtered-heading").performScrollTo().assertIsDisplayed()
        composeRule.onNodeWithTag("summary-filtered-income").performScrollTo()
            .assertContentDescriptionEquals("Wpływy: 100,00 zł")
        composeRule.onNodeWithTag("summary-filtered-expense").performScrollTo()
            .assertContentDescriptionEquals("Wydatki: 50,00 zł")
        composeRule.onNodeWithTag("summary-filtered-net").performScrollTo()
            .assertContentDescriptionEquals("Bilans: +50,00 zł")

        composeRule.runOnIdle { state = state.copy(filteredTotals = SummaryTotals()) }
        composeRule.onNodeWithTag("summary-filtered-income").performScrollTo()
            .assertContentDescriptionEquals("Wpływy: 0,00 zł")
        composeRule.onNodeWithTag("summary-filtered-expense").performScrollTo()
            .assertContentDescriptionEquals("Wydatki: 0,00 zł")
        composeRule.onNodeWithTag("summary-filtered-net").performScrollTo()
            .assertContentDescriptionEquals("Bilans: 0,00 zł")

        composeRule.onNodeWithTag("summary-period").performScrollTo().performClick()
        composeRule.onNodeWithTag("summary-filtered-result").performScrollTo().assertExists()
        composeRule.onNodeWithTag("summary-sort-direction").performScrollTo().performClick()
        composeRule.onNodeWithTag("summary-filtered-net").performScrollTo()
            .assertContentDescriptionEquals("Bilans: 0,00 zł")
        composeRule.onNodeWithTag("summary-clear-controls").performScrollTo().performClick()
        composeRule.onNodeWithTag("summary-filtered-result").assertDoesNotExist()
        composeRule.onNodeWithTag("summary-income").assertTextContains("300,00", substring = true)
    }

    @Test fun sortAndResetIconsShareRowHaveAccessibleTargetsAndResetDefaults() {
        var state by mutableStateOf(SummaryUiState(
            month = YearMonth.of(2026, 9), year = Year.of(2026), isLoading = false,
            categories = listOf(Category("food", "home", "Żywność", authorId = "anna", updatedById = "anna")),
            tags = listOf("dom"), selectedCategoryId = "food", selectedTag = "dom",
            sort = SummaryEntrySort.AMOUNT, direction = SummarySortDirection.ASCENDING,
        ))
        composeRule.setContent {
            ThesaurusTheme {
                SummaryScreen(
                    state = state,
                    onSelectPeriodMode = {}, onPreviousPeriod = {}, onNextPeriod = {}, onRetry = {},
                    onToggleSortDirection = { state = state.copy(direction = SummarySortDirection.DESCENDING) },
                    onClearControls = { state = state.copy(
                        selectedCategoryId = null, selectedSubcategoryId = null, selectedTag = null,
                        sort = SummaryEntrySort.DATE, direction = SummarySortDirection.DESCENDING,
                    ) },
                )
            }
        }

        val sort = composeRule.onNodeWithTag("summary-sort").performScrollTo()
        val direction = composeRule.onNodeWithTag("summary-sort-direction")
        val clear = composeRule.onNodeWithTag("summary-clear-controls").performScrollTo()
        sort.assertContentDescriptionEquals("Sortuj według: Kwota")
        direction.assertHasClickAction()
            .assertContentDescriptionEquals("Sortowanie rosnące. Zmień na malejące")
        clear.assertHasClickAction().assertContentDescriptionEquals("Wyczyść filtry i sortowanie")
        val sortBounds = sort.fetchSemanticsNode().boundsInRoot
        val directionBounds = direction.fetchSemanticsNode().boundsInRoot
        val clearBounds = clear.fetchSemanticsNode().boundsInRoot
        val density = InstrumentationRegistry.getInstrumentation().targetContext.resources.displayMetrics.density
        assertTrue(sortBounds.right <= directionBounds.left)
        assertTrue(directionBounds.right <= clearBounds.left)
        assertTrue(sortBounds.top < directionBounds.bottom && directionBounds.top < sortBounds.bottom)
        assertTrue(directionBounds.top < clearBounds.bottom && clearBounds.top < directionBounds.bottom)
        assertTrue(directionBounds.width >= 48f * density - 1f)
        assertTrue(directionBounds.height >= 48f * density - 1f)
        assertTrue(clearBounds.width >= 48f * density - 1f)
        assertTrue(clearBounds.height >= 48f * density - 1f)

        direction.performClick()
        direction.assertContentDescriptionEquals("Sortowanie malejące. Zmień na rosnące")
        clear.performClick()
        composeRule.onNodeWithTag("summary-filter-category")
            .assertContentDescriptionEquals("Kategoria: Wszystkie kategorie")
        composeRule.onNodeWithTag("summary-filter-tag")
            .assertContentDescriptionEquals("Tag: Wszystkie tagi")
        sort.assertContentDescriptionEquals("Sortuj według: Data księgowania")
        direction.assertContentDescriptionEquals("Sortowanie malejące. Zmień na rosnące")
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
        composeRule.onNodeWithTag("summary-period").performScrollTo().performClick()
        composeRule.onNodeWithTag("summary-income").assertTextContains("3,00", substring = true)
        composeRule.onNodeWithTag("summary-entry-february").performScrollTo().assertIsDisplayed()
        composeRule.onNodeWithTag("summary-entry-count").performScrollTo().assertTextContains("2", substring = true)
        composeRule.onNodeWithTag("summary-previous-period").performScrollTo().performClick()
        composeRule.onNodeWithTag("summary-period").assertTextContains("2025", substring = true)
        composeRule.onNodeWithTag("summary-entry-february").assertDoesNotExist()
        composeRule.onNodeWithTag("summary-empty").assertExists()
    }
}
