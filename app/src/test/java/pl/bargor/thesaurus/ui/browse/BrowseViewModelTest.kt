package pl.bargor.thesaurus.ui.browse

import androidx.lifecycle.SavedStateHandle
import java.time.Clock
import java.time.Instant
import java.time.LocalDate
import java.time.Year
import java.time.YearMonth
import java.time.ZoneOffset
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import pl.bargor.thesaurus.data.firebase.HouseholdRepository
import pl.bargor.thesaurus.data.firebase.LedgerRepository
import pl.bargor.thesaurus.data.firebase.TaxonomyRepository
import pl.bargor.thesaurus.data.model.*
import pl.bargor.thesaurus.ui.summary.SummaryPeriodMode

@OptIn(ExperimentalCoroutinesApi::class)
class BrowseViewModelTest {
    private val dispatcher = StandardTestDispatcher()
    private val clock = Clock.fixed(Instant.parse("2028-02-15T12:00:00Z"), ZoneOffset.UTC)
    @Before fun before() = Dispatchers.setMain(dispatcher)
    @After fun after() = Dispatchers.resetMain()

    @Test fun periodsAndFreshSavedStateRetainMonthAcrossYearAndLeapBoundaries() = runTest {
        val ledger = FakeLedger(listOf(entry("leap", -100, "2028-02-29"), entry("prior", 200, "2027-02-28")))
        val saved = SavedStateHandle()
        val vm = vm(ledger, saved = saved)
        vm.start("home", "actor"); advanceUntilIdle()
        assertEquals(listOf("leap"), vm.state.value.entryItems.keys.toList())
        vm.selectPeriodMode(SummaryPeriodMode.YEAR)
        vm.previousYear()
        assertEquals(Year.of(2027), vm.state.value.year)
        vm.selectPeriodMode(SummaryPeriodMode.MONTH)
        assertEquals(YearMonth.of(2027, 2), vm.state.value.month)
        assertEquals(listOf("prior"), vm.state.value.entryItems.keys.toList())
        vm.previousMonth(); vm.previousMonth()
        assertEquals(YearMonth.of(2026, 12), vm.state.value.month)
        vm.nextMonth()
        vm.selectPeriodMode(SummaryPeriodMode.YEAR)
        val restored = vm(ledger, saved = SavedStateHandle(saved.keys().associateWith { saved.get<Any?>(it) }))
        assertEquals(SummaryPeriodMode.YEAR, restored.state.value.mode)
        assertEquals(Year.of(2027), restored.state.value.year)
        assertEquals(YearMonth.of(2027, 1), restored.state.value.month)
        restored.nextYear(); restored.selectPeriodMode(SummaryPeriodMode.MONTH)
        assertEquals(YearMonth.of(2028, 1), restored.state.value.month)
    }

    @Test fun expansionRequiresParentAndPrunesDisappearingBucketsAndPeriodSections() = runTest {
        val ledger = FakeLedger(listOf(entry("a", 100).copy(subcategoryId = "shop"), entry("b", -20).copy(subcategoryId = "fuel")))
        val vm = vm(ledger)
        vm.start("home", "actor"); advanceUntilIdle()
        val shop = BrowseSubcategoryKey("food", "shop")
        val fuel = BrowseSubcategoryKey("food", "fuel")
        vm.toggleSubcategory(shop)
        assertTrue(vm.state.value.expandedSubcategories.isEmpty())
        vm.toggleCategory("missing")
        assertTrue(vm.state.value.expandedCategoryIds.isEmpty())
        vm.toggleCategory("food"); vm.toggleSubcategory(shop); vm.toggleSubcategory(fuel)
        assertEquals(setOf(shop, fuel), vm.state.value.expandedSubcategories)
        ledger.home.value = SyncObservation(listOf(entry("a", 100).copy(subcategoryId = "shop")), SyncState.SYNCED)
        advanceUntilIdle()
        assertEquals(setOf(shop), vm.state.value.expandedSubcategories)
        vm.toggleCategory("food")
        assertTrue(vm.state.value.expandedSubcategories.isEmpty())
        vm.toggleCategory("food"); vm.toggleSubcategory(shop)
        vm.nextMonth()
        assertTrue(vm.state.value.categories.isEmpty())
        assertTrue(vm.state.value.expandedCategoryIds.isEmpty())
        assertTrue(vm.state.value.expandedSubcategories.isEmpty())
    }

