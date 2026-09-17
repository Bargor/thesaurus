package pl.bargor.thesaurus.ui.entries

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import java.time.Instant
import javax.inject.Inject
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import pl.bargor.thesaurus.data.firebase.HouseholdRepository
import pl.bargor.thesaurus.data.firebase.LedgerRepository
import pl.bargor.thesaurus.data.firebase.TaxonomyRepository
import pl.bargor.thesaurus.data.model.Category
import pl.bargor.thesaurus.data.model.LedgerEntry
import pl.bargor.thesaurus.data.model.Member
import pl.bargor.thesaurus.data.model.Subcategory
import pl.bargor.thesaurus.data.model.SyncObservation
import pl.bargor.thesaurus.data.model.SyncState

const val ENTRY_LIST_PAGE_SIZE = 20

enum class EntryListSort { ACCOUNTING_DATE, CREATION_ORDER }

data class EntryListItem(
    val entry: LedgerEntry,
    val categoryName: String?,
    val subcategoryName: String?,
    val authorName: String,
)

data class EntryListUiState(
    val isLoading: Boolean = true,
    val entries: List<EntryListItem> = emptyList(),
    val visibleCount: Int = ENTRY_LIST_PAGE_SIZE,
    val sort: EntryListSort = EntryListSort.ACCOUNTING_DATE,
    val syncState: SyncState = SyncState.SYNCED,
    val error: EntryListError? = null,
) {
    val visibleEntries: List<EntryListItem> get() = entries.take(visibleCount)
    val hasMore: Boolean get() = visibleCount < entries.size
}

sealed interface EntryListError { data object LoadFailed : EntryListError }

private data class TaxonomySnapshot(
    val categories: List<Category>,
    val subcategories: Map<String, List<Subcategory>>,
    val state: SyncState,
    val error: Throwable?,
)

/**
 * Loads a bounded, deterministic page from the locally observed entry collection. The source is
 * retained by Firestore's persistent cache, so a pending local write is immediately listable.
 */
