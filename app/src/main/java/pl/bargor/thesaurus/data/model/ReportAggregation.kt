package pl.bargor.thesaurus.data.model

import java.math.BigInteger
import java.time.LocalDate

/** Filter is deliberately based on the sign stored in the ledger, never on a UI-selected type. */
enum class ReportTypeFilter { ALL, INCOME, EXPENSE }

typealias ReportTotals = LedgerTotals

data class ReportCategoryValue(val categoryId: String, val amountGrosze: BigInteger)
data class ReportTrendValue(val date: LocalDate, val amountGrosze: BigInteger)

data class ReportAggregation(
    val totals: ReportTotals = ReportTotals(),
    val categories: List<ReportCategoryValue> = emptyList(),
    val trend: List<ReportTrendValue> = emptyList(),
)

/**
 * Returns active entries in an inclusive interval.  Expenses and income are classified from the
 * persisted signed amount, which also keeps old documents consistent with newly entered ones.
 */
fun filterReportEntries(
    entries: Iterable<LedgerEntry>,
    period: SummaryPeriod,
    type: ReportTypeFilter,
): List<LedgerEntry> = entries.filter { entry ->
    !entry.deleted && !entry.date.isBefore(period.from) && !entry.date.isAfter(period.to) && when (type) {
        ReportTypeFilter.ALL -> true
        ReportTypeFilter.INCOME -> entry.amountGrosze > 0
        ReportTypeFilter.EXPENSE -> entry.amountGrosze < 0
    }
}

/** Amounts are arbitrary precision: a valid collection of Long entries cannot overflow a report. */
fun aggregateReportEntries(
    entries: Iterable<LedgerEntry>,
    period: SummaryPeriod,
    type: ReportTypeFilter,
    bucket: (LocalDate) -> LocalDate = { it },
): ReportAggregation {
    var totals = ReportTotals()
    val categories = linkedMapOf<String, BigInteger>()
    val trend = sortedMapOf<LocalDate, BigInteger>()
    val selected = filterReportEntries(entries, period, type)
    selected.forEach { entry ->
        val signed = BigInteger.valueOf(entry.amountGrosze)
        totals = totals.add(entry.amountGrosze)
        categories[entry.categoryId] = (categories[entry.categoryId] ?: BigInteger.ZERO) + signed.abs()
        val date = bucket(entry.date)
        trend[date] = (trend[date] ?: BigInteger.ZERO) + signed
    }
    return ReportAggregation(
        totals = totals,
        categories = categories.map { ReportCategoryValue(it.key, it.value) }
            .sortedWith(compareByDescending<ReportCategoryValue> { it.amountGrosze }.thenBy { it.categoryId }),
        trend = trend.map { ReportTrendValue(it.key, it.value) },
    )
}
