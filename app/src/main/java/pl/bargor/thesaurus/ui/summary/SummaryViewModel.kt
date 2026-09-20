package pl.bargor.thesaurus.ui.summary

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import java.time.Clock
import java.time.Year
import java.time.YearMonth
import javax.inject.Inject
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import pl.bargor.thesaurus.data.firebase.LedgerRepository
import pl.bargor.thesaurus.data.model.LedgerEntry
import pl.bargor.thesaurus.data.model.SummaryTotals
import pl.bargor.thesaurus.data.model.SyncState
import pl.bargor.thesaurus.data.model.aggregateEntries
import pl.bargor.thesaurus.data.model.summaryPeriod

enum class SummaryPeriodMode { MONTH, YEAR }

data class SummaryUiState(
    val month: YearMonth,
    val year: Year,
    val mode: SummaryPeriodMode = SummaryPeriodMode.MONTH,
    val totals: SummaryTotals = SummaryTotals(),
    val isLoading: Boolean = true,
    val syncState: SyncState = SyncState.SYNCED,
    val hasError: Boolean = false,
)

@HiltViewModel
class SummaryViewModel @Inject constructor(
    private val ledgerRepository: LedgerRepository,
    clock: Clock,
) : ViewModel() {
    private val mutableState = MutableStateFlow(
        SummaryUiState(month = YearMonth.now(clock), year = Year.now(clock)),
    )
    val state: StateFlow<SummaryUiState> = mutableState.asStateFlow()

    private var householdId: String? = null
    private var observationJob: Job? = null
    private var latestEntries: List<LedgerEntry>? = null

    fun start(householdId: String) {
        if (this.householdId == householdId && observationJob?.isActive == true) return
        this.householdId = householdId
        observationJob?.cancel()
        latestEntries = null
        mutableState.update { it.copy(totals = SummaryTotals(), isLoading = true, hasError = false) }
        observationJob = viewModelScope.launch {
            ledgerRepository.observeEntries(householdId).collect { observation ->
                observation.value?.let { latestEntries = it }
                mutableState.update { old ->
                    old.copy(
                        totals = latestEntries?.let { aggregateEntries(it, old.summaryPeriod()) } ?: old.totals,
                        isLoading = false,
                        syncState = observation.state,
                        hasError = observation.error != null || observation.state == SyncState.ERROR,
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
        mutableState.update { old ->
            old.copy(
                mode = mode,
                totals = latestEntries?.let { aggregateEntries(it, old.copy(mode = mode).summaryPeriod()) }
                    ?: SummaryTotals(),
            )
        }
    }

    private fun changeMonth(delta: Long) {
        mutableState.update { old ->
            val month = old.month.plusMonths(delta)
            old.copy(
                month = month,
                totals = latestEntries?.let { aggregateEntries(it, month.summaryPeriod()) } ?: SummaryTotals(),
            )
        }
    }

    private fun changeYear(delta: Long) {
        mutableState.update { old ->
            val year = old.year.plusYears(delta)
            old.copy(
                year = year,
                totals = latestEntries?.let { aggregateEntries(it, year.summaryPeriod()) } ?: SummaryTotals(),
            )
        }
    }

    fun retry() { householdId?.let { startAgain(it) } }

    private fun startAgain(householdId: String) {
        observationJob?.cancel()
        observationJob = null
        start(householdId)
    }
}

private fun SummaryUiState.summaryPeriod() = when (mode) {
    SummaryPeriodMode.MONTH -> month.summaryPeriod()
    SummaryPeriodMode.YEAR -> year.summaryPeriod()
}
