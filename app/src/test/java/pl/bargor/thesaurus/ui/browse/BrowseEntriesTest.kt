package pl.bargor.thesaurus.ui.browse

import java.math.BigInteger
import java.time.LocalDate
import java.time.Year
import java.time.YearMonth
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Test
import pl.bargor.thesaurus.data.model.Category
import pl.bargor.thesaurus.data.model.LedgerEntry
import pl.bargor.thesaurus.data.model.Subcategory
import pl.bargor.thesaurus.data.model.summaryPeriod

class BrowseEntriesTest {
    @Test fun leapMonthAndYearUseInclusiveBoundariesAndExcludeDeletedAndForeignEntries() {
        val entries = listOf(
            entry("before", 1, "2028-01-31"), entry("first", 2, "2028-02-01"),
            entry("leap", -3, "2028-02-29"), entry("after", 4, "2028-03-01"),
            entry("year-first", 5, "2028-01-01"), entry("year-last", 6, "2028-12-31"),
            entry("next-year", 7, "2029-01-01"),
            entry("deleted", 100, "2028-02-12").copy(deleted = true, deletedById = "actor"),
            entry("foreign", 200, "2028-02-12").copy(householdId = "other"),
        )
        val month = groupBrowseEntries(entries, "home", YearMonth.of(2028, 2).summaryPeriod(), emptyList(), emptyMap()).single()
        assertEquals(listOf("leap", "first"), month.subcategories.single().entries.map { it.id })
        assertEquals((-1).toBigInteger(), month.netGrosze)
        val year = groupBrowseEntries(entries, "home", Year.of(2028).summaryPeriod(), emptyList(), emptyMap()).single()
        assertEquals(6, year.entryCount)
        assertEquals(15.toBigInteger(), year.netGrosze)
    }

    @Test fun netSumsAreSignedExactAndDoNotDropZeroGroups() {
        val entries = listOf(
            entry("max-one", Long.MAX_VALUE, subcategory = "large"),
            entry("max-two", Long.MAX_VALUE, subcategory = "large"),
            entry("min", Long.MIN_VALUE, category = "negative"),
            entry("plus", 500, category = "zero", subcategory = "mixed"),
            entry("minus", -500, category = "zero", subcategory = "mixed"),
        )
        val groups = grouped(entries).associateBy { it.categoryId }
        val twiceMax = BigInteger.valueOf(Long.MAX_VALUE).multiply(BigInteger.TWO)
        assertEquals(twiceMax, groups.getValue("food").netGrosze)
        assertEquals(twiceMax, groups.getValue("food").subcategories.single().netGrosze)
        assertEquals(BigInteger.valueOf(Long.MIN_VALUE), groups.getValue("negative").netGrosze)
        assertEquals(BigInteger.ZERO, groups.getValue("zero").netGrosze)
        assertEquals(2, groups.getValue("zero").entryCount)
    }

    @Test fun configuredCategoryOrderAndSubcategoryOrderPrecedeStableMissingIdsAndNoSubcategory() {
        val entries = listOf(
            entry("a", 1, category = "a"), entry("b", 2, category = "b"),
            entry("missing-z", 3, category = "z"), entry("missing-c", 4, category = "c"),
            entry("second", 5, subcategory = "second"), entry("first", 6, subcategory = "first"),
            entry("unknown", 7, subcategory = "unknown"), entry("none", 8),
        )
        val categories = listOf(category("b"), category("food"), category("a"), category("unused"))
        val groups = grouped(entries, categories, mapOf("food" to listOf(subcategory("first"), subcategory("second"))))
        assertEquals(listOf("b", "food", "a", "c", "z"), groups.map { it.categoryId })
        assertEquals(listOf("first", "second", "unknown", null), groups[1].subcategories.map { it.key.subcategoryId })
        assertNull(groups[1].subcategories[2].subcategory)
        assertNull(groups[1].subcategories[3].subcategory)
        assertEquals(BrowseSubcategoryKey("food", null), groups[1].subcategories.last().key)
    }

