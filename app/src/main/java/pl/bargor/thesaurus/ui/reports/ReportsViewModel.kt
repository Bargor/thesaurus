package pl.bargor.thesaurus.ui.reports

import androidx.lifecycle.ViewModel
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import java.time.Clock
import java.time.LocalDate
import java.time.Year
import java.time.YearMonth
import javax.inject.Inject
import kotlinx.coroutines.Job
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import pl.bargor.thesaurus.data.firebase.LedgerRepository
import pl.bargor.thesaurus.data.firebase.TaxonomyRepository
import pl.bargor.thesaurus.data.model.Category
import pl.bargor.thesaurus.data.model.LedgerEntry
import pl.bargor.thesaurus.data.model.ReportAggregation
import pl.bargor.thesaurus.data.model.ReportTypeFilter
import pl.bargor.thesaurus.data.model.SummaryPeriod
import pl.bargor.thesaurus.data.model.Subcategory
import pl.bargor.thesaurus.data.model.SyncObservation
import pl.bargor.thesaurus.data.model.SyncState
import pl.bargor.thesaurus.data.model.aggregateReportEntries

enum class ReportPeriodMode { MONTH, YEAR, CUSTOM }

data class ReportEntryItem(
    val entry: LedgerEntry,
    val categoryName: String,
    val categoryColor: String? = null,
    val subcategoryName: String? = null,
)

data class ReportsUiState(
    val today: LocalDate,
    val month: YearMonth,
    val year: Year,
    val mode: ReportPeriodMode = ReportPeriodMode.MONTH,
    val typeFilter: ReportTypeFilter = ReportTypeFilter.ALL,
    val customFromInput: String = today.withDayOfMonth(1).toString(),
    val customToInput: String = today.toString(),
    val customDateError: Boolean = false,
    val aggregation: ReportAggregation = ReportAggregation(),
    val entries: List<ReportEntryItem> = emptyList(),
    val isLoading: Boolean = true,
    val syncState: SyncState = SyncState.SYNCED,
    val hasError: Boolean = false,
    val categories: List<Category> = emptyList(),
    val subcategories: List<Subcategory> = emptyList(),
    val selectedCategoryId: String? = null,
    val selectedSubcategoryId: String? = null,
    val sort: ReportEntrySort = ReportEntrySort.DATE,
    val direction: ReportSortDirection = ReportSortDirection.DESCENDING,
) {
    val hasActiveFilters: Boolean get() = selectedCategoryId != null || selectedSubcategoryId != null
    /** Current and future calendar periods never make the report claim dates after today. */
    fun period(): SummaryPeriod = when (mode) {
        ReportPeriodMode.MONTH -> boundedPeriod(month.atDay(1), month.atEndOfMonth(), today)
        ReportPeriodMode.YEAR -> boundedPeriod(year.atDay(1), year.atMonth(12).atEndOfMonth(), today)
        ReportPeriodMode.CUSTOM -> {
            val from = LocalDate.parse(customFromInput).coerceAtMost(today)
            val to = LocalDate.parse(customToInput).coerceAtMost(today)
            SummaryPeriod(from, to)
        }
    }
}

internal fun boundedPeriod(from: LocalDate, to: LocalDate, today: LocalDate): SummaryPeriod {
    val end = to.coerceAtMost(today)
    return SummaryPeriod(from.coerceAtMost(end), end)
}