    @Test fun pendingOfflineAndErrorRetainCacheAndRetryRecoversWithNewValues() = runTest {
        val ledger = FakeLedger(listOf(entry("cached", -1250)))
        val taxonomy = FakeTaxonomy()
        val vm = vm(ledger, taxonomy)
        vm.start("home", "actor"); advanceUntilIdle()
        ledger.home.value = SyncObservation(listOf(entry("cached", -1250), entry("local", 250)), SyncState.PENDING)
        advanceUntilIdle()
        assertEquals(SyncState.PENDING, vm.state.value.syncState)
        assertEquals((-1000).toBigInteger(), vm.state.value.categories.single().netGrosze)
        ledger.home.value = SyncObservation(state = SyncState.OFFLINE)
        taxonomy.categories.value = SyncObservation(state = SyncState.ERROR, error = IllegalStateException("offline"))
        advanceUntilIdle()
        assertTrue(vm.state.value.hasError)
        assertFalse(vm.state.value.isLoading)
        assertEquals(2, vm.state.value.entryItems.size)
        assertEquals("rose", vm.state.value.categories.single().category?.color)
        vm.retry(); advanceUntilIdle()
        assertEquals(2, vm.state.value.entryItems.size)
        taxonomy.categories.value = SyncObservation(listOf(category("food")), SyncState.SYNCED)
        ledger.home.value = SyncObservation(emptyList(), SyncState.SYNCED)
        advanceUntilIdle()
        assertFalse(vm.state.value.hasError)
        assertTrue(vm.state.value.categories.isEmpty())
        assertTrue(ledger.subscriptions >= 2)
    }

    @Test fun orderAndAuthorMetadataFollowSnapshotsAndHouseholdChangeClearsEverything() = runTest {
        val ledger = FakeLedger(listOf(entry("food", -10), entry("car", 30).copy(categoryId = "car")))
        val taxonomy = FakeTaxonomy()
        taxonomy.categories.value = SyncObservation(listOf(category("food"), category("car")), SyncState.SYNCED)
        taxonomy.order.value = SyncObservation(CategoryOrder("home", "actor", listOf("food", "car")), SyncState.SYNCED)
        val vm = vm(ledger, taxonomy)
        vm.start("home", "actor"); advanceUntilIdle()
        assertEquals(listOf("food", "car"), vm.state.value.categories.map { it.categoryId })
        assertEquals("Anna", vm.state.value.entryItems.getValue("food").authorName)
        assertTrue(vm.state.value.entryItems.getValue("food").canManage)
        taxonomy.order.value = SyncObservation(CategoryOrder("home", "actor", listOf("car", "food")), SyncState.PENDING)
        advanceUntilIdle()
        assertEquals(listOf("car", "food"), vm.state.value.categories.map { it.categoryId })
        vm.toggleCategory("food"); vm.toggleSubcategory(BrowseSubcategoryKey("food", null))
        vm.start("other", "actor")
        assertTrue(vm.state.value.isLoading)
        assertTrue(vm.state.value.entryItems.isEmpty())
        assertTrue(vm.state.value.expandedCategoryIds.isEmpty())
        advanceUntilIdle()
        assertTrue(vm.state.value.categories.isEmpty())
        ledger.home.value = SyncObservation(listOf(entry("late-old", 999)), SyncState.SYNCED)
        advanceUntilIdle()
        assertTrue(vm.state.value.entryItems.isEmpty())
    }