    @Test fun archivedTaxonomyRemainsNamedWhileWrongHouseholdAndWrongParentTaxonomyAreIgnored() {
        val archivedCategory = category("food").copy(archived = true, color = "rose")
        val archivedSubcategory = subcategory("old").copy(archived = true)
        val groups = grouped(
            listOf(entry("historical", -100, subcategory = "old"), entry("wrong", 50, subcategory = "wrong")),
            listOf(archivedCategory, category("food").copy(householdId = "other", name = "Foreign")),
            mapOf("food" to listOf(archivedSubcategory, subcategory("wrong").copy(categoryId = "other"))),
        )
        assertEquals(archivedCategory, groups.single().category)
        assertEquals(archivedSubcategory, groups.single().subcategories.first().subcategory)
        assertNull(groups.single().subcategories.last().subcategory)
    }

    @Test fun entriesWithinEachBucketUseNewestDateThenStableId() {
        val entries = listOf(entry("b", 1), entry("a", 2), entry("new", 3, "2028-02-20"))
        assertEquals(listOf("new", "a", "b"), grouped(entries).single().subcategories.single().entries.map { it.id })
        assertEquals(emptyList<BrowseCategoryGroup>(), grouped(emptyList()))
    }

    @Test fun rebindingPreparedPeriodKeepsSortedEntryListsAndExactAmounts() {
        val entries = listOf(entry("old", Long.MAX_VALUE, subcategory = "shop"), entry("new", Long.MAX_VALUE, "2028-02-20", subcategory = "shop"), entry("other", -50, category = "car"))
        val prepared = prepareBrowseEntries(entries, "home", YearMonth.of(2028, 2).summaryPeriod())
        val first = bindBrowseTaxonomy(prepared, "home", listOf(category("food"), category("car")), mapOf("food" to listOf(subcategory("shop"))))
        val refreshed = bindBrowseTaxonomy(prepared, "home", listOf(category("car"), category("food").copy(name = "Nowa nazwa", color = "blue")), mapOf("food" to listOf(subcategory("shop").copy(name = "Nowy sklep"))))
        assertEquals(listOf("car", "food"), refreshed.map { it.categoryId })
        val firstFood = first.first { it.categoryId == "food" }
        val refreshedFood = refreshed.last()
        assertEquals(firstFood.netGrosze, refreshedFood.netGrosze)
        assertEquals(BigInteger.valueOf(Long.MAX_VALUE).multiply(BigInteger.TWO), refreshedFood.netGrosze)
        assertEquals(firstFood.entryCount, refreshedFood.entryCount)
        assertSame(firstFood.subcategories.single().entries, refreshedFood.subcategories.single().entries)
        assertEquals(listOf("new", "old"), refreshedFood.subcategories.single().entries.map { it.id })
        assertEquals("Nowa nazwa", refreshedFood.category?.name)
        assertEquals("Nowy sklep", refreshedFood.subcategories.single().subcategory?.name)
    }

    private fun grouped(entries: List<LedgerEntry>, categories: List<Category> = emptyList(), subcategories: Map<String, List<Subcategory>> = emptyMap()) =
        groupBrowseEntries(entries, "home", YearMonth.of(2028, 2).summaryPeriod(), categories, subcategories)
    private fun entry(id: String, amount: Long, date: String = "2028-02-10", category: String = "food", subcategory: String? = null) =
        LedgerEntry(id, "home", amount, LocalDate.parse(date), categoryId = category, subcategoryId = subcategory, authorId = "actor", updatedById = "actor")
    private fun category(id: String) = Category(id, "home", id, authorId = "actor", updatedById = "actor")
    private fun subcategory(id: String) = Subcategory(id, "home", "food", id, authorId = "actor", updatedById = "actor")
}
