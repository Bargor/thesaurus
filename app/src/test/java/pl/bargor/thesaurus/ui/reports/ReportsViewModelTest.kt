package pl.bargor.thesaurus.ui.reports

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
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import pl.bargor.thesaurus.data.firebase.LedgerRepository
import pl.bargor.thesaurus.data.firebase.TaxonomyRepository
import pl.bargor.thesaurus.data.model.Category
import pl.bargor.thesaurus.data.model.EntryType
import pl.bargor.thesaurus.data.model.LedgerEntry
import pl.bargor.thesaurus.data.model.ReportTypeFilter
import pl.bargor.thesaurus.data.model.Subcategory
import pl.bargor.thesaurus.data.model.SyncObservation
import pl.bargor.thesaurus.data.model.SyncState

@OptIn(ExperimentalCoroutinesApi::class)
class ReportsViewModelTest {
    private val dispatcher = StandardTestDispatcher()
    private val clock = Clock.fixed(Instant.parse("2026-02-15T12:00:00Z"), ZoneOffset.UTC)

    @Before fun setUp() = Dispatchers.setMain(dispatcher)
    @After fun tearDown() = Dispatchers.resetMain()

    @Test fun customRangeRejectsReversedDatesCapsFutureAndExpenseTrendUsesMagnitude() = runTest {
        val ledger = FakeLedger(listOf(entry("expense", -500, LocalDate.of(2026, 2, 15))))
        val taxonomy = FakeTaxonomy()
        val vm = ReportsViewModel(ledger, taxonomy, clock)
        vm.start("home")
        advanceUntilIdle()
        vm.selectPeriodMode(ReportPeriodMode.CUSTOM)
        vm.updateCustomFrom("2026-02-14")
        vm.updateCustomTo("2026-02-13")
        vm.applyCustomPeriod()
        assertTrue(vm.state.value.customDateError)
        vm.updateCustomFrom("2026-02-01")
        vm.updateCustomTo("2027-01-01")
        vm.applyCustomPeriod()
        assertFalse(vm.state.value.customDateError)
        assertEquals(LocalDate.of(2026, 2, 15), vm.state.value.period().to)
        vm.selectType(ReportTypeFilter.EXPENSE)
        assertEquals(500.toBigInteger(), vm.state.value.aggregation.trend.single().amountGrosze)
        assertEquals((-500).toBigInteger(), vm.state.value.aggregation.totals.netGrosze)
    }

    @Test fun nextPeriodDoesNotPassCurrentMonthOrYearAndOfflineDataStaysVisible() = runTest {
        val ledger = FakeLedger(listOf(entry("current", 400, LocalDate.of(2026, 2, 1))), SyncState.OFFLINE)
        val vm = ReportsViewModel(ledger, FakeTaxonomy(), clock)
        vm.start("home")
        advanceUntilIdle()
        vm.nextPeriod()
        assertEquals("2026-02", vm.state.value.month.toString())
        vm.selectPeriodMode(ReportPeriodMode.YEAR)
        vm.nextPeriod()
        assertEquals("2026", vm.state.value.year.toString())
        assertEquals(SyncState.OFFLINE, vm.state.value.syncState)
        assertEquals(400.toBigInteger(), vm.state.value.aggregation.totals.incomeGrosze)
    }

    private fun entry(id: String, amount: Long, date: LocalDate) = LedgerEntry(id, "home", amount, date, categoryId = "food", authorId = "anna", updatedById = "anna")

    private class FakeLedger(entries: List<LedgerEntry>, state: SyncState = SyncState.SYNCED) : LedgerRepository {
        private val data = MutableStateFlow(SyncObservation(entries, state))
        override fun observeEntries(householdId: String, includeDeleted: Boolean): Flow<SyncObservation<List<LedgerEntry>>> = data
        override suspend fun save(entry: LedgerEntry) = Unit
        override suspend fun tombstone(householdId: String, entryId: String, actorId: String) = Unit
    }

    private class FakeTaxonomy : TaxonomyRepository {
        private val categories = MutableStateFlow(SyncObservation(listOf(Category("food", "home", "Jedzenie", defaultEntryType = EntryType.EXPENSE, authorId = "anna", updatedById = "anna")), SyncState.SYNCED))
        override fun observeCategories(householdId: String): Flow<SyncObservation<List<Category>>> = categories
        override fun observeSubcategories(householdId: String, categoryId: String): Flow<SyncObservation<List<Subcategory>>> = MutableStateFlow(SyncObservation(emptyList(), SyncState.SYNCED))
        override suspend fun save(category: Category) = Unit
        override suspend fun save(subcategory: Subcategory) = Unit
    }
}