    @Test fun firstErrorIsNotEmptySuccessAndStartWithSameIdentityDoesNotDuplicateObservers() = runTest {
        val ledger = FakeLedger(emptyList())
        ledger.home.value = SyncObservation(state = SyncState.ERROR, error = IllegalStateException("unavailable"))
        val vm = vm(ledger)
        vm.start("home", "actor"); advanceUntilIdle()
        assertTrue(vm.state.value.hasError)
        assertFalse(vm.state.value.isLoading)
        vm.start("home", "actor"); advanceUntilIdle()
        assertEquals(1, ledger.subscriptions)
        ledger.home.value = SyncObservation(emptyList(), SyncState.SYNCED)
        vm.retry(); advanceUntilIdle()
        assertFalse(vm.state.value.hasError)
        assertTrue(vm.state.value.categories.isEmpty())
    }

    @Test fun subcategoryCachesStayWithTheirParentAndHistoricalTaxonomySurvivesErrors() = runTest {
        val ledger = FakeLedger(listOf(
            entry("food", -10).copy(subcategoryId = "shared"),
            entry("car", -20).copy(categoryId = "car", subcategoryId = "shared"),
        ))
        val taxonomy = FakeTaxonomy()
        taxonomy.categories.value = SyncObservation(listOf(category("food").copy(archived = true), category("car")), SyncState.SYNCED)
        val foodSub = Subcategory("shared", "home", "food", "Zakupy", archived = true, authorId = "actor", updatedById = "actor")
        val carSub = foodSub.copy(categoryId = "car", name = "Paliwo", archived = false)
        taxonomy.subcategories["food"] = MutableStateFlow(SyncObservation(listOf(foodSub), SyncState.SYNCED))
        taxonomy.subcategories["car"] = MutableStateFlow(SyncObservation(listOf(carSub), SyncState.SYNCED))
        val vm = vm(ledger, taxonomy)
        vm.start("home", "actor"); advanceUntilIdle()
        assertEquals("Zakupy", vm.state.value.entryItems.getValue("food").subcategoryName)
        assertEquals("Paliwo", vm.state.value.entryItems.getValue("car").subcategoryName)
        taxonomy.subcategories.getValue("food").value = SyncObservation(state = SyncState.ERROR, error = IllegalStateException("lost cache connection"))
        advanceUntilIdle()
        assertEquals("Zakupy", vm.state.value.entryItems.getValue("food").subcategoryName)
        assertEquals("Paliwo", vm.state.value.entryItems.getValue("car").subcategoryName)
        assertTrue(vm.state.value.hasError)
        taxonomy.subcategories.getValue("food").value = SyncObservation(emptyList(), SyncState.SYNCED)
        advanceUntilIdle()
        assertNull(vm.state.value.entryItems.getValue("food").subcategoryName)
        assertEquals("Paliwo", vm.state.value.entryItems.getValue("car").subcategoryName)
    }

    @Test fun actorSwitchClearsExpansionAndRecalculatesOwnerAuthorAndReadOnlyPermissions() = runTest {
        val ledger = FakeLedger(listOf(entry("actors-own", -10), entry("members-own", 30).copy(authorId = "member", updatedById = "member")))
        val taxonomy = FakeTaxonomy()
        val households = FakeHousehold()
        val vm = BrowseViewModel(ledger, taxonomy, households, clock, SavedStateHandle())
        vm.start("home", "actor"); advanceUntilIdle()
        assertTrue(vm.state.value.entryItems.values.all { it.canManage })
        vm.toggleCategory("food"); vm.toggleSubcategory(BrowseSubcategoryKey("food", null))
        vm.start("home", "member")
        assertTrue(vm.state.value.entryItems.isEmpty())
        assertTrue(vm.state.value.expandedCategoryIds.isEmpty())
        assertTrue(vm.state.value.expandedSubcategories.isEmpty())
        advanceUntilIdle()
        assertFalse(vm.state.value.entryItems.getValue("actors-own").canManage)
        assertTrue(vm.state.value.entryItems.getValue("members-own").canManage)
        assertEquals("Anna", vm.state.value.entryItems.getValue("actors-own").authorName)
        assertEquals("Marek", vm.state.value.entryItems.getValue("members-own").authorName)
        households.members.value = SyncObservation(listOf(Member("actor", "actor@example.test", "Anna", MemberRole.MEMBER), Member("member", "member@example.test", "Marek", MemberRole.OWNER)), SyncState.SYNCED)
        advanceUntilIdle()
        assertTrue(vm.state.value.entryItems.values.all { it.canManage })
    }

