package pl.bargor.thesaurus.ui.summary

import androidx.lifecycle.ViewModel
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import java.time.Clock
import java.time.Year
import java.time.YearMonth
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
import pl.bargor.thesaurus.data.firebase.LedgerRepository
import pl.bargor.thesaurus.data.firebase.TaxonomyRepository
import pl.bargor.thesaurus.data.model.Category
import pl.bargor.thesaurus.data.model.LedgerEntry
import pl.bargor.thesaurus.data.model.Subcategory
import pl.bargor.thesaurus.data.model.SummaryTotals
import pl.bargor.thesaurus.data.model.SyncState
import pl.bargor.thesaurus.data.model.aggregateEntries
import pl.bargor.thesaurus.data.model.normalizeTags
import pl.bargor.thesaurus.data.model.summaryPeriod

enum class SummaryPeriodMode { MONTH, YEAR }

data class SummaryUiState(
    val month: YearMonth,
    val year: Year,
    val mode: SummaryPeriodMode = SummaryPeriodMode.MONTH,
    val totals: SummaryTotals = SummaryTotals(),
    val filteredTotals: SummaryTotals = SummaryTotals(),
    val entries: List<SummaryEntryItem> = emptyList(),
    val categories: List<Category> = emptyList(),
    val subcategories: List<Subcategory> = emptyList(),
    val tags: List<String> = emptyList(),
    val selectedCategoryId: String? = null,
    val selectedSubcategoryId: String? = null,
    val selectedTag: String? = null,
    val sort: SummaryEntrySort = SummaryEntrySort.DATE,
    val direction: SummarySortDirection = SummarySortDirection.DESCENDING,
    val isLoading: Boolean = true,
    val syncState: SyncState = SyncState.SYNCED,
    val hasError: Boolean = false,
) {
    val hasActiveFilters: Boolean get() =
        selectedCategoryId != null || selectedSubcategoryId != null || selectedTag != null
}

