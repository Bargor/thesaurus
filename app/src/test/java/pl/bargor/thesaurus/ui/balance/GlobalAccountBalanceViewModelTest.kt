package pl.bargor.thesaurus.ui.balance

import java.math.BigInteger
import java.time.LocalDate
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.flow
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
import pl.bargor.thesaurus.data.firebase.HouseholdRepository
import pl.bargor.thesaurus.data.model.Household
import pl.bargor.thesaurus.data.model.Member
import pl.bargor.thesaurus.data.model.LedgerEntry
import pl.bargor.thesaurus.data.model.SyncObservation
import pl.bargor.thesaurus.data.model.SyncState

@OptIn(ExperimentalCoroutinesApi::class)
class GlobalAccountBalanceViewModelTest {
    private val dispatcher = StandardTestDispatcher()
    @Before fun before() = Dispatchers.setMain(dispatcher)
    @After fun after() = Dispatchers.resetMain()

    @Test fun includesAllDatesCategoriesAuthorsAndSignsButExcludesDeletedAndForeignHouseholds() {
        val entries = listOf(entry("old", 900, "2001-01-01"), entry("future", -300, "2099-12-31").copy(categoryId = "other", authorId = "member"),
            entry("deleted", 50_000).copy(deleted = true, deletedById = "actor"), entry("foreign", 999).copy(householdId = "other"))
        assertEquals(600.toBigInteger(), globalAccountBalance(entries, "home"))
        assertEquals(BigInteger.ZERO, globalAccountBalance(emptyList(), "home"))
        assertEquals(999.toBigInteger(), globalAccountBalance(entries, "other"))
    }

    @Test fun aggregationCannotOverflowLongIncludingItsMinimumValue() {
        assertEquals(Long.MAX_VALUE.toBigInteger() * BigInteger.TWO,
            globalAccountBalance(listOf(entry("a", Long.MAX_VALUE), entry("b", Long.MAX_VALUE)), "home"))
        assertEquals(Long.MIN_VALUE.toBigInteger() * BigInteger.TWO,
            globalAccountBalance(listOf(entry("a", Long.MIN_VALUE), entry("b", Long.MIN_VALUE)), "home"))
        assertEquals((-1).toBigInteger(), globalAccountBalance(listOf(entry("a", Long.MAX_VALUE), entry("b", Long.MIN_VALUE)), "home"))
    }

    @Test fun loadingAndFirstReadFailureDoNotInventZeroThenRealEmptySnapshotPublishesZero() = runTest {
        val ledger = FakeLedger(); val vm = GlobalAccountBalanceViewModel(ledger, ledger)
        vm.start("home", "actor"); advanceUntilIdle()
        assertTrue(vm.state.value.isLoading); assertNull(vm.state.value.amountGrosze)
        ledger.home.value = SyncObservation(state = SyncState.ERROR, error = IllegalStateException("unavailable"))
        advanceUntilIdle(); assertTrue(vm.state.value.hasError); assertFalse(vm.state.value.isLoading); assertNull(vm.state.value.amountGrosze)
        ledger.home.value = SyncObservation(emptyList(), SyncState.SYNCED)
        advanceUntilIdle(); assertEquals(BigInteger.ZERO, vm.state.value.amountGrosze); assertFalse(vm.state.value.hasError)
    }

