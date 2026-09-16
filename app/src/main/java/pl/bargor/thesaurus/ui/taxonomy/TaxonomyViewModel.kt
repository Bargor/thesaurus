package pl.bargor.thesaurus.ui.taxonomy

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import pl.bargor.thesaurus.data.firebase.TaxonomyRepository
import pl.bargor.thesaurus.data.model.Category
import pl.bargor.thesaurus.data.model.EntryType
import pl.bargor.thesaurus.data.model.Subcategory
import pl.bargor.thesaurus.data.model.SyncState
import java.util.UUID
import javax.inject.Inject

data class TaxonomyUiState(
    val isLoading: Boolean = true,
    val categories: List<CategoryWithSubcategories> = emptyList(),
    val syncState: SyncState = SyncState.SYNCED,
    val error: TaxonomyError? = null,
    val saving: Boolean = false,
)

sealed interface TaxonomyError {
    data object InvalidName : TaxonomyError
    data object ArchivedParent : TaxonomyError
    data object SaveFailed : TaxonomyError
}

data class CategoryWithSubcategories(
    val category: Category,
    val subcategories: List<Subcategory>,
)

sealed interface TaxonomyMutation {
    data class AddCategory(val name: String, val defaultEntryType: EntryType = EntryType.EXPENSE) : TaxonomyMutation
    data class EditCategory(val category: Category, val name: String, val defaultEntryType: EntryType) : TaxonomyMutation
    data class AddSubcategory(val category: Category, val name: String) : TaxonomyMutation
    data class EditSubcategory(val subcategory: Subcategory, val name: String) : TaxonomyMutation
    data class SetCategoryArchived(val category: Category, val archived: Boolean) : TaxonomyMutation
    data class SetSubcategoryArchived(val subcategory: Subcategory, val archived: Boolean) : TaxonomyMutation
}

/** Domain validation stays independent of Compose so invalid offline writes are never queued. */
object TaxonomyValidation {
    const val MAX_NAME_LENGTH = 60

    fun nameOrNull(raw: String): String? = raw.trim().takeIf {
        it.isNotEmpty() && it.length <= MAX_NAME_LENGTH
    }
}

@HiltViewModel
class TaxonomyViewModel @Inject constructor(
    private val repository: TaxonomyRepository,
) : ViewModel() {
    private val mutableState = MutableStateFlow(TaxonomyUiState())
    val state: StateFlow<TaxonomyUiState> = mutableState.asStateFlow()

    private var startedFor: Pair<String, String>? = null

    /** Called after authentication because household identity is intentionally not persisted in UI routes. */
    @OptIn(ExperimentalCoroutinesApi::class)
    fun start(householdId: String, actorId: String) {
        if (startedFor == householdId to actorId) return
        startedFor = householdId to actorId
        mutableState.value = TaxonomyUiState()
        viewModelScope.launch {
            repository.observeCategories(householdId)
                .flatMapLatest { categoryObservation ->
                    val categories = categoryObservation.value.orEmpty()
                    val subcategoryObservations = categories.map { category ->
                        repository.observeSubcategories(householdId, category.id)
                    }
                    if (subcategoryObservations.isEmpty()) {
                        flowOf(TaxonomySnapshot(categories, emptyMap(), categoryObservation.state, categoryObservation.error))
                    } else {
                        combine(subcategoryObservations) { observations ->
                            val byCategory = categories.indices.associate { index ->
                                categories[index].id to observations[index].value.orEmpty()
                            }
                            val error = categoryObservation.error ?: observations.firstNotNullOfOrNull { it.error }
                            TaxonomySnapshot(
                                categories = categories,
                                subcategories = byCategory,
                                state = mostRelevantState(categoryObservation.state, observations.map { it.state }),
                                error = error,
                            )
                        }
                    }
                }
                .collect { snapshot ->
                    mutableState.update {
                        it.copy(
                            isLoading = false,
                            categories = snapshot.categories.map { category ->
                                CategoryWithSubcategories(category, snapshot.subcategories[category.id].orEmpty())
                            },
                            syncState = snapshot.state,
                            error = snapshot.error?.let { TaxonomyError.SaveFailed },
                        )
                    }
                }
        }
    }

    fun mutate(mutation: TaxonomyMutation) {
        val context = startedFor ?: return
        val (householdId, actorId) = context
        val name = when (mutation) {
            is TaxonomyMutation.AddCategory -> TaxonomyValidation.nameOrNull(mutation.name)
            is TaxonomyMutation.EditCategory -> TaxonomyValidation.nameOrNull(mutation.name)
            is TaxonomyMutation.AddSubcategory -> TaxonomyValidation.nameOrNull(mutation.name)
            is TaxonomyMutation.EditSubcategory -> TaxonomyValidation.nameOrNull(mutation.name)
            else -> "valid"
        }
        if (name == null) {
            mutableState.update { it.copy(error = TaxonomyError.InvalidName) }
            return
        }
        if (mutation is TaxonomyMutation.AddSubcategory && mutation.category.archived) {
            mutableState.update { it.copy(error = TaxonomyError.ArchivedParent) }
            return
        }
        viewModelScope.launch {
            mutableState.update { it.copy(saving = true, error = null) }
            runCatching {
                when (mutation) {
                    is TaxonomyMutation.AddCategory -> repository.save(
                        Category(
                            id = UUID.randomUUID().toString(),
                            householdId = householdId,
                            name = name,
                            defaultEntryType = mutation.defaultEntryType,
                            authorId = actorId,
                            updatedById = actorId,
                        ),
                    )
                    is TaxonomyMutation.EditCategory -> repository.save(
                        mutation.category.copy(
                            name = name,
                            defaultEntryType = mutation.defaultEntryType,
                            updatedById = actorId,
                        ),
                    )
                    is TaxonomyMutation.AddSubcategory -> repository.save(
                        Subcategory(
                            id = UUID.randomUUID().toString(),
                            householdId = householdId,
                            categoryId = mutation.category.id,
                            name = name,
                            authorId = actorId,
                            updatedById = actorId,
                        ),
                    )
                    is TaxonomyMutation.EditSubcategory -> repository.save(
                        mutation.subcategory.copy(name = name, updatedById = actorId),
                    )
                    is TaxonomyMutation.SetCategoryArchived -> repository.save(
                        mutation.category.copy(archived = mutation.archived, updatedById = actorId),
                    )
                    is TaxonomyMutation.SetSubcategoryArchived -> repository.save(
                        mutation.subcategory.copy(archived = mutation.archived, updatedById = actorId),
                    )
                }
            }.onFailure {
                mutableState.update { it.copy(error = TaxonomyError.SaveFailed) }
            }
            mutableState.update { it.copy(saving = false) }
        }
    }
}

private data class TaxonomySnapshot(
    val categories: List<Category>,
    val subcategories: Map<String, List<Subcategory>>,
    val state: SyncState,
    val error: Throwable?,
)

private fun mostRelevantState(categoryState: SyncState, subcategoryStates: List<SyncState>): SyncState = when {
    categoryState == SyncState.ERROR || SyncState.ERROR in subcategoryStates -> SyncState.ERROR
    categoryState == SyncState.PENDING || SyncState.PENDING in subcategoryStates -> SyncState.PENDING
    categoryState == SyncState.OFFLINE || SyncState.OFFLINE in subcategoryStates -> SyncState.OFFLINE
    else -> SyncState.SYNCED
}
