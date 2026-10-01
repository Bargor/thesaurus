package pl.bargor.thesaurus.ui.reports

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.width
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModelStore
import java.time.Clock
import java.time.Instant
import java.time.LocalDate
import java.time.Year
import java.time.YearMonth
import java.time.ZoneOffset
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flowOf
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import pl.bargor.thesaurus.ThesaurusTheme
import pl.bargor.thesaurus.data.firebase.LedgerRepository
import pl.bargor.thesaurus.data.firebase.TaxonomyRepository
import pl.bargor.thesaurus.data.model.*

class ReportsControlsTest {
    @get:Rule val compose = createComposeRule()
    @Test fun categoryAndSubcategorySelectionUpdateTotalsChartsAndEntriesTogetherAndClearRestoresAll() {
        lateinit var vm: ReportsViewModel
        val store = ViewModelStore()
        compose.setContent { ThesaurusTheme {
            val model = remember { ReportsViewModel(Ledger(), Taxonomy(), Clock.fixed(Instant.parse("2026-09-30T12:00:00Z"), ZoneOffset.UTC), SavedStateHandle()).also { vm = it; store.put("reports", it) } }
            DisposableEffect(model) { onDispose { store.clear() } }
            LaunchedEffect(model) { model.start("home") }
            val state by model.state.collectAsState()
            ReportsScreen(state, model::selectPeriodMode, model::previousPeriod, model::nextPeriod, model::selectType,
                model::updateCustomFrom, model::updateCustomTo, model::applyCustomPeriod, {}, model::retry,
                onSelectCategory = model::selectCategory, onSelectSubcategory = model::selectSubcategory,
                onSelectSort = model::selectSort, onToggleSortDirection = model::toggleSortDirection, onClearControls = model::clearControls)
        } }
        compose.onNodeWithTag("reports-filter-subcategory").assertIsNotEnabled()
        compose.onNodeWithTag("reports-net").performScrollTo().assertTextContains("-15,00", substring = true)
        choose("reports-filter-category", "food")
        compose.onNodeWithTag("reports-filter-subcategory").assertIsEnabled()
        compose.onNodeWithTag("reports-net").performScrollTo().assertTextContains("-12,00", substring = true)
        compose.onNodeWithTag("report-entry-car").assertDoesNotExist()
        compose.onNodeWithTag("reports-category-row-car", useUnmergedTree = true).assertDoesNotExist()
        choose("reports-filter-subcategory", "shop")
        compose.onNodeWithTag("reports-income").performScrollTo().assertTextContains("2,00", substring = true)
        compose.onNodeWithTag("reports-expense").assertTextContains("10,00", substring = true)
        compose.onNodeWithTag("reports-net").assertTextContains("-8,00", substring = true)
        compose.onNodeWithTag("reports-category-amount-food", useUnmergedTree = true).assertTextEquals("12,00 zł")
        compose.onNodeWithTag("reports-category-chart").performScrollTo().assertContentDescriptionEquals("Wykres pierścieniowy kategorii: Jedzenie: 12,00 zł (100%)")
        compose.onNodeWithTag("report-entry-cafe").assertDoesNotExist()
        compose.onNodeWithTag("report-entry-shop-expense").performScrollTo().assertIsDisplayed()
        compose.onNodeWithTag("report-entry-shop-income").performScrollTo().assertIsDisplayed()
        assertEquals(setOf("shop-income", "shop-expense"), vm.state.value.entries.map { it.entry.id }.toSet())
        compose.onNodeWithTag("reports-mode-year").performScrollTo().performClick()
        compose.onNodeWithTag("reports-trend-summary").performScrollTo().assertTextContains("wrz: -8,00", substring = true)
        compose.onNodeWithTag("reports-clear-controls").performScrollTo().performClick()
        compose.onNodeWithTag("reports-filter-category").assert(SemanticsMatcher.expectValue(SemanticsProperties.StateDescription, "Wszystkie kategorie"))
        compose.onNodeWithTag("reports-filter-subcategory").assertIsNotEnabled()
        compose.onNodeWithTag("reports-net").performScrollTo().assertTextContains("-15,00", substring = true)
        compose.onNodeWithTag("report-entry-car").performScrollTo().assertIsDisplayed()
        choose("reports-sort", "AMOUNT")
        assertEquals(listOf("shop-income", "car", "cafe", "shop-expense"), vm.state.value.entries.map { it.entry.id })
        compose.onNodeWithTag("reports-sort-direction").performScrollTo().performClick()
        assertEquals(listOf("shop-expense", "cafe", "car", "shop-income"), vm.state.value.entries.map { it.entry.id })
        compose.onNodeWithTag("reports-net").performScrollTo().assertTextContains("-15,00", substring = true)
    }

