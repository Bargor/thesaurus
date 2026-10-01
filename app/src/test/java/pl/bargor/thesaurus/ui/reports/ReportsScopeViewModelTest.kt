package pl.bargor.thesaurus.ui.reports

import androidx.lifecycle.SavedStateHandle
import java.math.BigInteger
import java.time.Clock
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneOffset
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import pl.bargor.thesaurus.data.firebase.LedgerRepository
import pl.bargor.thesaurus.data.firebase.TaxonomyRepository
import pl.bargor.thesaurus.data.model.*

@OptIn(ExperimentalCoroutinesApi::class)
class ReportsScopeViewModelTest {
    private val dispatcher = StandardTestDispatcher()
    private val clock = Clock.fixed(Instant.parse("2026-09-30T12:00:00Z"), ZoneOffset.UTC)
    @Before fun before() = Dispatchers.setMain(dispatcher)
    @After fun after() = Dispatchers.resetMain()

    @Test fun totalsCategoryChartTrendAndEntriesConsumeOneScopedDataset() = runTest {
        val ledger = Ledger(listOf(entry("shop-expense", -1200, "shop"), entry("shop-income", 200, "shop"), entry("cafe", -500, "cafe"), entry("car", -300, category = "car")))
        val vm = ReportsViewModel(ledger, Taxonomy(), clock, SavedStateHandle())
        vm.start("home"); advanceUntilIdle()
        assertEquals(4, vm.state.value.aggregation.totals.entryCount)
        vm.selectCategory("food")
        assertEquals(3, vm.state.value.aggregation.totals.entryCount)
        vm.selectSubcategory("shop")
        val scoped = vm.state.value
        assertEquals(setOf("shop-expense", "shop-income"), scoped.entries.map { it.entry.id }.toSet())
        assertEquals(200.toBigInteger(), scoped.aggregation.totals.incomeGrosze)
        assertEquals(1200.toBigInteger(), scoped.aggregation.totals.expenseGrosze)
        assertEquals((-1000).toBigInteger(), scoped.aggregation.totals.netGrosze)
        assertEquals(listOf(ReportCategoryValue("food", 1400.toBigInteger())), scoped.aggregation.categories)
        assertEquals((-1000).toBigInteger(), scoped.aggregation.trend.sumOfBig { it.amountGrosze })
        assertTrue(scoped.entries.all { it.subcategoryName == "Sklep" && it.categoryColor == "rose" })
        vm.selectType(ReportTypeFilter.EXPENSE)
        assertEquals(listOf("shop-expense"), vm.state.value.entries.map { it.entry.id })
        assertEquals(1200.toBigInteger(), vm.state.value.aggregation.trend.single().amountGrosze)
        assertEquals((-1200).toBigInteger(), vm.state.value.aggregation.totals.netGrosze)
        vm.selectPeriodMode(ReportPeriodMode.YEAR)
        assertEquals(LocalDate.of(2026, 9, 1), vm.state.value.aggregation.trend.single().date)
        assertEquals(1, vm.state.value.aggregation.totals.entryCount)
    }

    @Test fun dependentChoicesRejectForeignSubcategoryAndChangingParentOrClearingResetsScope() = runTest {
        val vm = ReportsViewModel(Ledger(listOf(entry("food", -100, "shop"), entry("car", -200, "fuel", "car"))), Taxonomy(), clock, SavedStateHandle())
        vm.start("home"); advanceUntilIdle()
        vm.selectSubcategory("shop")
        assertNull(vm.state.value.selectedSubcategoryId)
        vm.selectCategory("food"); vm.selectSubcategory("shop")
        assertEquals(listOf("cafe", "shop"), vm.state.value.subcategories.map { it.id })
        vm.selectCategory("car")
        assertNull(vm.state.value.selectedSubcategoryId)
        assertEquals(listOf("fuel"), vm.state.value.subcategories.map { it.id })
        vm.selectSubcategory("shop")
        assertNull(vm.state.value.selectedSubcategoryId)
        vm.selectSubcategory("fuel"); vm.selectSort(ReportEntrySort.AMOUNT); vm.toggleSortDirection()
        vm.clearControls()
        assertFalse(vm.state.value.hasActiveFilters)
        assertEquals(ReportEntrySort.DATE, vm.state.value.sort)
        assertEquals(ReportSortDirection.DESCENDING, vm.state.value.direction)
        assertTrue(vm.state.value.subcategories.isEmpty())
        assertEquals(2, vm.state.value.entries.size)
    }

    @Test fun disappearingTaxonomyPrunesSelectedIdsWhileArchivedHistoryRemainsSelectable() = runTest {
        val taxonomy = Taxonomy()
        taxonomy.categories.value = SyncObservation(listOf(category("food").copy(archived = true), category("car")), SyncState.SYNCED)
        val vm = ReportsViewModel(Ledger(listOf(entry("food", -100, "shop"))), taxonomy, clock, SavedStateHandle())
        vm.start("home"); advanceUntilIdle()
        vm.selectCategory("food"); vm.selectSubcategory("shop")
        assertTrue(vm.state.value.categories.single { it.id == "food" }.archived)
        taxonomy.subs.getValue("food").value = SyncObservation(emptyList(), SyncState.SYNCED)
        advanceUntilIdle()
        assertEquals("food", vm.state.value.selectedCategoryId)
        assertNull(vm.state.value.selectedSubcategoryId)
        taxonomy.categories.value = SyncObservation(listOf(category("car")), SyncState.SYNCED)
        advanceUntilIdle()
        assertNull(vm.state.value.selectedCategoryId)
        assertTrue(vm.state.value.subcategories.isEmpty())
        assertEquals(1, vm.state.value.entries.size)
    }

