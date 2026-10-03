package pl.bargor.thesaurus.ui.reports

import androidx.lifecycle.ViewModel
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import java.time.Clock
import java.time.LocalDate
import java.time.Year
import java.time.YearMonth
import java.time.temporal.ChronoUnit
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
import pl.bargor.thesaurus.data.firebase.HouseholdRepository
import pl.bargor.thesaurus.data.model.Member
import pl.bargor.thesaurus.data.firebase.TaxonomyRepository
import pl.bargor.thesaurus.data.model.Category
import pl.bargor.thesaurus.data.model.LedgerEntry
import pl.bargor.thesaurus.data.model.ReportAggregation
import pl.bargor.thesaurus.data.model.ReportBalanceTrend
import pl.bargor.thesaurus.data.model.ReportBalanceGranularity
import pl.bargor.thesaurus.data.model.buildReportBalanceTrend
import pl.bargor.thesaurus.data.model.ReportTypeFilter
import pl.bargor.thesaurus.data.model.SummaryPeriod
import pl.bargor.thesaurus.data.model.Subcategory
import pl.bargor.thesaurus.data.model.SyncObservation
import pl.bargor.thesaurus.data.model.SyncState
import pl.bargor.thesaurus.data.model.aggregateReportEntries

enum class ReportPeriodMode { MONTH, YEAR, CUSTOM }

data class ReportMemberOption(val id: String, val name: String, val former: Boolean = false)