    @Test fun refreshedSamePeriodEntriesUpdateExpandedTotalsAndChildrenWithoutCollapse() = runTest {
        val original = entry("first", -100).copy(subcategoryId = "shop")
        val ledger = FakeLedger(listOf(original))
        val vm = vm(ledger)
        vm.start("home", "actor"); advanceUntilIdle()
        val key = BrowseSubcategoryKey("food", "shop")
        vm.toggleCategory("food"); vm.toggleSubcategory(key)
        ledger.home.value = SyncObservation(listOf(original.copy(amountGrosze = -300), entry("new", 100, "2028-02-13").copy(subcategoryId = "shop")), SyncState.PENDING)
        advanceUntilIdle()
        assertEquals(setOf("food"), vm.state.value.expandedCategoryIds)
        assertEquals(setOf(key), vm.state.value.expandedSubcategories)
        assertEquals((-200).toBigInteger(), vm.state.value.categories.single().netGrosze)
        assertEquals((-200).toBigInteger(), vm.state.value.categories.single().subcategories.single().netGrosze)
        assertEquals(listOf("new", "first"), vm.state.value.categories.single().subcategories.single().entries.map { it.id })
        assertEquals(-300L, vm.state.value.entryItems.getValue("first").entry.amountGrosze)
        ledger.home.value = SyncObservation(listOf(original.copy(deleted = true, deletedById = "actor"), entry("new", 100, "2028-02-13").copy(subcategoryId = "shop")), SyncState.SYNCED)
        advanceUntilIdle()
        assertEquals(setOf(key), vm.state.value.expandedSubcategories)
        assertEquals(100.toBigInteger(), vm.state.value.categories.single().netGrosze)
        assertEquals(setOf("new"), vm.state.value.entryItems.keys)
    }

    @Test fun statusOnlyUpdatesReusePreparedUiListsAndTaxonomyRebindChangesLabelsAndOrderOnly() = runTest {
        val entries = listOf(entry("food", -1200).copy(subcategoryId = "shop"), entry("car", 300).copy(categoryId = "car"))
        val ledger = FakeLedger(entries)
        val taxonomy = FakeTaxonomy()
        taxonomy.categories.value = SyncObservation(listOf(category("food"), category("car")), SyncState.SYNCED)
        taxonomy.order.value = SyncObservation(CategoryOrder("home", "actor", listOf("food", "car")), SyncState.SYNCED)
        taxonomy.subcategories["food"] = MutableStateFlow(SyncObservation(listOf(Subcategory("shop", "home", "food", "Zakupy", authorId = "actor", updatedById = "actor")), SyncState.SYNCED))
        val vm = vm(ledger, taxonomy)
        vm.start("home", "actor"); advanceUntilIdle()
        val groups = vm.state.value.categories
        val items = vm.state.value.entryItems
        val foodEntries = groups.first { it.categoryId == "food" }.subcategories.single().entries
        for (status in listOf(SyncState.PENDING, SyncState.OFFLINE)) {
            ledger.home.value = SyncObservation(ArrayList(entries), status)
            advanceUntilIdle()
            assertEquals(status, vm.state.value.syncState)
            assertSame("Ledger status must not rebuild groups", groups, vm.state.value.categories)
            assertSame("Ledger status must not rebuild entry presentation", items, vm.state.value.entryItems)
        }
        taxonomy.categories.value = SyncObservation(ArrayList(taxonomy.categories.value.value.orEmpty()), SyncState.OFFLINE)
        advanceUntilIdle()
        assertSame(groups, vm.state.value.categories)
        assertSame(items, vm.state.value.entryItems)
        ledger.home.value = SyncObservation(state = SyncState.ERROR, error = IllegalStateException("status-only failure"))
        advanceUntilIdle()
        assertTrue(vm.state.value.hasError)
        assertSame(groups, vm.state.value.categories)
        assertSame(items, vm.state.value.entryItems)

        taxonomy.categories.value = SyncObservation(listOf(category("food").copy(name = "Żywność", color = "blue"), category("car")), SyncState.SYNCED)
        taxonomy.subcategories.getValue("food").value = SyncObservation(listOf(Subcategory("shop", "home", "food", "Sklep", authorId = "actor", updatedById = "actor")), SyncState.SYNCED)
        taxonomy.order.value = SyncObservation(CategoryOrder("home", "actor", listOf("car", "food")), SyncState.SYNCED)
        advanceUntilIdle()
        assertEquals(listOf("car", "food"), vm.state.value.categories.map { it.categoryId })
        val reboundFood = vm.state.value.categories.last()
        assertEquals((-1200).toBigInteger(), reboundFood.netGrosze)
        assertEquals((-1200).toBigInteger(), reboundFood.subcategories.single().netGrosze)
        assertSame("Taxonomy refresh must reuse sorted ledger entries", foodEntries, reboundFood.subcategories.single().entries)
        assertEquals("Żywność", vm.state.value.entryItems.getValue("food").categoryName)
        assertEquals("Sklep", vm.state.value.entryItems.getValue("food").subcategoryName)
        assertEquals("blue", vm.state.value.entryItems.getValue("food").categoryColor)
    }