    @Test fun compactControlsOfferOnlyDateAndAmountAndDispatchDirectionAndClearIcons() {
        var state by mutableStateOf(fixture())
        var category: String? = "unselected"; var subcategory: String? = "unselected"
        var toggles = 0; var clears = 0
        compose.setContent { ThesaurusTheme {
            ReportsScreen(state, {}, {}, {}, {}, {}, {}, {}, {}, {},
                onSelectCategory = { category = it; state = state.copy(selectedCategoryId = it, subcategories = if (it == "food") listOf(sub("shop", "food", "Sklep")) else emptyList()) },
                onSelectSubcategory = { subcategory = it }, onSelectSort = { state = state.copy(sort = it) },
                onToggleSortDirection = { toggles++; state = state.copy(direction = ReportSortDirection.ASCENDING) }, onClearControls = { clears++ })
        } }
        compose.onNodeWithTag("reports-filter-tag").assertDoesNotExist()
        choose("reports-filter-category", "food"); assertEquals("food", category)
        choose("reports-filter-subcategory", "shop"); assertEquals("shop", subcategory)
        compose.onNodeWithTag("reports-sort").performScrollTo().performClick()
        compose.onNodeWithTag("reports-sort-option-DATE").assertIsDisplayed()
        compose.onNodeWithTag("reports-sort-option-AMOUNT").assertIsDisplayed()
        listOf("CATEGORY", "SUBCATEGORY", "TAGS").forEach { compose.onNodeWithTag("reports-sort-option-$it").assertDoesNotExist() }
        compose.onNodeWithTag("reports-sort-option-AMOUNT").performClick()
        compose.onNodeWithTag("reports-sort-direction").assertContentDescriptionEquals("Sortowanie malejące. Zmień na rosnące").performClick()
            .assertContentDescriptionEquals("Sortowanie rosnące. Zmień na malejące")
        compose.onNodeWithTag("reports-clear-controls").assertContentDescriptionEquals("Wyczyść filtry i sortowanie").performClick()
        assertEquals(1, toggles); assertEquals(1, clears)
        val sort = compose.onNodeWithTag("reports-sort").getUnclippedBoundsInRoot()
        val direction = compose.onNodeWithTag("reports-sort-direction").getUnclippedBoundsInRoot()
        val clear = compose.onNodeWithTag("reports-clear-controls").getUnclippedBoundsInRoot()
        assertTrue(sort.right <= direction.left && direction.right <= clear.left)
        assertTrue(sort.top < clear.bottom && clear.top < sort.bottom)
        listOf(direction, clear).forEach { assertTrue(it.right - it.left >= 48.dp && it.bottom - it.top >= 48.dp) }
    }

    @Test fun selectedEmptyScopesStaySelectedRemoveOldChartAndEntriesAndClearRestoresAll() {
        val store = ViewModelStore()
        compose.setContent { ThesaurusTheme {
            val model = remember { ReportsViewModel(Ledger(), Taxonomy(includeEmpty = true), Clock.fixed(Instant.parse("2026-09-30T12:00:00Z"), ZoneOffset.UTC), SavedStateHandle()).also { store.put("reports", it) } }
            DisposableEffect(model) { onDispose { store.clear() } }
            LaunchedEffect(model) { model.start("home") }
            val state by model.state.collectAsState()
            ReportsScreen(state, model::selectPeriodMode, model::previousPeriod, model::nextPeriod, model::selectType,
                model::updateCustomFrom, model::updateCustomTo, model::applyCustomPeriod, {}, model::retry,
                onSelectCategory = model::selectCategory, onSelectSubcategory = model::selectSubcategory,
                onSelectSort = model::selectSort, onToggleSortDirection = model::toggleSortDirection, onClearControls = model::clearControls)
        } }
        compose.onNodeWithTag("reports-net").performScrollTo().assertTextContains("-15,00", substring = true)
        choose("reports-filter-category", "food")
        choose("reports-filter-subcategory", "empty-shop")
        assertEmptyScope("Jedzenie", "Pusty sklep")
        choose("reports-filter-category", "empty")
        compose.onNodeWithTag("reports-filter-subcategory").assert(SemanticsMatcher.expectValue(SemanticsProperties.StateDescription, "Wszystkie podkategorie"))
        choose("reports-filter-subcategory", "empty-branch")
        assertEmptyScope("Pusta kategoria", "Pusta podkategoria")
        compose.onNodeWithTag("reports-clear-controls").performScrollTo().performClick()
        compose.onNodeWithTag("reports-filter-category").assert(SemanticsMatcher.expectValue(SemanticsProperties.StateDescription, "Wszystkie kategorie"))
        compose.onNodeWithTag("reports-filter-subcategory").assertIsNotEnabled()
        compose.onNodeWithTag("reports-empty").assertDoesNotExist()
        compose.onNodeWithTag("reports-net").performScrollTo().assertTextContains("-15,00", substring = true)
        compose.onNodeWithTag("reports-category-chart").performScrollTo().assertIsDisplayed()
        compose.onNodeWithTag("report-entry-car").performScrollTo().assertIsDisplayed()
    }

