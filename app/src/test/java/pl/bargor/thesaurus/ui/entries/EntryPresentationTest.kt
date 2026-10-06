package pl.bargor.thesaurus.ui.entries

import java.time.LocalDate
import org.junit.Assert.*
import org.junit.Test
import pl.bargor.thesaurus.data.model.*

class EntryPresentationTest {
    private val entry = LedgerEntry("entry", "home", Long.MIN_VALUE, LocalDate.of(2026, 9, 1),
        categoryId = "old", subcategoryId = "archived", authorId = "former", updatedById = "former")
    @Test fun historicalMissingRecordsKeepSignedEntryAndStableFallbacks() {
        val item = presentEntry(entry, emptyMap(), emptyMap()).listItem(entry)
        assertSame(entry, item.entry)
        assertEquals(Long.MIN_VALUE, item.entry.amountGrosze)
        assertNull(item.categoryName)
        assertNull(item.subcategoryName)
        assertEquals("former", item.authorName)
        assertFalse(item.canManage)
    }
    @Test fun archivedTaxonomyLabelsAndColorsAreBoundWithoutChangingAmounts() {
        val category = Category("old", "home", "Dawna kategoria", color = "blue", archived = true,
            authorId = "owner", updatedById = "owner")
        val subcategory = Subcategory("archived", "home", "old", "Dawna podkategoria", archived = true,
            authorId = "owner", updatedById = "owner")
        val owner = Member("owner", "owner@example.test", "Owner", MemberRole.OWNER)
        val author = Member("former", "author@example.test", "  Anna  ", MemberRole.MEMBER)
        val members = listOf(owner, author).associateBy { it.uid }
        val item = presentEntry(entry, mapOf("old" to category), mapOf("old" to listOf(subcategory)),
            members, "owner", trimAuthorName = true).listItem(entry)
        assertEquals("Dawna kategoria", item.categoryName)
        assertEquals("Dawna podkategoria", item.subcategoryName)
        assertEquals("blue", item.categoryColor)
        assertEquals("Anna", item.authorName)
        assertTrue(item.canManage)
        assertTrue(canManageEntry(entry, "former", emptyMap()))
        assertFalse(canManageEntry(entry, "stranger", members))
        assertFalse(canManageEntry(entry, "", members))
        assertEquals("author@example.test", presentEntry(entry, emptyMap(), emptyMap(),
            mapOf("former" to author.copy(displayName = " "))).authorName)
    }
}
