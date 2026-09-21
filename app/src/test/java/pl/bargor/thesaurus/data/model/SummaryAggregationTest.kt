package pl.bargor.thesaurus.data.model

import java.math.BigInteger
import java.time.LocalDate
import java.time.Year
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

    @Test fun yearPeriodIncludesCalendarBoundaryAndLeapDay() {
        assertEquals(
            SummaryPeriod(LocalDate.of(2028, 1, 1), LocalDate.of(2028, 12, 31)),
            Year.of(2028).summaryPeriod(),
        )
        val totals = aggregateEntries(listOf(
            entry("previous", 100, LocalDate.of(2027, 12, 31)),
            entry("first", 2_500, LocalDate.of(2028, 1, 1)),
            entry("leap-day", -400, LocalDate.of(2028, 2, 29)),
            entry("last", -600, LocalDate.of(2028, 12, 31)),
            entry("next", 700, LocalDate.of(2029, 1, 1)),
        ), Year.of(2028).summaryPeriod())
        assertEquals(BigInteger.valueOf(2_500), totals.incomeGrosze)
        assertEquals(BigInteger.valueOf(1_000), totals.expenseGrosze)
        assertEquals(BigInteger.valueOf(1_500), totals.netGrosze)
        assertEquals(3, totals.entryCount)
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
