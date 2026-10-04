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
            entry("before", -50, "2023-12-28"), entry("after", 999, "2024-03-01"), entry("deleted", 999, "2024-01-02").copy(deleted = true, deletedById = "actor"))
        val trend = buildReportBalanceTrend(entries, period, ReportBalanceGranularity.MONTHLY)
        assertEquals(listOf("2023-12-29", "2024-01-01", "2024-02-01"), trend.buckets.map { it.from.toString() })
        assertEquals(listOf("2023-12-31", "2024-01-31", "2024-02-29"), trend.buckets.map { it.to.toString() })
        assertEquals((-50).toBigInteger(), trend.startBalanceGrosze)
        assertEquals(listOf(50, 20, 40).map(Int::toBigInteger), trend.buckets.map { it.balanceGrosze })
        assertEquals(4, trend.entryCount)
    }

    @Test fun incomeExpenseMixedAndCancellingTransactionsHaveExactEndBalanceEvenBeyondLongRange() {
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

    @Test fun inactiveViewportCarriesGlobalHistoryAndIgnoresFutureAndTombstones() {
        val period = period("2026-09-01", "2026-09-30")
        val entries = listOf(entry("old-income", Long.MAX_VALUE, "2020-01-01"),
            entry("old-income-two", Long.MAX_VALUE, "2020-01-02"), entry("old-expense", -100, "2026-08-31"),
            entry("deleted-history", -999, "2020-01-03").copy(deleted = true, deletedById = "actor"),
            entry("future", 999, "2026-10-01"))
        val trend = buildReportBalanceTrend(entries, period, ReportBalanceGranularity.DAILY)
        val expected = BigInteger.valueOf(Long.MAX_VALUE).multiply(BigInteger.valueOf(2)) - 100.toBigInteger()
        assertEquals(3, trend.entryCount)
        assertEquals(expected, trend.startBalanceGrosze)
        assertEquals(expected, trend.endBalanceGrosze)
        assertTrue(trend.buckets.all { it.changeGrosze == BigInteger.ZERO && it.balanceGrosze == expected })
        val beforeHistory = buildReportBalanceTrend(entries, period("2019-01-01", "2019-01-31"), ReportBalanceGranularity.DAILY)
        assertEquals(0, beforeHistory.entryCount)
        assertEquals(BigInteger.ZERO, beforeHistory.endBalanceGrosze)
        assertEquals(trend, buildReportBalanceTrend(entries.reversed(), period, ReportBalanceGranularity.DAILY))
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

    @Test fun openingSettingOffsetsOnlyAbsoluteBalancesWithoutChangingPeriodMovementsOrEntries() {
        val period = period("2026-09-01", "2026-09-30")
        val entries = listOf(entry("history", 5_000, "2001-01-01"), entry("income", 2_000, "2026-09-10"),
            entry("expense", -750, "2026-09-15"), entry("future", 99_999, "2099-12-31"),
            entry("deleted", 99_999, "2026-09-15").copy(deleted = true, deletedById = "actor"))
        val totals = aggregateReportEntries(entries, period, ReportTypeFilter.ALL).totals
        val baseline = buildReportBalanceTrend(entries, period, ReportBalanceGranularity.DAILY)
        for (opening in listOf(20_000L, -20_000L, 0L, Long.MAX_VALUE)) {
            val trend = buildReportBalanceTrend(entries, period, ReportBalanceGranularity.DAILY, openingBalanceGrosze = opening)
            assertEquals(baseline.startBalanceGrosze + opening.toBigInteger(), trend.startBalanceGrosze)
            assertEquals(baseline.endBalanceGrosze + opening.toBigInteger(), trend.endBalanceGrosze)
            assertEquals(baseline.entryCount, trend.entryCount)
            assertEquals(baseline.buckets.map { it.changeGrosze }, trend.buckets.map { it.changeGrosze })
            assertEquals(baseline.buckets.map { it.balanceGrosze + opening.toBigInteger() }, trend.buckets.map { it.balanceGrosze })
            assertEquals(1_250.toBigInteger(), totals.netGrosze)
        }
        for (opening in listOf(20_000L, -20_000L)) {
            val empty = buildReportBalanceTrend(emptyList(), period, ReportBalanceGranularity.MONTHLY, openingBalanceGrosze = opening)
            assertEquals(0, empty.entryCount)
            assertEquals(opening.toBigInteger(), empty.startBalanceGrosze)
            assertEquals(opening.toBigInteger(), empty.endBalanceGrosze)
            assertTrue(empty.buckets.all { it.changeGrosze == BigInteger.ZERO && it.balanceGrosze == opening.toBigInteger() })
        }
    }

    private fun period(from: String, to: String) = SummaryPeriod(LocalDate.parse(from), LocalDate.parse(to))
    private fun entry(id: String, amount: Long, date: String) = LedgerEntry(id, "home", amount,
        LocalDate.parse(date), categoryId = "food", authorId = "actor", updatedById = "actor")
}
