package pl.bargor.thesaurus.ui.entries

import java.time.Instant
import java.time.LocalDate
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
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import pl.bargor.thesaurus.data.firebase.HouseholdRepository
import pl.bargor.thesaurus.data.firebase.LedgerRepository
import pl.bargor.thesaurus.data.firebase.TaxonomyRepository
import pl.bargor.thesaurus.data.model.Category
import pl.bargor.thesaurus.data.model.EntryType
import pl.bargor.thesaurus.data.model.Household
import pl.bargor.thesaurus.data.model.LedgerEntry
import pl.bargor.thesaurus.data.model.Member
import pl.bargor.thesaurus.data.model.Subcategory
import pl.bargor.thesaurus.data.model.SyncObservation
import pl.bargor.thesaurus.data.model.SyncState

@OptIn(ExperimentalCoroutinesApi::class)
class EntryListViewModelTest {
    private val dispatcher = StandardTestDispatcher()

    @Before fun setUp() = Dispatchers.setMain(dispatcher)
    @After fun tearDown() = Dispatchers.resetMain()

    @Test
    fun `date sorting has stable id tie breaker and loading another page exposes exactly one page`() = runTest {
        val ledger = FakeLedger(entries = (1..21).map { index ->
            entry(id = if (index == 1) "z" else if (index == 2) "a" else "id-$index", date = LocalDate.of(2026, 9, 16))
        })
        val viewModel = viewModel(ledger)

        viewModel.start("home")
        advanceUntilIdle()

        assertEquals("a", viewModel.state.value.visibleEntries.first().entry.id)
        assertEquals(20, viewModel.state.value.visibleEntries.size)
        assertTrue(viewModel.state.value.hasMore)
        viewModel.loadNextPage()
        assertEquals(21, viewModel.state.value.visibleEntries.size)
        assertFalse(viewModel.state.value.hasMore)
    }

    @Test
    fun `creation sorting is persisted and uses date then id for equal creation timestamps`() = runTest {
        val created = Instant.parse("2026-09-16T10:00:00Z")
        val ledger = FakeLedger(entries = listOf(
            entry("z", LocalDate.of(2026, 9, 15), created),
            entry("a", LocalDate.of(2026, 9, 16), created),
            entry("old", LocalDate.of(2026, 9, 17), created.minusSeconds(1)),
        ))
        val preference = FakePreference()
        val viewModel = viewModel(ledger, preference = preference)

        viewModel.start("home")
        advanceUntilIdle()
        viewModel.changeSort(EntryListSort.CREATION_ORDER)

        assertEquals(listOf("a", "z", "old"), viewModel.state.value.visibleEntries.map { it.entry.id })
        assertEquals(EntryListSort.CREATION_ORDER, preference.value)

        val restored = viewModel(ledger, preference = preference)
        restored.start("home")
        advanceUntilIdle()
        assertEquals(EntryListSort.CREATION_ORDER, restored.state.value.sort)
        assertEquals(listOf("a", "z", "old"), restored.state.value.visibleEntries.map { it.entry.id })
    }

    @Test
    fun `pending entry without server creation timestamp stays first in creation order`() = runTest {
        val ledger = FakeLedger(entries = listOf(
            entry("synced", LocalDate.of(2026, 9, 16), Instant.parse("2026-09-16T10:00:00Z")),
            entry("pending", LocalDate.of(2026, 9, 16)),
        ))
        ledger.entries.value = SyncObservation(ledger.entries.value.value, SyncState.PENDING)
        val viewModel = viewModel(ledger, preference = FakePreference(EntryListSort.CREATION_ORDER))

        viewModel.start("home")
        advanceUntilIdle()

        assertEquals("pending", viewModel.state.value.visibleEntries.first().entry.id)
        assertEquals(SyncState.PENDING, viewModel.state.value.syncState)
    }

    @Test
    fun `subcategory is resolved inside its parent category when ids repeat`() = runTest {
        val ledger = FakeLedger(entries = listOf(
            entry("one", LocalDate.of(2026, 9, 16), subcategoryId = "other", categoryId = "food"),
        ))
        val taxonomy = FakeTaxonomy()
        taxonomy.categories.value = SyncObservation(
            listOf(
                Category("food", "home", "Jedzenie", authorId = "author", updatedById = "author"),
                Category("home", "home", "Dom", authorId = "author", updatedById = "author"),
            ),
            SyncState.SYNCED,
        )
        taxonomy.subcategories["food"] = MutableStateFlow(SyncObservation(
            listOf(Subcategory("other", "home", "food", "Inne jedzenie", authorId = "author", updatedById = "author")),
            SyncState.SYNCED,
        ))
        taxonomy.subcategories["home"] = MutableStateFlow(SyncObservation(
            listOf(Subcategory("other", "home", "home", "Inne domowe", authorId = "author", updatedById = "author")),
            SyncState.SYNCED,
        ))
        val viewModel = EntryListViewModel(ledger, taxonomy, FakeHouseholds(), FakePreference())

        viewModel.start("home")
        advanceUntilIdle()

        assertEquals("Inne jedzenie", viewModel.state.value.entries.single().subcategoryName)
    }

