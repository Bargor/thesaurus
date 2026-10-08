package pl.bargor.thesaurus.ui

import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModelStore
import java.time.Clock
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneOffset
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import pl.bargor.thesaurus.data.firebase.*
import pl.bargor.thesaurus.data.model.*
import pl.bargor.thesaurus.data.observation.ObservedSource
import pl.bargor.thesaurus.ui.balance.GlobalAccountBalanceViewModel
import pl.bargor.thesaurus.ui.browse.BrowseViewModel
import pl.bargor.thesaurus.ui.entries.*
import pl.bargor.thesaurus.ui.entry.EntryFormError
import pl.bargor.thesaurus.ui.entry.EntryFormViewModel
import pl.bargor.thesaurus.ui.reports.*
import pl.bargor.thesaurus.ui.summary.*

@OptIn(ExperimentalCoroutinesApi::class)
class MalformedFinancialPresentationTest {
    private val dispatcher = StandardTestDispatcher()
    private val clock = Clock.fixed(Instant.parse("2026-09-30T12:00:00Z"), ZoneOffset.UTC)
    private val stores = ViewModelStore()
    @Before fun before() = Dispatchers.setMain(dispatcher)
    @After fun after() { stores.clear(); Dispatchers.resetMain() }

    @Test fun sourceInvalidationSurvivesTransientErrorsAndMetadataUntilDecodedRecovery() {
        val decoded = listOf(entry())
        val valid = ObservedSource<List<LedgerEntry>>().accept(SyncObservation(decoded, SyncState.SYNCED))
        val transient = valid.accept(SyncObservation(state = SyncState.ERROR, error = IllegalStateException("network")))
        assertSame(decoded, transient.value)
        assertFalse(transient.hasInvalidData)
        val invalid = transient.accept(SyncObservation(state = SyncState.ERROR, error = malformed()))
        assertNull(invalid.value)
        assertTrue(invalid.hasInvalidData)
        val later = invalid.accept(SyncObservation(state = SyncState.ERROR, error = IllegalStateException("network")))
            .accept(SyncObservation(state = SyncState.OFFLINE))
        assertNull(later.value)
        assertTrue(later.hasInvalidData)
        val repaired = later.accept(SyncObservation(emptyList(), SyncState.SYNCED))
        assertFalse(repaired.hasInvalidData)
        assertEquals(emptyList<LedgerEntry>(), repaired.value)
    }