@HiltViewModel
class SummaryViewModel @Inject constructor(
    private val ledgerRepository: LedgerRepository,
    private val taxonomyRepository: TaxonomyRepository,
    clock: Clock,
    private val savedStateHandle: SavedStateHandle,
) : ViewModel() {
    private val currentMonth = YearMonth.now(clock)
    private val initialMonth = YearMonth.of(
        savedStateHandle["summary.year"] ?: currentMonth.year,
        savedStateHandle["summary.month"] ?: currentMonth.monthValue,
    )
    private val mutableState = MutableStateFlow(
        SummaryUiState(
            month = initialMonth, year = Year.of(initialMonth.year),
            mode = savedStateHandle.get<String>("summary.mode")?.let {
                SummaryPeriodMode.entries.firstOrNull { mode -> mode.name == it }
            } ?: SummaryPeriodMode.MONTH,
        ),
    )
    val state: StateFlow<SummaryUiState> = mutableState.asStateFlow()

    init { savePeriod() }

    private var householdId: String? = null
    private var observationJob: Job? = null
    private var latestEntries: List<LedgerEntry>? = null
    private var latestCategories: List<Category> = emptyList()
    private var latestSubcategories: Map<String, List<Subcategory>> = emptyMap()

    @OptIn(ExperimentalCoroutinesApi::class)
    fun start(householdId: String) {
        if (this.householdId == householdId && observationJob?.isActive == true) return
        this.householdId = householdId
        observationJob?.cancel()
        latestEntries = null
        latestCategories = emptyList()
        latestSubcategories = emptyMap()
        mutableState.update { it.copy(
            totals = SummaryTotals(), filteredTotals = SummaryTotals(), entries = emptyList(),
            isLoading = true, hasError = false,
        ) }
        observationJob = viewModelScope.launch {
            combine(
                ledgerRepository.observeEntries(householdId),
                taxonomySnapshots(householdId),
            ) { entries, taxonomy -> entries to taxonomy }.collect { (observation, taxonomy) ->
                observation.value?.let { latestEntries = it }
                taxonomy.categories?.let { latestCategories = it }
                taxonomy.subcategories?.let { latestSubcategories = it }
                mutableState.update { old ->
                    old.recalculated(
                        isLoading = false,
                        syncState = combinedSyncState(observation.state, taxonomy.state),
                        hasError = observation.error != null || taxonomy.error != null ||
                            observation.state == SyncState.ERROR || taxonomy.state == SyncState.ERROR,
                    )
                }
            }
        }
    }

    fun previousMonth() = changeMonth(-1)
    fun nextMonth() = changeMonth(1)
    fun previousYear() = changeYear(-1)
    fun nextYear() = changeYear(1)

    fun selectPeriodMode(mode: SummaryPeriodMode) {
        updatePeriod { old ->
            old.copy(mode = mode).recalculated()
        }
    }

    private fun changeMonth(delta: Long) {
        updatePeriod { old ->
            val month = old.month.plusMonths(delta)
            old.copy(month = month, year = Year.of(month.year)).recalculated()
        }
    }

    private fun changeYear(delta: Long) {
        updatePeriod { old ->
            val year = old.year.plusYears(delta)
            old.copy(year = year, month = YearMonth.of(year.value, old.month.monthValue)).recalculated()
        }
    }

    private fun updatePeriod(transform: (SummaryUiState) -> SummaryUiState) {
        mutableState.update(transform)
        savePeriod()
    }

    private fun savePeriod() {
        val period = mutableState.value
        savedStateHandle["summary.mode"] = period.mode.name
        savedStateHandle["summary.year"] = period.year.value
        savedStateHandle["summary.month"] = period.month.monthValue
    }

    fun selectCategory(id: String?) = mutableState.update { old ->
        old.copy(selectedCategoryId = id, selectedSubcategoryId = null).recalculated()
    }

    fun selectSubcategory(id: String?) = mutableState.update { old ->
        old.copy(selectedSubcategoryId = id?.takeIf { candidate ->
            latestSubcategories[old.selectedCategoryId].orEmpty().any { it.id == candidate }
        }).recalculated()
    }

    fun selectTag(tag: String?) = mutableState.update { old ->
        old.copy(selectedTag = tag?.let { normalizeTags(listOf(it)).firstOrNull() }).recalculated()
    }

    fun selectSort(sort: SummaryEntrySort) = mutableState.update { old ->
        old.copy(sort = sort).recalculated()
    }

    fun toggleSortDirection() = mutableState.update { old ->
        old.copy(direction = if (old.direction == SummarySortDirection.DESCENDING)
            SummarySortDirection.ASCENDING else SummarySortDirection.DESCENDING).recalculated()
    }

    fun clearControls() = mutableState.update { old ->
        old.copy(
            selectedCategoryId = null, selectedSubcategoryId = null, selectedTag = null,
            sort = SummaryEntrySort.DATE, direction = SummarySortDirection.DESCENDING,
        ).recalculated()
    }

    fun retry() { householdId?.let { startAgain(it) } }

    private fun startAgain(householdId: String) {
        observationJob?.cancel()
        observationJob = null
        start(householdId)
    }

    private fun SummaryUiState.recalculated(
        isLoading: Boolean = this.isLoading,
        syncState: SyncState = this.syncState,
        hasError: Boolean = this.hasError,
    ): SummaryUiState {
        val period = summaryPeriod()
        val periodEntries = latestEntries.orEmpty().filter { !it.deleted &&
            !it.date.isBefore(period.from) && !it.date.isAfter(period.to) }
        val selectedEntries = selectSummaryEntries(
            latestEntries.orEmpty(), period, latestCategories, latestSubcategories,
            selectedCategoryId, selectedSubcategoryId, selectedTag, sort, direction,
        )
        return copy(
            totals = aggregateEntries(latestEntries.orEmpty(), period),
            filteredTotals = aggregateEntries(selectedEntries.map { it.entry }, period),
            entries = selectedEntries,
            categories = latestCategories.sortedWith(compareBy(String.CASE_INSENSITIVE_ORDER) { it.name }),
            subcategories = selectedCategoryId?.let { latestSubcategories[it].orEmpty() }
                .orEmpty().sortedWith(compareBy(String.CASE_INSENSITIVE_ORDER) { it.name }),
            tags = periodEntries.flatMap { it.normalizedTags }.distinct().sorted(),
            isLoading = isLoading,
            syncState = syncState,
            hasError = hasError,
        )
    }

    @OptIn(ExperimentalCoroutinesApi::class)
    private fun taxonomySnapshots(householdId: String): Flow<SummaryTaxonomySnapshot> =
        taxonomyRepository.observeCategories(householdId).flatMapLatest { categoryObservation ->
            val categories = categoryObservation.value.orEmpty()
            val flows = categories.map { taxonomyRepository.observeSubcategories(householdId, it.id) }
            if (flows.isEmpty()) flowOf(SummaryTaxonomySnapshot(
                categoryObservation.value, emptyMap(), categoryObservation.state, categoryObservation.error,
            )) else combine(flows) { observations ->
                SummaryTaxonomySnapshot(
                    categoryObservation.value,
                    categories.indices.associate { index -> categories[index].id to observations[index].value.orEmpty() },
                    combinedSyncState(categoryObservation.state, *observations.map { it.state }.toTypedArray()),
                    categoryObservation.error ?: observations.firstNotNullOfOrNull { it.error },
                )
            }
        }
}

private data class SummaryTaxonomySnapshot(
    val categories: List<Category>?,
    val subcategories: Map<String, List<Subcategory>>?,
    val state: SyncState,
    val error: Throwable?,
)

private fun combinedSyncState(vararg states: SyncState): SyncState = when {
    SyncState.ERROR in states -> SyncState.ERROR
    SyncState.PENDING in states -> SyncState.PENDING
    SyncState.OFFLINE in states -> SyncState.OFFLINE
    else -> SyncState.SYNCED
}

private fun SummaryUiState.summaryPeriod() = when (mode) {
    SummaryPeriodMode.MONTH -> month.summaryPeriod()
    SummaryPeriodMode.YEAR -> year.summaryPeriod()
}