@HiltViewModel
class EntryListViewModel @Inject constructor(
    private val ledgerRepository: LedgerRepository,
    private val taxonomyRepository: TaxonomyRepository,
    private val householdRepository: HouseholdRepository,
    private val sortPreference: EntryListSortPreference,
) : ViewModel() {
    private val mutableState = MutableStateFlow(EntryListUiState(sort = sortPreference.read()))
    val state: StateFlow<EntryListUiState> = mutableState.asStateFlow()

    private var householdId: String? = null
    private var observeJob: Job? = null

    @OptIn(ExperimentalCoroutinesApi::class)
    fun start(householdId: String) {
        if (this.householdId == householdId && observeJob?.isActive == true) return
        this.householdId = householdId
        observeJob?.cancel()
        val sort = sortPreference.read()
        mutableState.value = EntryListUiState(sort = sort)
        observeJob = viewModelScope.launch {
            combine(
                ledgerRepository.observeEntries(householdId),
                taxonomySnapshots(householdId),
                householdRepository.observeMembers(householdId),
            ) { entries, taxonomy, members -> EntryListSnapshot(entries, taxonomy, members) }
                .collect { snapshot ->
                    val error = snapshot.entries.error ?: snapshot.taxonomy.error ?: snapshot.members.error
                    val syncState = relevantSyncState(
                        snapshot.entries.state,
                        snapshot.taxonomy.state,
                        snapshot.members.state,
                    )
                    mutableState.update { old ->
                        val items = snapshot.entries.value.orEmpty().let { entries ->
                            toItems(entries, snapshot.taxonomy, snapshot.members.value.orEmpty(), old.sort)
                        }
                        old.copy(
                            isLoading = false,
                            entries = items,
                            visibleCount = old.visibleCount.coerceAtMost(items.size).coerceAtLeast(ENTRY_LIST_PAGE_SIZE),
                            syncState = syncState,
                            error = error?.let { EntryListError.LoadFailed },
                        )
                    }
                }
        }
    }

    fun changeSort(sort: EntryListSort) {
        if (state.value.sort == sort) return
        sortPreference.save(sort)
        mutableState.update { old ->
            old.copy(sort = sort, entries = sortItems(old.entries, sort), visibleCount = ENTRY_LIST_PAGE_SIZE)
        }
    }

    fun loadNextPage() {
        mutableState.update { old ->
            old.copy(visibleCount = (old.visibleCount + ENTRY_LIST_PAGE_SIZE).coerceAtMost(old.entries.size))
        }
    }

    fun retry() { householdId?.let(::startAfterFailure) }

    private fun startAfterFailure(id: String) {
        observeJob?.cancel()
        observeJob = null
        start(id)
    }

    @OptIn(ExperimentalCoroutinesApi::class)
    private fun taxonomySnapshots(householdId: String): Flow<TaxonomySnapshot> =
        taxonomyRepository.observeCategories(householdId).flatMapLatest { categoryObservation ->
            val categories = categoryObservation.value.orEmpty()
            val flows = categories.map { category -> taxonomyRepository.observeSubcategories(householdId, category.id) }
            if (flows.isEmpty()) {
                flowOf(TaxonomySnapshot(categories, emptyMap(), categoryObservation.state, categoryObservation.error))
            } else {
                combine(flows) { observations ->
                    TaxonomySnapshot(
                        categories = categories,
                        subcategories = categories.indices.associate { index ->
                            categories[index].id to observations[index].value.orEmpty()
                        },
                        state = relevantSyncState(categoryObservation.state, *observations.map { it.state }.toTypedArray()),
                        error = categoryObservation.error ?: observations.firstNotNullOfOrNull { it.error },
                    )
                }
            }
        }

    private fun toItems(
        entries: List<LedgerEntry>,
        taxonomy: TaxonomySnapshot,
        members: List<Member>,
        sort: EntryListSort,
    ): List<EntryListItem> {
        val categories = taxonomy.categories.associateBy { it.id }
        val authors = members.associateBy { it.uid }
        return sortEntries(entries, sort).map { entry ->
            EntryListItem(
                entry = entry,
                categoryName = categories[entry.categoryId]?.name,
                subcategoryName = entry.subcategoryId?.let { subcategoryId ->
                    taxonomy.subcategories[entry.categoryId]
                        .orEmpty()
                        .firstOrNull { it.id == subcategoryId }
                        ?.name
                },
                authorName = authors[entry.authorId].authorLabel(entry.authorId),
            )
        }
    }
}

private data class EntryListSnapshot(
    val entries: SyncObservation<List<LedgerEntry>>,
    val taxonomy: TaxonomySnapshot,
    val members: SyncObservation<List<Member>>,
)

private fun Member?.authorLabel(fallback: String): String =
    this?.displayName?.trim()?.takeIf(String::isNotEmpty) ?: this?.email ?: fallback

/** The id is always the final tie-breaker, so Firestore/cache ordering cannot shuffle equal rows. */
fun sortEntries(entries: List<LedgerEntry>, sort: EntryListSort): List<LedgerEntry> = when (sort) {
    EntryListSort.ACCOUNTING_DATE -> entries.sortedWith(
        compareByDescending<LedgerEntry> { it.date }
            .thenByDescending { it.createdAt ?: Instant.MAX }
            .thenBy { it.id },
    )
    EntryListSort.CREATION_ORDER -> entries.sortedWith(
        compareByDescending<LedgerEntry> { it.createdAt ?: Instant.MAX }
            .thenByDescending { it.date }
            .thenBy { it.id },
    )
}

private fun sortItems(items: List<EntryListItem>, sort: EntryListSort): List<EntryListItem> =
    sortEntries(items.map { it.entry }, sort).map { entry -> items.first { it.entry.id == entry.id } }

private fun relevantSyncState(vararg states: SyncState): SyncState = when {
    SyncState.ERROR in states -> SyncState.ERROR
    SyncState.PENDING in states -> SyncState.PENDING
    SyncState.OFFLINE in states -> SyncState.OFFLINE
    else -> SyncState.SYNCED
}