    @Test fun financialProjectionsDiscardCorruptLedgerAndCannotReviveItThroughControlsOrIdentitySwitch() = runTest {
        val data = Sources()
        val reports = ReportsViewModel(data, data, clock, SavedStateHandle(), data)
        val summary = SummaryViewModel(data, data, data, clock, SavedStateHandle())
        val browse = BrowseViewModel(data, data, data, clock, SavedStateHandle())
        val list = EntryListViewModel(data, data, data, SortPreference())
        stores.put("reports", reports); stores.put("summary", summary)
        stores.put("browse", browse); stores.put("list", list)
        reports.start("home"); summary.start("home", "actor")
        browse.start("home", "actor"); list.start("home", "actor")
        advanceUntilIdle()
        assertEquals((-1250).toBigInteger(), reports.state.value.aggregation.totals.netGrosze)
        summary.openPeriod(summary.state.value.cards.first().key)
        assertNotNull(summary.state.value.detailCard)
        assertEquals(setOf("existing"), browse.state.value.entryItems.keys)
        assertEquals("existing", list.state.value.entries.single().entry.id)

        data.ledger.value = SyncObservation(state = SyncState.ERROR, error = malformed())
        advanceUntilIdle()
        fun assertUnavailable() {
            assertTrue(reports.state.value.hasError)
            assertTrue(reports.state.value.aggregation.totals.isEmpty)
            assertTrue(reports.state.value.entries.isEmpty())
            assertNull(reports.state.value.balanceTrend)
            assertTrue(summary.state.value.hasError)
            assertTrue(summary.state.value.cards.isEmpty())
            assertNull(summary.state.value.detailCard)
            assertTrue(summary.state.value.detailEntries.isEmpty())
            assertTrue(browse.state.value.hasError)
            assertTrue(browse.state.value.categories.isEmpty())
            assertTrue(browse.state.value.entryItems.isEmpty())
            assertEquals(EntryListError.LoadFailed, list.state.value.error)
            assertTrue(list.state.value.entries.isEmpty())
        }
        assertUnavailable()
        reports.selectSort(ReportEntrySort.AMOUNT); reports.toggleSortDirection()
        reports.openFilters(); reports.selectType(ReportTypeFilter.EXPENSE); reports.applyFilters()
        summary.selectPeriodMode(SummaryPeriodMode.YEAR); summary.selectPeriodMode(SummaryPeriodMode.MONTH)
        browse.selectPeriodMode(SummaryPeriodMode.YEAR); browse.selectPeriodMode(SummaryPeriodMode.MONTH)
        list.changeSort(EntryListSort.CREATION_ORDER); list.revealMoreEntries()
        assertUnavailable()
        data.ledger.value = SyncObservation(state = SyncState.ERROR, error = IllegalStateException("network"))
        advanceUntilIdle(); assertUnavailable()
        data.ledger.value = SyncObservation(state = SyncState.OFFLINE)
        advanceUntilIdle(); assertUnavailable()
        reports.retry(); summary.retry(); browse.retry(); list.retry()
        advanceUntilIdle(); assertUnavailable()

        data.ledger.value = SyncObservation(listOf(entry().copy(amountGrosze = -1800)), SyncState.SYNCED)
        advanceUntilIdle()
        assertFalse(reports.state.value.hasError)
        assertEquals((-1800).toBigInteger(), reports.state.value.aggregation.totals.netGrosze)
        assertFalse(summary.state.value.hasError)
        assertTrue(summary.state.value.cards.isNotEmpty())
        assertEquals(setOf("existing"), browse.state.value.entryItems.keys)
        assertNull(list.state.value.error)
        assertEquals(-1800L, list.state.value.entries.single().entry.amountGrosze)

        reports.start("other"); summary.start("other", "next")
        browse.start("other", "next"); list.start("other", "next")
        advanceUntilIdle()
        data.ledger.value = SyncObservation(listOf(entry().copy(amountGrosze = -9999)), SyncState.SYNCED)
        advanceUntilIdle()
        assertTrue(reports.state.value.entries.isEmpty())
        assertTrue(reports.state.value.aggregation.totals.isEmpty)
        assertTrue(summary.state.value.cards.isEmpty())
        assertTrue(browse.state.value.entryItems.isEmpty())
        assertTrue(list.state.value.entries.isEmpty())
    }

    @Test fun malformedTaxonomyAndHouseholdSuppressFinancialDisplayWithoutChangingTransientCachePolicy() = runTest {
        val data = Sources()
        val reports = ReportsViewModel(data, data, clock, SavedStateHandle(), data)
        val summary = SummaryViewModel(data, data, data, clock, SavedStateHandle())
        val browse = BrowseViewModel(data, data, data, clock, SavedStateHandle())
        val list = EntryListViewModel(data, data, data, SortPreference())
        stores.put("reports", reports); stores.put("summary", summary)
        stores.put("browse", browse); stores.put("list", list)
        reports.start("home"); summary.start("home", "actor")
        browse.start("home", "actor"); list.start("home", "actor")
        advanceUntilIdle()
        data.categories.value = SyncObservation(state = SyncState.ERROR, error = IllegalStateException("network"))
        advanceUntilIdle()
        assertEquals(1, reports.state.value.entries.size)
        assertTrue(summary.state.value.cards.isNotEmpty())
        assertTrue(browse.state.value.entryItems.isNotEmpty())
        assertEquals(1, list.state.value.entries.size)
        data.categories.value = SyncObservation(state = SyncState.ERROR,
            error = malformed(FirestoreDocumentType.CATEGORY))
        advanceUntilIdle()
        assertTrue(reports.state.value.aggregation.totals.isEmpty)
        assertTrue(summary.state.value.cards.isEmpty())
        assertTrue(browse.state.value.categories.isEmpty())
        assertTrue(list.state.value.entries.isEmpty())
        data.categories.value = SyncObservation(state = SyncState.ERROR, error = IllegalStateException("network"))
        advanceUntilIdle()
        assertTrue(reports.state.value.entries.isEmpty())
        data.categories.value = SyncObservation(listOf(category()), SyncState.SYNCED)
        advanceUntilIdle()
        assertEquals(1, reports.state.value.entries.size)
        assertTrue(summary.state.value.cards.isNotEmpty())
        data.household.value = SyncObservation(state = SyncState.ERROR,
            error = malformed(FirestoreDocumentType.HOUSEHOLD))
        advanceUntilIdle()
        assertTrue(reports.state.value.aggregation.totals.isEmpty)
        assertNull(reports.state.value.balanceTrend)
        data.household.value = SyncObservation(Household("home", "Dom", "actor"), SyncState.SYNCED)
        advanceUntilIdle()
        assertEquals((-1250).toBigInteger(), reports.state.value.aggregation.totals.netGrosze)
    }