    @Test fun offlinePendingAddEditTombstoneAndRestorationReplaceTheWholeSnapshot() = runTest {
        val ledger = FakeLedger(); val vm = GlobalAccountBalanceViewModel(ledger, ledger)
        vm.start("home", "actor")
        ledger.home.value = SyncObservation(listOf(entry("salary", 1000), entry("bill", -300)), SyncState.OFFLINE)
        advanceUntilIdle(); assertEquals(700.toBigInteger(), vm.state.value.amountGrosze); assertEquals(SyncState.OFFLINE, vm.state.value.syncState)
        ledger.home.value = SyncObservation(listOf(entry("salary", 1000), entry("bill", -450), entry("local", -50)), SyncState.PENDING)
        advanceUntilIdle(); assertEquals(500.toBigInteger(), vm.state.value.amountGrosze); assertEquals(SyncState.PENDING, vm.state.value.syncState)
        ledger.home.value = SyncObservation(listOf(entry("salary", 1000), entry("bill", -450).copy(deleted = true, deletedById = "actor"), entry("local", -50)), SyncState.PENDING)
        advanceUntilIdle(); assertEquals(950.toBigInteger(), vm.state.value.amountGrosze)
        ledger.home.value = SyncObservation(listOf(entry("salary", 1000), entry("bill", -450), entry("local", -50)), SyncState.SYNCED)
        advanceUntilIdle(); assertEquals(500.toBigInteger(), vm.state.value.amountGrosze); assertEquals(SyncState.SYNCED, vm.state.value.syncState)
    }

    @Test fun offlineMetadataRetainsKnownAmountButReadErrorClearsItUntilRecovery() = runTest {
        val ledger = FakeLedger(); val vm = GlobalAccountBalanceViewModel(ledger, ledger)
        vm.start("home", "actor"); ledger.home.value = SyncObservation(listOf(entry("cached", -123)), SyncState.SYNCED)
        advanceUntilIdle()
        ledger.home.value = SyncObservation(state = SyncState.OFFLINE); advanceUntilIdle()
        assertEquals((-123).toBigInteger(), vm.state.value.amountGrosze)
        ledger.home.value = SyncObservation(state = SyncState.ERROR, error = IllegalStateException("failure")); advanceUntilIdle()
        assertNull(vm.state.value.amountGrosze); assertTrue(vm.state.value.hasError)
        ledger.home.value = SyncObservation(listOf(entry("server", 456)), SyncState.SYNCED); advanceUntilIdle()
        assertEquals(456.toBigInteger(), vm.state.value.amountGrosze); assertFalse(vm.state.value.hasError)
    }

    @Test fun repeatedStartDoesNotCreateDuplicateObserversAndRetryReconnects() = runTest {
        val ledger = FakeLedger(); val vm = GlobalAccountBalanceViewModel(ledger, ledger)
        vm.start("home", "actor"); advanceUntilIdle(); vm.start("home", "actor"); advanceUntilIdle()
        assertEquals(1, ledger.subscriptions)
        ledger.home.value = SyncObservation(state = SyncState.ERROR, error = IllegalStateException("failure")); advanceUntilIdle()
        vm.retry(); advanceUntilIdle(); assertEquals(2, ledger.subscriptions); assertEquals(1, ledger.active)
        ledger.home.value = SyncObservation(listOf(entry("fresh", 42)), SyncState.SYNCED); advanceUntilIdle()
        assertEquals(42.toBigInteger(), vm.state.value.amountGrosze); assertFalse(vm.state.value.hasError)
    }

    @Test fun householdSwitchImmediatelyClearsAndOldHouseholdCannotRepopulateBalance() = runTest {
        val ledger = FakeLedger(); val vm = GlobalAccountBalanceViewModel(ledger, ledger)
        vm.start("home", "actor"); ledger.home.value = SyncObservation(listOf(entry("a", 123)), SyncState.SYNCED); advanceUntilIdle()
        vm.start("other", "actor"); assertNull(vm.state.value.amountGrosze); assertTrue(vm.state.value.isLoading)
        advanceUntilIdle(); ledger.home.value = SyncObservation(listOf(entry("late", 99999)), SyncState.SYNCED); advanceUntilIdle()
        assertNull(vm.state.value.amountGrosze)
        ledger.other.value = SyncObservation(listOf(entry("foreign", 99999), entry("own", -20).copy(householdId = "other")), SyncState.SYNCED)
        advanceUntilIdle(); assertEquals((-20).toBigInteger(), vm.state.value.amountGrosze); assertEquals(1, ledger.active)
    }

