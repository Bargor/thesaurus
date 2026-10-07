package pl.bargor.thesaurus.ui.reports

import java.time.LocalDate
import java.time.Year
import java.time.YearMonth
import pl.bargor.thesaurus.data.model.Category
import pl.bargor.thesaurus.data.model.LedgerEntry
import pl.bargor.thesaurus.data.model.ReportAggregation
import pl.bargor.thesaurus.data.model.ReportBalanceTrend
import pl.bargor.thesaurus.data.model.ReportTypeFilter
import pl.bargor.thesaurus.data.model.Subcategory
import pl.bargor.thesaurus.data.model.SummaryPeriod
import pl.bargor.thesaurus.data.model.SyncState

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