    @Test fun corruptEditBaseCannotBeSavedAndRepairedSameIdKeepsUnsavedDraft() = runTest {
        val data = Sources()
        val vm = EntryFormViewModel(data, data, SavedStateHandle())
        stores.put("edit", vm)
        val today = LocalDate.of(2026, 9, 30)
        vm.start("home", "actor", entryId = "existing", today = today)
        advanceUntilIdle()
        assertEquals("12,50", vm.state.value.amount)
        vm.updateTitle("Moja nowa nazwa")
        data.ledger.value = SyncObservation(state = SyncState.ERROR, error = malformed())
        advanceUntilIdle()
        assertEquals(EntryFormError.SaveFailed, vm.state.value.error)
        assertEquals("12,50", vm.state.value.amount)
        vm.updateAmount("18")
        assertEquals(EntryFormError.SaveFailed, vm.state.value.error)
        vm.save(today)
        advanceUntilIdle()
        assertTrue(data.saved.isEmpty())
        data.ledger.value = SyncObservation(state = SyncState.ERROR, error = IllegalStateException("network"))
        advanceUntilIdle(); vm.save(today); advanceUntilIdle()
        assertTrue(data.saved.isEmpty())
        data.ledger.value = SyncObservation(listOf(entry().copy(title = "Naprawiony serwer")), SyncState.SYNCED)
        advanceUntilIdle()
        assertEquals("18", vm.state.value.amount)
        assertEquals("Moja nowa nazwa", vm.state.value.title)
        vm.save(today); advanceUntilIdle()
        assertEquals("existing", data.saved.single().id)
        assertEquals(-1800L, data.saved.single().amountGrosze)
        assertEquals("actor", data.saved.single().authorId)
    }

    @Test fun corruptReadDoesNotReplaceTheIdOfAnAlreadyQueuedCreation() = runTest {
        val data = Sources()
        val vm = EntryFormViewModel(data, data, SavedStateHandle())
        stores.put("create", vm)
        val today = LocalDate.of(2026, 9, 30)
        vm.start("home", "actor", today)
        advanceUntilIdle()
        vm.selectCategory("food"); vm.updateAmount("12,50"); vm.save(today)
        advanceUntilIdle()
        val queuedId = data.saved.single().id
        assertTrue(vm.state.value.queuedOffline)
        data.ledger.value = SyncObservation(state = SyncState.ERROR, error = malformed())
        advanceUntilIdle()
        assertFalse(vm.state.value.saved)
        assertEquals("12,50", vm.state.value.amount)
        data.ledger.value = SyncObservation(emptyList(), SyncState.SYNCED)
        advanceUntilIdle()
        vm.save(today); advanceUntilIdle()
        assertEquals(listOf(queuedId, queuedId), data.saved.map { it.id })
    }

