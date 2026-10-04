package pl.bargor.thesaurus.data.model

import java.math.BigInteger
import java.time.LocalDate
import java.time.YearMonth
import java.time.temporal.ChronoUnit

enum class ReportBalanceGranularity { DAILY, MONTHLY }

/** Empty runs in exceptionally long reports have an inclusive date span and zero change. */
data class ReportBalanceBucket(
    val from: LocalDate,
    val to: LocalDate,
    val changeGrosze: BigInteger,
    val balanceGrosze: BigInteger,
)

data class ReportBalanceTrend(
    val period: SummaryPeriod,
    val granularity: ReportBalanceGranularity,
    val buckets: List<ReportBalanceBucket>,
    val entryCount: Int,
    val sparse: Boolean = false,
    val startBalanceGrosze: BigInteger = BigInteger.ZERO,
) {
    val endBalanceGrosze: BigInteger get() = buckets.lastOrNull()?.balanceGrosze ?: startBalanceGrosze
}

/**
 * Global cumulative signed ledger amounts, independent of report filters and input order.
 * Entries before the viewport contribute its opening balance. Calendar dates stay local.
 * Ordinary ranges fill every bucket. More than 1200 months compress only empty runs; active
 * months remain exact, so memory and work depend on entries rather than an arbitrary year range.
 */
fun buildReportBalanceTrend(
    entries: Iterable<LedgerEntry>,
    period: SummaryPeriod,
    granularity: ReportBalanceGranularity,
    openingBalanceGrosze: Long = 0L,
): ReportBalanceTrend {
    require(period.from <= period.to)
    val changes = sortedMapOf<LocalDate, BigInteger>()
    var count = 0
    var opening = BigInteger.valueOf(openingBalanceGrosze)
    entries.forEach { entry ->
        if (!entry.deleted && entry.date <= period.to) {
            count++
            val amount = BigInteger.valueOf(entry.amountGrosze)
            if (entry.date < period.from) opening += amount else {
                val key = if (granularity == ReportBalanceGranularity.DAILY) entry.date
                    else entry.date.withDayOfMonth(1)
                changes[key] = (changes[key] ?: BigInteger.ZERO) + amount
            }
        }
    }
    val first = if (granularity == ReportBalanceGranularity.DAILY) period.from else period.from.withDayOfMonth(1)
    val last = if (granularity == ReportBalanceGranularity.DAILY) period.to else period.to.withDayOfMonth(1)
    val units = if (granularity == ReportBalanceGranularity.DAILY) ChronoUnit.DAYS.between(first, last)
        else ChronoUnit.MONTHS.between(YearMonth.from(first), YearMonth.from(last))
    // Also bound defensive direct calls with extreme daily ranges.
    val sparse = units >= 1200
    val buckets = mutableListOf<ReportBalanceBucket>()
    var balance = opening
    fun end(key: LocalDate) = if (granularity == ReportBalanceGranularity.DAILY) key
        else YearMonth.from(key).atEndOfMonth()
    fun next(key: LocalDate) = if (granularity == ReportBalanceGranularity.DAILY) key.plusDays(1) else key.plusMonths(1)
    fun add(from: LocalDate, to: LocalDate, change: BigInteger) {
        balance += change
        buckets += ReportBalanceBucket(from.coerceAtLeast(period.from), to.coerceAtMost(period.to), change, balance)
    }
    if (!sparse) {
        var cursor = first
        while (true) {
            add(cursor, end(cursor), changes[cursor] ?: BigInteger.ZERO)
            if (cursor == last) break
            cursor = next(cursor)
        }
    } else {
        var cursor = first
        changes.forEach { (key, change) ->
            if (cursor < key) add(cursor, key.minusDays(1), BigInteger.ZERO)
            add(key, end(key), change)
            // Do not step beyond LocalDate.MAX in a direct daily call.
            if (key < last) cursor = next(key) else cursor = key
        }
        if (changes.isEmpty()) add(first, period.to, BigInteger.ZERO)
        else if (changes.lastKey() < last) add(cursor, period.to, BigInteger.ZERO)
    }
    return ReportBalanceTrend(period, granularity, buckets, count, sparse, opening)
}
