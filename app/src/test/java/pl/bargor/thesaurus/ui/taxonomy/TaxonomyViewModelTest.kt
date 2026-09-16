package pl.bargor.thesaurus.ui.taxonomy

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
import pl.bargor.thesaurus.data.firebase.TaxonomyRepository
import pl.bargor.thesaurus.data.model.Category
import pl.bargor.thesaurus.data.model.EntryType
import pl.bargor.thesaurus.data.model.Subcategory
import pl.bargor.thesaurus.data.model.SyncObservation
import pl.bargor.thesaurus.data.model.SyncState

@OptIn(ExperimentalCoroutinesApi::class)
class TaxonomyViewModelTest {
    private val dispatcher = StandardTestDispatcher()

    @Before
    fun setUp() {
        Dispatchers.setMain(dispatcher)
    }

    @After
    fun tearDown() {
        Dispatchers.resetMain()
    }

    @Test
    fun newCustomCategoryDefaultsToExpenseAndGetsAStableGeneratedId() = runTest {
        val repository = FakeTaxonomyRepository()
        val viewModel = TaxonomyViewModel(repository)
        viewModel.start("house", "actor")
        advanceUntilIdle()

        viewModel.mutate(TaxonomyMutation.AddCategory("  Zwierzęta  "))
        advanceUntilIdle()

        val saved = repository.savedCategories.single()
        assertEquals("house", saved.householdId)
        assertEquals("Zwierzęta", saved.name)
        assertEquals(EntryType.EXPENSE, saved.defaultEntryType)
        assertEquals("actor", saved.authorId)
        assertEquals("actor", saved.updatedById)
        assertTrue(saved.id.isNotBlank())
    }

    @Test
    fun renameAndArchiveKeepIdAndAuthorshipWhileRecordingEditor() = runTest {
        val repository = FakeTaxonomyRepository()
        val viewModel = TaxonomyViewModel(repository)
        val original = Category(
            id = "stable-id", householdId = "house", name = "Stara", authorId = "author", updatedById = "author",
        )
        viewModel.start("house", "owner")
        advanceUntilIdle()

        viewModel.mutate(TaxonomyMutation.EditCategory(original, "Nowa", EntryType.INCOME))
        viewModel.mutate(TaxonomyMutation.SetCategoryArchived(original, archived = true))
        advanceUntilIdle()

        val renamed = repository.savedCategories[0]
        val archived = repository.savedCategories[1]
        assertEquals("stable-id", renamed.id)
        assertEquals("author", renamed.authorId)
        assertEquals("owner", renamed.updatedById)
        assertEquals("Nowa", renamed.name)
        assertEquals(EntryType.INCOME, renamed.defaultEntryType)
        assertEquals("stable-id", archived.id)
        assertTrue(archived.archived)
    }

    @Test
    fun invalidNameAndArchivedParentDoNotQueueWrites() = runTest {
        val repository = FakeTaxonomyRepository()
        val viewModel = TaxonomyViewModel(repository)
        val archived = Category(
            id = "archived", householdId = "house", name = "Archiwum", archived = true,
            authorId = "actor", updatedById = "actor",
        )
        viewModel.start("house", "actor")
        advanceUntilIdle()

        viewModel.mutate(TaxonomyMutation.AddCategory(" "))
        assertEquals(TaxonomyError.InvalidName, viewModel.state.value.error)
        viewModel.mutate(TaxonomyMutation.AddSubcategory(archived, "Dziecko"))
        assertEquals(TaxonomyError.ArchivedParent, viewModel.state.value.error)
        assertTrue(repository.savedCategories.isEmpty())
        assertTrue(repository.savedSubcategories.isEmpty())
    }

    @Test
    fun repositoryFailureIsShownWithoutChangingCallerData() = runTest {
        val repository = FakeTaxonomyRepository(failSaves = true)
        val viewModel = TaxonomyViewModel(repository)
        viewModel.start("house", "actor")
        advanceUntilIdle()

        viewModel.mutate(TaxonomyMutation.AddCategory("Transport"))
        advanceUntilIdle()

        assertEquals(TaxonomyError.SaveFailed, viewModel.state.value.error)
        assertFalse(viewModel.state.value.saving)
        assertEquals(1, repository.saveAttempts)
    }
}

private class FakeTaxonomyRepository(
    private val failSaves: Boolean = false,
) : TaxonomyRepository {
    private val categories = MutableStateFlow(SyncObservation(value = emptyList<Category>(), state = SyncState.SYNCED))
    val savedCategories = mutableListOf<Category>()
    val savedSubcategories = mutableListOf<Subcategory>()
    var saveAttempts = 0

    override fun observeCategories(householdId: String): Flow<SyncObservation<List<Category>>> = categories

    override fun observeSubcategories(
        householdId: String,
        categoryId: String,
    ): Flow<SyncObservation<List<Subcategory>>> = flowOf(
        SyncObservation(value = emptyList(), state = SyncState.SYNCED),
    )

    override suspend fun save(category: Category) {
        saveAttempts++
        if (failSaves) error("Zapis niedostępny")
        savedCategories += category
    }

    override suspend fun save(subcategory: Subcategory) {
        saveAttempts++
        if (failSaves) error("Zapis niedostępny")
        savedSubcategories += subcategory
    }
}
