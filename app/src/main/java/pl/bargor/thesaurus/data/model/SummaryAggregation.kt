package pl.bargor.thesaurus.data.model

import java.time.Year
import java.time.YearMonth

/** Inclusive dates keep the same meaning for monthly and yearly summaries. */
fun YearMonth.summaryPeriod(): SummaryPeriod = SummaryPeriod(atDay(1), atEndOfMonth())

fun Year.summaryPeriod(): SummaryPeriod = SummaryPeriod(atDay(1), atMonth(12).atEndOfMonth())

typealias SummaryTotals = LedgerTotals

/** Uses arbitrary precision so valid Long amounts cannot overflow when combined. */
fun aggregateEntries(entries: Iterable<LedgerEntry>, period: SummaryPeriod): SummaryTotals {
    var totals = SummaryTotals()
    for (entry in entries) {
        if (entry.deleted || entry.date.isBefore(period.from) || entry.date.isAfter(period.to)) continue
        totals = totals.add(entry.amountGrosze)
    }
    return totals
}
