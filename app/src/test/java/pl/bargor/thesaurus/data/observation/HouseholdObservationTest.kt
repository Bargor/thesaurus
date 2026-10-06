package pl.bargor.thesaurus.data.observation

import java.time.LocalDate
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.*
import org.junit.Assert.*
import org.junit.Test
import pl.bargor.thesaurus.data.firebase.*
import pl.bargor.thesaurus.data.model.*

@OptIn(ExperimentalCoroutinesApi::class)
class HouseholdObservationTest {
    @Test fun initialErrorEmptyAndRetainedCacheAreDistinct() = runTest {
        val repo = Sources()
        repo.entries.value = SyncObservation(state = SyncState.OFFLINE)
        var model: HouseholdReadModel? = null
        val job = backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) {
            repo.observer().observe("home").collect { model = it }
        }
        runCurrent()
        assertFalse(model.currentSnapshot().entries.hasSnapshot)
        assertFalse(model.currentSnapshot().hasError)
        repo.entries.value = SyncObservation(state = SyncState.ERROR, error = IllegalStateException("read"))
        runCurrent()
        assertTrue(model.currentSnapshot().entries.hasError)
        assertFalse(model.currentSnapshot().entries.isLoading)
        repo.entries.value = SyncObservation(emptyList(), SyncState.SYNCED)
        runCurrent()
        assertTrue(model.currentSnapshot().entries.hasSnapshot)
        assertEquals(emptyList<LedgerEntry>(), model.currentSnapshot().entries.value)
        val entries = listOf(entry("one", "old"))
        repo.entries.value = SyncObservation(entries, SyncState.PENDING)
        runCurrent()
        val retained = model.currentSnapshot().entries.value
        repo.entries.value = SyncObservation(ArrayList(entries), SyncState.OFFLINE)
        runCurrent()
        assertSame(retained, model.currentSnapshot().entries.value)
        assertEquals(SyncState.OFFLINE, model.currentSnapshot().syncState)
        repo.entries.value = SyncObservation(state = SyncState.ERROR)
        runCurrent()
        assertSame(retained, model.currentSnapshot().entries.value)
        assertEquals(SyncState.ERROR, model.currentSnapshot().syncState)
        job.cancelAndJoin()
    }

    @Test fun currentAndHistoricalKeysKeepStableListenersAndDisposeRemovedKeys() = runTest {
        val repo = Sources()
        repo.entries.value = SyncObservation(listOf(entry("historical", "old")), SyncState.SYNCED)
        repo.categories.value = SyncObservation(listOf(category("current")), SyncState.SYNCED)
        var model: HouseholdReadModel? = null
        val job = backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) {
            repo.observer().observe("home", "actor").collect { model = it }
        }
        runCurrent()
        assertEquals(setOf("current", "old"), repo.active.keys)
        repo.sub("old").value = SyncObservation(listOf(
            Subcategory("archived", "home", "old", "History", archived = true,
                authorId = "actor", updatedById = "actor"),
            Subcategory("wrong", "elsewhere", "old", "Wrong", authorId = "actor", updatedById = "actor"),
        ), SyncState.PENDING)
        runCurrent()
        assertEquals(listOf("archived"), model.currentSnapshot().subcategoryValues.getValue("old").map { it.id })
        repo.categories.value = SyncObservation(listOf(category("current").copy(name = "Renamed", archived = true)), SyncState.OFFLINE)
        repo.order.value = SyncObservation(CategoryOrder("home", "actor", listOf("current")), SyncState.PENDING)
        runCurrent()
        assertEquals(mapOf("current" to 1, "old" to 1), repo.starts)
        assertEquals("Renamed", requireNotNull(model.currentSnapshot().categories.value).single().name)
        repo.entries.value = SyncObservation(emptyList(), SyncState.SYNCED)
        runCurrent()
        assertEquals(setOf("current"), repo.active.keys)
        assertFalse("Removed listener cache must disappear", "old" in model.currentSnapshot().subcategories)
        repo.sub("old").value = SyncObservation(state = SyncState.ERROR)
        runCurrent()
        assertFalse(model.currentSnapshot().hasError)
        job.cancelAndJoin()
        assertTrue(repo.active.isEmpty())
        assertEquals(0, repo.entryCollectors)
        assertEquals(0, repo.categoryCollectors)
        assertEquals(0, repo.memberCollectors)
    }

    @Test fun cancellingAndSwitchingNeverPublishesOldHouseholdAndActorOrderIsIsolated() = runTest {
        val repo = Sources()
        var model: HouseholdReadModel? = null
        var job = backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) {
            repo.observer().observe("home", "actor", includeHousehold = true).collect { model = it }
        }
        runCurrent()
        val cached = model.currentSnapshot()
        job.cancelAndJoin()
        job = backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) {
            repo.observer().observe("other", "next", initial = cached).collect { model = it }
        }
        runCurrent()
        assertEquals("other", model.currentSnapshot().householdId)
        assertEquals("next", model.currentSnapshot().actorId)
        assertTrue(model.currentSnapshot().entries.value.orEmpty().isEmpty())
        assertNull(model.currentSnapshot().order.value)
        repo.entries.value = SyncObservation(listOf(entry("stale", "old")), SyncState.PENDING)
        runCurrent()
        assertTrue(model.currentSnapshot().entries.value.orEmpty().isEmpty())
        assertTrue(repo.orderRequests.contains("other" to "next"))
        job.cancelAndJoin()
        assertTrue(repo.active.isEmpty())
        assertEquals(0, repo.householdCollectors)
    }

    @Test fun retrySeedRetainsCacheButPrunesObsoleteHistoricalError() = runTest {
        val repo = Sources()
        repo.entries.value = SyncObservation(listOf(entry("one", "old")), SyncState.SYNCED)
        var model: HouseholdReadModel? = null
        var job = backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) {
            repo.observer().observe("home").collect { model = it }
        }
        runCurrent()
        repo.sub("old").value = SyncObservation(state = SyncState.ERROR)
        runCurrent()
        val seed = model.currentSnapshot()
        job.cancelAndJoin()
        repo.entries.value = SyncObservation(emptyList(), SyncState.SYNCED)
        job = backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) {
            repo.observer().observe("home", initial = seed).collect { model = it }
        }
        runCurrent()
        assertFalse(model.currentSnapshot().hasError)
        assertFalse("old" in model.currentSnapshot().subcategories)
        job.cancelAndJoin()
    }

    @Test fun collectorsOwnIndependentGroupsAndCancellingOneKeepsTheOtherAlive() = runTest {
        val repo = Sources()
        repo.categories.value = SyncObservation(listOf(category("current")), SyncState.SYNCED)
        val source = repo.observer().observe("home")
        var second: HouseholdReadModel? = null
        val firstJob = backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) { source.collect {} }
        val secondJob = backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) { source.collect { second = it } }
        runCurrent()
        assertEquals(2, repo.entryCollectors)
        assertEquals(2, repo.active["current"])
        firstJob.cancelAndJoin()
        assertEquals(1, repo.entryCollectors)
        assertEquals(1, repo.active["current"])
        repo.entries.value = SyncObservation(listOf(entry("still-listening", "current")), SyncState.PENDING)
        runCurrent()
        assertEquals("still-listening", requireNotNull(second.currentSnapshot().entries.value).single().id)
        secondJob.cancelAndJoin()
        assertEquals(0, repo.entryCollectors)
        assertTrue(repo.active.isEmpty())
    }

    @Test fun reducerPrecedenceIncludesErrorObjectsAndEmptySources() {
        assertEquals(SyncState.SYNCED, reduceSyncState(emptyList()))
        fun observation(state: SyncState) = SyncObservation<Unit>(state = state)
        assertEquals(SyncState.OFFLINE, reduceSyncState(listOf(observation(SyncState.SYNCED), observation(SyncState.OFFLINE))))
        assertEquals(SyncState.PENDING, reduceSyncState(listOf(observation(SyncState.OFFLINE), observation(SyncState.PENDING))))
        assertEquals(SyncState.ERROR, reduceSyncState(listOf(observation(SyncState.PENDING), observation(SyncState.ERROR))))
        assertEquals(SyncState.ERROR, reduceSyncState(listOf(SyncObservation<Unit>(state = SyncState.SYNCED, error = IllegalStateException()))))
    }

    @Test fun readinessWaitsOnlyForEnabledSourcesAndTheirFirstEmissions() {
        val category = category("current")
        val model = HouseholdReadModel("home", null,
            categories = ObservedSource(SyncObservation(listOf(category), SyncState.SYNCED), listOf(category)),
            subcategories = mapOf("current" to ObservedSource()),
        )
        assertFalse(model.isTaxonomyReady)
        val taxonomyReady = model.copy(subcategories = mapOf("current" to
            ObservedSource(SyncObservation(state = SyncState.ERROR))))
        assertTrue(taxonomyReady.isTaxonomyReady)
        assertTrue("Disabled entry/member/order/household sources must not block", taxonomyReady.isReady)
        val enabled = taxonomyReady.copy(subscriptions = ObservationSubscriptions(entries = true, members = true, order = true))
        assertFalse(enabled.isReady)
        val emitted = enabled.copy(entries = ObservedSource(SyncObservation(state = SyncState.ERROR)),
            members = ObservedSource(SyncObservation(emptyList(), SyncState.SYNCED)),
            order = ObservedSource(SyncObservation(state = SyncState.SYNCED)))
        assertTrue(emitted.isReady)
    }

    @Test fun initialEmissionIsLoadingBeforeAnyRepositoryValue() = runTest {
        val repo = Sources()
        val initial = repo.observer().observe("home", "actor", includeHousehold = true).first()
        assertTrue(initial.entries.isLoading)
        assertTrue(initial.categories.isLoading)
        assertTrue(initial.members.isLoading)
        assertTrue(initial.order.isLoading)
        assertTrue(initial.household.isLoading)
        assertTrue(initial.observations.isEmpty())
        runCurrent()
        assertEquals(0, repo.entryCollectors)
        assertEquals(0, repo.householdCollectors)
        assertTrue(repo.active.isEmpty())
    }

    @Test fun mismatchedSubscriptionSeedCannotRetainDisabledSourceErrors() = runTest {
        val repo = Sources()
        val seed = HouseholdReadModel("home", null,
            entries = ObservedSource(SyncObservation(state = SyncState.ERROR, error = IllegalStateException("old ledger"))),
            members = ObservedSource(SyncObservation(state = SyncState.ERROR)),
            subscriptions = ObservationSubscriptions(entries = true, members = true),
        )
        val initial = HouseholdObservation(null, repo).observe("home", initial = seed).first()
        assertEquals(ObservationSubscriptions(), initial.subscriptions)
        assertTrue(initial.observations.isEmpty())
        assertFalse(initial.hasError)
        assertTrue(initial.categories.isLoading)
        runCurrent()
        assertEquals(0, repo.categoryCollectors)
    }

    @Test fun successfulMissingOrderClearsPreferenceWhileOrderErrorsRetainLastGoodValue() = runTest {
        val repo = Sources()
        val saved = CategoryOrder("home", "actor", listOf("second", "first"))
        repo.order.value = SyncObservation(saved, SyncState.SYNCED)
        var model: HouseholdReadModel? = null
        val job = backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) {
            repo.observer().observe("home", "actor").collect { model = it }
        }
        runCurrent()
        assertEquals(saved, model.currentSnapshot().order.value)
        repo.order.value = SyncObservation(state = SyncState.ERROR, error = IllegalStateException("order"))
        runCurrent()
        assertEquals(saved, model.currentSnapshot().order.value)
        assertTrue(model.currentSnapshot().order.hasError)
        repo.order.value = SyncObservation(state = SyncState.SYNCED)
        runCurrent()
        assertNull(model.currentSnapshot().order.value)
        assertFalse(model.currentSnapshot().order.hasError)
        repo.order.value = SyncObservation(CategoryOrder("home", "wrong-actor", listOf("first")), SyncState.SYNCED)
        runCurrent()
        assertNull(model.currentSnapshot().order.value)
        job.cancelAndJoin()
    }

    @Test fun householdLatestMissingAndErrorRemainExplicitAlongsideLastGoodCache() = runTest {
        val repo = Sources()
        val household = Household("home", "Home", "actor", openingBalanceGrosze = -1234)
        val source = MutableStateFlow(SyncObservation(household, SyncState.SYNCED))
        val householdRepository = object : HouseholdRepository by repo {
            override fun observeHousehold(householdId: String): Flow<SyncObservation<Household>> = source
        }
        var model: HouseholdReadModel? = null
        val job = backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) {
            HouseholdObservation(repo, repo, householdRepository)
                .observe("home", includeHousehold = true).collect { model = it }
        }
        runCurrent()
        assertEquals(-1234L, requireNotNull(model.currentSnapshot().household.value).openingBalanceGrosze)
        source.value = SyncObservation(state = SyncState.OFFLINE)
        runCurrent()
        assertSame(household, model.currentSnapshot().household.value)
        assertNull(requireNotNull(model.currentSnapshot().household.observation).value)
        assertEquals(SyncState.OFFLINE, requireNotNull(model.currentSnapshot().household.observation).state)
        source.value = SyncObservation(state = SyncState.ERROR, error = IllegalStateException("household"))
        runCurrent()
        assertSame(household, model.currentSnapshot().household.value)
        assertTrue(model.currentSnapshot().household.hasError)
        source.value = SyncObservation(state = SyncState.SYNCED)
        runCurrent()
        assertSame(household, model.currentSnapshot().household.value)
        assertNull(requireNotNull(model.currentSnapshot().household.observation).value)
        assertFalse(model.currentSnapshot().household.hasError)
        // Balance consumers use this latest successful absence, never the retained cache, as current.
        assertEquals(SyncState.SYNCED, requireNotNull(model.currentSnapshot().household.observation).state)
        job.cancelAndJoin()
    }

    @Test fun thrownSourceErrorsRemainTypedAndOtherListenersContinue() = runTest {
        val repo = Sources()
        val ledger = object : LedgerRepository by repo {
            override fun observeEntries(householdId: String, includeDeleted: Boolean): Flow<SyncObservation<List<LedgerEntry>>> =
                flow { error("listener failed") }
        }
        var model: HouseholdReadModel? = null
        val job = backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) {
            HouseholdObservation(ledger, repo, repo).observe("home").collect { model = it }
        }
        runCurrent()
        assertTrue(model.currentSnapshot().entries.hasError)
        repo.categories.value = SyncObservation(listOf(category("new")), SyncState.SYNCED)
        runCurrent()
        assertEquals("new", requireNotNull(model.currentSnapshot().categories.value).single().id)
        assertTrue("new" in repo.active)
        job.cancelAndJoin()
        assertTrue(repo.active.isEmpty())
    }

    private fun HouseholdReadModel?.currentSnapshot(): HouseholdReadModel =
        requireNotNull(this) { "Expected the collector to publish a household snapshot" }

    private class Sources : LedgerRepository, TaxonomyRepository, HouseholdRepository {
        val entries = MutableStateFlow(SyncObservation<List<LedgerEntry>>(emptyList(), SyncState.SYNCED))
        val categories = MutableStateFlow(SyncObservation<List<Category>>(emptyList(), SyncState.SYNCED))
        val order = MutableStateFlow(SyncObservation<CategoryOrder>(state = SyncState.SYNCED))
        private val subs = mutableMapOf<String, MutableStateFlow<SyncObservation<List<Subcategory>>>>()
        val starts = mutableMapOf<String, Int>()
        val active = mutableMapOf<String, Int>()
        val orderRequests = mutableListOf<Pair<String, String>>()
        var entryCollectors = 0
        var categoryCollectors = 0
        var memberCollectors = 0
        var householdCollectors = 0
        fun observer() = HouseholdObservation(this, this, this)
        fun sub(id: String) = subs.getOrPut(id) { MutableStateFlow(SyncObservation(emptyList(), SyncState.SYNCED)) }
        override fun observeEntries(householdId: String, includeDeleted: Boolean) = flow {
            entryCollectors++
            try { emitAll(entries) } finally { entryCollectors-- }
        }
        override fun observeCategories(householdId: String) = flow {
            categoryCollectors++
            try { emitAll(categories) } finally { categoryCollectors-- }
        }
        override fun observeCategoryOrder(householdId: String, userId: String): Flow<SyncObservation<CategoryOrder>> {
            orderRequests += householdId to userId
            return order
        }
        override fun observeSubcategories(householdId: String, categoryId: String) = flow {
            starts[categoryId] = (starts[categoryId] ?: 0) + 1
            active[categoryId] = (active[categoryId] ?: 0) + 1
            try { emitAll(sub(categoryId)) } finally {
                val remaining = active.getValue(categoryId) - 1
                if (remaining == 0) active.remove(categoryId) else active[categoryId] = remaining
            }
        }
        override fun observeMembers(householdId: String): Flow<SyncObservation<List<Member>>> = flow {
            memberCollectors++
            try { emit(SyncObservation(emptyList(), SyncState.SYNCED)); kotlinx.coroutines.awaitCancellation() }
            finally { memberCollectors-- }
        }
        override fun observeHousehold(householdId: String): Flow<SyncObservation<Household>> = flow {
            householdCollectors++
            try { emit(SyncObservation(Household(householdId, "Home", "actor"), SyncState.SYNCED)); kotlinx.coroutines.awaitCancellation() }
            finally { householdCollectors-- }
        }
        override suspend fun save(entry: LedgerEntry) = Unit
        override suspend fun tombstone(householdId: String, entryId: String, actorId: String) = Unit
        override suspend fun save(category: Category) = Unit
        override suspend fun save(subcategory: Subcategory) = Unit
        override suspend fun saveHousehold(household: Household) = Unit
        override suspend fun removeMember(householdId: String, memberId: String) = Unit
    }

    companion object {
        private fun entry(id: String, categoryId: String) = LedgerEntry(id, "home", -123, LocalDate.of(2026, 9, 1),
            categoryId = categoryId, authorId = "actor", updatedById = "actor")
        private fun category(id: String) = Category(id, "home", id, authorId = "actor", updatedById = "actor")
    }
}
