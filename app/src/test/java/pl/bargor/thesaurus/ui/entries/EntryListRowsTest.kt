package pl.bargor.thesaurus.ui.entries

import java.time.Instant
import java.time.LocalDate
import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test
import pl.bargor.thesaurus.data.model.LedgerEntry

class EntryListRowsTest {
    @Test
    fun `empty entries have no headings`() {
        assertTrue(entryListRows(emptyList()).isEmpty())
    }

    @Test
    fun `adjacent accounting dates share a heading and entries retain identity`() {
        val items = listOf(item("a", 16), item("b", 16), item("c", 15))
        val rows = entryListRows(items)
        assertEquals(listOf(16, 15), headings(rows))
        assertEquals(listOf("date-a", "entry-a", "entry-b", "date-c", "entry-c"), rows.map { it.key })
        rows.filterIsInstance<EntryListRow.Entry>().forEachIndexed { index, row ->
            assertSame(items[index], row.item)
            assertEquals("creator@example.test", row.item.authorName)
            assertEquals("creator", row.item.entry.authorId)
            assertEquals("updater", row.item.entry.updatedById)
        }
    }

    @Test
    fun `interleaved creation order repeats headings without changing order or keys`() {
        val items = listOf(item("a", 16), item("b", 15), item("c", 16))
        val rows = entryListRows(items)
        assertEquals(listOf(16, 15, 16), headings(rows))
        assertEquals(items, rows.filterIsInstance<EntryListRow.Entry>().map { it.item })
        assertEquals(rows.size, rows.map { it.key }.distinct().size)
    }

    @Test
    fun `full accounting dates include month year and leap boundaries independently of creation instant`() {
        val dates = listOf(
            LocalDate.of(2024, 2, 29), LocalDate.of(2024, 3, 29),
            LocalDate.of(2025, 3, 29), LocalDate.of(2025, 3, 29),
        )
        val items = dates.mapIndexed { index, date ->
            item("calendar-$index", 16).let { it.copy(entry = it.entry.copy(date = date)) }
        }
        assertEquals(dates.distinct(), entryListRows(items).filterIsInstance<EntryListRow.DateHeading>().map { it.date })
        assertTrue(items.all { it.entry.createdAt == Instant.parse("2026-08-01T23:59:59Z") })
    }

    @Test
    fun `new page continues the current date run and introduces only changed dates`() {
        val items = (1..22).map { item("id-$it", if (it <= 21) 16 else 15) }
        val state = EntryListUiState(isLoading = false, entries = items)
        assertEquals(listOf(16), headings(entryListRows(state.visibleEntries)))
        val expanded = entryListRows(state.copy(visibleCount = 22).visibleEntries)
        assertEquals(listOf(16, 15), headings(expanded))
        assertEquals(listOf("date-id-1", "date-id-22"), expanded.filterIsInstance<EntryListRow.DateHeading>().map { it.key })
    }

    @Test
    fun `filtered refreshed added edited and deleted subsets derive headings anew`() {
        val a = item("a", 16)
        val b = item("b", 15)
        val c = item("c", 16)
        assertEquals(listOf(16, 15, 16), headings(entryListRows(listOf(a, b, c))))
        // Filter or delete the middle item: the newly adjacent dates now share a heading.
        assertEquals(listOf(16), headings(entryListRows(listOf(a, c))))
        // A refreshed subset starts with its own heading even if the first run was removed.
        assertEquals(listOf("date-b", "entry-b", "date-c", "entry-c"), entryListRows(listOf(b, c)).map { it.key })
        assertEquals(listOf(17, 16, 15, 16), headings(entryListRows(listOf(item("added", 17), a, b, c))))
        val edited = b.copy(entry = b.entry.copy(date = a.entry.date))
        assertEquals(listOf(16), headings(entryListRows(listOf(a, edited, c))))
        assertEquals(listOf(15, 16), headings(entryListRows(listOf(b, c))))
    }

    private fun headings(rows: List<EntryListRow>) = rows.filterIsInstance<EntryListRow.DateHeading>().map { it.date.dayOfMonth }

    private fun item(id: String, day: Int) = EntryListItem(
        entry = LedgerEntry(
            id = id, householdId = "home", amountGrosze = -100,
            date = LocalDate.of(2026, 9, day), categoryId = "food",
            authorId = "creator", updatedById = "updater", createdAt = Instant.parse("2026-08-01T23:59:59Z"),
        ),
        categoryName = "Jedzenie", subcategoryName = null, authorName = "creator@example.test",
    )
}
