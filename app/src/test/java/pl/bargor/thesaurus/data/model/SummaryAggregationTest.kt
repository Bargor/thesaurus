package pl.bargor.thesaurus.data.model

import java.math.BigInteger
import java.time.LocalDate
import java.time.YearMonth
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class SummaryAggregationTest {
    @Test fun monthPeriodIncludesBothEndsAcrossYearBoundary() {
        assertEquals(
            SummaryPeriod(LocalDate.of(2025, 12, 1), LocalDate.of(2025, 12, 31)),
            YearMonth.of(2025, 12).summaryPeriod(),
        )
        assertEquals(
            SummaryPeriod(LocalDate.of(2026, 1, 1), LocalDate.of(2026, 1, 31)),
            YearMonth.of(2025, 12).plusMonths(1).summaryPeriod(),
        )
        assertEquals(LocalDate.of(2028, 2, 29), YearMonth.of(2028, 2).summaryPeriod().to)
    }

    @Test fun signedTotalsExcludeDeletedAndOutOfPeriodEntries() {
        val period = YearMonth.of(2026, 1).summaryPeriod()
        val totals = aggregateEntries(listOf(
            entry("before", 10_000, LocalDate.of(2025, 12, 31)),
            entry("income", 15_000, period.from),
            entry("expense", -4_500, period.to),
            entry("deleted", -8_000, period.from, deleted = true),
            entry("after", -3_000, LocalDate.of(2026, 2, 1)),
        ), period)
        assertEquals(BigInteger.valueOf(15_000), totals.incomeGrosze)
        assertEquals(BigInteger.valueOf(4_500), totals.expenseGrosze)
        assertEquals(BigInteger.valueOf(10_500), totals.netGrosze)
        assertEquals(2, totals.entryCount)
    }

    @Test fun expenseMagnitudeAndSumsHandleLongMinimumWithoutOverflow() {
        val totals = aggregateEntries(listOf(
            entry("one", Long.MIN_VALUE, LocalDate.of(2026, 1, 1)),
            entry("two", Long.MAX_VALUE, LocalDate.of(2026, 1, 1)),
            entry("three", Long.MAX_VALUE, LocalDate.of(2026, 1, 1)),
        ), YearMonth.of(2026, 1).summaryPeriod())
        assertEquals(BigInteger.valueOf(Long.MIN_VALUE).abs(), totals.expenseGrosze)
        assertEquals(BigInteger.valueOf(Long.MAX_VALUE) * BigInteger.TWO, totals.incomeGrosze)
    }

    @Test fun noMatchingEntriesIsEmpty() {
        assertTrue(aggregateEntries(emptyList(), YearMonth.of(2026, 1).summaryPeriod()).isEmpty)
    }

    private fun entry(id: String, amount: Long, date: LocalDate, deleted: Boolean = false) = LedgerEntry(
        id = id, householdId = "home", amountGrosze = amount, date = date,
        categoryId = "category", authorId = "anna", updatedById = "anna",
        deleted = deleted, deletedById = if (deleted) "anna" else null,
    )
}
