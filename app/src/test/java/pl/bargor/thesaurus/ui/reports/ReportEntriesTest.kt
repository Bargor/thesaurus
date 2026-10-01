package pl.bargor.thesaurus.ui.reports

import java.time.LocalDate
import java.time.YearMonth
import org.junit.Assert.assertEquals
import org.junit.Test
import pl.bargor.thesaurus.data.model.LedgerEntry
import pl.bargor.thesaurus.data.model.ReportTypeFilter
import pl.bargor.thesaurus.data.model.summaryPeriod

class ReportEntriesTest {
    @Test fun allCategoryAndSubcategoryScopesHaveNoTagDependency() {
        val entries = listOf(entry("shop", -100, sub = "shop", tags = listOf("dom")), entry("cafe", -200, sub = "cafe"), entry("bare", 300), entry("car", -400, category = "car", sub = "fuel"))
        assertEquals(setOf("shop", "cafe", "bare", "car"), selected(entries).map { it.id }.toSet())
        assertEquals(setOf("shop", "cafe", "bare"), selected(entries, category = "food").map { it.id }.toSet())
        assertEquals(listOf("shop"), selected(entries, category = "food", sub = "shop").map { it.id })
        assertEquals(emptyList<LedgerEntry>(), selected(entries, category = "car", sub = "shop"))
        assertEquals(emptyList<LedgerEntry>(), selected(entries, sub = "shop"))
    }
    @Test fun dateHouseholdDeletedAndSignedTypeFiltersIntersectScope() {
        val entries = listOf(entry("first", 100, "2028-02-01", sub = "shop"), entry("leap", -200, "2028-02-29", sub = "shop"),
            entry("before", 100, "2028-01-31", sub = "shop"), entry("after", -100, "2028-03-01", sub = "shop"),
            entry("deleted", -100, sub = "shop").copy(deleted = true, deletedById = "actor"),
            entry("foreign", -100, sub = "shop").copy(householdId = "other"))
        assertEquals(listOf("leap", "first"), selected(entries, "food", "shop").map { it.id })
        assertEquals(listOf("first"), selected(entries, "food", "shop", type = ReportTypeFilter.INCOME).map { it.id })
        assertEquals(listOf("leap"), selected(entries, "food", "shop", type = ReportTypeFilter.EXPENSE).map { it.id })
    }
    @Test fun signedAmountSortUsesStableAscendingIdTiesInBothDirectionsWithoutOverflow() {
        val entries = listOf(entry("b", -100), entry("max", Long.MAX_VALUE), entry("min", Long.MIN_VALUE), entry("a", -100), entry("positive", 100))
        assertEquals(listOf("min", "a", "b", "positive", "max"), selected(entries, sort = ReportEntrySort.AMOUNT, direction = ReportSortDirection.ASCENDING).map { it.id })
        assertEquals(listOf("max", "positive", "a", "b", "min"), selected(entries, sort = ReportEntrySort.AMOUNT).map { it.id })
        assertEquals(listOf(ReportEntrySort.DATE, ReportEntrySort.AMOUNT), ReportEntrySort.entries)
    }
    @Test fun dateSortKeepsEqualDateIdsStableRegardlessOfInputAndDirection() {
        val entries = listOf(entry("b", 200), entry("new", 100, "2028-02-20"), entry("a", -500))
        assertEquals(listOf("a", "b", "new"), selected(entries, direction = ReportSortDirection.ASCENDING).map { it.id })
        assertEquals(listOf("new", "a", "b"), selected(entries).map { it.id })
        assertEquals(selected(entries), selected(entries.reversed()))
    }
    private fun selected(entries: List<LedgerEntry>, category: String? = null, sub: String? = null, type: ReportTypeFilter = ReportTypeFilter.ALL,
        sort: ReportEntrySort = ReportEntrySort.DATE, direction: ReportSortDirection = ReportSortDirection.DESCENDING) =
        selectReportEntries(entries, "home", YearMonth.of(2028, 2).summaryPeriod(), type, category, sub, sort, direction)
    private fun entry(id: String, amount: Long, date: String = "2028-02-10", category: String = "food", sub: String? = null, tags: List<String> = emptyList()) =
        LedgerEntry(id, "home", amount, LocalDate.parse(date), categoryId = category, subcategoryId = sub, tags = tags, authorId = "actor", updatedById = "actor")
}
