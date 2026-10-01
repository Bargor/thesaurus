package pl.bargor.thesaurus.ui.reports

import pl.bargor.thesaurus.data.model.LedgerEntry
import pl.bargor.thesaurus.data.model.ReportTypeFilter
import pl.bargor.thesaurus.data.model.SummaryPeriod
import pl.bargor.thesaurus.data.model.filterReportEntries

enum class ReportEntrySort { DATE, AMOUNT }
enum class ReportSortDirection { ASCENDING, DESCENDING }

/** All report surfaces consume this exact selection; tags have no role in report scope. */
internal fun selectReportEntries(
    entries: List<LedgerEntry>,
    householdId: String,
    period: SummaryPeriod,
    type: ReportTypeFilter,
    categoryId: String?,
    subcategoryId: String?,
    sort: ReportEntrySort,
    direction: ReportSortDirection,
): List<LedgerEntry> {
    val selected = filterReportEntries(entries, period, type).filter {
        it.householdId == householdId && (categoryId == null || it.categoryId == categoryId) &&
            (subcategoryId == null || (categoryId != null && it.subcategoryId == subcategoryId))
    }
    val ascending = when (sort) {
        ReportEntrySort.DATE -> compareBy<LedgerEntry> { it.date }
        ReportEntrySort.AMOUNT -> compareBy { it.amountGrosze }
    }
    val primary = if (direction == ReportSortDirection.ASCENDING) ascending else ascending.reversed()
    return selected.sortedWith(primary.thenBy { it.id })
}