    private fun assertEmptyScope(category: String, subcategory: String) {
        compose.onNodeWithTag("reports-filter-category").assert(SemanticsMatcher.expectValue(SemanticsProperties.StateDescription, category))
        compose.onNodeWithTag("reports-filter-subcategory").assert(SemanticsMatcher.expectValue(SemanticsProperties.StateDescription, subcategory))
        compose.onNodeWithTag("reports-empty").performScrollTo().assertIsDisplayed()
        listOf("reports-income", "reports-expense", "reports-net").forEach { compose.onNodeWithTag(it).assertTextContains("0,00", substring = true) }
        listOf("reports-category-chart", "reports-trend-chart", "report-entry-car", "report-entry-cafe", "report-entry-shop-income", "report-entry-shop-expense").forEach { compose.onNodeWithTag(it).assertDoesNotExist() }
        compose.onNodeWithTag("reports-category-row-food", useUnmergedTree = true).assertDoesNotExist()
        compose.onNodeWithTag("reports-category-row-car", useUnmergedTree = true).assertDoesNotExist()
    }

    @Test fun narrowLargeFontSelectorsKeepLabelsBesideControlsAndExposeFullSelectedName() {
        val name = "Zakupy spożywcze dla całej rodziny na tydzień"
        compose.setContent {
            val density = LocalDensity.current
            CompositionLocalProvider(LocalDensity provides Density(density.density, 1.8f)) { ThesaurusTheme {
                Box(Modifier.width(320.dp).fillMaxHeight()) { ReportsScreen(fixture().copy(categories = listOf(category("food", name)), selectedCategoryId = "food"), {}, {}, {}, {}, {}, {}, {}, {}, {}) }
            } }
        }
        val category = compose.onNodeWithTag("reports-filter-category").performScrollTo()
        category.assert(SemanticsMatcher.expectValue(SemanticsProperties.StateDescription, name))
        val label = compose.onNodeWithText("Kategoria").getUnclippedBoundsInRoot()
        val button = category.getUnclippedBoundsInRoot()
        assertTrue(label.right <= button.left && label.top < button.bottom && button.top < label.bottom)
        assertTrue(button.bottom - button.top >= 48.dp && button.left >= 0.dp && button.right <= 320.dp)
        listOf("reports-sort", "reports-sort-direction", "reports-clear-controls").forEach { tag ->
            val bounds = compose.onNodeWithTag(tag).performScrollTo().assertIsDisplayed().assertHasClickAction().getUnclippedBoundsInRoot()
            assertTrue(bounds.right - bounds.left >= 48.dp && bounds.bottom - bounds.top >= 48.dp)
            assertTrue(bounds.left >= 0.dp && bounds.right <= 320.dp)
        }
    }

    private fun choose(tag: String, id: String) {
        compose.onNodeWithTag(tag).performScrollTo().performClick()
        compose.onNodeWithTag("$tag-option-$id").performClick()
    }
    private fun fixture() = ReportsUiState(LocalDate.of(2026, 9, 30), YearMonth.of(2026, 9), Year.of(2026), isLoading = false, categories = listOf(category("food", "Jedzenie"), category("car", "Samochód")))
    private fun category(id: String, name: String) = Category(id, "home", name, authorId = "actor", updatedById = "actor")
    private fun sub(id: String, parent: String, name: String) = Subcategory(id, "home", parent, name, authorId = "actor", updatedById = "actor")
    private class Ledger : LedgerRepository {
        override fun observeEntries(householdId: String, includeDeleted: Boolean): Flow<SyncObservation<List<LedgerEntry>>> = flowOf(SyncObservation(listOf(
            item("shop-income", 200, "shop"), item("shop-expense", -1000, "shop"), item("cafe", -400, "cafe"), item("car", -300, "fuel", "car")), SyncState.SYNCED))
        private fun item(id: String, amount: Long, sub: String, category: String = "food") = LedgerEntry(id, "home", amount, LocalDate.of(2026, 9, 12), categoryId = category, subcategoryId = sub, authorId = "actor", updatedById = "actor")
        override suspend fun save(entry: LedgerEntry) = Unit
        override suspend fun tombstone(householdId: String, entryId: String, actorId: String) = Unit
    }
    private inner class Taxonomy(private val includeEmpty: Boolean = false) : TaxonomyRepository {
        override fun observeCategories(householdId: String): Flow<SyncObservation<List<Category>>> = flowOf(SyncObservation(listOf(category("food", "Jedzenie"), category("car", "Samochód")) + if (includeEmpty) listOf(category("empty", "Pusta kategoria")) else emptyList(), SyncState.SYNCED))
        override fun observeSubcategories(householdId: String, categoryId: String): Flow<SyncObservation<List<Subcategory>>> = flowOf(SyncObservation(when (categoryId) {
            "food" -> listOf(sub("shop", "food", "Sklep"), sub("cafe", "food", "Kawiarnia")) + if (includeEmpty) listOf(sub("empty-shop", "food", "Pusty sklep")) else emptyList()
            "car" -> listOf(sub("fuel", "car", "Paliwo"))
            else -> if (includeEmpty) listOf(sub("empty-branch", "empty", "Pusta podkategoria")) else emptyList()
        }, SyncState.SYNCED))
        override suspend fun save(category: Category) = Unit
        override suspend fun save(subcategory: Subcategory) = Unit
    }
}
