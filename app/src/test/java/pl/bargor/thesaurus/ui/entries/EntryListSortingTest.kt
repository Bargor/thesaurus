package pl.bargor.thesaurus.ui.entries

import java.time.Instant
import java.time.LocalDate
import kotlin.random.Random
import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Test
import pl.bargor.thesaurus.data.model.LedgerEntry

class EntryListSortingTest {
    private val date = LocalDate.of(2026, 9, 16)
    private val created = Instant.parse("2026-09-16T12:00:00Z")

    @Test fun bothOrdersUseDescendingKeysPendingTimestampAndAscendingId() {
        val entries = listOf(
            entry("old-date", date.minusDays(1), created.plusSeconds(1)),
            entry("z", date, created), entry("a", date, created),
            entry("old-created", date, created.minusSeconds(1)),
            entry("pending-z", date, null), entry("pending-a", date, null),
            entry("pending-old-date", date.minusDays(1), null),
            entry("new-date", date.plusDays(1), created.minusSeconds(2)),
        )
        val accounting = listOf("new-date", "pending-a", "pending-z", "a", "z", "old-created", "pending-old-date", "old-date")
        val creation = listOf("pending-a", "pending-z", "pending-old-date", "old-date", "a", "z", "old-created", "new-date")
        listOf(EntryListSort.ACCOUNTING_DATE to accounting, EntryListSort.CREATION_ORDER to creation).forEach { (sort, expected) ->
            assertEquals(expected, sortEntries(entries.reversed(), sort).map { it.id })
            assertEquals(expected, sortItems(entries.map(::row).shuffled(Random(92)), sort).map { it.entry.id })
        }
    }

    @Test fun sortingRetainsExactRowsIncludingMetadataAndDuplicateIds() {
        val older = row(entry("same-id", date, created))
        val newer = older.copy(entry = older.entry.copy(date = date.plusDays(1)),
            categoryName = "Other", categoryColor = "rose", authorName = "Other author", canManage = false)
        EntryListSort.entries.forEach { sort ->
            val sorted = sortItems(listOf(older, newer), sort)
            assertSame(newer, sorted[0])
            assertSame(older, sorted[1])
        }
    }

    @Test fun largeShuffledFixtureMatchesExplicitKeyBucketsAndRetainsEveryRow() {
        val rows = (0 until 5_000).map { index ->
            row(entry("id-${index.toString().padStart(5, '0')}", date.plusDays((index % 11).toLong()),
                if (index % 7 == 0) null else created.plusSeconds((index % 13).toLong())))
                .copy(authorName = "Author $index", categoryName = "Category $index", canManage = index % 2 == 0)
        }
        // Construct the expected order from key buckets, independently of the production comparator.
        val timestamps = listOf<Instant?>(null) + (12 downTo 0).map { created.plusSeconds(it.toLong()) }
        val dates = (10 downTo 0).map { date.plusDays(it.toLong()) }
        EntryListSort.entries.forEach { sort ->
            val expected = when (sort) {
                EntryListSort.ACCOUNTING_DATE -> dates.flatMap { day -> timestamps.flatMap { timestamp ->
                    rows.filter { it.entry.date == day && it.entry.createdAt == timestamp }
                } }
                EntryListSort.CREATION_ORDER -> timestamps.flatMap { timestamp -> dates.flatMap { day ->
                    rows.filter { it.entry.date == day && it.entry.createdAt == timestamp }
                } }
            }
            val sorted = sortItems(rows.shuffled(Random(92)), sort)
            assertEquals(5_000, sorted.size)
            expected.zip(sorted).forEach { (original, actual) -> assertSame(original, actual) }
            assertEquals(expected.map { it.entry }, sortEntries(rows.map { it.entry }.reversed(), sort))
        }
    }

    private fun entry(id: String, date: LocalDate, createdAt: Instant?) = LedgerEntry(
        id, "home", -100, date, title = "Title $id", categoryId = "food", subcategoryId = "shop",
        tags = listOf("tag"), authorId = "author", updatedById = "author", createdAt = createdAt,
    )

    private fun row(entry: LedgerEntry) = EntryListItem(entry, "Food", "Shop", "Author", true, "mint")
}
