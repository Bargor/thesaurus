package pl.bargor.thesaurus.ui.summary

import androidx.lifecycle.SavedStateHandle
import java.time.*
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.test.*
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import pl.bargor.thesaurus.data.firebase.*
import pl.bargor.thesaurus.data.model.*

@OptIn(ExperimentalCoroutinesApi::class)
class SummaryViewModelTest {
    private val dispatcher = StandardTestDispatcher()
    private val clock = Clock.fixed(Instant.parse("2026-09-15T12:00:00Z"), ZoneOffset.UTC)
    @Before fun before() = Dispatchers.setMain(dispatcher)
    @After fun after() = Dispatchers.resetMain()
    @Test fun cappedYearsModeToggleAndFreshHandleKeepSelectedYear() = runTest {
        val saved = SavedStateHandle()
        val vm = vm(saved = saved)
        vm.nextYear(); assertEquals(Year.of(2026), vm.state.value.year)
        vm.previousYear(); vm.previousYear(); vm.selectPeriodMode(SummaryPeriodMode.YEAR)
        vm.selectPeriodMode(SummaryPeriodMode.MONTH)
        assertEquals(Year.of(2024), vm.state.value.year)
        val restored = vm(saved = SavedStateHandle(saved.keys().associateWith { saved.get<Any?>(it) }))
        assertEquals(vm.state.value.mode, restored.state.value.mode)
        assertEquals(Year.of(2024), restored.state.value.year)
    }
    @Test fun invalidFutureLegacyStateAndClockZoneAreHandled() = runTest {
        for (saved in listOf(mapOf("summary.year" to 2030, "summary.month" to 9), mapOf("summary.month" to 99, "summary.mode" to "BOGUS"))) {
            val state = vm(saved = SavedStateHandle(saved)).state.value
            assertEquals(Year.of(2026), state.year)
            assertEquals(SummaryPeriodMode.MONTH, state.mode)
        }
        val zoned = Clock.fixed(Instant.parse("2026-12-31T23:30:00Z"), ZoneId.of("Europe/Warsaw"))
        val ledger = FakeLedger(listOf(entry("today", -100, "2027-01-01"), entry("tomorrow", -999, "2027-01-02")))
        val vm = SummaryViewModel(ledger, FakeTaxonomy(), FakeHousehold(), zoned, SavedStateHandle())
        vm.start("home", "actor"); advanceUntilIdle()
        assertEquals(Year.of(2027), vm.state.value.year)
        assertEquals(listOf("today"), vm.state.value.cards.single().entries.map { it.id })
    }
    @Test fun exactDetailUpdatesWithPendingWritesRenameAndTombstoneThenCloses() = runTest {
        val ledger = FakeLedger(listOf(entry("expense", -100), entry("income", 400), entry("august", -50, "2026-08-31"), entry("future", -999, "2026-09-16")))
        val taxonomy = FakeTaxonomy()
        val vm = vm(ledger, taxonomy)
        vm.start("home", "actor"); advanceUntilIdle()
        val card = vm.state.value.cards.last(); vm.openPeriod(card.key)
        assertEquals(setOf("expense", "income"), vm.state.value.detailEntries.map { it.entry.id }.toSet())
        assertEquals(card.totals, vm.state.value.detailCard!!.totals)
        ledger.home.value = SyncObservation(listOf(entry("expense", -300), entry("income", 400), entry("local", -20)), SyncState.PENDING)
        taxonomy.categories.value = SyncObservation(listOf(category().copy(name = "Żywność", color = "blue")), SyncState.PENDING)
        advanceUntilIdle()
        assertEquals(80.toBigInteger(), vm.state.value.detailCard!!.totals.netGrosze)
        assertEquals(SyncState.PENDING, vm.state.value.syncState)
        assertTrue(vm.state.value.detailEntries.all { it.categoryName == "Żywność" && it.categoryColor == "blue" })
        ledger.home.value = SyncObservation(listOf(entry("expense", -300).copy(deleted = true, deletedById = "actor"), entry("income", 400)), SyncState.SYNCED)
        advanceUntilIdle()
        assertEquals(listOf("income"), vm.state.value.detailEntries.map { it.entry.id })
        assertEquals(vm.state.value.cards.last().totals, vm.state.value.detailCard!!.totals)
        vm.closePeriod(); assertNull(vm.state.value.detailCard)
        assertTrue(vm.state.value.detailEntries.isEmpty()); assertEquals(Year.of(2026), vm.state.value.year)
    }
    @Test fun cacheAndDetailSurviveErrorAndRetryWithoutDuplicateStartObservers() = runTest {
        val ledger = FakeLedger(listOf(entry("cached", -100)))
        val taxonomy = FakeTaxonomy(); val vm = vm(ledger, taxonomy)
        vm.start("home", "actor"); advanceUntilIdle()
        vm.openPeriod(vm.state.value.cards.last().key)
        vm.start("home", "actor"); advanceUntilIdle(); assertEquals(1, ledger.subscriptions)
        ledger.home.value = SyncObservation(state = SyncState.OFFLINE)
        taxonomy.categories.value = SyncObservation(state = SyncState.ERROR, error = IllegalStateException("unavailable"))
        advanceUntilIdle(); assertTrue(vm.state.value.hasError); assertFalse(vm.state.value.isLoading)
        assertEquals(listOf("cached"), vm.state.value.detailEntries.map { it.entry.id })
        vm.retry(); advanceUntilIdle(); assertTrue(ledger.subscriptions >= 2)
        ledger.home.value = SyncObservation(emptyList(), SyncState.SYNCED)
        taxonomy.categories.value = SyncObservation(listOf(category()), SyncState.SYNCED)
        advanceUntilIdle(); assertFalse(vm.state.value.hasError)
        assertTrue(vm.state.value.detailEntries.isEmpty())
        assertEquals(0.toBigInteger(), vm.state.value.cards.last().totals.netGrosze)
    }
    @Test fun identityResetClearsDetailAndRecalculatesOwnerAuthorPermissions() = runTest {
        val ledger = FakeLedger(listOf(entry("owner", -100), entry("member", -50).copy(authorId = "member", updatedById = "member")))
        val vm = vm(ledger); vm.start("home", "actor"); advanceUntilIdle()
        vm.openPeriod(vm.state.value.cards.last().key); assertTrue(vm.state.value.detailEntries.all { it.canManage })
        vm.start("home", "member"); assertNull(vm.state.value.detailCard); assertTrue(vm.state.value.detailEntries.isEmpty())
        advanceUntilIdle(); vm.openPeriod(vm.state.value.cards.last().key)
        assertFalse(vm.state.value.detailEntries.single { it.entry.id == "owner" }.canManage)
        assertTrue(vm.state.value.detailEntries.single { it.entry.id == "member" }.canManage)
        assertEquals("Anna", vm.state.value.detailEntries.single { it.entry.id == "owner" }.authorName)
        vm.start("other", "member"); assertTrue(vm.state.value.cards.isEmpty()); advanceUntilIdle()
        ledger.home.value = SyncObservation(listOf(entry("old-household", 999)), SyncState.SYNCED)
        advanceUntilIdle(); assertTrue(vm.state.value.cards.flatMap { it.entries }.isEmpty())
    }
    @Test fun freshErrorIsDistinctFromEmptyAndAnnualLastEntryDeletionDismissesDetail() = runTest {
        val ledger = FakeLedger(emptyList())
        ledger.home.value = SyncObservation(state = SyncState.ERROR, error = IllegalStateException("failed"))
        val vm = vm(ledger); vm.start("home", "actor"); advanceUntilIdle()
        assertTrue(vm.state.value.hasError); assertFalse(vm.state.value.isLoading)
        assertTrue(vm.state.value.cards.isEmpty()); assertNull(vm.state.value.detailCard)
        ledger.home.value = SyncObservation(listOf(entry("old", -100, "2023-12-31")), SyncState.SYNCED)
        advanceUntilIdle(); vm.selectPeriodMode(SummaryPeriodMode.YEAR)
        vm.openPeriod(vm.state.value.cards.single().key)
        assertEquals(2023, vm.state.value.detailCard!!.key.year)
        ledger.home.value = SyncObservation(emptyList(), SyncState.SYNCED)
        advanceUntilIdle(); assertNull(vm.state.value.detailCard); assertTrue(vm.state.value.cards.isEmpty())
        assertFalse(vm.state.value.hasError)
    }
    @Test fun metadataBeforeFirstLedgerValueCannotPublishZeroFinancialCardsAndLaterNullErrorRetainsCache() = runTest {
        val source = MutableSharedFlow<SyncObservation<List<LedgerEntry>>>()
        val ledger = FakeLedger(emptyList()).also { it.homeSource = source }
        val taxonomy = FakeTaxonomy()
        val vm = vm(ledger, taxonomy)
        vm.start("home", "actor"); advanceUntilIdle()
        taxonomy.categories.value = SyncObservation(listOf(category().copy(name = "Żywność")), SyncState.PENDING)
        advanceUntilIdle()
        assertTrue(vm.state.value.isLoading)
        assertTrue(vm.state.value.cards.isEmpty()); assertNull(vm.state.value.detailCard)
        source.emit(SyncObservation(state = SyncState.ERROR, error = IllegalStateException("first read failed")))
        advanceUntilIdle()
        assertFalse(vm.state.value.isLoading); assertTrue(vm.state.value.hasError)
        assertTrue(vm.state.value.cards.isEmpty()); assertTrue(vm.state.value.detailEntries.isEmpty())
        taxonomy.categories.value = SyncObservation(listOf(category()), SyncState.SYNCED)
        source.emit(SyncObservation(listOf(entry("cached", -250)), SyncState.SYNCED))
        advanceUntilIdle(); vm.openPeriod(vm.state.value.cards.last().key)
        val cards = vm.state.value.cards
        val detail = vm.state.value.detailCard
        source.emit(SyncObservation(state = SyncState.ERROR, error = IllegalStateException("later read failed")))
        advanceUntilIdle()
        assertTrue(vm.state.value.hasError); assertFalse(vm.state.value.isLoading)
        assertEquals(cards, vm.state.value.cards); assertEquals(detail, vm.state.value.detailCard)
        assertEquals(listOf("cached"), vm.state.value.detailEntries.map { it.entry.id })
    }
    @Test fun retryPrunesErroredSubcategoryObservationAfterItsCategoryAndEntriesDisappear() = runTest {
        val ledger = FakeLedger(listOf(entry("old", -100).copy(subcategoryId = "shop")))
        val taxonomy = FakeTaxonomy()
        taxonomy.subcategories["food"] = MutableStateFlow(SyncObservation(state = SyncState.ERROR, error = IllegalStateException("subcategory failed")))
        val vm = vm(ledger, taxonomy)
        vm.start("home", "actor"); advanceUntilIdle()
        assertTrue(vm.state.value.hasError)
        // Retry clears jobs before fresh snapshots remove the obsolete parent.
        ledger.home.value = SyncObservation(emptyList(), SyncState.SYNCED)
        taxonomy.categories.value = SyncObservation(emptyList(), SyncState.SYNCED)
        vm.retry(); advanceUntilIdle()
        assertFalse(vm.state.value.hasError)
        assertEquals(SyncState.SYNCED, vm.state.value.syncState)
        assertTrue(vm.state.value.cards.all { it.entries.isEmpty() })
    }
    private fun vm(ledger: FakeLedger = FakeLedger(emptyList()), taxonomy: FakeTaxonomy = FakeTaxonomy(), saved: SavedStateHandle = SavedStateHandle()) = SummaryViewModel(ledger, taxonomy, FakeHousehold(), clock, saved)
    private fun entry(id: String, amount: Long, date: String = "2026-09-12") = LedgerEntry(id, "home", amount, LocalDate.parse(date), categoryId = "food", authorId = "actor", updatedById = "actor")
    private fun category() = Category("food", "home", "Jedzenie", color = "rose", authorId = "actor", updatedById = "actor")
    private class FakeLedger(entries: List<LedgerEntry>) : LedgerRepository {
        val home = MutableStateFlow(SyncObservation(entries, SyncState.SYNCED))
        val other = MutableStateFlow(SyncObservation<List<LedgerEntry>>(state = SyncState.OFFLINE))
        var homeSource: Flow<SyncObservation<List<LedgerEntry>>> = home
        var subscriptions = 0
        override fun observeEntries(householdId: String, includeDeleted: Boolean): Flow<SyncObservation<List<LedgerEntry>>> { subscriptions++; return if (householdId == "home") homeSource else other }
        override suspend fun save(entry: LedgerEntry) = Unit
        override suspend fun tombstone(householdId: String, entryId: String, actorId: String) = Unit
    }
    private inner class FakeTaxonomy : TaxonomyRepository {
        val categories = MutableStateFlow(SyncObservation(listOf(category()), SyncState.SYNCED))
        val subcategories = mutableMapOf<String, MutableStateFlow<SyncObservation<List<Subcategory>>>>()
        override fun observeCategories(householdId: String) = categories
        override fun observeSubcategories(householdId: String, categoryId: String): Flow<SyncObservation<List<Subcategory>>> = subcategories.getOrPut(categoryId) { MutableStateFlow(SyncObservation(emptyList(), SyncState.SYNCED)) }
        override suspend fun save(category: Category) = Unit
        override suspend fun save(subcategory: Subcategory) = Unit
    }
    private class FakeHousehold : HouseholdRepository {
        override fun observeHousehold(householdId: String): Flow<SyncObservation<Household>> = flowOf(SyncObservation(state = SyncState.SYNCED))
        override fun observeMembers(householdId: String): Flow<SyncObservation<List<Member>>> = flowOf(SyncObservation(listOf(Member("actor", "actor@example.test", "Anna", MemberRole.OWNER), Member("member", "member@example.test", "Marek", MemberRole.MEMBER)), SyncState.SYNCED))
        override suspend fun saveHousehold(household: Household) = Unit
        override suspend fun removeMember(householdId: String, memberId: String) = Unit
    }
}
