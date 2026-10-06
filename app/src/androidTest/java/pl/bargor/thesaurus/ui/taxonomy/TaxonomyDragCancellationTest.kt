package pl.bargor.thesaurus.ui.taxonomy

import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performTouchInput
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import pl.bargor.thesaurus.ThesaurusTheme
import pl.bargor.thesaurus.data.model.Category

/** The extracted drag preview must discard cancellation and keep the next drop connected. */
class TaxonomyDragCancellationTest {
    @get:Rule val composeRule = createComposeRule()

    @Test fun cancelledPreviewDoesNotPersistAndTheNextDropCommitsOnce() {
        val orders = mutableListOf<List<String>>()
        val categories = listOf("food" to "Jedzenie", "home" to "Dom").map { (id, name) ->
            CategoryWithSubcategories(Category(id, "house", name, authorId = "user", updatedById = "user"), emptyList())
        }
        composeRule.setContent {
            ThesaurusTheme {
                TaxonomyScreen(TaxonomyUiState(isLoading = false, categories = categories),
                    onMutation = { error("Dragging must not invoke taxonomy editing") },
                    onReorderCategories = orders::add)
            }
        }
        composeRule.onNodeWithTag("taxonomy-category-food").performTouchInput {
            down(center)
            advanceEventTime(1_000)
            moveBy(Offset(0f, 400f))
            advanceEventTime(100)
            cancel()
        }
        composeRule.waitForIdle()
        assertTrue(orders.isEmpty())

        composeRule.onNodeWithTag("taxonomy-category-food").performTouchInput {
            down(center)
            advanceEventTime(1_000)
            moveBy(Offset(0f, 400f))
            advanceEventTime(100)
            up()
        }
        composeRule.waitForIdle()
        assertEquals(listOf(listOf("home", "food")), orders)
    }
}