    @Test fun validLedgerMetadataCannotAcknowledgeQueuedDraftWhileTaxonomyIsMalformed() = runTest {
        val data = Sources()
        val vm = EntryFormViewModel(data, data, SavedStateHandle())
        stores.put("taxonomy-create", vm)
        val today = LocalDate.of(2026, 9, 30)
        vm.start("home", "actor", today); advanceUntilIdle()
        vm.selectCategory("food"); vm.updateAmount("12,50"); vm.save(today)
        advanceUntilIdle()
        val queued = data.saved.single()
        assertTrue(vm.state.value.saved)
        assertTrue(vm.state.value.queuedOffline)
        data.categories.value = SyncObservation(state = SyncState.ERROR,
            error = malformed(FirestoreDocumentType.CATEGORY))
        advanceUntilIdle()
        fun assertUnavailable() {
            assertEquals(SyncState.ERROR, vm.state.value.syncState)
            assertEquals(EntryFormError.SaveFailed, vm.state.value.error)
            assertFalse(vm.state.value.saved)
            assertFalse(vm.state.value.queuedOffline)
            assertEquals("12,50", vm.state.value.amount)
            assertEquals("food", vm.state.value.categoryId)
        }
        assertUnavailable()
        data.ledger.value = SyncObservation(state = SyncState.OFFLINE)
        advanceUntilIdle(); assertUnavailable()
        data.ledger.value = SyncObservation(listOf(queued), SyncState.PENDING)
        advanceUntilIdle(); assertUnavailable()
        data.ledger.value = SyncObservation(listOf(queued), SyncState.SYNCED)
        advanceUntilIdle(); assertUnavailable()
        vm.save(today); advanceUntilIdle()
        assertEquals(1, data.saved.size)
        data.categories.value = SyncObservation(listOf(category()), SyncState.SYNCED)
        advanceUntilIdle()
        assertEquals(SyncState.SYNCED, vm.state.value.syncState)
        vm.save(today); advanceUntilIdle()
        assertEquals(listOf(queued.id, queued.id), data.saved.map { it.id })
        assertTrue(vm.state.value.saved)
        assertNull(vm.state.value.error)
    }

    @Test fun invalidLedgerClearsPreviouslyVisibleRowsEvenWhileNewTaxonomyIsNotReady() = runTest {
        val data = Sources()
        val vm = EntryListViewModel(data, data, data, SortPreference())
        stores.put("list", vm)
        vm.start("home", "actor"); advanceUntilIdle()
        assertEquals(1, vm.state.value.entries.size)
        data.categories.value = SyncObservation(listOf(category(), category().copy(id = "late")), SyncState.SYNCED)
        advanceUntilIdle()
        assertEquals(1, vm.state.value.entries.size)
        data.ledger.value = SyncObservation(state = SyncState.ERROR, error = malformed())
        advanceUntilIdle()
        assertTrue(vm.state.value.entries.isEmpty())
        assertFalse(vm.state.value.isLoading)
        assertEquals(EntryListError.LoadFailed, vm.state.value.error)
    }

    @Test fun accountBalanceKeepsMalformedStateUnavailableUntilBothSourcesHaveDecodedValues() = runTest {
        val data = Sources()
        val vm = GlobalAccountBalanceViewModel(data, data)
        stores.put("balance", vm)
        vm.start("home", "actor"); advanceUntilIdle()
        assertEquals((-1250).toBigInteger(), vm.state.value.amountGrosze)
        data.ledger.value = SyncObservation(state = SyncState.ERROR, error = malformed())
        advanceUntilIdle()
        data.ledger.value = SyncObservation(state = SyncState.PENDING)
        advanceUntilIdle()
        assertNull(vm.state.value.amountGrosze)
        assertFalse(vm.state.value.isLoading)
        assertTrue(vm.state.value.hasError)
        assertEquals(SyncState.ERROR, vm.state.value.syncState)
        vm.retry(); advanceUntilIdle()
        assertNull(vm.state.value.amountGrosze)
        assertTrue(vm.state.value.hasError)
        assertEquals(SyncState.ERROR, vm.state.value.syncState)
        data.ledger.value = SyncObservation(listOf(entry()), SyncState.SYNCED)
        advanceUntilIdle()
        assertFalse(vm.state.value.hasError)
        data.household.value = SyncObservation(state = SyncState.ERROR,
            error = malformed(FirestoreDocumentType.HOUSEHOLD))
        advanceUntilIdle()
        data.household.value = SyncObservation(state = SyncState.OFFLINE)
        advanceUntilIdle()
        assertNull(vm.state.value.amountGrosze)
        assertTrue(vm.state.value.hasError)
        assertEquals(SyncState.ERROR, vm.state.value.syncState)
        data.household.value = SyncObservation(Household("home", "Dom", "actor", openingBalanceGrosze = 2500), SyncState.SYNCED)
        advanceUntilIdle()
        assertEquals(1250.toBigInteger(), vm.state.value.amountGrosze)
        assertFalse(vm.state.value.hasError)
    }