    private fun vm(ledger: FakeLedger, taxonomy: FakeTaxonomy = FakeTaxonomy(), saved: SavedStateHandle = SavedStateHandle()) =
        BrowseViewModel(ledger, taxonomy, FakeHousehold(), clock, saved)
    private fun entry(id: String, amount: Long, date: String = "2028-02-12") = LedgerEntry(id, "home", amount, LocalDate.parse(date), categoryId = "food", authorId = "actor", updatedById = "actor")
    private fun category(id: String) = Category(id, "home", id, color = "rose", authorId = "actor", updatedById = "actor")
    private inner class FakeTaxonomy : TaxonomyRepository {
        val categories = MutableStateFlow(SyncObservation(listOf(category("food")), SyncState.SYNCED))
        val order = MutableStateFlow(SyncObservation<CategoryOrder>(state = SyncState.SYNCED))
        val subcategories = mutableMapOf<String, MutableStateFlow<SyncObservation<List<Subcategory>>>>()
        override fun observeCategories(householdId: String) = categories
        override fun observeCategoryOrder(householdId: String, userId: String) = order
        override fun observeSubcategories(householdId: String, categoryId: String): Flow<SyncObservation<List<Subcategory>>> =
            subcategories.getOrPut(categoryId) { MutableStateFlow(SyncObservation(emptyList(), SyncState.SYNCED)) }
        override suspend fun save(category: Category) = Unit
        override suspend fun save(subcategory: Subcategory) = Unit
    }
    private class FakeLedger(entries: List<LedgerEntry>) : LedgerRepository {
        val home = MutableStateFlow(SyncObservation(entries, SyncState.SYNCED))
        val other = MutableStateFlow(SyncObservation<List<LedgerEntry>>(state = SyncState.OFFLINE))
        var subscriptions = 0
        override fun observeEntries(householdId: String, includeDeleted: Boolean): Flow<SyncObservation<List<LedgerEntry>>> {
            subscriptions++
            return if (householdId == "home") home else other
        }
        override suspend fun save(entry: LedgerEntry) = Unit
        override suspend fun tombstone(householdId: String, entryId: String, actorId: String) = Unit
    }
    private class FakeHousehold : HouseholdRepository {
        val members = MutableStateFlow(SyncObservation(listOf(Member("actor", "actor@example.test", "Anna", MemberRole.OWNER), Member("member", "member@example.test", "Marek", MemberRole.MEMBER)), SyncState.SYNCED))
        override fun observeHousehold(householdId: String): Flow<SyncObservation<Household>> = flowOf(SyncObservation(state = SyncState.SYNCED))
        override fun observeMembers(householdId: String): Flow<SyncObservation<List<Member>>> =
            members
        override suspend fun saveHousehold(household: Household) = Unit
        override suspend fun removeMember(householdId: String, memberId: String) = Unit
    }
}
