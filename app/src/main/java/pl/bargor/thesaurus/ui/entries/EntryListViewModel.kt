package pl.bargor.thesaurus.ui.entries

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import java.time.Instant
import javax.inject.Inject
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import pl.bargor.thesaurus.data.firebase.HouseholdRepository
import pl.bargor.thesaurus.data.firebase.LedgerRepository
import pl.bargor.thesaurus.data.firebase.TaxonomyRepository
import pl.bargor.thesaurus.data.model.LedgerEntry
import pl.bargor.thesaurus.data.model.SyncState
import pl.bargor.thesaurus.data.observation.HouseholdObservation

/** Number of already observed entries exposed by each local reveal action. */
const val ENTRY_LIST_REVEAL_SIZE = 20

enum class EntryListSort { ACCOUNTING_DATE, CREATION_ORDER }

data class EntryListItem(
    val entry: LedgerEntry,
    val categoryName: String?,
    val subcategoryName: String?,
    val authorName: String,
    val canManage: Boolean = false,
    val categoryColor: String? = null,
)

data class EntryListUiState(
    val isLoading: Boolean = true,
    val entries: List<EntryListItem> = emptyList(),
    val visibleCount: Int = ENTRY_LIST_REVEAL_SIZE,
    val sort: EntryListSort = EntryListSort.ACCOUNTING_DATE,
    val syncState: SyncState = SyncState.SYNCED,
    val error: EntryListError? = null,
    val pendingDeletion: EntryListItem? = null,
    val deletionError: Boolean = false,
) {
    val visibleEntries: List<EntryListItem> get() = entries.take(visibleCount)
    val hasMore: Boolean get() = visibleCount < entries.size
}

sealed interface EntryListError { data object LoadFailed : EntryListError }

// Material's short snackbar is normally about four seconds. The small buffer prevents a
// last-frame action from racing a Firestore tombstone that has already been submitted.
const val ENTRY_DELETE_UNDO_WINDOW_MILLIS = 6_000L