    @Test fun actorSwitchImmediatelyClearsEvenWithinTheSameHousehold() = runTest {
        val ledger = FakeLedger(); val vm = GlobalAccountBalanceViewModel(ledger, ledger)
        vm.start("home", "actor"); ledger.home.value = SyncObservation(listOf(entry("a", 123)), SyncState.SYNCED); advanceUntilIdle()
        vm.start("home", "member"); assertNull(vm.state.value.amountGrosze); assertEquals("member", vm.state.value.actorId)
        advanceUntilIdle(); assertEquals(2, ledger.subscriptions); assertEquals(1, ledger.active)
        assertEquals(123.toBigInteger(), vm.state.value.amountGrosze)
    }

    @Test fun recreatedViewModelLoadsPendingCacheWithoutDoubleCountingServerAcknowledgment() = runTest {
        val ledger = FakeLedger().also { it.home.value = SyncObservation(listOf(entry("queued", -80)), SyncState.PENDING) }
        val vm = GlobalAccountBalanceViewModel(ledger, ledger); vm.start("home", "actor"); advanceUntilIdle()
        assertEquals((-80).toBigInteger(), vm.state.value.amountGrosze)
        ledger.home.value = SyncObservation(listOf(entry("queued", -80)), SyncState.SYNCED); advanceUntilIdle()
        assertEquals((-80).toBigInteger(), vm.state.value.amountGrosze)
        val recreated = GlobalAccountBalanceViewModel(ledger, ledger); recreated.start("home", "actor"); advanceUntilIdle()
        assertEquals(vm.state.value.amountGrosze, recreated.state.value.amountGrosze)
    }

    @Test fun stopClearsImmediatelyCancelsObserverAndRestartLoadsOnlyFreshIdentity() = runTest {
        val ledger = FakeLedger(); val vm = GlobalAccountBalanceViewModel(ledger, ledger)
        vm.start("home", "actor"); ledger.home.value = SyncObservation(listOf(entry("a", 123)), SyncState.SYNCED); advanceUntilIdle()
        vm.stop(); assertNull(vm.state.value.amountGrosze); assertNull(vm.state.value.householdId); assertNull(vm.state.value.actorId)
        advanceUntilIdle(); assertEquals(0, ledger.active)
        ledger.home.value = SyncObservation(listOf(entry("late", 9999)), SyncState.SYNCED); advanceUntilIdle()
        assertNull(vm.state.value.amountGrosze)
        vm.start("other", "member"); ledger.other.value = SyncObservation(emptyList(), SyncState.SYNCED); advanceUntilIdle()
        assertEquals(BigInteger.ZERO, vm.state.value.amountGrosze); assertEquals(1, ledger.active)
    }

    @Test fun cachedPositiveOpeningIsAddedOnceAndItsPendingMetadataAffectsTheFooter() = runTest {
        val ledger = FakeLedger()
        ledger.settings.value = SyncObservation(Household("home", "Dom", "actor", openingBalanceGrosze = 20_000), SyncState.OFFLINE)
        ledger.home.value = SyncObservation(listOf(entry("old", 5_000, "2001-01-01"), entry("future", -1_250, "2099-12-31")), SyncState.OFFLINE)
        val vm = GlobalAccountBalanceViewModel(ledger, ledger)
        vm.start("home", "actor"); advanceUntilIdle()
        assertEquals(23_750.toBigInteger(), vm.state.value.amountGrosze)
        assertEquals(SyncState.OFFLINE, vm.state.value.syncState)
        ledger.settings.value = SyncObservation(Household("home", "Dom", "actor", openingBalanceGrosze = 30_000), SyncState.PENDING)
        advanceUntilIdle()
        assertEquals(33_750.toBigInteger(), vm.state.value.amountGrosze)
        assertEquals(SyncState.PENDING, vm.state.value.syncState)
        ledger.settings.value = ledger.settings.value.copy(state = SyncState.SYNCED)
        ledger.home.value = ledger.home.value.copy(state = SyncState.SYNCED)
        advanceUntilIdle()
        assertEquals(33_750.toBigInteger(), vm.state.value.amountGrosze)
        assertEquals(SyncState.SYNCED, vm.state.value.syncState)
    }

