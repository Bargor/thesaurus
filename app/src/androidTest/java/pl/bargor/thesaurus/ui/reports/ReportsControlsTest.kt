package pl.bargor.thesaurus.ui.reports

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.width
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModelStore
import java.time.Clock
import java.time.Instant
import java.time.LocalDate
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
    private lateinit var vm: ReportsViewModel

    private fun render(includeEmpty: Boolean = false, largeFont: Boolean = false) {
        val store = ViewModelStore()
        compose.setContent {
            val density = LocalDensity.current
            CompositionLocalProvider(LocalDensity provides Density(density.density, if (largeFont) 1.8f else density.fontScale)) {
                ThesaurusTheme {
                    val model = remember { ReportsViewModel(Ledger(), Taxonomy(includeEmpty), Clock.fixed(Instant.parse("2026-09-30T12:00:00Z"), ZoneOffset.UTC), SavedStateHandle(), ReportHouseholds()).also { vm = it; store.put("reports", it) } }
                    DisposableEffect(model) { onDispose { store.clear() } }
                    LaunchedEffect(model) { model.start("home") }
                    val state by model.state.collectAsState()
                    Box(Modifier.width(320.dp).fillMaxHeight()) {
                        ReportsScreen(state, model::selectPeriodMode, model::previousPeriod, model::nextPeriod, model::selectType,
                            model::updateCustomFrom, model::updateCustomTo, model::applyCustomPeriod, {}, model::retry,
                            onSelectCategory = model::selectCategory, onSelectSubcategory = model::selectSubcategory,
                            onSelectSort = model::selectSort, onToggleSortDirection = model::toggleSortDirection,
                            onOpenFilters = model::openFilters, onDismissFilters = model::dismissFilters, onApplyFilters = model::applyFilters,
                            onResetFilters = model::resetFilters, onSelectMembers = model::selectMembers)
                    }
                }
            }
        }
    }
    private fun open() { compose.onNodeWithTag("reports-open-filters").performClick() }
    private fun apply() { compose.onNodeWithTag("reports-apply-filters").performClick() }
    private fun choose(tag: String, id: String) {
        compose.onNodeWithTag(tag).performScrollTo().performClick()
        compose.onNodeWithTag("$tag-option-$id").performClick()
    }
    private fun net(amount: String) {
        compose.onNodeWithTag("reports-net", useUnmergedTree = true).performScrollTo().assertTextContains(amount, substring = true)
    }

    @Test fun categoryAndSubcategoryDraftApplyUpdatesTotalsChartsEntriesAndResetRestoresAll() {
        render()
        net("-15,00")
        compose.onNodeWithTag("reports-filter-category").assertDoesNotExist()
        open()
        compose.onNodeWithTag("reports-filter-subcategory").performScrollTo().assertIsNotEnabled()
        choose("reports-filter-category", "food")
        compose.onNodeWithTag("reports-filter-subcategory").performScrollTo().assertIsEnabled()
        choose("reports-filter-subcategory", "shop")
        assertEquals(4, vm.state.value.entries.size)
        assertEquals(null, vm.state.value.selectedCategoryId)
        apply()
        net("-8,00")
        compose.onNodeWithTag("reports-income", useUnmergedTree = true).assertTextContains("2,00", substring = true)
        compose.onNodeWithTag("reports-expense", useUnmergedTree = true).assertTextContains("10,00", substring = true)
        compose.onNodeWithTag("reports-category-amount-food", useUnmergedTree = true).assertTextEquals("12,00 zł")
        compose.onNodeWithTag("reports-category-chart").performScrollTo().assertContentDescriptionEquals("Wykres pierścieniowy kategorii: Jedzenie: 12,00 zł (100%)")
        compose.onNodeWithTag("report-entry-car").assertDoesNotExist()
        compose.onNodeWithTag("report-entry-cafe").assertDoesNotExist()
        compose.onNodeWithTag("report-entry-shop-expense").performScrollTo().assertIsDisplayed()
        compose.onNodeWithTag("report-entry-shop-income").performScrollTo().assertIsDisplayed()
        assertEquals(setOf("shop-income", "shop-expense"), vm.state.value.entries.map { it.entry.id }.toSet())
        open(); choose("reports-period-selector", "YEAR"); apply()
        compose.onNodeWithTag("reports-trend-chart").performScrollTo().assert(SemanticsMatcher("monthly trend retains exact scoped September amount") {
            it.config.getOrNull(SemanticsProperties.ContentDescription).orEmpty().any { description ->
                "wrz" in description && "2026" in description && "-8,00" in description
            }
        })
        compose.onNodeWithTag("reports-trend-y-axis", useUnmergedTree = true).assertExists()
        compose.onNodeWithTag("reports-trend-x-axis", useUnmergedTree = true).assertExists()
        compose.onNodeWithTag("reports-trend-summary").assertDoesNotExist()
        compose.onNodeWithTag("reports-clear-controls").assertDoesNotExist()
        assertEquals("shop", vm.state.value.selectedSubcategoryId)
        net("-8,00")
        open(); compose.onNodeWithTag("reports-reset-filters").performClick()
        compose.onNodeWithTag("reports-filter-category").performScrollTo().assert(SemanticsMatcher.expectValue(SemanticsProperties.StateDescription, "Wszystkie kategorie"))
        compose.onNodeWithTag("reports-filter-subcategory").performScrollTo().assertIsNotEnabled()
        assertEquals("food", vm.state.value.selectedCategoryId)
        apply(); net("-15,00")
        open(); choose("reports-sort", "AMOUNT"); apply()
        assertEquals(listOf("shop-income", "car", "cafe", "shop-expense"), vm.state.value.entries.map { it.entry.id })
        open(); compose.onNodeWithTag("reports-sort-direction").performScrollTo().performClick(); apply()
        assertEquals(listOf("shop-expense", "cafe", "car", "shop-income"), vm.state.value.entries.map { it.entry.id })
        net("-15,00")
    }

    @Test fun selectedEmptyScopesRemoveChartsEntriesAndResetRequiresApply() {
        render(includeEmpty = true)
        net("-15,00")
        open(); choose("reports-filter-category", "food"); choose("reports-filter-subcategory", "empty-shop"); apply()
        assertEmptyScope()
        assertEquals("empty-shop", vm.state.value.selectedSubcategoryId)
        open(); choose("reports-filter-category", "empty")
        compose.onNodeWithTag("reports-filter-subcategory").performScrollTo().assert(SemanticsMatcher.expectValue(SemanticsProperties.StateDescription, "Wszystkie podkategorie"))
        choose("reports-filter-subcategory", "empty-branch"); apply()
        assertEmptyScope()
        assertEquals("empty", vm.state.value.selectedCategoryId)
        open(); compose.onNodeWithTag("reports-reset-filters").performClick()
        compose.onNodeWithTag("reports-cancel-filters").performClick()
        assertEmptyScope()
        open(); compose.onNodeWithTag("reports-reset-filters").performClick(); apply()
        net("-15,00")
        compose.onNodeWithTag("reports-empty").assertDoesNotExist()
        compose.onNodeWithTag("reports-category-chart").performScrollTo().assertIsDisplayed()
        compose.onNodeWithTag("report-entry-car").performScrollTo().assertIsDisplayed()
    }
    private fun assertEmptyScope() {
        compose.onNodeWithTag("reports-empty").performScrollTo().assertIsDisplayed()
        listOf("reports-income", "reports-expense", "reports-net").forEach {
            compose.onNodeWithTag(it, useUnmergedTree = true).assertTextContains("0,00", substring = true)
        }
        listOf("reports-category-chart", "reports-trend-chart", "report-entry-car", "report-entry-cafe", "report-entry-shop-income", "report-entry-shop-expense").forEach {
            compose.onNodeWithTag(it).assertDoesNotExist()
        }
    }

    @Test fun checkboxSemanticsMemberNoneAndCancelDoNotMutateAppliedReport() {
        render()
        open()
        compose.onNodeWithTag("reports-members-all").performScrollTo().assertIsOn().assertHasClickAction().performClick().assertIsOff()
        compose.onNodeWithTag("reports-member-actor").performScrollTo().assertIsOff().performClick().assertIsOn()
        assertEquals(null, vm.state.value.selectedMemberIds)
        compose.onNodeWithTag("reports-cancel-filters").performClick()
        assertEquals(null, vm.state.value.selectedMemberIds)
        open(); compose.onNodeWithTag("reports-members-clear").performScrollTo().assertHasClickAction().performClick()
        compose.onNodeWithTag("reports-members-all").performScrollTo().assertIsOff()
        apply(); assertEmptyScope()
        assertEquals(emptySet<String>(), vm.state.value.selectedMemberIds)
        open(); compose.onNodeWithTag("reports-members-all").performScrollTo().performClick(); apply()
        net("-15,00")
    }

    @Test fun backAndOutsideTapDiscardDraftSelection() {
        render()
        open(); choose("reports-filter-category", "food"); choose("reports-sort", "AMOUNT")
        compose.onNodeWithTag("reports-sort-direction").performScrollTo().performClick()
        compose.waitForIdle()
        androidx.test.platform.app.InstrumentationRegistry.getInstrumentation().waitForIdleSync()
        androidx.test.platform.app.InstrumentationRegistry.getInstrumentation()
            .sendKeyDownUpSync(android.view.KeyEvent.KEYCODE_BACK)
        compose.waitUntil(timeoutMillis = 5_000) { vm.state.value.filterDraft == null }
        compose.onNodeWithTag("reports-filter-dialog").assertDoesNotExist()
        assertEquals(null, vm.state.value.selectedCategoryId)
        assertEquals(ReportEntrySort.DATE, vm.state.value.sort)
        assertEquals(ReportSortDirection.DESCENDING, vm.state.value.direction)
        open(); choose("reports-filter-category", "car"); choose("reports-sort", "AMOUNT")
        compose.onAllNodes(isRoot()).onLast().performTouchInput { click(androidx.compose.ui.geometry.Offset(1f, 1f)) }
        compose.waitUntil(timeoutMillis = 5_000) { vm.state.value.filterDraft == null }
        compose.onNodeWithTag("reports-filter-dialog").assertDoesNotExist()
        assertEquals(null, vm.state.value.selectedCategoryId)
        assertEquals(ReportEntrySort.DATE, vm.state.value.sort)
        net("-15,00")
    }

    @Test fun missingTaxonomyIdsExposeExplicitLabelsAndAppliedFilterState() {
        val applied = ReportsUiState(LocalDate.of(2026, 9, 30),
            java.time.YearMonth.of(2026, 9), java.time.Year.of(2026), isLoading = false,
            selectedCategoryId = "missing-category", selectedSubcategoryId = "missing-subcategory")
        compose.setContent {
            var state by remember { androidx.compose.runtime.mutableStateOf(applied) }
            ThesaurusTheme {
                ReportsScreen(state, {}, {}, {}, {}, {}, {}, {}, {}, {},
                    onOpenFilters = { state = state.copy(filterDraft = ReportFilterDraft(state.mode,
                        state.month, state.year, state.typeFilter, state.customFromInput, state.customToInput,
                        selectedCategoryId = state.selectedCategoryId, selectedSubcategoryId = state.selectedSubcategoryId)) },
                    onDismissFilters = { state = state.copy(filterDraft = null) })
            }
        }
        compose.onNodeWithTag("reports-open-filters")
            .assertContentDescriptionEquals("Otwórz filtry raportów")
            .assert(SemanticsMatcher.expectValue(SemanticsProperties.StateDescription, "Aktywne filtry lub sortowanie"))
        compose.onNodeWithTag("reports-filters-active", useUnmergedTree = true).assertExists()
        open()
        compose.onNodeWithTag("reports-filter-category").performScrollTo()
            .assert(SemanticsMatcher.expectValue(SemanticsProperties.StateDescription, "Nieznana kategoria (missing-category)"))
        compose.onNodeWithTag("reports-filter-subcategory").performScrollTo()
            .assertIsEnabled()
            .assert(SemanticsMatcher.expectValue(SemanticsProperties.StateDescription, "Nieznana podkategoria (missing-subcategory)"))
        compose.onNodeWithTag("reports-filter-subcategory").performClick()
        compose.onNodeWithTag("reports-filter-subcategory-option-missing-subcategory").assertIsDisplayed().performClick()
        compose.onNodeWithTag("reports-filter-category").performScrollTo().performClick()
        compose.onNodeWithTag("reports-filter-category-option-missing-category").assertIsDisplayed().performClick()
        compose.onNodeWithTag("reports-cancel-filters").performClick()
        compose.onNodeWithTag("reports-open-filters")
            .assert(SemanticsMatcher.expectValue(SemanticsProperties.StateDescription, "Aktywne filtry lub sortowanie"))
    }

    @Test fun largeFontDialogControlsAndCustomInputRemainReachableAndSortOptionsAreLimited() {
        render(largeFont = true)
        compose.onNodeWithTag("reports-open-filters").assertIsDisplayed().assertHasClickAction()
        open()
        choose("reports-period-selector", "CUSTOM")
        compose.onNodeWithTag("reports-custom-from").performScrollTo().performTextReplacement("2026-09-30")
        compose.onNodeWithTag("reports-custom-to").performScrollTo().performTextReplacement("2026-09-01")
        compose.onNodeWithTag("reports-apply-filters").assertIsDisplayed().performClick()
        compose.onNodeWithTag("reports-filter-dialog").assertIsDisplayed()
        assertTrue(vm.state.value.filterDraft!!.customDateError)
        compose.onNodeWithTag("reports-custom-from").performScrollTo().performTextReplacement("2026-09-01")
        compose.onNodeWithTag("reports-custom-to").performScrollTo().performTextReplacement("2026-09-30")
        compose.onNodeWithTag("reports-reset-filters").assertIsDisplayed().assertHasClickAction()
        compose.onNodeWithTag("reports-cancel-filters").assertIsDisplayed().assertHasClickAction()
        compose.onNodeWithTag("reports-sort").performScrollTo().performClick()
        compose.onNodeWithTag("reports-sort-option-DATE").assertIsDisplayed()
        compose.onNodeWithTag("reports-sort-option-AMOUNT").assertIsDisplayed()
        listOf("CATEGORY", "SUBCATEGORY", "TAGS").forEach { compose.onNodeWithTag("reports-sort-option-$it").assertDoesNotExist() }
        compose.onNodeWithTag("reports-sort-option-AMOUNT").performClick()
        compose.onNodeWithTag("reports-sort-direction").performScrollTo().assertContentDescriptionEquals("Sortowanie malejące. Zmień na rosnące").performClick()
            .assertContentDescriptionEquals("Sortowanie rosnące. Zmień na malejące")
        listOf("reports-sort", "reports-sort-direction").forEach { tag ->
            val bounds = compose.onNodeWithTag(tag).performScrollTo().assertIsDisplayed().assertHasClickAction().getUnclippedBoundsInRoot()
            assertTrue(bounds.right - bounds.left >= 48.dp && bounds.bottom - bounds.top >= 48.dp)
        }
        assertEquals(ReportEntrySort.DATE, vm.state.value.sort)
        apply()
        assertEquals(ReportEntrySort.AMOUNT, vm.state.value.sort)
        assertEquals(ReportPeriodMode.CUSTOM, vm.state.value.mode)
        open(); compose.onNodeWithTag("reports-reset-filters").performClick()
        assertEquals(ReportEntrySort.AMOUNT, vm.state.value.sort)
        apply()
        assertEquals(ReportEntrySort.DATE, vm.state.value.sort)
        assertEquals(ReportPeriodMode.MONTH, vm.state.value.mode)
    }
    @Test fun sortingOnlyLivesInDialogAndCancelReopenResetAndFunnelReflectAppliedSort() {
        render()
        listOf("reports-sort", "reports-sort-direction", "reports-clear-controls").forEach {
            compose.onNodeWithTag(it).assertDoesNotExist()
        }
        compose.onNodeWithTag("reports-open-filters").assert(
            SemanticsMatcher.expectValue(SemanticsProperties.StateDescription, "Domyślne filtry i sortowanie"))
        open(); choose("reports-sort", "AMOUNT")
        compose.onNodeWithTag("reports-sort-direction").performScrollTo().performClick()
            .assert(SemanticsMatcher.expectValue(SemanticsProperties.StateDescription, "Rosnąco"))
        assertEquals(ReportEntrySort.DATE, vm.state.value.sort)
        compose.onNodeWithTag("reports-cancel-filters").performClick()
        open()
        compose.onNodeWithTag("reports-sort").performScrollTo().assert(
            SemanticsMatcher.expectValue(SemanticsProperties.StateDescription, "Data księgowania"))
        choose("reports-sort", "AMOUNT"); apply()
        compose.onNodeWithTag("reports-sort").assertDoesNotExist()
        compose.onNodeWithTag("reports-open-filters").assert(
            SemanticsMatcher.expectValue(SemanticsProperties.StateDescription, "Aktywne filtry lub sortowanie"))
        compose.onNodeWithTag("reports-filters-active", useUnmergedTree = true).assertExists()
        open()
        compose.onNodeWithTag("reports-sort").performScrollTo().assert(
            SemanticsMatcher.expectValue(SemanticsProperties.StateDescription, "Kwota"))
        compose.onNodeWithTag("reports-reset-filters").performClick()
        assertEquals(ReportEntrySort.AMOUNT, vm.state.value.sort)
        compose.onNodeWithTag("reports-cancel-filters").performClick()
        assertEquals(ReportEntrySort.AMOUNT, vm.state.value.sort)
        open(); compose.onNodeWithTag("reports-reset-filters").performClick(); apply()
        compose.onNodeWithTag("reports-open-filters").assert(
            SemanticsMatcher.expectValue(SemanticsProperties.StateDescription, "Domyślne filtry i sortowanie"))
        net("-15,00")
    }

    @Test fun hardwareKeyboardOpensSortSelectsDirectionAndDiscardsDialogWithBack() {
        render(); open()
        val instrumentation = androidx.test.platform.app.InstrumentationRegistry.getInstrumentation()
        fun key(code: Int) { instrumentation.sendKeyDownUpSync(code); compose.waitForIdle() }
        fun focus(tag: String) {
            val node = compose.onNodeWithTag(tag).performScrollTo()
            repeat(35) {
                val config = node.fetchSemanticsNode().config
                if (SemanticsProperties.Focused in config && config[SemanticsProperties.Focused]) return
                key(android.view.KeyEvent.KEYCODE_TAB)
            }
            node.assertIsFocused()
        }
        focus("reports-sort"); key(android.view.KeyEvent.KEYCODE_ENTER)
        compose.onNodeWithTag("reports-sort-option-DATE").assertIsDisplayed()
        val amountOption = compose.onNodeWithTag("reports-sort-option-AMOUNT")
        for (step in 0..3) {
            val config = amountOption.fetchSemanticsNode().config
            if (SemanticsProperties.Focused in config && config[SemanticsProperties.Focused]) break
            key(android.view.KeyEvent.KEYCODE_DPAD_DOWN)
        }
        amountOption.assertIsFocused()
        key(android.view.KeyEvent.KEYCODE_ENTER)
        compose.waitUntil(5_000) { vm.state.value.filterDraft!!.sort == ReportEntrySort.AMOUNT }
        focus("reports-sort-direction"); key(android.view.KeyEvent.KEYCODE_ENTER)
        compose.onNodeWithTag("reports-sort-direction").assert(
            SemanticsMatcher.expectValue(SemanticsProperties.StateDescription, "Rosnąco"))
        assertEquals(ReportEntrySort.DATE, vm.state.value.sort)
        key(android.view.KeyEvent.KEYCODE_BACK)
        compose.waitUntil(5_000) { vm.state.value.filterDraft == null }
        assertEquals(ReportEntrySort.DATE, vm.state.value.sort)
        assertEquals(ReportSortDirection.DESCENDING, vm.state.value.direction)
        open()
        assertEquals(ReportEntrySort.DATE, vm.state.value.filterDraft!!.sort)
        focus("reports-sort"); key(android.view.KeyEvent.KEYCODE_ENTER)
        key(android.view.KeyEvent.KEYCODE_ESCAPE)
        compose.onNodeWithTag("reports-sort-option-DATE").assertDoesNotExist()
        compose.onNodeWithTag("reports-filter-dialog").assertIsDisplayed()
    }

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
