package pl.bargor.thesaurus.data.model

import java.time.LocalDate
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class LedgerEntryTest {
    private fun entry(amount: Long = -1L, title: String? = null, tags: List<String> = emptyList()) = LedgerEntry(
        id = "entry-1",
        householdId = "home-1",
        amountGrosze = amount,
        date = LocalDate.of(2026, 9, 14),
        title = title,
        categoryId = "food",
        tags = tags,
        authorId = "alice",
        updatedById = "alice",
    )

    @Test
    fun `amount keeps its sign so expenses and income are unambiguous`() {
        assertEquals(-1L, entry(-1).amountGrosze)
        assertEquals(1L, entry(1).amountGrosze)
        assertEquals(EntryType.EXPENSE, entry(-1).type)
        assertEquals(EntryType.INCOME, entry(1).type)
    }

    @Test(expected = IllegalArgumentException::class)
    fun `zero amount is rejected`() {
        entry(0)
    }

    @Test
    fun `blank optional title becomes null`() {
        assertNull(entry(title = "  ").normalizedTitle)
    }

    @Test
    fun `tags are trimmed lowercased deduplicated and sorted`() {
        assertEquals(listOf("dom", "zakupy"), entry(tags = listOf(" Zakupy ", "DOM", "zakupy", " ")).normalizedTags)
    }

    @Test(expected = IllegalArgumentException::class)
    fun `tags longer than the Firestore limit are rejected locally`() {
        entry(tags = listOf("a".repeat(41)))
    }
}
