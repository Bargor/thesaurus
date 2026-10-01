package pl.bargor.thesaurus.ui.summary

import java.math.BigInteger
import java.time.Instant
import java.time.LocalDate
import java.time.Year
import java.time.YearMonth
import pl.bargor.thesaurus.data.model.Category
import pl.bargor.thesaurus.data.model.LedgerEntry
import pl.bargor.thesaurus.data.model.SummaryPeriod
import pl.bargor.thesaurus.data.model.SummaryTotals
import pl.bargor.thesaurus.data.model.aggregateEntries
import pl.bargor.thesaurus.data.model.summaryPeriod

data class SummaryPeriodKey(val mode: SummaryPeriodMode, val year: Int, val month: Int? = null)

data class SummaryPeriodCard(
    val key: SummaryPeriodKey,
    val period: SummaryPeriod,
    val totals: SummaryTotals,
    val entries: List<LedgerEntry>,
    val highestExpenseCategoryId: String? = null,
    val highestExpenseCategory: Category? = null,
)

/** Each card owns the exact entries used by its totals, chart and drill-down. */
internal fun prepareSummaryOverview(
    entries: List<LedgerEntry>,
    householdId: String,
    selectedYear: Year,
    mode: SummaryPeriodMode,
    today: LocalDate,
): List<SummaryPeriodCard> {
    val relevant = entries.filter { it.householdId == householdId && !it.deleted && !it.date.isAfter(today) }
    val periods = when (mode) {
        SummaryPeriodMode.MONTH -> {
            if (selectedYear.value > today.year) return emptyList()
            val lastMonth = if (selectedYear.value == today.year) today.monthValue else 12
            (1..lastMonth).map { month ->
                SummaryPeriodKey(mode, selectedYear.value, month) to YearMonth.of(selectedYear.value, month).summaryPeriod()
            }
        }
        SummaryPeriodMode.YEAR -> relevant.map { it.date.year }.distinct().sorted().map { year ->
            SummaryPeriodKey(mode, year) to Year.of(year).summaryPeriod()
        }
    }
    val buckets = when (mode) {
        SummaryPeriodMode.MONTH -> relevant.groupBy { SummaryPeriodKey(mode, it.date.year, it.date.monthValue) }
        SummaryPeriodMode.YEAR -> relevant.groupBy { SummaryPeriodKey(mode, it.date.year) }
    }
    val order = compareByDescending<LedgerEntry> { it.date }
        .thenByDescending { it.createdAt ?: Instant.MIN }.thenBy { it.id }
    return periods.map { (key, period) ->
        val selected = buckets[key].orEmpty().sortedWith(order)
        val expenses = mutableMapOf<String, BigInteger>()
        selected.filter { it.amountGrosze < 0 }.forEach {
            expenses[it.categoryId] = (expenses[it.categoryId] ?: BigInteger.ZERO) - BigInteger.valueOf(it.amountGrosze)
        }
        val highest = expenses.keys.sorted().maxByOrNull { expenses.getValue(it) }
        SummaryPeriodCard(key, period, aggregateEntries(selected, period), selected, highest)
    }
}