    @Test fun cachedScopeSurvivesErrorsRetryAndPendingUpdatesButCannotLeakAcrossHouseholds() = runTest {
        val ledger = Ledger(listOf(entry("cached", -100, "shop")))
        val taxonomy = Taxonomy()
        val vm = ReportsViewModel(ledger, taxonomy, clock, SavedStateHandle())
        vm.start("home"); advanceUntilIdle()
        vm.selectCategory("food"); vm.selectSubcategory("shop")
        ledger.home.value = SyncObservation(listOf(entry("cached", -100, "shop"), entry("local", -50, "shop"), entry("deleted", 900, "shop").copy(deleted = true, deletedById = "actor"), entry("foreign", 800, "shop").copy(householdId = "other")), SyncState.PENDING)
        advanceUntilIdle()
        assertEquals(SyncState.PENDING, vm.state.value.syncState)
        assertEquals((-150).toBigInteger(), vm.state.value.aggregation.totals.netGrosze)
        ledger.home.value = SyncObservation(state = SyncState.OFFLINE)
        taxonomy.subs.getValue("food").value = SyncObservation(state = SyncState.ERROR, error = IllegalStateException("offline"))
        advanceUntilIdle()
        assertTrue(vm.state.value.hasError)
        vm.retry(); advanceUntilIdle()
        assertEquals("shop", vm.state.value.selectedSubcategoryId)
        assertEquals(2, vm.state.value.entries.size)
        taxonomy.subs.getValue("food").value = SyncObservation(listOf(sub("shop", "food", "Sklep")), SyncState.SYNCED)
        ledger.home.value = SyncObservation(listOf(entry("new", -25, "shop")), SyncState.SYNCED)
        advanceUntilIdle()
        assertFalse(vm.state.value.hasError)
        assertEquals((-25).toBigInteger(), vm.state.value.aggregation.totals.netGrosze)
        vm.start("other")
        assertTrue(vm.state.value.isLoading)
        assertNull(vm.state.value.selectedCategoryId)
        assertNull(vm.state.value.selectedSubcategoryId)
        assertTrue(vm.state.value.entries.isEmpty())
        advanceUntilIdle()
        assertTrue(vm.state.value.aggregation.totals.isEmpty)
    }

    @Test fun obsoleteRestoredTagsAndSortValuesCannotRestrictDataWhileValidSortRoundTrips() = runTest {
        val saved = SavedStateHandle(mapOf("reports.selectedTag" to "dom", "reports.filterTag" to "dom", "reports.sort" to "SUBCATEGORY", "reports.direction" to "invalid"))
        val ledger = Ledger(listOf(entry("a", -100, "shop").copy(tags = listOf("dom")), entry("b", 300, "shop")))
        val vm = ReportsViewModel(ledger, Taxonomy(), clock, saved)
        assertEquals(ReportEntrySort.DATE, vm.state.value.sort)
        assertEquals(ReportSortDirection.DESCENDING, vm.state.value.direction)
        vm.start("home"); advanceUntilIdle()
        assertEquals(2, vm.state.value.entries.size)
        assertFalse(saved.keys().any { "tag" in it.lowercase() })
        vm.selectSort(ReportEntrySort.AMOUNT); vm.toggleSortDirection()
        assertEquals(listOf("a", "b"), vm.state.value.entries.map { it.entry.id })
        val restored = ReportsViewModel(ledger, Taxonomy(), clock, SavedStateHandle(saved.keys().associateWith { saved.get<Any?>(it) }))
        assertEquals(ReportEntrySort.AMOUNT, restored.state.value.sort)
        assertEquals(ReportSortDirection.ASCENDING, restored.state.value.direction)
    }

    @Test fun customDraftWaitsForApplyEvenAcrossMetadataUpdatesAndAllPeriodsRespectCategoryScope() = runTest {
        val ledger = Ledger(listOf(entry("aug", -200, "shop", date = "2026-08-31"), entry("sept", 300, "shop"), entry("other", -999, "fuel", "car")))
        val vm = ReportsViewModel(ledger, Taxonomy(), clock, SavedStateHandle())
        vm.start("home"); advanceUntilIdle()
        vm.selectCategory("food"); vm.selectSubcategory("shop")
        vm.selectPeriodMode(ReportPeriodMode.CUSTOM)
        vm.updateCustomFrom("2026-08-01"); vm.updateCustomTo("2026-08-31")
        ledger.home.value = SyncObservation(ledger.home.value.value, SyncState.OFFLINE)
        advanceUntilIdle()
        assertEquals(listOf("sept"), vm.state.value.entries.map { it.entry.id })
        vm.applyCustomPeriod()
        assertEquals(listOf("aug"), vm.state.value.entries.map { it.entry.id })
        assertEquals((-200).toBigInteger(), vm.state.value.aggregation.totals.netGrosze)
        vm.selectPeriodMode(ReportPeriodMode.YEAR)
        assertEquals(100.toBigInteger(), vm.state.value.aggregation.totals.netGrosze)
        assertEquals(2, vm.state.value.aggregation.trend.size)
        vm.selectPeriodMode(ReportPeriodMode.MONTH); vm.previousPeriod()
        assertEquals(listOf("aug"), vm.state.value.entries.map { it.entry.id })
    }

