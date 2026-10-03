package pl.bargor.thesaurus.data.model

import java.math.BigInteger
import java.time.LocalDate
import org.junit.Assert.*
import org.junit.Test

class ReportBalanceTrendTest {
    @Test fun dailyBalanceStartsAtZeroCarriesGapsAndAddsSignedSameDayEntriesChronologically() {
        val period = period("2024-02-28", "2024-03-02")
        val entries = listOf(entry("last", -70, "2024-03-02"), entry("refund", 30, "2024-02-29"),
            entry("first", 100, "2024-02-28"), entry("expense", -50, "2024-02-29"))
        val trend = buildReportBalanceTrend(entries, period, ReportBalanceGranularity.DAILY)
        assertEquals(BigInteger.ZERO, trend.startBalanceGrosze)
        assertEquals(listOf("2024-02-28", "2024-02-29", "2024-03-01", "2024-03-02"), trend.buckets.map { it.from.toString() })
        assertTrue(trend.buckets.all { it.from == it.to })
        assertEquals(listOf(100, -20, 0, -70).map(Int::toBigInteger), trend.buckets.map { it.changeGrosze })
        assertEquals(listOf(100, 80, 80, 10).map(Int::toBigInteger), trend.buckets.map { it.balanceGrosze })
        assertEquals(trend, buildReportBalanceTrend(entries.reversed(), period, ReportBalanceGranularity.DAILY))
        assertEquals(aggregateReportEntries(entries, period, ReportTypeFilter.ALL).totals.netGrosze, trend.endBalanceGrosze)
    }

    @Test fun partialMonthlyBoundariesAndDecemberToJanuaryKeepExactCalendarDates() {
        val period = period("2023-12-29", "2024-02-29")
        val entries = listOf(entry("dec", 100, "2023-12-29"), entry("jan", -30, "2024-01-31"), entry("feb", 20, "2024-02-29"),
            entry("before", 999, "2023-12-28"), entry("after", 999, "2024-03-01"), entry("deleted", 999, "2024-01-02").copy(deleted = true, deletedById = "actor"))
        val trend = buildReportBalanceTrend(entries, period, ReportBalanceGranularity.MONTHLY)
        assertEquals(listOf("2023-12-29", "2024-01-01", "2024-02-01"), trend.buckets.map { it.from.toString() })
        assertEquals(listOf("2023-12-31", "2024-01-31", "2024-02-29"), trend.buckets.map { it.to.toString() })
        assertEquals(listOf(100, 70, 90).map(Int::toBigInteger), trend.buckets.map { it.balanceGrosze })
        assertEquals(3, trend.entryCount)
    }

    @Test fun incomeExpenseMixedAndZeroEntriesHaveExactEndBalanceEvenBeyondLongRange() {
        val period = period("2026-09-12", "2026-09-12")
        for (amounts in listOf(listOf(125L), listOf(-125L), listOf(125L, -125L),
            listOf(Long.MAX_VALUE, Long.MAX_VALUE), listOf(Long.MIN_VALUE, Long.MIN_VALUE),
            listOf(Long.MAX_VALUE, Long.MAX_VALUE, Long.MIN_VALUE))) {
            val entries = amounts.mapIndexed { index, amount -> entry("$index", amount, "2026-09-12") }
            val trend = buildReportBalanceTrend(entries, period, ReportBalanceGranularity.DAILY)
            val expected = amounts.fold(BigInteger.ZERO) { sum, amount -> sum + BigInteger.valueOf(amount) }
            assertEquals(expected, trend.buckets.single().changeGrosze)
            assertEquals(expected, trend.buckets.single().balanceGrosze)
            assertEquals(expected, trend.endBalanceGrosze)
            assertEquals(amounts.size, trend.entryCount)
        }
    }

    @Test fun emptyAndCancellingTransactionsAreDistinguishableWithoutInventingTransactions() {
        val period = period("2026-09-01", "2026-09-30")
        val empty = buildReportBalanceTrend(emptyList(), period, ReportBalanceGranularity.DAILY)
        assertEquals(0, empty.entryCount)
        assertEquals(BigInteger.ZERO, empty.endBalanceGrosze)
        val zero = buildReportBalanceTrend(listOf(entry("income", 100, "2026-09-15"), entry("expense", -100, "2026-09-15")), period, ReportBalanceGranularity.DAILY)
        assertEquals(2, zero.entryCount)
        assertTrue(zero.buckets.all { it.changeGrosze == BigInteger.ZERO && it.balanceGrosze == BigInteger.ZERO })
    }

    @Test(timeout = 2_000) fun extremeCalendarRangeIsBoundedAndRetainsExactSparseChanges() {
        val period = SummaryPeriod(LocalDate.MIN, LocalDate.MAX)
        val entries = listOf(entry("middle", -3, "2024-02-29"),
            entry("first", 7, "2024-02-29").copy(date = LocalDate.MIN),
            entry("last", 2, "2024-02-29").copy(date = LocalDate.MAX))
        val trend = buildReportBalanceTrend(entries, period, ReportBalanceGranularity.MONTHLY)
        assertTrue(trend.sparse)
        assertTrue("Calendar gaps must be compressed", trend.buckets.size <= 10)
        assertEquals(LocalDate.MIN, trend.buckets.first().from)
        assertEquals(LocalDate.MAX, trend.buckets.last().to)
        assertEquals(6.toBigInteger(), trend.endBalanceGrosze)
        var running = BigInteger.ZERO
        trend.buckets.forEach { bucket -> running += bucket.changeGrosze; assertEquals(running, bucket.balanceGrosze) }
        trend.buckets.zipWithNext().forEach { (a, b) -> assertEquals(a.to.plusDays(1), b.from) }
    }

    private fun period(from: String, to: String) = SummaryPeriod(LocalDate.parse(from), LocalDate.parse(to))
    private fun entry(id: String, amount: Long, date: String) = LedgerEntry(id, "home", amount,
        LocalDate.parse(date), categoryId = "food", authorId = "actor", updatedById = "actor")
}
