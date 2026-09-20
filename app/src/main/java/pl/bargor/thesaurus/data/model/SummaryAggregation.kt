package pl.bargor.thesaurus.data.model

import java.math.BigInteger
import java.time.YearMonth

/** Inclusive dates keep the same meaning for monthly and future yearly summaries. */
fun YearMonth.summaryPeriod(): SummaryPeriod = SummaryPeriod(atDay(1), atEndOfMonth())

data class SummaryTotals(
    val incomeGrosze: BigInteger = BigInteger.ZERO,
    val expenseGrosze: BigInteger = BigInteger.ZERO,
    val entryCount: Int = 0,
) {
    val netGrosze: BigInteger get() = incomeGrosze - expenseGrosze
    val isEmpty: Boolean get() = entryCount == 0
}

/** Uses arbitrary precision so valid Long amounts cannot overflow when combined. */
fun aggregateEntries(entries: Iterable<LedgerEntry>, period: SummaryPeriod): SummaryTotals {
    var income = BigInteger.ZERO
    var expense = BigInteger.ZERO
    var count = 0
    for (entry in entries) {
        if (entry.deleted || entry.date.isBefore(period.from) || entry.date.isAfter(period.to)) continue
        val amount = BigInteger.valueOf(entry.amountGrosze)
        if (amount.signum() > 0) income += amount else expense -= amount
        count++
    }
    return SummaryTotals(income, expense, count)
}