    @Test fun successfulAbsentOrderRepairsInvalidPreferenceWithoutRevivingCachedOrder() = runTest {
        val data = Sources()
        val vm = BrowseViewModel(data, data, data, clock, SavedStateHandle())
        stores.put("order", vm)
        vm.start("home", "actor"); advanceUntilIdle()
        assertTrue(vm.state.value.entryItems.isNotEmpty())
        data.order.value = SyncObservation(state = SyncState.ERROR,
            error = malformed(FirestoreDocumentType.CATEGORY_ORDER))
        advanceUntilIdle()
        assertTrue(vm.state.value.hasError)
        assertTrue(vm.state.value.categories.isEmpty())
        data.order.value = SyncObservation(state = SyncState.ERROR, error = IllegalStateException("network"))
        advanceUntilIdle(); vm.retry(); advanceUntilIdle()
        assertTrue(vm.state.value.hasError)
        assertTrue(vm.state.value.entryItems.isEmpty())
        data.order.value = SyncObservation(state = SyncState.SYNCED)
        advanceUntilIdle()
        assertFalse(vm.state.value.hasError)
        assertEquals(setOf("existing"), vm.state.value.entryItems.keys)
    }

    @Test fun invalidationCancelsSuspendedDeletionWithoutRestoringItsCachedRow() = runTest {
        val data = Sources().apply { suspendDeletion = true }
        val vm = EntryListViewModel(data, data, data, SortPreference())
        stores.put("delete", vm)
        vm.start("home", "actor"); advanceUntilIdle()
        vm.confirmDelete(vm.state.value.entries.single())
        advanceTimeBy(ENTRY_DELETE_UNDO_WINDOW_MILLIS); runCurrent()
        assertTrue(data.deletionStarted.isCompleted)
        data.ledger.value = SyncObservation(state = SyncState.ERROR, error = malformed())
        advanceUntilIdle()
        assertTrue(vm.state.value.entries.isEmpty())
        assertNull(vm.state.value.pendingDeletion)
        assertEquals(EntryListError.LoadFailed, vm.state.value.error)
        assertFalse(vm.state.value.deletionError)
    }

    @Test fun malformedSnapshotQueuedBeforeSaveClearsAuthorityBeforeWriteJobRuns() = runTest {
        val data = Sources()
        val vm = EntryFormViewModel(data, data, SavedStateHandle())
        stores.put("queued-edit", vm)
        val today = LocalDate.of(2026, 9, 30)
        vm.start("home", "actor", entryId = "existing", today = today)
        advanceUntilIdle()
        vm.updateAmount("18")
        data.ledger.value = SyncObservation(state = SyncState.ERROR, error = malformed())
        vm.save(today)
        advanceUntilIdle()
        assertTrue(data.saved.isEmpty())
        assertEquals("existing", vm.state.value.editingEntryId)
        assertEquals("18", vm.state.value.amount)
        assertEquals(EntryFormError.SaveFailed, vm.state.value.error)
    }

