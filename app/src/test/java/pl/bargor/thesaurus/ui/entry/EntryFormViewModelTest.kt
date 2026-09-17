package pl.bargor.thesaurus.ui.entry

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
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import pl.bargor.thesaurus.data.firebase.LedgerRepository
import pl.bargor.thesaurus.data.firebase.TaxonomyRepository
import pl.bargor.thesaurus.data.model.Category
import pl.bargor.thesaurus.data.model.EntryType
import pl.bargor.thesaurus.data.model.LedgerEntry
import pl.bargor.thesaurus.data.model.Subcategory
import pl.bargor.thesaurus.data.model.SyncObservation
import pl.bargor.thesaurus.data.model.SyncState

@OptIn(ExperimentalCoroutinesApi::class)
class EntryFormViewModelTest {
    private val dispatcher = StandardTestDispatcher()

    @Before fun setUp() = Dispatchers.setMain(dispatcher)
    @After fun tearDown() = Dispatchers.resetMain()

    @Test
    fun `parser accepts Polish comma and keyboard dot only when exactly representable in grosze`() {
        assertEquals(1_250L, EntryFormValidation.parseMagnitudeGrosze("12,50"))
        assertEquals(1_250L, EntryFormValidation.parseMagnitudeGrosze("12.50"))
        assertEquals(1_200L, EntryFormValidation.parseMagnitudeGrosze("12,0"))
        assertNull(EntryFormValidation.parseMagnitudeGrosze("12,345"))
        assertNull(EntryFormValidation.parseMagnitudeGrosze("0"))
        assertNull(EntryFormValidation.parseMagnitudeGrosze("-12"))
        assertNull(EntryFormValidation.parseMagnitudeGrosze("9,99 zł"))
        assertNull(EntryFormValidation.normalizedTags((1..11).joinToString(",") { "tag$it" }))
        assertEquals(listOf("dom"), EntryFormValidation.normalizedTags("Dom, DOM"))
    }

    @Test
    fun `category sets its default but user can override and saved entry has a signed amount`() = runTest {
        val ledger = FakeLedgerRepository()
        val categories = listOf(
            Category("food", "home", "Jedzenie", defaultEntryType = EntryType.EXPENSE, authorId = "actor", updatedById = "actor"),
            Category("income", "home", "Wpływy", defaultEntryType = EntryType.INCOME, authorId = "actor", updatedById = "actor"),
        )
        val viewModel = EntryFormViewModel(ledger, FakeTaxonomyRepository(categories))
        val today = LocalDate.of(2026, 9, 16)
        viewModel.start("home", "actor", today)
        advanceUntilIdle()

        viewModel.selectCategory("income")
        assertEquals(EntryType.INCOME, viewModel.state.value.type)
        viewModel.updateType(EntryType.EXPENSE)
        viewModel.updateAmount("12,50")
        viewModel.updateTitle("   ")
        viewModel.updateTags(" Dom, zakupy,DOM, ")
        viewModel.save(today)
        advanceUntilIdle()

        val saved = ledger.saved.single()
        assertEquals(-1_250L, saved.amountGrosze)
        assertNull(saved.title)
        assertEquals(listOf("dom", "zakupy"), saved.tags)
        assertEquals("income", saved.categoryId)
        assertTrue(viewModel.state.value.saved)
    }

    @Test
    fun `future date invalid amount and inactive category do not queue writes`() = runTest {
        val ledger = FakeLedgerRepository()
        val viewModel = EntryFormViewModel(
            ledger,
            FakeTaxonomyRepository(listOf(Category("food", "home", "Jedzenie", authorId = "actor", updatedById = "actor"))),
        )
        val today = LocalDate.of(2026, 9, 16)
        viewModel.start("home", "actor", today)
        advanceUntilIdle()
        viewModel.selectCategory("food")
        viewModel.updateAmount("0")
        viewModel.save(today)
        assertEquals(EntryFormError.InvalidAmount, viewModel.state.value.error)
        viewModel.updateAmount("1")
        viewModel.updateDate(today.plusDays(1))
        viewModel.save(today)
        assertEquals(EntryFormError.FutureDate, viewModel.state.value.error)
        assertTrue(ledger.saved.isEmpty())
    }

    @Test
    fun `repository failure is retained and saving is cleared`() = runTest {
        val ledger = FakeLedgerRepository(fail = true)
        val viewModel = EntryFormViewModel(
            ledger,
            FakeTaxonomyRepository(listOf(Category("food", "home", "Jedzenie", authorId = "actor", updatedById = "actor"))),
        )
        val today = LocalDate.of(2026, 9, 16)
        viewModel.start("home", "actor", today)
        advanceUntilIdle()
        viewModel.selectCategory("food")
        viewModel.updateAmount("2")
        viewModel.save(today)
        advanceUntilIdle()
        assertEquals(EntryFormError.SaveFailed, viewModel.state.value.error)
        assertFalse(viewModel.state.value.saving)
    }

    @Test
    fun `pending local snapshot completes the form while offline save task is still pending`() = runTest {
        val ledger = FakeLedgerRepository(holdSaveTask = true)
        val viewModel = EntryFormViewModel(
            ledger,
            FakeTaxonomyRepository(listOf(Category("food", "home", "Jedzenie", authorId = "actor", updatedById = "actor"))),
        )
        val today = LocalDate.of(2026, 9, 16)
        viewModel.start("home", "actor", today)
        advanceUntilIdle()
        viewModel.selectCategory("food")
        viewModel.updateAmount("4")
        viewModel.save(today)
        advanceUntilIdle()

        assertTrue(viewModel.state.value.saved)
        assertTrue(viewModel.state.value.queuedOffline)
        assertFalse(viewModel.state.value.saving)
    }
}

private class FakeLedgerRepository(
    private val fail: Boolean = false,
    private val holdSaveTask: Boolean = false,
) : LedgerRepository {
    val saved = mutableListOf<LedgerEntry>()
    private val entries = MutableStateFlow(SyncObservation(emptyList<LedgerEntry>(), SyncState.SYNCED))
    override fun observeEntries(householdId: String, includeDeleted: Boolean): Flow<SyncObservation<List<LedgerEntry>>> =
        entries
    override suspend fun save(entry: LedgerEntry) {
        if (fail) error("offline")
        saved += entry
        entries.value = SyncObservation(listOf(entry), SyncState.PENDING)
        if (holdSaveTask) kotlinx.coroutines.awaitCancellation()
    }
    override suspend fun tombstone(householdId: String, entryId: String, actorId: String) = Unit
}

private class FakeTaxonomyRepository(categories: List<Category>) : TaxonomyRepository {
    private val state = MutableStateFlow(SyncObservation(categories, SyncState.SYNCED))
    override fun observeCategories(householdId: String): Flow<SyncObservation<List<Category>>> = state
    override fun observeSubcategories(householdId: String, categoryId: String): Flow<SyncObservation<List<Subcategory>>> =
        flowOf(SyncObservation(emptyList(), SyncState.SYNCED))
    override suspend fun save(category: Category) = Unit
    override suspend fun save(subcategory: Subcategory) = Unit
}
