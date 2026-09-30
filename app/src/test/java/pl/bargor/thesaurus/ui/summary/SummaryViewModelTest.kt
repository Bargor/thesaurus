package pl.bargor.thesaurus.ui.summary

import androidx.lifecycle.SavedStateHandle
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
        val vm = SummaryViewModel(ledger, FakeTaxonomy(), clock, SavedStateHandle())
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
        val vm = SummaryViewModel(ledger, FakeTaxonomy(), clock, SavedStateHandle())
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

    @Test fun yearlyModeUsesSelectedMonthsYearAndYearArrowsRetainMonth() = runTest {
        val leapYearClock = Clock.fixed(Instant.parse("2028-01-15T12:00:00Z"), ZoneOffset.UTC)
        val ledger = FakeLedger(SyncObservation(listOf(
            entry("previous-year", 500, LocalDate.of(2027, 12, 31)),
            entry("first-day", 25_000, LocalDate.of(2028, 1, 1)),
            entry("leap-day", -4_500, LocalDate.of(2028, 2, 29)),
            entry("last-day", -10_500, LocalDate.of(2028, 12, 31)),
            entry("next-year", -800, LocalDate.of(2029, 1, 1)),
        ), SyncState.SYNCED))
        val vm = SummaryViewModel(ledger, FakeTaxonomy(), leapYearClock, SavedStateHandle())

        assertEquals(YearMonth.of(2028, 1), vm.state.value.month)
        assertEquals(Year.of(2028), vm.state.value.year)
        assertEquals(SummaryPeriodMode.MONTH, vm.state.value.mode)
        vm.start("home")
        advanceUntilIdle()

        vm.previousMonth()
        assertEquals(YearMonth.of(2027, 12), vm.state.value.month)
        assertEquals(500.toBigInteger(), vm.state.value.totals.incomeGrosze)

        vm.selectPeriodMode(SummaryPeriodMode.YEAR)
        assertEquals(Year.of(2027), vm.state.value.year)
        assertEquals(500.toBigInteger(), vm.state.value.totals.incomeGrosze)
        vm.nextYear()
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
        assertEquals(YearMonth.of(2028, 12), vm.state.value.month)
        assertEquals(10_500.toBigInteger(), vm.state.value.totals.expenseGrosze)
        assertEquals(listOf("last-day"), vm.state.value.entries.map { it.entry.id })
    }

    @Test fun changedMonthAndSelectedYearSurviveFreshSavedStateHandle() = runTest {
        val septemberClock = Clock.fixed(Instant.parse("2026-09-15T12:00:00Z"), ZoneOffset.UTC)
        val saved = SavedStateHandle()
        val vm = SummaryViewModel(FakeLedger(SyncObservation(emptyList(), SyncState.SYNCED)), FakeTaxonomy(), septemberClock, saved)
        vm.nextMonth()
        vm.selectPeriodMode(SummaryPeriodMode.YEAR)
        vm.previousYear()
        val restored = SummaryViewModel(
            FakeLedger(SyncObservation(emptyList(), SyncState.SYNCED)), FakeTaxonomy(), clock,
            SavedStateHandle(saved.keys().associateWith { saved.get<Any?>(it) }),
        )
        assertEquals(SummaryPeriodMode.YEAR, restored.state.value.mode)
        assertEquals(Year.of(2025), restored.state.value.year)
        assertEquals(YearMonth.of(2025, 10), restored.state.value.month)
        restored.selectPeriodMode(SummaryPeriodMode.MONTH)
        assertEquals(YearMonth.of(2025, 10), restored.state.value.month)
        restored.selectPeriodMode(SummaryPeriodMode.YEAR)
        assertEquals(Year.of(2025), restored.state.value.year)
    }

    @Test fun septemberHeadingToggleRemembersMonthAndDecemberNavigationMovesYear() = runTest {
        val septemberClock = Clock.fixed(Instant.parse("2026-09-15T12:00:00Z"), ZoneOffset.UTC)
        val vm = SummaryViewModel(FakeLedger(SyncObservation(emptyList(), SyncState.SYNCED)), FakeTaxonomy(), septemberClock, SavedStateHandle())
        vm.selectPeriodMode(SummaryPeriodMode.YEAR)
        assertEquals(Year.of(2026), vm.state.value.year)
        vm.selectPeriodMode(SummaryPeriodMode.MONTH)
        assertEquals(YearMonth.of(2026, 9), vm.state.value.month)
        repeat(4) { vm.nextMonth() }
        assertEquals(YearMonth.of(2027, 1), vm.state.value.month)
        vm.selectPeriodMode(SummaryPeriodMode.YEAR)
        assertEquals(Year.of(2027), vm.state.value.year)
        vm.nextYear()
        vm.selectPeriodMode(SummaryPeriodMode.MONTH)
        assertEquals(YearMonth.of(2028, 1), vm.state.value.month)
    }

    @Test fun leapFebruaryStaysFebruaryWhenYearNavigationMovesToNonLeapYear() = runTest {
        val leapClock = Clock.fixed(Instant.parse("2028-02-15T12:00:00Z"), ZoneOffset.UTC)
        val ledger = FakeLedger(SyncObservation(listOf(
            entry("leap", 100, LocalDate.of(2028, 2, 29)),
            entry("non-leap", 200, LocalDate.of(2027, 2, 28)),
            entry("march", 300, LocalDate.of(2027, 3, 1)),
        ), SyncState.SYNCED))
        val vm = SummaryViewModel(ledger, FakeTaxonomy(), leapClock, SavedStateHandle())
        vm.start("home")
        advanceUntilIdle()
        assertEquals(listOf("leap"), vm.state.value.entries.map { it.entry.id })
        vm.selectPeriodMode(SummaryPeriodMode.YEAR)
        vm.previousYear()
        assertEquals(500.toBigInteger(), vm.state.value.totals.incomeGrosze)
        vm.selectPeriodMode(SummaryPeriodMode.MONTH)
        assertEquals(YearMonth.of(2027, 2), vm.state.value.month)
        assertEquals(listOf("non-leap"), vm.state.value.entries.map { it.entry.id })
        assertEquals(200.toBigInteger(), vm.state.value.totals.incomeGrosze)
    }

    @Test fun errorWithoutDataAndRetryRecoverToEmptyState() = runTest {
        val ledger = FakeLedger(SyncObservation(state = SyncState.ERROR, error = IllegalStateException("offline")))
        val vm = SummaryViewModel(ledger, FakeTaxonomy(), clock, SavedStateHandle())
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
        val vm = SummaryViewModel(ledger, taxonomy, clock, SavedStateHandle())
        vm.start("home")
        advanceUntilIdle()
        assertEquals(listOf("new", "car", "old"), vm.state.value.entries.map { it.entry.id })
        assertFalse(vm.state.value.hasActiveFilters)
        assertEquals(vm.state.value.totals, vm.state.value.filteredTotals)
        vm.selectCategory("food")
        assertTrue(vm.state.value.hasActiveFilters)
        assertEquals(100.toBigInteger(), vm.state.value.filteredTotals.incomeGrosze)
        assertEquals(200.toBigInteger(), vm.state.value.filteredTotals.expenseGrosze)
        assertEquals((-100).toBigInteger(), vm.state.value.filteredTotals.netGrosze)
        assertEquals(listOf("cafe", "shop"), vm.state.value.subcategories.map { it.id })
        vm.selectSubcategory("shop")
        vm.selectTag(" PILNE ")
        assertEquals(listOf("old"), vm.state.value.entries.map { it.entry.id })
        assertEquals(3, vm.state.value.totals.entryCount)
        assertEquals(1, vm.state.value.filteredTotals.entryCount)
        assertEquals(100.toBigInteger(), vm.state.value.filteredTotals.incomeGrosze)
        assertEquals(0.toBigInteger(), vm.state.value.filteredTotals.expenseGrosze)
        assertEquals(100.toBigInteger(), vm.state.value.filteredTotals.netGrosze)
        vm.selectCategory("car")
        assertEquals(null, vm.state.value.selectedSubcategoryId)
        assertEquals(listOf("fuel"), vm.state.value.subcategories.map { it.id })
        vm.selectSubcategory("shop")
        assertEquals(null, vm.state.value.selectedSubcategoryId)
        assertTrue(vm.state.value.entries.isEmpty())
        assertEquals(0, vm.state.value.filteredTotals.entryCount)
        assertEquals(0.toBigInteger(), vm.state.value.filteredTotals.netGrosze)
        vm.selectSort(SummaryEntrySort.AMOUNT)
        vm.toggleSortDirection()
        assertEquals(0.toBigInteger(), vm.state.value.filteredTotals.netGrosze)
        vm.clearControls()
        assertFalse(vm.state.value.hasActiveFilters)
        assertEquals(SummaryEntrySort.DATE, vm.state.value.sort)
        assertEquals(SummarySortDirection.DESCENDING, vm.state.value.direction)
        assertEquals(null, vm.state.value.selectedCategoryId)
        assertEquals(null, vm.state.value.selectedTag)
        assertEquals(listOf("new", "car", "old"), vm.state.value.entries.map { it.entry.id })
        assertEquals(vm.state.value.totals, vm.state.value.filteredTotals)
    }

    @Test fun filteredTotalsFollowMonthlyAndYearlyPeriodsWithoutChangingPeriodTotals() = runTest {
        val leapYearClock = Clock.fixed(Instant.parse("2028-01-15T12:00:00Z"), ZoneOffset.UTC)
        val ledger = FakeLedger(SyncObservation(listOf(
            entry("jan", 10_000, LocalDate.of(2028, 1, 1)).copy(tags = listOf("shared")),
            entry("feb", -4_000, LocalDate.of(2028, 2, 29)).copy(tags = listOf("shared")),
            entry("other", -2_000, LocalDate.of(2028, 12, 31)),
            entry("deleted", 9_000, LocalDate.of(2028, 1, 10)).copy(
                tags = listOf("shared"), deleted = true, deletedById = "anna",
            ),
            entry("previous", 5_000, LocalDate.of(2027, 12, 31)).copy(tags = listOf("shared")),
        ), SyncState.SYNCED))
        val vm = SummaryViewModel(ledger, FakeTaxonomy(), leapYearClock, SavedStateHandle())
        vm.start("home")
        advanceUntilIdle()

        vm.selectTag("shared")
        assertEquals(10_000.toBigInteger(), vm.state.value.filteredTotals.incomeGrosze)
        assertEquals(0.toBigInteger(), vm.state.value.filteredTotals.expenseGrosze)
        vm.selectPeriodMode(SummaryPeriodMode.YEAR)
        assertEquals(10_000.toBigInteger(), vm.state.value.totals.incomeGrosze)
        assertEquals(6_000.toBigInteger(), vm.state.value.totals.expenseGrosze)
        assertEquals(10_000.toBigInteger(), vm.state.value.filteredTotals.incomeGrosze)
        assertEquals(4_000.toBigInteger(), vm.state.value.filteredTotals.expenseGrosze)
        assertEquals(6_000.toBigInteger(), vm.state.value.filteredTotals.netGrosze)
        vm.selectSort(SummaryEntrySort.AMOUNT)
        vm.toggleSortDirection()
        assertEquals(6_000.toBigInteger(), vm.state.value.filteredTotals.netGrosze)
        vm.previousYear()
        assertEquals(5_000.toBigInteger(), vm.state.value.filteredTotals.incomeGrosze)
        vm.nextYear()
        vm.selectPeriodMode(SummaryPeriodMode.MONTH)
        vm.nextMonth()
        assertEquals(0.toBigInteger(), vm.state.value.filteredTotals.incomeGrosze)
        assertEquals(4_000.toBigInteger(), vm.state.value.filteredTotals.expenseGrosze)
        assertEquals((-4_000).toBigInteger(), vm.state.value.filteredTotals.netGrosze)
    }

    @Test fun deletedAndOutOfPeriodRowsAreAbsentFromTotalsAndList() = runTest {
        val ledger = FakeLedger(SyncObservation(listOf(
            entry("kept", 100, LocalDate.of(2026, 1, 31)),
            entry("deleted", 200, LocalDate.of(2026, 1, 15)).copy(deleted = true, deletedById = "anna"),
            entry("feb", 300, LocalDate.of(2026, 2, 1)),
        ), SyncState.SYNCED))
        val vm = SummaryViewModel(ledger, FakeTaxonomy(), clock, SavedStateHandle())
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