    @Test fun validEmptyCategoryAndSubcategoryRemainSelectedWithZeroReportUntilCleared() = runTest {
        val taxonomy = Taxonomy()
        taxonomy.categories.value = SyncObservation(listOf(category("food"), category("empty")), SyncState.SYNCED)
        taxonomy.subs.getValue("food").value = SyncObservation(listOf(sub("shop", "food", "Sklep"), sub("empty-shop", "food", "Pusty sklep")), SyncState.SYNCED)
        taxonomy.subs["empty"] = MutableStateFlow(SyncObservation(listOf(sub("empty-branch", "empty", "Pusta podkategoria")), SyncState.SYNCED))
        val vm = ReportsViewModel(Ledger(listOf(entry("existing", -100, "shop"))), taxonomy, clock, SavedStateHandle())
        vm.start("home"); advanceUntilIdle()
        vm.selectCategory("food"); vm.selectSubcategory("empty-shop")
        assertEquals("food", vm.state.value.selectedCategoryId)
        assertEquals("empty-shop", vm.state.value.selectedSubcategoryId)
        assertTrue(vm.state.value.hasActiveFilters)
        assertEquals(ReportTotals(), vm.state.value.aggregation.totals)
        assertTrue(vm.state.value.entries.isEmpty())
        assertTrue(vm.state.value.aggregation.categories.isEmpty())
        assertTrue(vm.state.value.aggregation.trend.isEmpty())
        vm.selectCategory("empty"); vm.selectSubcategory("empty-branch")
        assertEquals("empty", vm.state.value.selectedCategoryId)
        assertEquals("empty-branch", vm.state.value.selectedSubcategoryId)
        assertEquals(ReportTotals(), vm.state.value.aggregation.totals)
        assertTrue(vm.state.value.entries.isEmpty())
        vm.clearControls()
        assertNull(vm.state.value.selectedCategoryId)
        assertNull(vm.state.value.selectedSubcategoryId)
        assertEquals(listOf("existing"), vm.state.value.entries.map { it.entry.id })
        assertEquals((-100).toBigInteger(), vm.state.value.aggregation.totals.netGrosze)
    }

    private fun <T> List<T>.sumOfBig(value: (T) -> BigInteger) = fold(BigInteger.ZERO) { sum, item -> sum + value(item) }
    private fun entry(id: String, amount: Long, subcategory: String? = null, category: String = "food", date: String = "2026-09-12") = LedgerEntry(id, "home", amount, LocalDate.parse(date), categoryId = category, subcategoryId = subcategory, authorId = "actor", updatedById = "actor")
    private fun category(id: String) = Category(id, "home", id, color = "rose", authorId = "actor", updatedById = "actor")
    private fun sub(id: String, parent: String, name: String) = Subcategory(id, "home", parent, name, authorId = "actor", updatedById = "actor")
    private inner class Taxonomy : TaxonomyRepository {
        val categories = MutableStateFlow(SyncObservation(listOf(category("food"), category("car")), SyncState.SYNCED))
        val subs = mutableMapOf("food" to MutableStateFlow(SyncObservation(listOf(sub("shop", "food", "Sklep"), sub("cafe", "food", "Kawiarnia")), SyncState.SYNCED)), "car" to MutableStateFlow(SyncObservation(listOf(sub("fuel", "car", "Paliwo")), SyncState.SYNCED)))
        override fun observeCategories(householdId: String) = categories
        override fun observeSubcategories(householdId: String, categoryId: String): Flow<SyncObservation<List<Subcategory>>> = subs.getOrPut(categoryId) { MutableStateFlow(SyncObservation(emptyList(), SyncState.SYNCED)) }
        override suspend fun save(category: Category) = Unit
        override suspend fun save(subcategory: Subcategory) = Unit
    }
    private class Ledger(entries: List<LedgerEntry>) : LedgerRepository {
        val home = MutableStateFlow(SyncObservation(entries, SyncState.SYNCED))
        override fun observeEntries(householdId: String, includeDeleted: Boolean): Flow<SyncObservation<List<LedgerEntry>>> = if (householdId == "home") home else MutableStateFlow(SyncObservation(emptyList(), SyncState.SYNCED))
        override suspend fun save(entry: LedgerEntry) = Unit
        override suspend fun tombstone(householdId: String, entryId: String, actorId: String) = Unit
    }
}
