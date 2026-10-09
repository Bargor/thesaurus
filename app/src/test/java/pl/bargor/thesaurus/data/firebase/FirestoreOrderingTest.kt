package pl.bargor.thesaurus.data.firebase

import java.time.LocalDate
import org.junit.Assert.assertEquals
import org.junit.Test
import pl.bargor.thesaurus.data.model.Category
import pl.bargor.thesaurus.data.model.LedgerEntry
import pl.bargor.thesaurus.data.model.Subcategory

class FirestoreOrderingTest {
    private val privateUse = "\uE000"
    private val emoji = "\uD83D\uDE00"

    @Test fun textOrderMatchesUnsignedUtf8ForCasePolishPrefixesAndSupplementaryCharacters() {
        assertEquals(listOf("A", "Z", "a", "aa", "z", "ą", "ż", privateUse, emoji),
            listOf(emoji, "ż", "aa", "A", privateUse, "ą", "z", "Z", "a").sortedWith(firestoreStringOrder))
    }

    @Test fun taxonomySortsNamesAndTiesByFirestoreStringOrder() {
        fun category(id: String, name: String) = Category(id, "home", name, authorId = "actor", updatedById = "actor")
        val categories = listOf(category("emoji", emoji), category(emoji, "same"),
            category("private", privateUse), category(privateUse, "same"))
        assertEquals(listOf(privateUse, emoji, "private", "emoji"), orderedCategories(categories).map { it.id })
        val subcategories = categories.map {
            Subcategory(it.id, "home", "category", it.name, authorId = "actor", updatedById = "actor")
        }
        assertEquals(listOf(privateUse, emoji, "private", "emoji"), orderedSubcategories(subcategories).map { it.id })
    }

    @Test fun ledgerDateTiesUseDescendingFirestoreDocumentIdOrder() {
        fun entry(id: String) = LedgerEntry(id, "home", -1L, LocalDate.of(2026, 10, 8),
            categoryId = "category", authorId = "actor", updatedById = "actor")
        assertEquals(listOf(emoji, privateUse, "a"),
            visibleLedgerEntries(listOf(entry(privateUse), entry("a"), entry(emoji)), false).map { it.id })
    }
}
