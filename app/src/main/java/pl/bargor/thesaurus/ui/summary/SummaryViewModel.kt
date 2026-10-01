package pl.bargor.thesaurus.ui.summary

import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import dagger.hilt.android.lifecycle.HiltViewModel
import java.time.Clock
import java.time.Year
import java.time.YearMonth
import javax.inject.Inject
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update

enum class SummaryPeriodMode { MONTH, YEAR }

data class SummaryUiState(
    val month: YearMonth,
    val year: Year,
    val mode: SummaryPeriodMode = SummaryPeriodMode.MONTH,
)

@HiltViewModel
class SummaryViewModel @Inject constructor(
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

    fun previousMonth() = changeMonth(-1)
    fun nextMonth() = changeMonth(1)
    fun previousYear() = changeYear(-1)
    fun nextYear() = changeYear(1)

    fun selectPeriodMode(mode: SummaryPeriodMode) = updatePeriod { it.copy(mode = mode) }

    private fun changeMonth(delta: Long) {
        updatePeriod { old ->
            val month = old.month.plusMonths(delta)
            old.copy(month = month, year = Year.of(month.year))
        }
    }

    private fun changeYear(delta: Long) {
        updatePeriod { old ->
            val year = old.year.plusYears(delta)
            old.copy(year = year, month = YearMonth.of(year.value, old.month.monthValue))
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
}