data class ReportFilterDraft(
    val mode: ReportPeriodMode,
    val month: YearMonth,
    val year: Year,
    val typeFilter: ReportTypeFilter = ReportTypeFilter.ALL,
    val customFromInput: String,
    val customToInput: String,
    val customDateError: Boolean = false,
    val selectedCategoryId: String? = null,
    val selectedSubcategoryId: String? = null,
    val selectedMemberIds: Set<String>? = null,
    val sort: ReportEntrySort = ReportEntrySort.DATE,
    val direction: ReportSortDirection = ReportSortDirection.DESCENDING,
)

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
    val selectedMemberIds: Set<String>? = null,
    val members: List<ReportMemberOption> = emptyList(),
    val filterDraft: ReportFilterDraft? = null,
    val allSubcategories: List<Subcategory> = emptyList(),
    val balanceTrend: ReportBalanceTrend? = null,
) {
    val hasActiveFilters: Boolean get() = mode != ReportPeriodMode.MONTH || month != YearMonth.from(today) ||
        typeFilter != ReportTypeFilter.ALL || selectedCategoryId != null || selectedSubcategoryId != null || selectedMemberIds != null ||
        sort != ReportEntrySort.DATE || direction != ReportSortDirection.DESCENDING
    /** Current and future calendar periods never make the report claim dates after today. */
    fun period(): SummaryPeriod = when (mode) {
        ReportPeriodMode.MONTH -> boundedPeriod(month.atDay(1), month.atEndOfMonth(), today)
        ReportPeriodMode.YEAR -> boundedPeriod(year.atDay(1), year.atMonth(12).atEndOfMonth(), today)
        ReportPeriodMode.CUSTOM -> {
            val from = LocalDate.parse(customFromInput)
            val to = LocalDate.parse(customToInput)
            require(from <= to && to <= today && from <= today)
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
    private val householdRepository: HouseholdRepository,
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
    private var latestMembers: List<Member> = emptyList()
    private val latestSubcategories = mutableMapOf<String, List<Subcategory>>()
    private val subcategoryJobs = mutableMapOf<String, Job>()
    private val observations = mutableMapOf<String, SyncObservation<*>>()
    private var entriesObserved = false
    private var appliedCustomPeriod = SummaryPeriod(today.withDayOfMonth(1), today)
    private var cachedEntries: List<LedgerEntry>? = null
    private var cachedSelection: ReportSelection? = null
    private var cachedSelectedEntries: List<LedgerEntry> = emptyList()
    private var cachedAggregation = ReportAggregation()
    private var cachedBalanceTrend: ReportBalanceTrend? = null

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
            latestMembers = emptyList()
            latestEntries = null; latestCategories = emptyList(); latestSubcategories.clear()
            latestEntriesSource = null; historicalCategoryIds = emptySet()
            cachedEntries = null; cachedSelection = null
            cachedSelectedEntries = emptyList(); cachedAggregation = ReportAggregation()
            cachedBalanceTrend = null
            observations.clear(); entriesObserved = false
            mutableState.update { it.copy(isLoading = true, hasError = false, syncState = SyncState.SYNCED,
                aggregation = ReportAggregation(), entries = emptyList(), categories = emptyList(), subcategories = emptyList(),
                selectedCategoryId = null, selectedSubcategoryId = null, selectedMemberIds = null,
                members = emptyList(), filterDraft = null, allSubcategories = emptyList(), balanceTrend = null) }
            restoreFilters(householdId)
        }
        observeJob = viewModelScope.launch(start = CoroutineStart.LAZY) {
            launch {
                householdRepository.observeMembers(householdId).withReportErrors().collect { observation ->
                    observations["members"] = observation
                    observation.value?.let { latestMembers = it }
                    refresh()
                }
            }
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
        val current = latestMembers.associateBy { it.uid }
        val former = (latestEntries.orEmpty().filterNot { it.deleted }.map { it.authorId } +
            old.selectedMemberIds.orEmpty() + old.filterDraft?.selectedMemberIds.orEmpty()).distinct()
            .filterNot { it in current }.map { ReportMemberOption(it, it, former = true) }
        old.copy(members = (current.values.map { ReportMemberOption(it.uid,
            it.displayName?.takeIf(String::isNotBlank)?.let { name -> "$name (${it.email})" } ?: it.email) } + former).sortedBy { it.name.lowercase() })
            .recalculated(latestEntries.orEmpty(), latestCategories, isLoading = !entriesObserved,
            syncState = reportSyncState(states),
            hasError = observations.values.any { it.error != null || it.state == SyncState.ERROR })
    }

    fun selectCategory(id: String?) {
        val selected = id?.takeIf { value -> latestCategories.any { it.id == value } ||
            value == mutableState.value.selectedCategoryId || value == mutableState.value.filterDraft?.selectedCategoryId }
        if (editDraft { it.copy(selectedCategoryId = selected,
            selectedSubcategoryId = null) }) return
        mutableState.update { old ->
            old.copy(selectedCategoryId = selected,
                selectedSubcategoryId = null).recalculated(latestEntries.orEmpty(), latestCategories)
        }
        saveFilters()
    }
    fun selectSubcategory(id: String?) {
        if (editDraft { it.copy(selectedSubcategoryId = id?.takeIf { value ->
            latestSubcategories[it.selectedCategoryId].orEmpty().any { sub -> sub.id == value } ||
                (it.selectedCategoryId != null && value == it.selectedSubcategoryId) ||
                (it.selectedCategoryId != null && it.selectedCategoryId == mutableState.value.selectedCategoryId &&
                    value == mutableState.value.selectedSubcategoryId) }) }) return
        mutableState.update { old ->
            old.copy(selectedSubcategoryId = id?.takeIf { value -> latestSubcategories[old.selectedCategoryId]
                .orEmpty().any { it.id == value } || (old.selectedCategoryId != null && value == old.selectedSubcategoryId) })
                .recalculated(latestEntries.orEmpty(), latestCategories)
        }
        saveFilters()
    }
    fun selectSort(sort: ReportEntrySort) {
        if (editDraft { it.copy(sort = sort) }) return
        mutableState.update { it.copy(sort = sort).recalculated(latestEntries.orEmpty(), latestCategories) }
        saveSort()
    }
    fun toggleSortDirection() {
        if (editDraft { it.copy(direction = if (it.direction == ReportSortDirection.DESCENDING)
            ReportSortDirection.ASCENDING else ReportSortDirection.DESCENDING) }) return
        mutableState.update { it.copy(direction = if (it.direction == ReportSortDirection.DESCENDING)
            ReportSortDirection.ASCENDING else ReportSortDirection.DESCENDING)
            .recalculated(latestEntries.orEmpty(), latestCategories) }
        saveSort()
    }
    fun clearControls() {
        if (editDraft { it.copy(sort = ReportEntrySort.DATE, direction = ReportSortDirection.DESCENDING) }) return
        mutableState.update { it.copy(sort = ReportEntrySort.DATE, direction = ReportSortDirection.DESCENDING)
            .recalculated(latestEntries.orEmpty(), latestCategories) }
        saveSort()
    }
    private fun saveSort() {
        savedStateHandle["reports.sort"] = mutableState.value.sort.name
        savedStateHandle["reports.direction"] = mutableState.value.direction.name
    }

    fun selectPeriodMode(mode: ReportPeriodMode) {
        if (editDraft { it.copy(mode = mode, customDateError = false) }) return
        mutableState.update { old ->
            val selected = old.copy(mode = mode, customDateError = false)
            selected.recalculated(latestEntries.orEmpty(), latestCategories)
        }
        saveFilters()
    }

    fun selectType(type: ReportTypeFilter) {
        if (editDraft { it.copy(typeFilter = type) }) return
        mutableState.update { old ->
            old.copy(typeFilter = type).recalculated(latestEntries.orEmpty(), latestCategories)
        }
        saveFilters()
    }

    fun previousPeriod() {
        if (editDraft { when (it.mode) {
            ReportPeriodMode.MONTH -> it.copy(month = it.month.minusMonths(1))
            ReportPeriodMode.YEAR -> it.copy(year = it.year.minusYears(1))
            ReportPeriodMode.CUSTOM -> it
        } }) return
        mutableState.update { old ->
            val changed = when (old.mode) {
                ReportPeriodMode.MONTH -> old.copy(month = old.month.minusMonths(1))
                ReportPeriodMode.YEAR -> old.copy(year = old.year.minusYears(1))
                ReportPeriodMode.CUSTOM -> old
            }
            changed.recalculated(latestEntries.orEmpty(), latestCategories)
        }
        saveFilters()
    }

    fun nextPeriod() {
        if (editDraft { when (it.mode) {
            ReportPeriodMode.MONTH -> it.copy(month = it.month.plusMonths(1).coerceAtMost(YearMonth.from(today)))
            ReportPeriodMode.YEAR -> it.copy(year = it.year.plusYears(1).coerceAtMost(Year.from(today)))
            ReportPeriodMode.CUSTOM -> it
        } }) return
        mutableState.update { old ->
            val changed = when (old.mode) {
                ReportPeriodMode.MONTH -> old.copy(month = old.month.plusMonths(1).coerceAtMost(YearMonth.from(today)))
                ReportPeriodMode.YEAR -> old.copy(year = old.year.plusYears(1).coerceAtMost(Year.from(today)))
                ReportPeriodMode.CUSTOM -> old
            }
            changed.recalculated(latestEntries.orEmpty(), latestCategories)
        }
        saveFilters()
    }

    fun updateCustomFrom(value: String) {
        if (!editDraft { it.copy(customFromInput = value, customDateError = false) })
            mutableState.update { it.copy(customFromInput = value, customDateError = false) }
    }
    fun updateCustomTo(value: String) {
        if (!editDraft { it.copy(customToInput = value, customDateError = false) })
            mutableState.update { it.copy(customToInput = value, customDateError = false) }
    }

    fun applyCustomPeriod() {
        if (mutableState.value.filterDraft != null) { applyFilters(); return }
        mutableState.update { old ->
            val parsed = runCatching { old.period() }.getOrNull()
            if (parsed == null) old.copy(customDateError = true) else {
                appliedCustomPeriod = parsed
                old.copy(customDateError = false).recalculated(latestEntries.orEmpty(), latestCategories)
            }
        }
        saveFilters()
    }

    private fun ReportsUiState.toDraft() = ReportFilterDraft(mode, month, year, typeFilter,
        customFromInput, customToInput, selectedCategoryId = selectedCategoryId,
        selectedSubcategoryId = selectedSubcategoryId, selectedMemberIds = selectedMemberIds,
        sort = sort, direction = direction)

    fun openFilters() { mutableState.update { it.copy(filterDraft = it.toDraft()) } }
    fun dismissFilters() {
        mutableState.update { it.copy(filterDraft = null) }
        refresh()
    }
    private fun editDraft(change: (ReportFilterDraft) -> ReportFilterDraft): Boolean {
        val draft = mutableState.value.filterDraft ?: return false
        mutableState.update { it.copy(filterDraft = change(draft)) }
        return true
    }
    fun resetFilters() {
        editDraft { ReportFilterDraft(ReportPeriodMode.MONTH, YearMonth.from(today), Year.from(today),
            customFromInput = today.withDayOfMonth(1).toString(), customToInput = today.toString()) }
        refresh()
    }
    fun selectMembers(ids: Set<String>?) {
        val authorized = latestMembers.mapTo(mutableSetOf()) { it.uid }.apply {
            addAll(latestEntries.orEmpty().filterNot { it.deleted }.map { it.authorId })
            addAll(mutableState.value.selectedMemberIds.orEmpty())
            addAll(mutableState.value.filterDraft?.selectedMemberIds.orEmpty())
        }
        val selected = ids?.intersect(authorized)
        if (editDraft { it.copy(selectedMemberIds = selected) }) {
            refresh()
            return
        }
        mutableState.update { it.copy(selectedMemberIds = selected).recalculated(latestEntries.orEmpty(), latestCategories) }
        saveFilters()
        refresh()
    }
    fun applyFilters() {
        val draft = mutableState.value.filterDraft ?: return
        val period = runCatching {
            val from = LocalDate.parse(draft.customFromInput)
            val to = LocalDate.parse(draft.customToInput)
            require(from <= to && from <= today && to <= today)
            SummaryPeriod(from, to)
        }.getOrNull()
        if (draft.mode == ReportPeriodMode.CUSTOM && period == null) {
            editDraft { it.copy(customDateError = true) }; return
        }
        if (period != null) appliedCustomPeriod = period
        mutableState.update { it.copy(mode = draft.mode, month = draft.month, year = draft.year,
            typeFilter = draft.typeFilter, customFromInput = draft.customFromInput,
            customToInput = draft.customToInput, customDateError = false,
            selectedCategoryId = draft.selectedCategoryId, selectedSubcategoryId = draft.selectedSubcategoryId,
            selectedMemberIds = draft.selectedMemberIds, sort = draft.sort, direction = draft.direction, filterDraft = null)
            .recalculated(latestEntries.orEmpty(), latestCategories) }
        saveFilters()
        saveSort()
        refresh()
    }

    private fun saveFilters() {
        val state = mutableState.value
        savedStateHandle["reports.household"] = householdId
        savedStateHandle["reports.mode"] = state.mode.name
        savedStateHandle["reports.month"] = state.month.toString()
        savedStateHandle["reports.year"] = state.year.value
        savedStateHandle["reports.type"] = state.typeFilter.name
        savedStateHandle["reports.from"] = appliedCustomPeriod.from.toString()
        savedStateHandle["reports.to"] = appliedCustomPeriod.to.toString()
        savedStateHandle["reports.category"] = state.selectedCategoryId
        savedStateHandle["reports.subcategory"] = state.selectedSubcategoryId
        savedStateHandle["reports.members"] = state.selectedMemberIds?.let { ArrayList(it.sorted()) }
    }
    private fun restoreFilters(id: String) {
        if (savedStateHandle.get<String>("reports.household") != id) {
            appliedCustomPeriod = SummaryPeriod(today.withDayOfMonth(1), today)
            mutableState.update { ReportsUiState(today, YearMonth.from(today), Year.from(today), sort = it.sort, direction = it.direction) }
            saveFilters(); return
        }
        val from = savedStateHandle.get<String>("reports.from") ?: today.withDayOfMonth(1).toString()
        val to = savedStateHandle.get<String>("reports.to") ?: today.toString()
        appliedCustomPeriod = runCatching { SummaryPeriod(LocalDate.parse(from), LocalDate.parse(to)).also {
            require(it.from <= it.to && it.to <= today)
        } }.getOrDefault(SummaryPeriod(today.withDayOfMonth(1), today))
        mutableState.update { it.copy(
            mode = ReportPeriodMode.entries.firstOrNull { mode -> mode.name == savedStateHandle.get<String>("reports.mode") } ?: ReportPeriodMode.MONTH,
            month = runCatching { YearMonth.parse(savedStateHandle.get<String>("reports.month")) }.getOrDefault(YearMonth.from(today)),
            year = Year.of(savedStateHandle.get<Int>("reports.year") ?: today.year),
            typeFilter = ReportTypeFilter.entries.firstOrNull { type -> type.name == savedStateHandle.get<String>("reports.type") } ?: ReportTypeFilter.ALL,
            customFromInput = appliedCustomPeriod.from.toString(), customToInput = appliedCustomPeriod.to.toString(),
            selectedCategoryId = savedStateHandle["reports.category"], selectedSubcategoryId = savedStateHandle["reports.subcategory"],
            selectedMemberIds = savedStateHandle.get<ArrayList<String>>("reports.members")?.toSet()) }
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
        val categoryId = selectedCategoryId
        val availableSubs = latestSubcategories[categoryId].orEmpty()
        val subcategoryId = selectedSubcategoryId?.takeIf { categoryId != null }
        val selection = ReportSelection(householdId.orEmpty(), period, typeFilter, categoryId, subcategoryId,
            sort, direction, mode == ReportPeriodMode.YEAR, selectedMemberIds)
        if (entries !== cachedEntries || selection != cachedSelection) {
            cachedEntries = entries
            cachedSelection = selection
            cachedSelectedEntries = selectReportEntries(entries, selection.householdId, period, typeFilter,
                categoryId, subcategoryId, sort, direction, selectedMemberIds)
            val bucket: (LocalDate) -> LocalDate = if (selection.monthlyTrend) {
                date -> date.withDayOfMonth(1)
            } else { date -> date }
            val rawAggregation = aggregateReportEntries(cachedSelectedEntries, period, typeFilter, bucket)
            cachedBalanceTrend = buildReportBalanceTrend(entries, period,
                if (mode == ReportPeriodMode.YEAR || (mode == ReportPeriodMode.CUSTOM &&
                    ChronoUnit.DAYS.between(period.from, period.to) >= 62)) ReportBalanceGranularity.MONTHLY
                else ReportBalanceGranularity.DAILY)
            // Expense trends use magnitudes while totals keep the signed balance.
            cachedAggregation = if (typeFilter == ReportTypeFilter.EXPENSE) rawAggregation.copy(
                trend = rawAggregation.trend.map { it.copy(amountGrosze = it.amountGrosze.abs()) },
            ) else rawAggregation
        }
        val taxonomy = categories.associateBy { it.id }
        return copy(
            categories = categories.sortedWith(compareBy(String.CASE_INSENSITIVE_ORDER) { it.name }),
            subcategories = availableSubs.sortedWith(compareBy(String.CASE_INSENSITIVE_ORDER) { it.name }),
            allSubcategories = latestSubcategories.values.flatten(),
            selectedCategoryId = categoryId,
            selectedSubcategoryId = subcategoryId,
            aggregation = cachedAggregation,
            balanceTrend = cachedBalanceTrend,
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
    val memberIds: Set<String>?,
)

private fun <T> Flow<SyncObservation<T>>.withReportErrors(): Flow<SyncObservation<T>> =
    catch { emit(SyncObservation(state = SyncState.ERROR, error = it)) }