/**
 * Observes the complete active household ledger and reveals a sorted local prefix in increments.
 * Revealing entries does not fetch a Firestore page or change the complete-history consumers.
 * Firestore's persistent cache makes cached entries and pending local writes immediately listable.
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
    private var actorId: String? = null
    private var observeJob: Job? = null
    private var deleteJob: Job? = null
    private val locallyHiddenEntryIds = mutableSetOf<String>()

    fun start(householdId: String) = start(householdId, actorId = "")

    fun start(householdId: String, actorId: String) {
        if (this.householdId == householdId && this.actorId == actorId && observeJob?.isActive == true) return
        this.householdId = householdId
        this.actorId = actorId
        observeJob?.cancel()
        deleteJob?.cancel()
        locallyHiddenEntryIds.clear()
        val sort = sortPreference.read()
        mutableState.value = EntryListUiState(sort = sort)
        observeJob = viewModelScope.launch {
            HouseholdObservation(ledgerRepository, taxonomyRepository, householdRepository)
                .observe(householdId).collect { snapshot ->
                    if (this@EntryListViewModel.householdId != householdId ||
                        this@EntryListViewModel.actorId != actorId) return@collect
                    if (!snapshot.isReady) {
                        mutableState.update { old -> old.copy(
                            isLoading = old.isLoading && !snapshot.hasError,
                            syncState = snapshot.syncState,
                            error = if (snapshot.hasError) EntryListError.LoadFailed else old.error,
                        ) }
                        return@collect
                    }
                    mutableState.update { old ->
                        val categories = snapshot.categories.value.orEmpty().associateBy { it.id }
                        val authors = snapshot.members.value.orEmpty().associateBy { it.uid }
                        val items = sortEntries(snapshot.entries.value.orEmpty().filterNot { it.deleted }, old.sort).map { entry ->
                            presentEntry(entry, categories, snapshot.subcategoryValues, authors, actorId,
                                trimAuthorName = true).listItem(entry)
                        }
                        locallyHiddenEntryIds.retainAll(items.map { it.entry.id }.toSet())
                        val visibleItems = items.filterNot { it.entry.id in locallyHiddenEntryIds }
                        old.copy(
                            isLoading = false,
                            entries = visibleItems,
                            visibleCount = old.visibleCount.coerceAtMost(visibleItems.size).coerceAtLeast(ENTRY_LIST_REVEAL_SIZE),
                            syncState = snapshot.syncState,
                            error = if (snapshot.hasError) EntryListError.LoadFailed else null,
                        )
                    }
                }
        }
    }

    fun changeSort(sort: EntryListSort) {
        if (state.value.sort == sort) return
        sortPreference.save(sort)
        mutableState.update { old ->
            old.copy(sort = sort, entries = sortItems(old.entries, sort), visibleCount = ENTRY_LIST_REVEAL_SIZE)
        }
    }

    /** Reveals more of the current local ordering without restarting or limiting observation. */
    fun revealMoreEntries() {
        mutableState.update { old ->
            old.copy(visibleCount = (old.visibleCount + ENTRY_LIST_REVEAL_SIZE).coerceAtMost(old.entries.size))
        }
    }

    fun retry() { householdId?.let(::startAfterFailure) }

    /** Starts the undo window only after the confirmation dialog is accepted. */
    fun confirmDelete(item: EntryListItem) {
        val household = householdId ?: return
        val actor = actorId ?: return
        val current = state.value.entries.firstOrNull { it.entry.id == item.entry.id } ?: return
        if (!current.canManage || deleteJob?.isActive == true) return
        locallyHiddenEntryIds += item.entry.id
        mutableState.update { old ->
            old.copy(
                entries = old.entries.filterNot { it.entry.id == current.entry.id },
                pendingDeletion = current,
                deletionError = false,
            )
        }
        deleteJob = viewModelScope.launch {
            delay(ENTRY_DELETE_UNDO_WINDOW_MILLIS)
            runCatching { ledgerRepository.tombstone(household, current.entry.id, actor) }
                .onFailure {
                    locallyHiddenEntryIds -= current.entry.id
                    mutableState.update { old ->
                        old.copy(
                            entries = sortItems(old.entries + current, old.sort),
                            pendingDeletion = null,
                            deletionError = true,
                        )
                    }
                }
                .onSuccess {
                    // Retain the local hide until Firestore's active-entry listener removes the row.
                    mutableState.update { old -> old.copy(pendingDeletion = null) }
                }
        }
    }

    fun undoDelete() {
        val pending = state.value.pendingDeletion ?: return
        deleteJob?.cancel()
        deleteJob = null
        locallyHiddenEntryIds -= pending.entry.id
        mutableState.update { old ->
            old.copy(
                entries = sortItems(old.entries + pending, old.sort),
                pendingDeletion = null,
                deletionError = false,
            )
        }
    }

    private fun startAfterFailure(id: String) {
        observeJob?.cancel()
        observeJob = null
        actorId?.let { start(id, it) }
    }

    override fun onCleared() {
        // A queued delete is deliberately not committed after this UI owner disappears. The original
        // Firestore document remains active, so a recreated list deterministically shows it again.
        deleteJob?.cancel()
        locallyHiddenEntryIds.clear()
        super.onCleared()
    }
}

/** The id is always the final tie-breaker, so Firestore/cache ordering cannot shuffle equal rows. */
fun sortEntries(entries: List<LedgerEntry>, sort: EntryListSort): List<LedgerEntry> =
    entries.sortedWith(entryListComparator(sort))

private fun entryListComparator(sort: EntryListSort): Comparator<LedgerEntry> = when (sort) {
    EntryListSort.ACCOUNTING_DATE ->
        compareByDescending<LedgerEntry> { it.date }
            .thenByDescending { it.createdAt ?: Instant.MAX }
            .thenBy { it.id }
    EntryListSort.CREATION_ORDER ->
        compareByDescending<LedgerEntry> { it.createdAt ?: Instant.MAX }
            .thenByDescending { it.date }
            .thenBy { it.id }
}

/** Sort row objects directly: O(n log n), retaining their metadata and object identity. */
internal fun sortItems(items: List<EntryListItem>, sort: EntryListSort): List<EntryListItem> {
    val comparator = entryListComparator(sort)
    return items.sortedWith { left, right -> comparator.compare(left.entry, right.entry) }
}
