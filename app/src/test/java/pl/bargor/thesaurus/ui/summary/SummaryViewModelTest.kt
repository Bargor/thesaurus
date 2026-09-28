package pl.bargor.thesaurus.ui.summary

import java.time.Clock
import java.time.Instant
import java.time.LocalDate
import java.time.Year
import java.time.ZoneOffset
import java.time.YearMonth
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
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import pl.bargor.thesaurus.data.firebase.LedgerRepository
import pl.bargor.thesaurus.data.firebase.TaxonomyRepository
import pl.bargor.thesaurus.data.model.Category
import pl.bargor.thesaurus.data.model.LedgerEntry
import pl.bargor.thesaurus.data.model.Subcategory
import pl.bargor.thesaurus.data.model.SyncObservation
import pl.bargor.thesaurus.data.model.SyncState

@OptIn(ExperimentalCoroutinesApi::class)
class SummaryViewModelTest {
    private val dispatcher = StandardTestDispatcher()
    private val clock = Clock.fixed(Instant.parse("2026-01-15T12:00:00Z"), ZoneOffset.UTC)

    @Before fun setUp() = Dispatchers.setMain(dispatcher)
    @After fun tearDown() = Dispatchers.resetMain()

    @Test fun currentMonthAndNavigationCrossYearBoundary() = runTest {
        val ledger = FakeLedger(SyncObservation(listOf(entry("dec", 1_000, LocalDate.of(2025, 12, 31))), SyncState.SYNCED))
        val vm = SummaryViewModel(ledger, FakeTaxonomy(), clock)
        assertEquals(YearMonth.of(2026, 1), vm.state.value.month)
        vm.start("home")
        advanceUntilIdle()
        assertTrue(vm.state.value.totals.isEmpty)
        vm.previousMonth()
        assertEquals(YearMonth.of(2025, 12), vm.state.value.month)
        assertEquals(1_000.toBigInteger(), vm.state.value.totals.incomeGrosze)
        assertEquals(listOf("dec"), vm.state.value.entries.map { it.entry.id })
        vm.nextMonth()
        assertTrue(vm.state.value.totals.isEmpty)
        assertTrue(vm.state.value.entries.isEmpty())
    }

    @Test fun pendingLocalWritesRecalculateTotalsAndOfflineKeepsCachedValues() = runTest {
        val ledger = FakeLedger(SyncObservation(emptyList(), SyncState.SYNCED))
        val vm = SummaryViewModel(ledger, FakeTaxonomy(), clock)
        vm.start("home")
        advanceUntilIdle()
        assertFalse(vm.state.value.isLoading)
        ledger.observations.value = SyncObservation(listOf(entry("local", -1_250, LocalDate.of(2026, 1, 15))), SyncState.PENDING)
        advanceUntilIdle()
        assertEquals(SyncState.PENDING, vm.state.value.syncState)
        assertEquals(1_250.toBigInteger(), vm.state.value.totals.expenseGrosze)
        assertEquals(listOf("local"), vm.state.value.entries.map { it.entry.id })
        assertEquals((-1_250).toBigInteger(), vm.state.value.totals.netGrosze)
        ledger.observations.value = SyncObservation(ledger.observations.value.value, SyncState.OFFLINE)
        advanceUntilIdle()
        assertEquals(SyncState.OFFLINE, vm.state.value.syncState)
        assertEquals(1_250.toBigInteger(), vm.state.value.totals.expenseGrosze)
        assertEquals(listOf("local"), vm.state.value.entries.map { it.entry.id })
    }

