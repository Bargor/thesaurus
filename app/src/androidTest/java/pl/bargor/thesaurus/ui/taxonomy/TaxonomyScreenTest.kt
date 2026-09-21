package pl.bargor.thesaurus.ui.taxonomy

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsNotDisplayed
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollToNode
import androidx.compose.ui.test.performTextInput
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import pl.bargor.thesaurus.ThesaurusTheme
import pl.bargor.thesaurus.data.model.Category
import pl.bargor.thesaurus.data.model.EntryType
import pl.bargor.thesaurus.data.model.Subcategory

class TaxonomyScreenTest {
    @get:Rule
    val composeRule = createComposeRule()

    @Test
    fun createsCustomCategoryWithExpenseDefaultAndShowsArchivedOnDemand() {
        val mutations = mutableListOf<TaxonomyMutation>()
        val active = Category("food", "house", "Jedzenie", authorId = "user", updatedById = "user")
        val archived = Category("old", "house", "Dawne", archived = true, authorId = "user", updatedById = "user")
        composeRule.setContent {
            ThesaurusTheme {
                TaxonomyScreen(
                    state = TaxonomyUiState(
                        isLoading = false,
                        categories = listOf(
                            CategoryWithSubcategories(
                                active,
                                listOf(Subcategory("market", "house", "food", "Supermarket", authorId = "user", updatedById = "user")),
                            ),
                            CategoryWithSubcategories(archived, emptyList()),
                        ),
                    ),
                    onMutation = mutations::add,
                )
            }
        }

        composeRule.onNodeWithText("Jedzenie").assertIsDisplayed()
        composeRule.onNodeWithText("Dawne").assertIsNotDisplayed()
        composeRule.onNodeWithTag("taxonomy-show-archived").performClick()
        composeRule.onNodeWithTag("taxonomy-list").performScrollToNode(hasText("Dawne"))
        composeRule.onNodeWithText("Dawne").assertIsDisplayed()

        composeRule.onNodeWithTag("taxonomy-add-category").performClick()
        composeRule.onNodeWithTag("taxonomy-name").performTextInput(" Zwierzęta ")
        composeRule.onNodeWithTag("taxonomy-save").performClick()

        assertEquals(
            TaxonomyMutation.AddCategory(" Zwierzęta ", EntryType.EXPENSE),
            mutations.single(),
        )
    }
}
