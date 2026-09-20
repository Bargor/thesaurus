package pl.bargor.thesaurus.ui.summary

import java.time.Clock
import java.time.Instant
import java.time.LocalDate
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
import pl.bargor.thesaurus.data.model.LedgerEntry
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
        val vm = SummaryViewModel(ledger, clock)
        assertEquals(YearMonth.of(2026, 1), vm.state.value.month)
        vm.start("home")
        advanceUntilIdle()
        assertTrue(vm.state.value.totals.isEmpty)
        vm.previousMonth()
        assertEquals(YearMonth.of(2025, 12), vm.state.value.month)
        assertEquals(1_000.toBigInteger(), vm.state.value.totals.incomeGrosze)
        vm.nextMonth()
        assertTrue(vm.state.value.totals.isEmpty)
    }

    @Test fun pendingLocalWritesRecalculateTotalsAndOfflineKeepsCachedValues() = runTest {
        val ledger = FakeLedger(SyncObservation(emptyList(), SyncState.SYNCED))
        val vm = SummaryViewModel(ledger, clock)
        vm.start("home")
        advanceUntilIdle()
        assertFalse(vm.state.value.isLoading)
        ledger.observations.value = SyncObservation(listOf(entry("local", -1_250, LocalDate.of(2026, 1, 15))), SyncState.PENDING)
        advanceUntilIdle()
        assertEquals(SyncState.PENDING, vm.state.value.syncState)
        assertEquals(1_250.toBigInteger(), vm.state.value.totals.expenseGrosze)
        assertEquals((-1_250).toBigInteger(), vm.state.value.totals.netGrosze)
        ledger.observations.value = SyncObservation(ledger.observations.value.value, SyncState.OFFLINE)
        advanceUntilIdle()
        assertEquals(SyncState.OFFLINE, vm.state.value.syncState)
        assertEquals(1_250.toBigInteger(), vm.state.value.totals.expenseGrosze)
    }

    @Test fun errorWithoutDataAndRetryRecoverToEmptyState() = runTest {
        val ledger = FakeLedger(SyncObservation(state = SyncState.ERROR, error = IllegalStateException("offline")))
        val vm = SummaryViewModel(ledger, clock)
        vm.start("home")
        advanceUntilIdle()
        assertTrue(vm.state.value.hasError)
        assertFalse(vm.state.value.isLoading)
        ledger.observations.value = SyncObservation(emptyList(), SyncState.SYNCED)
        vm.retry()
        advanceUntilIdle()
        assertFalse(vm.state.value.hasError)
        assertTrue(vm.state.value.totals.isEmpty)
    }

    private fun entry(id: String, amount: Long, date: LocalDate) = LedgerEntry(
        id = id, householdId = "home", amountGrosze = amount, date = date,
        categoryId = "category", authorId = "anna", updatedById = "anna",
    )

    private class FakeLedger(initial: SyncObservation<List<LedgerEntry>>) : LedgerRepository {
        val observations = MutableStateFlow(initial)
        override fun observeEntries(householdId: String, includeDeleted: Boolean): Flow<SyncObservation<List<LedgerEntry>>> = observations
        override suspend fun save(entry: LedgerEntry) = Unit
        override suspend fun tombstone(householdId: String, entryId: String, actorId: String) = Unit
    }
}