    @Test fun yearlyModeUsesCurrentYearAndPreservesTheIndependentMonthSelection() = runTest {
        val leapYearClock = Clock.fixed(Instant.parse("2028-01-15T12:00:00Z"), ZoneOffset.UTC)
        val ledger = FakeLedger(SyncObservation(listOf(
            entry("previous-year", 500, LocalDate.of(2027, 12, 31)),
            entry("first-day", 25_000, LocalDate.of(2028, 1, 1)),
            entry("leap-day", -4_500, LocalDate.of(2028, 2, 29)),
            entry("last-day", -10_500, LocalDate.of(2028, 12, 31)),
            entry("next-year", -800, LocalDate.of(2029, 1, 1)),
        ), SyncState.SYNCED))
        val vm = SummaryViewModel(ledger, FakeTaxonomy(), leapYearClock)

        assertEquals(YearMonth.of(2028, 1), vm.state.value.month)
        assertEquals(Year.of(2028), vm.state.value.year)
        assertEquals(SummaryPeriodMode.MONTH, vm.state.value.mode)
        vm.start("home")
        advanceUntilIdle()

        vm.previousMonth()
        assertEquals(YearMonth.of(2027, 12), vm.state.value.month)
        assertEquals(500.toBigInteger(), vm.state.value.totals.incomeGrosze)

        vm.selectPeriodMode(SummaryPeriodMode.YEAR)
        assertEquals(Year.of(2028), vm.state.value.year)
        assertEquals(25_000.toBigInteger(), vm.state.value.totals.incomeGrosze)
        assertEquals(15_000.toBigInteger(), vm.state.value.totals.expenseGrosze)
        assertEquals(10_000.toBigInteger(), vm.state.value.totals.netGrosze)
        assertEquals(3, vm.state.value.totals.entryCount)
        assertEquals(listOf("last-day", "leap-day", "first-day"), vm.state.value.entries.map { it.entry.id })

        vm.previousYear()
        assertEquals(Year.of(2027), vm.state.value.year)
        assertEquals(500.toBigInteger(), vm.state.value.totals.incomeGrosze)
        vm.nextYear()
        vm.selectPeriodMode(SummaryPeriodMode.MONTH)
        assertEquals(YearMonth.of(2027, 12), vm.state.value.month)
        assertEquals(500.toBigInteger(), vm.state.value.totals.incomeGrosze)
    }

    @Test fun errorWithoutDataAndRetryRecoverToEmptyState() = runTest {
        val ledger = FakeLedger(SyncObservation(state = SyncState.ERROR, error = IllegalStateException("offline")))
        val vm = SummaryViewModel(ledger, FakeTaxonomy(), clock)
        vm.start("home")
        advanceUntilIdle()
        assertTrue(vm.state.value.hasError)
        assertFalse(vm.state.value.isLoading)
        ledger.observations.value = SyncObservation(emptyList(), SyncState.SYNCED)
        vm.retry()
        advanceUntilIdle()
        assertFalse(vm.state.value.hasError)
        assertTrue(vm.state.value.totals.isEmpty)
        assertTrue(vm.state.value.entries.isEmpty())
    }