    @Test
    fun `taxonomy author sync and error states are projected to list rows`() = runTest {
        val ledger = FakeLedger(entries = listOf(entry("one", LocalDate.of(2026, 9, 16), amount = -1200)))
        val taxonomy = FakeTaxonomy()
        val households = FakeHouseholds()
        val viewModel = EntryListViewModel(ledger, taxonomy, households, FakePreference())
        taxonomy.categories.value = SyncObservation(
            listOf(Category("food", "home", "Jedzenie", authorId = "author", updatedById = "author")),
            SyncState.PENDING,
        )
        taxonomy.subcategories["food"] = MutableStateFlow(SyncObservation(
            listOf(Subcategory("shop", "home", "food", "Sklep", authorId = "author", updatedById = "author")), SyncState.SYNCED,
        ))
        households.members.value = SyncObservation(
            listOf(Member("author", "anna@example.com", "Anna", pl.bargor.thesaurus.data.model.MemberRole.MEMBER)), SyncState.OFFLINE,
        )
        ledger.entries.value = SyncObservation(listOf(entry("one", LocalDate.of(2026, 9, 16), amount = -1200, subcategoryId = "shop")), SyncState.SYNCED)

        viewModel.start("home")
        advanceUntilIdle()

        assertEquals("Jedzenie", viewModel.state.value.entries.single().categoryName)
        assertEquals("Sklep", viewModel.state.value.entries.single().subcategoryName)
        assertEquals("Anna", viewModel.state.value.entries.single().authorName)
        assertEquals(SyncState.PENDING, viewModel.state.value.syncState)
        ledger.entries.value = SyncObservation(state = SyncState.ERROR, error = IllegalStateException("offline"))
        advanceUntilIdle()
        assertEquals(EntryListError.LoadFailed, viewModel.state.value.error)
    }

    private fun viewModel(
        ledger: FakeLedger,
        preference: FakePreference = FakePreference(),
    ) = EntryListViewModel(ledger, FakeTaxonomy(), FakeHouseholds(), preference)

    private fun entry(
        id: String,
        date: LocalDate,
        createdAt: Instant? = null,
        amount: Long = 1200,
        subcategoryId: String? = null,
        categoryId: String = "food",
    ) = LedgerEntry(id, "home", amount, date, categoryId = categoryId, subcategoryId = subcategoryId, authorId = "author", updatedById = "author", createdAt = createdAt)
}

private class FakePreference(var value: EntryListSort = EntryListSort.ACCOUNTING_DATE) : EntryListSortPreference {
    override fun read() = value
    override fun save(sort: EntryListSort) { value = sort }
}

private class FakeLedger(entries: List<LedgerEntry> = emptyList()) : LedgerRepository {
    val entries = MutableStateFlow(SyncObservation(entries, SyncState.SYNCED))
    override fun observeEntries(householdId: String, includeDeleted: Boolean): Flow<SyncObservation<List<LedgerEntry>>> = entries
    override suspend fun save(entry: LedgerEntry) = Unit
    override suspend fun tombstone(householdId: String, entryId: String, actorId: String) = Unit
}

private class FakeTaxonomy : TaxonomyRepository {
    val categories = MutableStateFlow(SyncObservation(emptyList<Category>(), SyncState.SYNCED))
    val subcategories = mutableMapOf<String, MutableStateFlow<SyncObservation<List<Subcategory>>>>()
    override fun observeCategories(householdId: String): Flow<SyncObservation<List<Category>>> = categories
    override fun observeSubcategories(householdId: String, categoryId: String): Flow<SyncObservation<List<Subcategory>>> =
        subcategories.getOrPut(categoryId) { MutableStateFlow(SyncObservation(emptyList(), SyncState.SYNCED)) }
    override suspend fun save(category: Category) = Unit
    override suspend fun save(subcategory: Subcategory) = Unit
}

private class FakeHouseholds : HouseholdRepository {
    val members = MutableStateFlow(SyncObservation(emptyList<Member>(), SyncState.SYNCED))
    override fun observeHousehold(householdId: String): Flow<SyncObservation<Household>> = flowOf(SyncObservation(state = SyncState.SYNCED))
    override fun observeMembers(householdId: String): Flow<SyncObservation<List<Member>>> = members
    override suspend fun saveHousehold(household: Household) = Unit
    override suspend fun removeMember(householdId: String, memberId: String) = Unit
}