@HiltViewModel
class ReportsViewModel @Inject constructor(
    private val ledgerRepository: LedgerRepository,
    private val taxonomyRepository: TaxonomyRepository,
    clock: Clock,
    private val savedStateHandle: SavedStateHandle,
) : ViewModel() {
    private val today = LocalDate.now(clock)
    private val mutableState = MutableStateFlow(
        ReportsUiState(today = today, month = YearMonth.from(today), year = Year.from(today),
            sort = ReportEntrySort.entries.firstOrNull { it.name == savedStateHandle.get<String>("reports.sort") }
                ?: ReportEntrySort.DATE,
            direction = ReportSortDirection.entries.firstOrNull { it.name == savedStateHandle.get<String>("reports.direction") }
                ?: ReportSortDirection.DESCENDING),
    )
    val state: StateFlow<ReportsUiState> = mutableState.asStateFlow()
    private var householdId: String? = null
    private var observeJob: Job? = null
    private var latestEntries: List<LedgerEntry>? = null
    private var latestEntriesSource: List<LedgerEntry>? = null
    private var historicalCategoryIds: Set<String> = emptySet()
    private var latestCategories: List<Category> = emptyList()
    private val latestSubcategories = mutableMapOf<String, List<Subcategory>>()
    private val subcategoryJobs = mutableMapOf<String, Job>()
    private val observations = mutableMapOf<String, SyncObservation<*>>()
    private var entriesObserved = false
    private var appliedCustomPeriod = SummaryPeriod(today.withDayOfMonth(1), today)
    private var cachedEntries: List<LedgerEntry>? = null
    private var cachedSelection: ReportSelection? = null
    private var cachedSelectedEntries: List<LedgerEntry> = emptyList()
    private var cachedAggregation = ReportAggregation()

    init {
        savedStateHandle.keys().filter { it.contains("tag", ignoreCase = true) }.forEach {
            savedStateHandle.remove<Any?>(it)
        }
        saveSort()
    }

    fun start(householdId: String) {
        if (this.householdId == householdId && observeJob?.isActive == true) return
        val changed = this.householdId != householdId
        observeJob?.cancel()
        subcategoryJobs.clear()
        this.householdId = householdId
        if (changed) {
            latestEntries = null; latestCategories = emptyList(); latestSubcategories.clear()
            latestEntriesSource = null; historicalCategoryIds = emptySet()
            cachedEntries = null; cachedSelection = null
            cachedSelectedEntries = emptyList(); cachedAggregation = ReportAggregation()
            observations.clear(); entriesObserved = false
            mutableState.update { it.copy(isLoading = true, hasError = false, syncState = SyncState.SYNCED,
                aggregation = ReportAggregation(), entries = emptyList(), categories = emptyList(), subcategories = emptyList(),
                selectedCategoryId = null, selectedSubcategoryId = null) }
        }
        observeJob = viewModelScope.launch(start = CoroutineStart.LAZY) {
            launch {
                ledgerRepository.observeEntries(householdId).withReportErrors().collect { observation ->
                    observations["entries"] = observation
                    observation.value?.let { values ->
                        if (values !== latestEntriesSource && values != latestEntriesSource) {
                            latestEntriesSource = values
                            latestEntries = values.filter { entry -> entry.householdId == householdId }
                            historicalCategoryIds = latestEntries.orEmpty().asSequence().filterNot { it.deleted }
                                .map { it.categoryId }.toSet()
                        }
                    }
                    entriesObserved = true
                    reconcileSubcategories(householdId)
                    refresh()
                }
            }
            launch {
                taxonomyRepository.observeCategories(householdId).withReportErrors().collect { observation ->
                    observations["categories"] = observation
                    observation.value?.let { latestCategories = it.filter { category -> category.householdId == householdId } }
                    reconcileSubcategories(householdId)
                    refresh()
                }
            }
        }
        observeJob?.start()
    }

    private fun reconcileSubcategories(householdId: String) {
        val required = latestCategories.mapTo(mutableSetOf()) { it.id }
        required.addAll(historicalCategoryIds)
        (subcategoryJobs.keys - required).forEach { id ->
            subcategoryJobs.remove(id)?.cancel(); latestSubcategories.remove(id); observations.remove("sub:$id")
        }
        val parent = observeJob ?: return
        required.filterNot { it in subcategoryJobs }.forEach { id ->
            subcategoryJobs[id] = viewModelScope.launch(parent) {
                taxonomyRepository.observeSubcategories(householdId, id).withReportErrors().collect { observation ->
                    observations["sub:$id"] = observation
                    observation.value?.let { values -> latestSubcategories[id] = values.filter {
                        it.householdId == householdId && it.categoryId == id
                    } }
                    refresh()
                }
            }
        }
    }

    private fun refresh() = mutableState.update { old ->
        val states = observations.values.map { it.state }
        old.recalculated(latestEntries.orEmpty(), latestCategories, isLoading = !entriesObserved,
            syncState = reportSyncState(states),
            hasError = observations.values.any { it.error != null || it.state == SyncState.ERROR })
    }

    fun selectCategory(id: String?) = mutableState.update { old ->
        old.copy(selectedCategoryId = id?.takeIf { value -> latestCategories.any { it.id == value } },
            selectedSubcategoryId = null).recalculated(latestEntries.orEmpty(), latestCategories)
    }
    fun selectSubcategory(id: String?) = mutableState.update { old ->
        old.copy(selectedSubcategoryId = id?.takeIf { value -> latestSubcategories[old.selectedCategoryId]
            .orEmpty().any { it.id == value } }).recalculated(latestEntries.orEmpty(), latestCategories)
    }
    fun selectSort(sort: ReportEntrySort) {
        mutableState.update { it.copy(sort = sort).recalculated(latestEntries.orEmpty(), latestCategories) }
        saveSort()
    }
    fun toggleSortDirection() {
        mutableState.update { it.copy(direction = if (it.direction == ReportSortDirection.DESCENDING)
            ReportSortDirection.ASCENDING else ReportSortDirection.DESCENDING)
            .recalculated(latestEntries.orEmpty(), latestCategories) }
        saveSort()
    }
    fun clearControls() {
        mutableState.update { it.copy(selectedCategoryId = null, selectedSubcategoryId = null,
            sort = ReportEntrySort.DATE, direction = ReportSortDirection.DESCENDING)
            .recalculated(latestEntries.orEmpty(), latestCategories) }
        saveSort()
    }
    private fun saveSort() {
        savedStateHandle["reports.sort"] = mutableState.value.sort.name
        savedStateHandle["reports.direction"] = mutableState.value.direction.name
    }

    fun selectPeriodMode(mode: ReportPeriodMode) = mutableState.update { old ->
        val selected = old.copy(mode = mode, customDateError = false)
        selected.recalculated(latestEntries.orEmpty(), latestCategories)
    }

    fun selectType(type: ReportTypeFilter) = mutableState.update { old ->
        old.copy(typeFilter = type).recalculated(latestEntries.orEmpty(), latestCategories)
    }

    fun previousPeriod() = mutableState.update { old ->
        val changed = when (old.mode) {
            ReportPeriodMode.MONTH -> old.copy(month = old.month.minusMonths(1))
            ReportPeriodMode.YEAR -> old.copy(year = old.year.minusYears(1))
            ReportPeriodMode.CUSTOM -> old
        }
        changed.recalculated(latestEntries.orEmpty(), latestCategories)
    }

    fun nextPeriod() = mutableState.update { old ->
        val changed = when (old.mode) {
            ReportPeriodMode.MONTH -> old.copy(month = old.month.plusMonths(1).coerceAtMost(YearMonth.from(today)))
            ReportPeriodMode.YEAR -> old.copy(year = old.year.plusYears(1).coerceAtMost(Year.from(today)))
            ReportPeriodMode.CUSTOM -> old
        }
        changed.recalculated(latestEntries.orEmpty(), latestCategories)
    }

    fun updateCustomFrom(value: String) = mutableState.update { it.copy(customFromInput = value, customDateError = false) }
    fun updateCustomTo(value: String) = mutableState.update { it.copy(customToInput = value, customDateError = false) }

    fun applyCustomPeriod() = mutableState.update { old ->
        val parsed = runCatching { old.period() }.getOrNull()
        if (parsed == null) old.copy(customDateError = true) else {
            appliedCustomPeriod = parsed
            old.copy(customDateError = false).recalculated(latestEntries.orEmpty(), latestCategories)
        }
    }

    fun retry() { householdId?.let { id -> observeJob?.cancel(); observeJob = null; start(id) } }

    private fun ReportsUiState.recalculated(
        entries: List<LedgerEntry>,
        categories: List<Category>,
        isLoading: Boolean = this.isLoading,
        syncState: SyncState = this.syncState,
        hasError: Boolean = this.hasError,
    ): ReportsUiState {
        val period = if (mode == ReportPeriodMode.CUSTOM) appliedCustomPeriod else period()
        val categoryId = selectedCategoryId?.takeIf { id -> categories.any { it.id == id } }
        val availableSubs = latestSubcategories[categoryId].orEmpty()
        val subcategoryId = selectedSubcategoryId?.takeIf { id -> availableSubs.any { it.id == id } }
        val selection = ReportSelection(householdId.orEmpty(), period, typeFilter, categoryId, subcategoryId,
            sort, direction, mode == ReportPeriodMode.YEAR)
        if (entries !== cachedEntries || selection != cachedSelection) {
            cachedEntries = entries
            cachedSelection = selection
            cachedSelectedEntries = selectReportEntries(entries, selection.householdId, period, typeFilter,
                categoryId, subcategoryId, sort, direction)
            val bucket: (LocalDate) -> LocalDate = if (selection.monthlyTrend) {
                date -> date.withDayOfMonth(1)
            } else { date -> date }
            val rawAggregation = aggregateReportEntries(cachedSelectedEntries, period, typeFilter, bucket)
            // Expense trends use magnitudes while totals keep the signed balance.
            cachedAggregation = if (typeFilter == ReportTypeFilter.EXPENSE) rawAggregation.copy(
                trend = rawAggregation.trend.map { it.copy(amountGrosze = it.amountGrosze.abs()) },
            ) else rawAggregation
        }
        val taxonomy = categories.associateBy { it.id }
        return copy(
            categories = categories.sortedWith(compareBy(String.CASE_INSENSITIVE_ORDER) { it.name }),
            subcategories = availableSubs.sortedWith(compareBy(String.CASE_INSENSITIVE_ORDER) { it.name }),
            selectedCategoryId = categoryId,
            selectedSubcategoryId = subcategoryId,
            aggregation = cachedAggregation,
            entries = cachedSelectedEntries.map { entry ->
                    ReportEntryItem(
                        entry = entry,
                        categoryName = taxonomy[entry.categoryId]?.name ?: entry.categoryId,
                        categoryColor = taxonomy[entry.categoryId]?.color,
                        subcategoryName = entry.subcategoryId?.let { id ->
                            latestSubcategories[entry.categoryId].orEmpty().firstOrNull { it.id == id }?.name ?: id
                        },
                    )
                },
            isLoading = isLoading,
            syncState = syncState,
            hasError = hasError,
        )
    }
}

private fun reportSyncState(states: List<SyncState>): SyncState = when {
    SyncState.ERROR in states -> SyncState.ERROR
    SyncState.PENDING in states -> SyncState.PENDING
    SyncState.OFFLINE in states -> SyncState.OFFLINE
    else -> SyncState.SYNCED
}

private data class ReportSelection(
    val householdId: String,
    val period: SummaryPeriod,
    val type: ReportTypeFilter,
    val categoryId: String?,
    val subcategoryId: String?,
    val sort: ReportEntrySort,
    val direction: ReportSortDirection,
    val monthlyTrend: Boolean,
)

private fun <T> Flow<SyncObservation<T>>.withReportErrors(): Flow<SyncObservation<T>> =
    catch { emit(SyncObservation(state = SyncState.ERROR, error = it)) }