    @Test fun filtersAreCombinedSubcategoriesFollowCategoryAndResetRestoresNewestDate() = runTest {
        val taxonomy = FakeTaxonomy()
        taxonomy.categories.value = SyncObservation(listOf(
            category("food", "Żywność"), category("car", "Auto"),
        ), SyncState.SYNCED)
        taxonomy.subcategories["food"] = MutableStateFlow(SyncObservation(listOf(
            subcategory("shop", "food", "Zakupy"), subcategory("cafe", "food", "Kawiarnia"),
        ), SyncState.SYNCED))
        taxonomy.subcategories["car"] = MutableStateFlow(SyncObservation(listOf(
            subcategory("fuel", "car", "Paliwo"),
        ), SyncState.SYNCED))
        val ledger = FakeLedger(SyncObservation(listOf(
            entry("old", 100, LocalDate.of(2026, 1, 1)).copy(categoryId = "food", subcategoryId = "shop", tags = listOf(" Dom ", "pilne")),
            entry("new", -200, LocalDate.of(2026, 1, 31)).copy(categoryId = "food", subcategoryId = "cafe", tags = listOf("dom")),
            entry("car", -300, LocalDate.of(2026, 1, 20)).copy(categoryId = "car", subcategoryId = "fuel"),
        ), SyncState.SYNCED))
        val vm = SummaryViewModel(ledger, taxonomy, clock)
        vm.start("home")
        advanceUntilIdle()
        assertEquals(listOf("new", "car", "old"), vm.state.value.entries.map { it.entry.id })
        vm.selectCategory("food")
        assertEquals(listOf("cafe", "shop"), vm.state.value.subcategories.map { it.id })
        vm.selectSubcategory("shop")
        vm.selectTag(" PILNE ")
        assertEquals(listOf("old"), vm.state.value.entries.map { it.entry.id })
        assertEquals(3, vm.state.value.totals.entryCount)
        vm.selectCategory("car")
        assertEquals(null, vm.state.value.selectedSubcategoryId)
        assertEquals(listOf("fuel"), vm.state.value.subcategories.map { it.id })
        vm.selectSubcategory("shop")
        assertEquals(null, vm.state.value.selectedSubcategoryId)
        vm.selectSort(SummaryEntrySort.AMOUNT)
        vm.toggleSortDirection()
        vm.clearControls()
        assertEquals(SummaryEntrySort.DATE, vm.state.value.sort)
        assertEquals(SummarySortDirection.DESCENDING, vm.state.value.direction)
        assertEquals(null, vm.state.value.selectedCategoryId)
        assertEquals(null, vm.state.value.selectedTag)
        assertEquals(listOf("new", "car", "old"), vm.state.value.entries.map { it.entry.id })
    }

    @Test fun deletedAndOutOfPeriodRowsAreAbsentFromTotalsAndList() = runTest {
        val ledger = FakeLedger(SyncObservation(listOf(
            entry("kept", 100, LocalDate.of(2026, 1, 31)),
            entry("deleted", 200, LocalDate.of(2026, 1, 15)).copy(deleted = true, deletedById = "anna"),
            entry("feb", 300, LocalDate.of(2026, 2, 1)),
        ), SyncState.SYNCED))
        val vm = SummaryViewModel(ledger, FakeTaxonomy(), clock)
        vm.start("home")
        advanceUntilIdle()
        assertEquals(1, vm.state.value.totals.entryCount)
        assertEquals(listOf("kept"), vm.state.value.entries.map { it.entry.id })
    }

    private fun entry(id: String, amount: Long, date: LocalDate) = LedgerEntry(
        id = id, householdId = "home", amountGrosze = amount, date = date,
        categoryId = "category", authorId = "anna", updatedById = "anna",
    )

    private fun category(id: String, name: String) = Category(
        id, "home", name, authorId = "anna", updatedById = "anna",
    )
    private fun subcategory(id: String, categoryId: String, name: String) = Subcategory(
        id, "home", categoryId, name, authorId = "anna", updatedById = "anna",
    )

    private class FakeLedger(initial: SyncObservation<List<LedgerEntry>>) : LedgerRepository {
        val observations = MutableStateFlow(initial)
        override fun observeEntries(householdId: String, includeDeleted: Boolean): Flow<SyncObservation<List<LedgerEntry>>> = observations
        override suspend fun save(entry: LedgerEntry) = Unit
        override suspend fun tombstone(householdId: String, entryId: String, actorId: String) = Unit
    }

    private class FakeTaxonomy : TaxonomyRepository {
        val categories = MutableStateFlow(SyncObservation<List<Category>>(emptyList(), SyncState.SYNCED))
        val subcategories = mutableMapOf<String, MutableStateFlow<SyncObservation<List<Subcategory>>>>()
        override fun observeCategories(householdId: String): Flow<SyncObservation<List<Category>>> = categories
        override fun observeSubcategories(householdId: String, categoryId: String): Flow<SyncObservation<List<Subcategory>>> =
            subcategories.getOrPut(categoryId) { MutableStateFlow(SyncObservation(emptyList(), SyncState.SYNCED)) }
        override suspend fun save(category: Category) = Unit
        override suspend fun save(subcategory: Subcategory) = Unit
    }
}