    @Test fun ledgerCannotPublishBeforeSettingsAndWrongHouseholdSettingsCannotLeak() = runTest {
        val ledger = FakeLedger()
        ledger.settings.value = SyncObservation(state = SyncState.OFFLINE)
        ledger.home.value = SyncObservation(listOf(entry("a", 700)), SyncState.SYNCED)
        val vm = GlobalAccountBalanceViewModel(ledger, ledger)
        vm.start("home", "actor"); advanceUntilIdle()
        assertNull(vm.state.value.amountGrosze); assertTrue(vm.state.value.isLoading)
        ledger.settings.value = SyncObservation(Household("other", "Inny", "actor", openingBalanceGrosze = 99_999), SyncState.SYNCED)
        advanceUntilIdle()
        assertNull(vm.state.value.amountGrosze); assertTrue(vm.state.value.hasError)
        ledger.settings.value = SyncObservation(Household("home", "Dom", "actor", openingBalanceGrosze = -1_000), SyncState.SYNCED)
        advanceUntilIdle()
        assertEquals((-300).toBigInteger(), vm.state.value.amountGrosze)
        assertFalse(vm.state.value.hasError)
    }

    @Test fun settingsReadErrorClearsAbsoluteBalanceUntilACompleteSettingsRecovery() = runTest {
        val ledger = FakeLedger()
        ledger.home.value = SyncObservation(emptyList(), SyncState.SYNCED)
        ledger.settings.value = SyncObservation(Household("home", "Dom", "actor", openingBalanceGrosze = 25_000), SyncState.SYNCED)
        val vm = GlobalAccountBalanceViewModel(ledger, ledger)
        vm.start("home", "actor"); advanceUntilIdle()
        assertEquals(25_000.toBigInteger(), vm.state.value.amountGrosze)
        ledger.settings.value = SyncObservation(state = SyncState.ERROR, error = IllegalStateException("failure"))
        advanceUntilIdle(); assertNull(vm.state.value.amountGrosze); assertTrue(vm.state.value.hasError)
        ledger.settings.value = SyncObservation(state = SyncState.OFFLINE)
        advanceUntilIdle(); assertNull(vm.state.value.amountGrosze)
        ledger.settings.value = SyncObservation(Household("home", "Dom", "actor", openingBalanceGrosze = 42), SyncState.SYNCED)
        advanceUntilIdle(); assertEquals(42.toBigInteger(), vm.state.value.amountGrosze)
    }

    private fun entry(id: String, amount: Long, date: String = "2026-09-15") = LedgerEntry(id, "home", amount, LocalDate.parse(date), categoryId = "food", authorId = "actor", updatedById = "actor")
    private class FakeLedger : LedgerRepository, HouseholdRepository {
        val settings = MutableStateFlow(SyncObservation(Household("home", "Dom", "actor"), SyncState.SYNCED))
        val otherSettings = MutableStateFlow(SyncObservation(Household("other", "Drugi", "actor"), SyncState.SYNCED))
        override fun observeHousehold(householdId: String): Flow<SyncObservation<Household>> =
            if (householdId == "home") settings else otherSettings
        override fun observeMembers(householdId: String): Flow<SyncObservation<List<Member>>> = error("Unused")
        override suspend fun saveHousehold(household: Household) = Unit
        override suspend fun removeMember(householdId: String, memberId: String) = Unit
        val home = MutableStateFlow(SyncObservation<List<LedgerEntry>>(state = SyncState.OFFLINE))
        val other = MutableStateFlow(SyncObservation<List<LedgerEntry>>(state = SyncState.OFFLINE))
        var subscriptions = 0
        var active = 0
        override fun observeEntries(householdId: String, includeDeleted: Boolean): Flow<SyncObservation<List<LedgerEntry>>> = flow {
            subscriptions++; active++
            try { (if (householdId == "home") home else other).collect { emit(it) } } finally { active-- }
        }
        override suspend fun save(entry: LedgerEntry) = Unit
        override suspend fun tombstone(householdId: String, entryId: String, actorId: String) = Unit
    }
}
