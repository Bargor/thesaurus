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
import pl.bargor.thesaurus.data.model.CategoryPalette
import pl.bargor.thesaurus.data.model.CategoryOrder
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
        assertEquals(CategoryPalette.defaultToken, saved.color)
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

        viewModel.mutate(TaxonomyMutation.EditCategory(original, "Nowa", EntryType.INCOME, "ocean"))
        viewModel.mutate(TaxonomyMutation.SetCategoryArchived(original.copy(color = "violet"), archived = true))
        advanceUntilIdle()

        val renamed = repository.savedCategories[0]
        val archived = repository.savedCategories[1]
        assertEquals("stable-id", renamed.id)
        assertEquals("author", renamed.authorId)
        assertEquals("owner", renamed.updatedById)
        assertEquals("Nowa", renamed.name)
        assertEquals(EntryType.INCOME, renamed.defaultEntryType)
        assertEquals("ocean", renamed.color)
        assertEquals("stable-id", archived.id)
        assertTrue(archived.archived)
        assertEquals("violet", archived.color)
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
        viewModel.mutate(TaxonomyMutation.AddCategory("Kolor", color = "#FFFFFF"))
        assertEquals(TaxonomyError.InvalidColor, viewModel.state.value.error)
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

    @Test
    fun reorderingIsOptimisticAndPersistsEveryCategoryIncludingArchived() = runTest {
        val repository = FakeTaxonomyRepository(
            initialCategories = listOf(
                category("a"),
                category("archived", archived = true),
                category("b"),
            ),
        )
        val viewModel = TaxonomyViewModel(repository)
        viewModel.start("house", "actor")
        advanceUntilIdle()

        viewModel.moveCategory("b", "a")
        advanceUntilIdle()

        assertEquals(listOf("b", "archived", "a"), viewModel.state.value.categories.map { it.category.id })
        assertEquals(listOf("b", "archived", "a"), repository.savedOrder?.categoryIds)
        assertEquals("actor", repository.savedOrder?.userId)
    }

    private fun category(id: String, archived: Boolean = false) = Category(
        id = id, householdId = "house", name = id, archived = archived,
        authorId = "actor", updatedById = "actor",
    )
}

private class FakeTaxonomyRepository(
    private val failSaves: Boolean = false,
    initialCategories: List<Category> = emptyList(),
) : TaxonomyRepository {
    private val categories = MutableStateFlow(SyncObservation(value = initialCategories, state = SyncState.SYNCED))
    private val order = MutableStateFlow(SyncObservation<CategoryOrder>(state = SyncState.SYNCED))
    val savedCategories = mutableListOf<Category>()
    val savedSubcategories = mutableListOf<Subcategory>()
    var saveAttempts = 0
    var savedOrder: CategoryOrder? = null

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

    override fun observeCategoryOrder(
        householdId: String,
        userId: String,
    ): Flow<SyncObservation<CategoryOrder>> = order

    override suspend fun saveCategoryOrder(order: CategoryOrder) {
        if (failSaves) error("Zapis niedostępny")
        savedOrder = order
        this.order.value = SyncObservation(order, SyncState.SYNCED)
    }
}
