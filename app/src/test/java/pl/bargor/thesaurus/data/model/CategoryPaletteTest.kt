package pl.bargor.thesaurus.data.model

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class CategoryPaletteTest {
    @Test
    fun `palette has exactly 32 unique persisted tokens`() {
        assertEquals(32, CategoryPalette.swatches.size)
        assertEquals(32, CategoryPalette.swatches.map { it.token }.toSet().size)
        assertTrue(CategoryPalette.swatches.all { it.token.length <= 16 })
    }

    @Test
    fun `legacy starter categories without stored colors use their approved mapping`() {
        val expected = mapOf(
            "jedzenie" to "amber", "dom" to "blue", "odziez" to "violet",
            "zdrowie-i-uroda" to "pink", "dzieci" to "cyan", "samochod" to "slate",
            "czas-wolny" to "teal", "wplywy" to "green", "darowizny" to "red",
            "inne" to "gray",
        )

        expected.forEach { (id, token) ->
            val category = Category(id, "house", id, authorId = "actor", updatedById = "actor")
            assertEquals(token, CategoryPalette.forCategory(category).token)
        }
    }

    @Test
    fun `legacy custom fallback is deterministic and valid`() {
        val category = Category("custom-stable-id", "house", "Własna", authorId = "actor", updatedById = "actor")
        val first = CategoryPalette.forCategory(category)
        val second = CategoryPalette.forCategory(category.copy(name = "Nowa nazwa"))

        assertEquals(first, second)
        assertTrue(CategoryPalette.isToken(first.token))
        assertFalse(CategoryPalette.isToken("#FFFFFF"))
    }

    @Test
    fun `category accepts missing legacy color and rejects non-palette values`() {
        Category("legacy", "house", "Legacy", color = null, authorId = "actor", updatedById = "actor")
        val failure = runCatching {
            Category("bad", "house", "Błędna", color = "#FFFFFF", authorId = "actor", updatedById = "actor")
        }.exceptionOrNull()

        assertTrue(failure is IllegalArgumentException)
    }
}
