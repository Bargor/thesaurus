package pl.bargor.thesaurus.data.model

import org.junit.Assert.assertEquals
import org.junit.Test

class CategoryOrderingTest {
    private fun category(id: String, archived: Boolean = false) = Category(
        id = id,
        householdId = "house",
        name = id,
        archived = archived,
        authorId = "user",
        updatedById = "user",
    )

    @Test
    fun stalePreferenceIsMigratedAndNewCategoriesAreAppended() {
        val categories = listOf(category("a"), category("b"), category("new"))
        val preference = CategoryOrder("house", "user", listOf("b", "missing", "a"))

        assertEquals(listOf("b", "a", "new"), categories.orderedBy(preference).map(Category::id))
    }

    @Test
    fun noPreferenceKeepsDeterministicRepositoryOrder() {
        val categories = listOf(category("a"), category("b"))

        assertEquals(categories, categories.orderedBy(null))
    }

    @Test
    fun movingActiveCategoryPreservesArchivedSlotsForRestoration() {
        val categories = listOf(category("a"), category("archived", archived = true), category("b"), category("c"))

        val reordered = categories.moveActiveCategory("c", "a")

        assertEquals(listOf("c", "archived", "a", "b"), reordered.map(Category::id))
    }
}
