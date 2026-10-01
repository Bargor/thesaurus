package pl.bargor.thesaurus.data.model

import java.math.BigInteger
import java.time.LocalDate
import java.time.Year
import java.time.YearMonth
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import pl.bargor.thesaurus.ui.summary.SummaryPeriodMode
import pl.bargor.thesaurus.ui.summary.prepareSummaryOverview

class SummaryAggregationTest {
    @Test fun monthlyOverviewIncludesZeroMonthsButNeverFutureMonthsOrEntries() {
        val today = LocalDate.of(2028, 2, 29)
        val cards = prepareSummaryOverview(listOf(
            entry("leap", -200, today),
            entry("tomorrow", -800, today.plusDays(1)),
            entry("foreign", -999, today).copy(householdId = "other"),
            entry("deleted", -999, today, deleted = true),
        ), "home", Year.of(2028), SummaryPeriodMode.MONTH, today)
        assertEquals(listOf(1, 2), cards.map { it.key.month })
        assertTrue(cards.first().totals.isEmpty)
        assertEquals(listOf("leap"), cards.last().entries.map { it.id })
        assertEquals(200.toBigInteger(), cards.last().totals.expenseGrosze)
        assertEquals(today, cards.last().period.to)
        assertEquals(12, prepareSummaryOverview(emptyList(), "home", Year.of(2027), SummaryPeriodMode.MONTH, today).size)
        assertTrue(prepareSummaryOverview(emptyList(), "home", Year.of(2029), SummaryPeriodMode.MONTH, today).isEmpty())
    }

    @Test fun annualOverviewUsesOnlyRelevantYearsInAscendingOrderAndMatchesMonthlyTotals() {
        val today = LocalDate.of(2026, 9, 15)
        val entries = listOf(entry("old", -100, LocalDate.of(2023, 12, 31)),
            entry("old-income", 100, LocalDate.of(2025, 1, 1)),
            entry("income", 1000, LocalDate.of(2026, 1, 1)),
            entry("expense", -250, today), entry("future", -999, today.plusDays(1)),
            entry("deleted-year", -99, LocalDate.of(2024, 1, 1), deleted = true))
        val years = prepareSummaryOverview(entries, "home", Year.of(2026), SummaryPeriodMode.YEAR, today)
        assertEquals(listOf(2023, 2025, 2026), years.map { it.key.year })
        val months = prepareSummaryOverview(entries, "home", Year.of(2026), SummaryPeriodMode.MONTH, today)
        assertEquals(years.last().totals.incomeGrosze, months.fold(BigInteger.ZERO) { sum, card -> sum + card.totals.incomeGrosze })
        assertEquals(years.last().totals.expenseGrosze, months.fold(BigInteger.ZERO) { sum, card -> sum + card.totals.expenseGrosze })
        assertEquals(setOf("income", "expense"), years.last().entries.map { it.id }.toSet())
        assertTrue(prepareSummaryOverview(emptyList(), "home", Year.of(2026), SummaryPeriodMode.YEAR, today).isEmpty())
    }

    @Test fun topCategoryUsesNegativeExpensesWithStableIdTieBreakAndOverflowSafeMagnitude() {
        val today = LocalDate.of(2026, 1, 15)
        fun cards(entries: List<LedgerEntry>) = prepareSummaryOverview(entries, "home", Year.of(2026), SummaryPeriodMode.MONTH, today).single()
        val base = listOf(entry("expense-b", -500, today).copy(categoryId = "b"),
            entry("expense-a", -500, today).copy(categoryId = "a"),
            entry("refund", 10_000, today).copy(categoryId = "a"))
        assertEquals("a", cards(base).highestExpenseCategoryId)
        assertEquals("a", cards(base.reversed()).highestExpenseCategoryId)
        assertEquals(null, cards(listOf(entry("income", 100, today))).highestExpenseCategoryId)
        assertEquals(null, cards(emptyList()).highestExpenseCategoryId)
        assertEquals("huge", cards(base + entry("minimum", Long.MIN_VALUE, today).copy(categoryId = "huge")).highestExpenseCategoryId)
    }
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