    @Test fun accountBalanceIdentitySwitchClearsOldAmountAndInvalidMarker() = runTest {
        val data = Sources()
        val vm = GlobalAccountBalanceViewModel(data, data)
        stores.put("balance-identity", vm)
        vm.start("home", "actor"); advanceUntilIdle()
        data.ledger.value = SyncObservation(state = SyncState.ERROR, error = malformed())
        advanceUntilIdle()
        assertTrue(vm.state.value.hasError)
        vm.start("other", "next"); advanceUntilIdle()
        assertEquals("other", vm.state.value.householdId)
        assertEquals("next", vm.state.value.actorId)
        assertNull(vm.state.value.amountGrosze)
        assertFalse(vm.state.value.hasError)
        assertTrue(vm.state.value.isLoading)
        data.ledger.value = SyncObservation(listOf(entry()), SyncState.SYNCED)
        advanceUntilIdle()
        assertNull(vm.state.value.amountGrosze)
    }

    private class SortPreference : EntryListSortPreference {
        private var sort = EntryListSort.ACCOUNTING_DATE
        override fun read() = sort
        override fun save(sort: EntryListSort) { this.sort = sort }
    }

    private class Sources : LedgerRepository, TaxonomyRepository, HouseholdRepository {
        val ledger = MutableStateFlow(SyncObservation(listOf(entry()), SyncState.SYNCED))
        val categories = MutableStateFlow(SyncObservation(listOf(category()), SyncState.SYNCED))
        val household = MutableStateFlow(SyncObservation(Household("home", "Dom", "actor"), SyncState.SYNCED))
        val order = MutableStateFlow(SyncObservation<CategoryOrder>(state = SyncState.SYNCED))
        private val lateSubcategories = MutableSharedFlow<SyncObservation<List<Subcategory>>>()
        val saved = mutableListOf<LedgerEntry>()
        var suspendDeletion = false
        val deletionStarted = CompletableDeferred<Unit>()
        override fun observeEntries(householdId: String, includeDeleted: Boolean): Flow<SyncObservation<List<LedgerEntry>>> =
            if (householdId == "home") ledger else flowOf(SyncObservation(state = SyncState.OFFLINE))
        override fun observeCategories(householdId: String): Flow<SyncObservation<List<Category>>> =
            if (householdId == "home") categories else flowOf(SyncObservation(state = SyncState.OFFLINE))
        override fun observeCategoryOrder(householdId: String, userId: String): Flow<SyncObservation<CategoryOrder>> = order
        override fun observeSubcategories(householdId: String, categoryId: String): Flow<SyncObservation<List<Subcategory>>> =
            if (categoryId == "late") lateSubcategories else flowOf(SyncObservation(emptyList(), SyncState.SYNCED))
        override fun observeHousehold(householdId: String): Flow<SyncObservation<Household>> =
            if (householdId == "home") household else flowOf(SyncObservation(state = SyncState.OFFLINE))
        override fun observeMembers(householdId: String): Flow<SyncObservation<List<Member>>> =
            flowOf(SyncObservation(emptyList(), SyncState.SYNCED))
        override suspend fun save(entry: LedgerEntry) { saved += entry; ledger.value = SyncObservation(listOf(entry), SyncState.PENDING) }
        override suspend fun tombstone(householdId: String, entryId: String, actorId: String) {
            if (suspendDeletion) {
                deletionStarted.complete(Unit)
                awaitCancellation()
            }
        }
        override suspend fun save(category: Category) = Unit
        override suspend fun save(subcategory: Subcategory) = Unit
        override suspend fun saveHousehold(household: Household) = Unit
        override suspend fun removeMember(householdId: String, memberId: String) = Unit
    }

    companion object {
        private fun entry() = LedgerEntry("existing", "home", -1250, LocalDate.of(2026, 9, 12),
            categoryId = "food", authorId = "actor", updatedById = "actor")
        private fun category() = Category("food", "home", "Jedzenie", authorId = "actor", updatedById = "actor")
        private fun malformed(type: FirestoreDocumentType = FirestoreDocumentType.LEDGER_ENTRY) =
            FirestoreDecodeException(type, "amountGrosze", FirestoreDecodeReason.WRONG_TYPE)
    }
}
