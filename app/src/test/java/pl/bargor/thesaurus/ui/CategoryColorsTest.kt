package pl.bargor.thesaurus.ui

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.luminance
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import pl.bargor.thesaurus.data.model.CategoryPalette

class CategoryColorsTest {
    @Test
    fun `selected category is stronger and text keeps accessible contrast in both themes`() {
        CategoryPalette.swatches.forEach { swatch ->
            val accent = swatch.asColor()
            listOf(false to Color.White, true to Color(0xFF121212)).forEach { (dark, surface) ->
                val base = accent.categoryContainer(surface, selected = false, dark = dark)
                val selected = accent.categoryContainer(surface, selected = true, dark = dark)
                val content = if (dark) Color.White else Color.Black

                assertNotEquals(base, selected)
                assertTrue("${swatch.token} dark=$dark", contrastRatio(content, selected) >= 4.5f)
            }
        }
    }

    private fun contrastRatio(first: Color, second: Color): Float {
        val brighter = maxOf(first.luminance(), second.luminance())
        val darker = minOf(first.luminance(), second.luminance())
        return (brighter + 0.05f) / (darker + 0.05f)
    }
}
