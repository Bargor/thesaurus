package pl.bargor.thesaurus.ui.reports

import androidx.lifecycle.SavedStateHandle
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
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import pl.bargor.thesaurus.data.firebase.LedgerRepository
import pl.bargor.thesaurus.data.firebase.TaxonomyRepository
import pl.bargor.thesaurus.data.model.Category
import pl.bargor.thesaurus.data.model.EntryType
import pl.bargor.thesaurus.data.model.LedgerEntry
import pl.bargor.thesaurus.data.model.Household
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
        val vm = ReportsViewModel(ledger, taxonomy, clock, SavedStateHandle(), ReportHouseholds())
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
        assertTrue(vm.state.value.customDateError)
        vm.updateCustomTo("2026-02-15")
        vm.applyCustomPeriod()
        assertFalse(vm.state.value.customDateError)
        assertEquals(LocalDate.of(2026, 2, 15), vm.state.value.period().to)
        assertEquals("rose", vm.state.value.entries.single().categoryColor)
        vm.selectType(ReportTypeFilter.EXPENSE)
        assertEquals(500.toBigInteger(), vm.state.value.aggregation.trend.single().amountGrosze)
        assertEquals((-500).toBigInteger(), vm.state.value.aggregation.totals.netGrosze)
    }

    @Test fun nextPeriodDoesNotPassCurrentMonthOrYearAndOfflineDataStaysVisible() = runTest {
        val ledger = FakeLedger(listOf(entry("current", 400, LocalDate.of(2026, 2, 1))), SyncState.OFFLINE)
        val vm = ReportsViewModel(ledger, FakeTaxonomy(), clock, SavedStateHandle(), ReportHouseholds())
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

    @Test fun openingSettingsUpdateAbsoluteChartButLeavePeriodTotalsAndEntriesUnchanged() = runTest {
        val ledger = FakeLedger(listOf(entry("history", 1_000, LocalDate.of(2001, 1, 1)),
            entry("income", 600, LocalDate.of(2026, 2, 1)), entry("expense", -200, LocalDate.of(2026, 2, 2)),
            entry("future", 99_999, LocalDate.of(2099, 12, 31))))
        val households = ReportHouseholds()
        households.settings.value = SyncObservation(Household("home", "Dom", "actor", openingBalanceGrosze = 25_000), SyncState.OFFLINE)
        val vm = ReportsViewModel(ledger, FakeTaxonomy(), clock, SavedStateHandle(), households)
        vm.start("home"); advanceUntilIdle()
        val totals = vm.state.value.aggregation.totals
        val entries = vm.state.value.entries
        assertEquals(400.toBigInteger(), totals.netGrosze)
        assertEquals(26_000.toBigInteger(), vm.state.value.balanceTrend!!.startBalanceGrosze)
        assertEquals(26_400.toBigInteger(), vm.state.value.balanceTrend!!.endBalanceGrosze)
        households.settings.value = SyncObservation(Household("home", "Dom", "actor", openingBalanceGrosze = 30_000), SyncState.PENDING)
        advanceUntilIdle()
        assertEquals(31_400.toBigInteger(), vm.state.value.balanceTrend!!.endBalanceGrosze)
        assertEquals(SyncState.PENDING, vm.state.value.syncState)
        assertEquals(totals, vm.state.value.aggregation.totals); assertEquals(entries, vm.state.value.entries)
        households.settings.value = SyncObservation(state = SyncState.OFFLINE); advanceUntilIdle()
        assertEquals(31_400.toBigInteger(), vm.state.value.balanceTrend!!.endBalanceGrosze)
        households.settings.value = SyncObservation(state = SyncState.ERROR, error = IllegalStateException("failure"))
        advanceUntilIdle()
        assertNull(vm.state.value.balanceTrend); assertTrue(vm.state.value.hasError)
        assertEquals(totals, vm.state.value.aggregation.totals); assertEquals(entries, vm.state.value.entries)
        households.settings.value = SyncObservation(state = SyncState.OFFLINE); advanceUntilIdle()
        assertNull(vm.state.value.balanceTrend)
        households.settings.value = SyncObservation(Household("home", "Dom", "actor", openingBalanceGrosze = -5_000), SyncState.SYNCED)
        advanceUntilIdle()
        assertEquals((-3_600).toBigInteger(), vm.state.value.balanceTrend!!.endBalanceGrosze)
        assertFalse(vm.state.value.hasError)
        assertEquals(totals, vm.state.value.aggregation.totals); assertEquals(entries, vm.state.value.entries)
    }

    private fun entry(id: String, amount: Long, date: LocalDate) = LedgerEntry(id, "home", amount, date, categoryId = "food", authorId = "anna", updatedById = "anna")

    private class FakeLedger(entries: List<LedgerEntry>, state: SyncState = SyncState.SYNCED) : LedgerRepository {
        private val data = MutableStateFlow(SyncObservation(entries, state))
        override fun observeEntries(householdId: String, includeDeleted: Boolean): Flow<SyncObservation<List<LedgerEntry>>> = data
        override suspend fun save(entry: LedgerEntry) = Unit
        override suspend fun tombstone(householdId: String, entryId: String, actorId: String) = Unit
    }

    private class FakeTaxonomy : TaxonomyRepository {
        private val categories = MutableStateFlow(SyncObservation(listOf(Category("food", "home", "Jedzenie", color = "rose", defaultEntryType = EntryType.EXPENSE, authorId = "anna", updatedById = "anna")), SyncState.SYNCED))
        override fun observeCategories(householdId: String): Flow<SyncObservation<List<Category>>> = categories
        override fun observeSubcategories(householdId: String, categoryId: String): Flow<SyncObservation<List<Subcategory>>> = MutableStateFlow(SyncObservation(emptyList(), SyncState.SYNCED))
        override suspend fun save(category: Category) = Unit
        override suspend fun save(subcategory: Subcategory) = Unit
    }
}
