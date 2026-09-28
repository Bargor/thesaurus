package pl.bargor.thesaurus.ui.summary

import java.time.Instant
import java.time.LocalDate
import java.time.Year
import java.time.YearMonth
import org.junit.Assert.assertEquals
import org.junit.Test
import pl.bargor.thesaurus.data.model.Category
import pl.bargor.thesaurus.data.model.EntryType
import pl.bargor.thesaurus.data.model.LedgerEntry
import pl.bargor.thesaurus.data.model.Subcategory
import pl.bargor.thesaurus.data.model.summaryPeriod

class SummaryEntriesTest {
    private val categories = listOf(category("a", "Alfa"), category("b", "Beta"))
    private val subcategories = mapOf(
        "a" to listOf(subcategory("a1", "a", "Zakupy"), subcategory("a2", "a", "Czynsz")),
        "b" to listOf(subcategory("b1", "b", "Paliwo")),
    )
    private val entries = listOf(
        entry("first", "2026-01-01", 300, "a", "a1", listOf(" Dom ", "PILNE", "dom")),
        entry("second", "2026-01-15", -200, "b", "b1", listOf("Auto")),
        entry("third", "2026-01-31", 100, "a", "a2", listOf("dom")),
        entry("bare", "2026-01-20", -50, "a", null),
        entry("before", "2025-12-31", 900, "a", "a1"),
        entry("after", "2026-02-01", 800, "b", "b1"),
        entry("deleted", "2026-01-10", 700, "a", "a1", deleted = true),
    )

    @Test fun monthAndYearAreInclusiveAndExcludeDeleted() {
        assertEquals(listOf("third", "bare", "second", "first"), selected().map { it.entry.id })
        assertEquals(
            listOf("after", "third", "bare", "second", "first"),
            selected(period = Year.of(2026).summaryPeriod()).map { it.entry.id },
        )
        assertEquals(listOf("before"), selected(period = YearMonth.of(2025, 12).summaryPeriod()).map { it.entry.id })
    }

    @Test fun everySortFieldSupportsBothDirections() {
        assertEquals(
            listOf(SummaryEntrySort.DATE, SummaryEntrySort.AMOUNT, SummaryEntrySort.CATEGORY, SummaryEntrySort.SUBCATEGORY),
            SummaryEntrySort.entries,
        )
        val expectedAscending = mapOf(
            SummaryEntrySort.DATE to listOf("first", "second", "bare", "third"),
            SummaryEntrySort.AMOUNT to listOf("second", "bare", "third", "first"),
            SummaryEntrySort.CATEGORY to listOf("bare", "first", "third", "second"),
            SummaryEntrySort.SUBCATEGORY to listOf("third", "second", "first", "bare"),
        )
        expectedAscending.forEach { (sort, expected) ->
            assertEquals("$sort ascending", expected, selected(sort = sort, direction = SummarySortDirection.ASCENDING).map { it.entry.id })
            assertEquals("$sort descending", expected.reversed(), selected(sort = sort).map { it.entry.id })
        }
    }

    @Test fun combinedCategorySubcategoryAndNormalizedTagFilters() {
        assertEquals(listOf("first"), selected(categoryId = "a", subcategoryId = "a1", tag = "pilne").map { it.entry.id })
        assertEquals(listOf("third", "first"), selected(categoryId = "a", tag = "dom").map { it.entry.id })
        assertEquals(listOf("bare"), selected(categoryId = "a", subcategoryId = null).filter { it.entry.subcategoryId == null }.map { it.entry.id })
        assertEquals(emptyList<String>(), selected(categoryId = "b", subcategoryId = "a1").map { it.entry.id })
        assertEquals(listOf("dom", "pilne"), entries.first().normalizedTags)
    }

    private fun selected(
        period: pl.bargor.thesaurus.data.model.SummaryPeriod = YearMonth.of(2026, 1).summaryPeriod(),
        categoryId: String? = null,
        subcategoryId: String? = null,
        tag: String? = null,
        sort: SummaryEntrySort = SummaryEntrySort.DATE,
        direction: SummarySortDirection = SummarySortDirection.DESCENDING,
    ) = selectSummaryEntries(entries, period, categories, subcategories, categoryId, subcategoryId, tag, sort, direction)

    private fun entry(
        id: String, date: String, amount: Long, category: String, subcategory: String?,
        tags: List<String> = emptyList(), deleted: Boolean = false,
    ) = LedgerEntry(
        id = id, householdId = "home", amountGrosze = amount, date = LocalDate.parse(date),
        categoryId = category, subcategoryId = subcategory, tags = tags,
        authorId = "anna", updatedById = "anna", createdAt = Instant.parse("2026-01-01T00:00:00Z"),
        deleted = deleted, deletedById = if (deleted) "anna" else null,
    )

    private fun category(id: String, name: String) = Category(
        id, "home", name, defaultEntryType = EntryType.EXPENSE, authorId = "anna", updatedById = "anna",
    )
    private fun subcategory(id: String, category: String, name: String) = Subcategory(
        id, "home", category, name, authorId = "anna", updatedById = "anna",
    )
}
