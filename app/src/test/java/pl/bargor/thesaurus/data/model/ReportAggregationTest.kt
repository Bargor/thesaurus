package pl.bargor.thesaurus.data.model

import java.time.LocalDate
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ReportAggregationTest {
    private val period = SummaryPeriod(LocalDate.of(2026, 2, 1), LocalDate.of(2026, 2, 28))

    @Test fun inclusiveBoundsAndSignedTypeFilteringAreApplied() {
        val entries = listOf(
            entry("first", 1_000, LocalDate.of(2026, 2, 1), "food"),
            entry("last", -250, LocalDate.of(2026, 2, 28), "food"),
            entry("before", -900, LocalDate.of(2026, 1, 31), "travel"),
            entry("after", 800, LocalDate.of(2026, 3, 1), "travel"),
            entry("deleted", 600, LocalDate.of(2026, 2, 15), "other", deleted = true),
        )
        val expense = aggregateReportEntries(entries, period, ReportTypeFilter.EXPENSE)
        assertEquals(250.toBigInteger(), expense.totals.expenseGrosze)
        assertEquals((-250).toBigInteger(), expense.totals.netGrosze)
        assertEquals(1, expense.totals.entryCount)
        assertEquals(listOf("food"), expense.categories.map { it.categoryId })
        assertEquals((-250).toBigInteger(), expense.trend.single().amountGrosze)

        val all = aggregateReportEntries(entries, period, ReportTypeFilter.ALL)
        assertEquals(1_000.toBigInteger(), all.totals.incomeGrosze)
        assertEquals(250.toBigInteger(), all.totals.expenseGrosze)
        assertEquals(750.toBigInteger(), all.totals.netGrosze)
        assertEquals(2, all.totals.entryCount)
    }

    @Test fun annualBucketProducesMonthlySignedTrendAndMagnitudeCategorySlices() {
        val entries = listOf(
            entry("income", 500, LocalDate.of(2026, 1, 2), "food"),
            entry("expense", -800, LocalDate.of(2026, 1, 4), "food"),
            entry("feb", -300, LocalDate.of(2026, 2, 1), "travel"),
        )
        val report = aggregateReportEntries(entries, SummaryPeriod(LocalDate.of(2026, 1, 1), LocalDate.of(2026, 12, 31)), ReportTypeFilter.ALL) { it.withDayOfMonth(1) }
        assertEquals((-300).toBigInteger(), report.trend[0].amountGrosze)
        assertEquals((-300).toBigInteger(), report.trend[1].amountGrosze)
        assertEquals(1_300.toBigInteger(), report.categories.first { it.categoryId == "food" }.amountGrosze)
    }

    @Test fun emptyResultIsExplicit() {
        assertTrue(aggregateReportEntries(emptyList(), period, ReportTypeFilter.ALL).totals.isEmpty)
    }

    private fun entry(id: String, amount: Long, date: LocalDate, category: String, deleted: Boolean = false) = LedgerEntry(
        id = id, householdId = "home", amountGrosze = amount, date = date, categoryId = category,
        authorId = "anna", updatedById = "anna", deleted = deleted,
        deletedById = if (deleted) "anna" else null,
    )
}
