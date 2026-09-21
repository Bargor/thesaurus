package pl.bargor.thesaurus.ui.reports

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import java.time.Clock
import java.time.LocalDate
import java.time.Year
import java.time.YearMonth
import javax.inject.Inject
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import pl.bargor.thesaurus.data.firebase.LedgerRepository
import pl.bargor.thesaurus.data.firebase.TaxonomyRepository
import pl.bargor.thesaurus.data.model.Category
import pl.bargor.thesaurus.data.model.LedgerEntry
import pl.bargor.thesaurus.data.model.ReportAggregation
import pl.bargor.thesaurus.data.model.ReportTypeFilter
import pl.bargor.thesaurus.data.model.SummaryPeriod
import pl.bargor.thesaurus.data.model.SyncState
import pl.bargor.thesaurus.data.model.aggregateReportEntries
import pl.bargor.thesaurus.data.model.filterReportEntries

enum class ReportPeriodMode { MONTH, YEAR, CUSTOM }

data class ReportEntryItem(val entry: LedgerEntry, val categoryName: String)

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
) {
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
) : ViewModel() {
    private val today = LocalDate.now(clock)
    private val mutableState = MutableStateFlow(
        ReportsUiState(today = today, month = YearMonth.from(today), year = Year.from(today)),
    )
    val state: StateFlow<ReportsUiState> = mutableState.asStateFlow()
    private var householdId: String? = null
    private var observeJob: Job? = null
    private var latestEntries: List<LedgerEntry>? = null
    private var latestCategories: List<Category> = emptyList()

    fun start(householdId: String) {
        if (this.householdId == householdId && observeJob?.isActive == true) return
        this.householdId = householdId
        observeJob?.cancel()
        latestEntries = null
        latestCategories = emptyList()
        mutableState.update { it.copy(isLoading = true, hasError = false, aggregation = ReportAggregation(), entries = emptyList()) }
        observeJob = viewModelScope.launch {
            combine(
                ledgerRepository.observeEntries(householdId),
                taxonomyRepository.observeCategories(householdId),
            ) { entries, categories -> entries to categories }.collect { (entryObservation, categoryObservation) ->
                entryObservation.value?.let { latestEntries = it }
                categoryObservation.value?.let { latestCategories = it }
                mutableState.update { old ->
                    val sync = reportSyncState(entryObservation.state, categoryObservation.state)
                    old.recalculated(
                        entries = latestEntries.orEmpty(),
                        categories = latestCategories,
                        isLoading = false,
                        syncState = sync,
                        hasError = entryObservation.error != null || categoryObservation.error != null || sync == SyncState.ERROR,
                    )
                }
            }
        }
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
        if (parsed == null) old.copy(customDateError = true) else old.copy(customDateError = false)
            .recalculated(latestEntries.orEmpty(), latestCategories)
    }

    fun retry() { householdId?.let { id -> observeJob?.cancel(); observeJob = null; start(id) } }

    private fun ReportsUiState.recalculated(
        entries: List<LedgerEntry>,
        categories: List<Category>,
        isLoading: Boolean = this.isLoading,
        syncState: SyncState = this.syncState,
        hasError: Boolean = this.hasError,
    ): ReportsUiState {
        val period = runCatching { period() }.getOrNull() ?: return copy(isLoading = isLoading, syncState = syncState, hasError = hasError)
        val bucket: (LocalDate) -> LocalDate = if (mode == ReportPeriodMode.YEAR) { date -> date.withDayOfMonth(1) } else { date -> date }
        val rawAggregation = aggregateReportEntries(entries, period, typeFilter, bucket)
        // A chart's vertical scale is easier to read for a one-direction filter. Totals remain
        // signed (net is negative for expenses); only the expense trend uses magnitudes.
        val aggregation = if (typeFilter == ReportTypeFilter.EXPENSE) rawAggregation.copy(
            trend = rawAggregation.trend.map { it.copy(amountGrosze = it.amountGrosze.abs()) },
        ) else rawAggregation
        val names = categories.associate { it.id to it.name }
        return copy(
            aggregation = aggregation,
            entries = filterReportEntries(entries, period, typeFilter)
                .sortedWith(compareByDescending<LedgerEntry> { it.date }.thenBy { it.id })
                .map { ReportEntryItem(it, names[it.categoryId] ?: it.categoryId) },
            isLoading = isLoading,
            syncState = syncState,
            hasError = hasError,
        )
    }
}

private fun reportSyncState(first: SyncState, second: SyncState): SyncState = when {
    first == SyncState.ERROR || second == SyncState.ERROR -> SyncState.ERROR
    first == SyncState.PENDING || second == SyncState.PENDING -> SyncState.PENDING
    first == SyncState.OFFLINE || second == SyncState.OFFLINE -> SyncState.OFFLINE
    else -> SyncState.SYNCED
}
