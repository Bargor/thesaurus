package pl.bargor.thesaurus.ui.reports

import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import java.time.Clock
import java.time.LocalDate
import java.time.Year
import java.time.YearMonth
import java.time.temporal.ChronoUnit
import javax.inject.Inject
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import pl.bargor.thesaurus.data.firebase.HouseholdRepository
import pl.bargor.thesaurus.data.firebase.LedgerRepository
import pl.bargor.thesaurus.data.firebase.TaxonomyRepository
import pl.bargor.thesaurus.data.model.Category
import pl.bargor.thesaurus.data.model.LedgerEntry
import pl.bargor.thesaurus.data.model.Member
import pl.bargor.thesaurus.data.model.ReportAggregation
import pl.bargor.thesaurus.data.model.ReportBalanceGranularity
import pl.bargor.thesaurus.data.model.ReportBalanceTrend
import pl.bargor.thesaurus.data.model.ReportTypeFilter
import pl.bargor.thesaurus.data.model.Subcategory
import pl.bargor.thesaurus.data.model.SummaryPeriod
import pl.bargor.thesaurus.data.model.SyncObservation
import pl.bargor.thesaurus.data.model.SyncState
import pl.bargor.thesaurus.data.model.aggregateReportEntries
import pl.bargor.thesaurus.data.model.buildReportBalanceTrend
import pl.bargor.thesaurus.data.observation.HouseholdObservation
import pl.bargor.thesaurus.data.observation.HouseholdReadModel
import pl.bargor.thesaurus.data.observation.reduceSyncState
import pl.bargor.thesaurus.ui.entries.presentEntry

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
    private var latestOpeningBalance: Long? = null
    private var cachedOpeningBalance: Long? = null
    private var latestCategories: List<Category> = emptyList()
    private var latestMembers: List<Member> = emptyList()
    private val latestSubcategories = mutableMapOf<String, List<Subcategory>>()
    private val observations = mutableMapOf<String, SyncObservation<*>>()
    private var readModel: HouseholdReadModel? = null
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
        this.householdId = householdId
        if (changed) {
            readModel = null
            latestOpeningBalance = null; cachedOpeningBalance = null
            latestMembers = emptyList()
            latestEntries = null; latestCategories = emptyList(); latestSubcategories.clear()
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
        observeJob = viewModelScope.launch {
            HouseholdObservation(ledgerRepository, taxonomyRepository, householdRepository)
                .observe(householdId, includeHousehold = true, initial = readModel).collect { snapshot ->
                    if (this@ReportsViewModel.householdId != householdId) return@collect
                    readModel = snapshot
                    observations.clear(); observations.putAll(snapshot.observations)
                    if (snapshot.hasInvalidData) {
                        latestEntries = null
                        cachedEntries = null; cachedSelection = null
                        cachedSelectedEntries = emptyList(); cachedAggregation = ReportAggregation()
                        cachedBalanceTrend = null
                    } else snapshot.entries.value?.let { latestEntries = it }
                    latestCategories = snapshot.categories.value.orEmpty()
                    latestMembers = snapshot.members.value.orEmpty()
                    latestSubcategories.clear(); latestSubcategories.putAll(snapshot.subcategoryValues)
                    val household = snapshot.household.observation
                    latestOpeningBalance = when {
                        snapshot.household.hasError -> null
                        household?.value != null -> household.value.openingBalanceGrosze
                        household?.state == SyncState.SYNCED -> null
                        // After an error, metadata without a fresh value cannot revive an old balance.
                        else -> latestOpeningBalance
                    }
                    entriesObserved = snapshot.entries.observation != null
                    refresh()
                }
        }
    }

    private fun refresh() = mutableState.update { old ->
        val current = latestMembers.associateBy { it.uid }
        val former = (latestEntries.orEmpty().filterNot { it.deleted }.map { it.authorId } +
            old.selectedMemberIds.orEmpty() + old.filterDraft?.selectedMemberIds.orEmpty()).distinct()
            .filterNot { it in current }.map { ReportMemberOption(it, it, former = true) }
        old.copy(members = (current.values.map { ReportMemberOption(it.uid,
            it.displayName?.takeIf(String::isNotBlank)?.let { name -> "$name (${it.email})" } ?: it.email) } + former).sortedBy { it.name.lowercase() })
            .recalculated(latestEntries.orEmpty(), latestCategories, isLoading = !entriesObserved,
            syncState = reduceSyncState(observations.values),
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
        if (readModel?.hasInvalidData == true) return copy(
            aggregation = ReportAggregation(), entries = emptyList(), balanceTrend = null,
            isLoading = false, syncState = SyncState.ERROR, hasError = true,
        )
        val period = if (mode == ReportPeriodMode.CUSTOM) appliedCustomPeriod else period()
        val categoryId = selectedCategoryId
        val availableSubs = latestSubcategories[categoryId].orEmpty()
        val subcategoryId = selectedSubcategoryId?.takeIf { categoryId != null }
        val selection = ReportSelection(householdId.orEmpty(), period, typeFilter, categoryId, subcategoryId,
            sort, direction, mode == ReportPeriodMode.YEAR, selectedMemberIds)
        if (entries !== cachedEntries || selection != cachedSelection || cachedOpeningBalance != latestOpeningBalance) {
            cachedOpeningBalance = latestOpeningBalance
            cachedEntries = entries
            cachedSelection = selection
            cachedSelectedEntries = selectReportEntries(entries, selection.householdId, period, typeFilter,
                categoryId, subcategoryId, sort, direction, selectedMemberIds)
            val bucket: (LocalDate) -> LocalDate = if (selection.monthlyTrend) {
                date -> date.withDayOfMonth(1)
            } else { date -> date }
            val rawAggregation = aggregateReportEntries(cachedSelectedEntries, period, typeFilter, bucket)
            cachedBalanceTrend = latestOpeningBalance?.let { opening -> buildReportBalanceTrend(entries, period,
                if (mode == ReportPeriodMode.YEAR || (mode == ReportPeriodMode.CUSTOM &&
                    ChronoUnit.DAYS.between(period.from, period.to) >= 62)) ReportBalanceGranularity.MONTHLY
                else ReportBalanceGranularity.DAILY, opening) }
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
            balanceTrend = if (listOfNotNull(observations["household"], observations["entries"])
                .any { it.state == SyncState.ERROR || it.error != null })
                null else cachedBalanceTrend,
            entries = cachedSelectedEntries.map { entry ->
                val presentation = presentEntry(entry, taxonomy, latestSubcategories)
                ReportEntryItem(entry, presentation.categoryName ?: entry.categoryId,
                    presentation.categoryColor, presentation.subcategoryName ?: entry.subcategoryId)
            },
            isLoading = isLoading,
            syncState = syncState,
            hasError = hasError,
        )
    }
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
