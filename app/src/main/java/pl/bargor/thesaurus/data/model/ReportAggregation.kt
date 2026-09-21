package pl.bargor.thesaurus.data.model

import java.math.BigInteger
import java.time.LocalDate

/** Filter is deliberately based on the sign stored in the ledger, never on a UI-selected type. */
enum class ReportTypeFilter { ALL, INCOME, EXPENSE }

data class ReportTotals(
    val incomeGrosze: BigInteger = BigInteger.ZERO,
    val expenseGrosze: BigInteger = BigInteger.ZERO,
    val entryCount: Int = 0,
) {
    val netGrosze: BigInteger get() = incomeGrosze - expenseGrosze
    val isEmpty: Boolean get() = entryCount == 0
}

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
    var income = BigInteger.ZERO
    var expense = BigInteger.ZERO
    val categories = linkedMapOf<String, BigInteger>()
    val trend = sortedMapOf<LocalDate, BigInteger>()
    val selected = filterReportEntries(entries, period, type)
    selected.forEach { entry ->
        val signed = BigInteger.valueOf(entry.amountGrosze)
        if (signed.signum() > 0) income += signed else expense -= signed
        categories[entry.categoryId] = (categories[entry.categoryId] ?: BigInteger.ZERO) + signed.abs()
        val date = bucket(entry.date)
        trend[date] = (trend[date] ?: BigInteger.ZERO) + signed
    }
    return ReportAggregation(
        totals = ReportTotals(income, expense, selected.size),
        categories = categories.map { ReportCategoryValue(it.key, it.value) }
            .sortedWith(compareByDescending<ReportCategoryValue> { it.amountGrosze }.thenBy { it.categoryId }),
        trend = trend.map { ReportTrendValue(it.key, it.value) },
    )
}
