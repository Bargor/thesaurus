package pl.bargor.thesaurus.ui.taxonomy

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsNotDisplayed
import androidx.compose.ui.test.assertContentDescriptionEquals
import androidx.compose.ui.test.assertHasClickAction
import androidx.compose.ui.test.assertHasNoClickAction
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollToNode
import androidx.compose.ui.test.performTextInput
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
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

    @Test
    fun categoriesStartCollapsedAndExpandUnderTheirOwnHeader() {
        val food = Category("food", "house", "Jedzenie", authorId = "user", updatedById = "user")
        val travel = Category("travel", "house", "Podróże", authorId = "user", updatedById = "user")
        showCategories(
            CategoryWithSubcategories(food, listOf(subcategory("market", "food", "Supermarket"))),
            CategoryWithSubcategories(travel, listOf(subcategory("train", "travel", "Pociąg"))),
        )

        composeRule.onNodeWithTag("taxonomy-subcategory-market").assertDoesNotExist()
        composeRule.onNodeWithTag("taxonomy-subcategory-train").assertDoesNotExist()
        val foodHeader = composeRule.onNodeWithTag("taxonomy-category-header-food")
        foodHeader.assertContentDescriptionEquals("Rozwiń kategorię Jedzenie")
        assertEquals(
            "Zwinięta",
            foodHeader.fetchSemanticsNode().config[SemanticsProperties.StateDescription],
        )
        foodHeader.performClick()
        assertEquals(
            "Rozwinięta",
            foodHeader.fetchSemanticsNode().config[SemanticsProperties.StateDescription],
        )
        composeRule.onNodeWithTag("taxonomy-subcategory-market").assertIsDisplayed()
        composeRule.onNodeWithTag("taxonomy-subcategory-train").assertDoesNotExist()
        foodHeader
            .assertContentDescriptionEquals("Zwiń kategorię Jedzenie")
            .performClick()
        assertEquals(
            "Zwinięta",
            foodHeader.fetchSemanticsNode().config[SemanticsProperties.StateDescription],
        )
        composeRule.onNodeWithTag("taxonomy-subcategory-market").assertDoesNotExist()
    }

    @Test
    fun emptyCategoryHasVisibleNonExpandableState() {
        val empty = Category("empty", "house", "Inne", authorId = "user", updatedById = "user")
        showCategories(CategoryWithSubcategories(empty, emptyList()))

        composeRule.onNodeWithText("Brak podkategorii").assertIsDisplayed()
        val emptyHeader = composeRule.onNodeWithTag("taxonomy-category-header-empty")
            .assertHasNoClickAction()
        assertEquals(
            "Brak podkategorii",
            emptyHeader.fetchSemanticsNode().config[SemanticsProperties.StateDescription],
        )
        composeRule.onNodeWithTag("taxonomy-add-subcategory-empty").assertHasClickAction()
    }

    @Test
    fun archivedFilterControlsCategoriesAndTheirSubcategories() {
        val active = Category("food", "house", "Jedzenie", authorId = "user", updatedById = "user")
        val archived = Category("old", "house", "Dawne", archived = true, authorId = "user", updatedById = "user")
        showCategories(
            CategoryWithSubcategories(active, listOf(subcategory("old-market", "food", "Stary sklep", archived = true))),
            CategoryWithSubcategories(archived, listOf(subcategory("old-child", "old", "Stara podkategoria"))),
        )

        composeRule.onNodeWithTag("taxonomy-category-old").assertDoesNotExist()
        composeRule.onNodeWithText("Brak aktywnych podkategorii").assertIsDisplayed()
        composeRule.onNodeWithTag("taxonomy-category-header-food").assertHasNoClickAction()
        composeRule.onNodeWithTag("taxonomy-show-archived").performClick()
        composeRule.onNodeWithTag("taxonomy-category-old").assertExists()
        composeRule.onNodeWithTag("taxonomy-category-header-food").performClick()
        composeRule.onNodeWithTag("taxonomy-subcategory-old-market").assertIsDisplayed()
        composeRule.onNodeWithTag("taxonomy-show-archived").performClick()
        composeRule.onNodeWithTag("taxonomy-subcategory-old-market").assertDoesNotExist()
        composeRule.onNodeWithTag("taxonomy-category-old").assertDoesNotExist()
    }

    @Test
    fun expansionAndCategoryAndSubcategoryActionsHaveSeparateTargets() {
        val mutations = mutableListOf<TaxonomyMutation>()
        val food = Category("food", "house", "Jedzenie", authorId = "author", updatedById = "author")
        val market = subcategory("market", "food", "Supermarket")
        showCategories(CategoryWithSubcategories(food, listOf(market)), onMutation = mutations::add)

        val density = InstrumentationRegistry.getInstrumentation().targetContext.resources.displayMetrics.density
        val header = composeRule.onNodeWithTag("taxonomy-category-header-food")
        header.assertHasClickAction().assertContentDescriptionEquals("Rozwiń kategorię Jedzenie")
        assertTrue(header.fetchSemanticsNode().boundsInRoot.height >= 48f * density - 1f)
        composeRule.onNodeWithTag("taxonomy-edit-category-food")
            .assertContentDescriptionEquals("Edytuj kategorię Jedzenie")
            .performClick()
        composeRule.onNodeWithText("Edytuj kategorię").assertIsDisplayed()
        composeRule.onNodeWithText("Anuluj").performClick()
        composeRule.onNodeWithTag("taxonomy-add-subcategory-food")
            .assertContentDescriptionEquals("Dodaj podkategorię do kategorii Jedzenie")
            .performClick()
        composeRule.onNodeWithTag("taxonomy-name").assertIsDisplayed()
        composeRule.onNodeWithText("Anuluj").performClick()
        composeRule.onNodeWithTag("taxonomy-archive-category-food")
            .assertContentDescriptionEquals("Archiwizuj kategorię Jedzenie")
            .performClick()
        assertEquals(TaxonomyMutation.SetCategoryArchived(food, true), mutations.single())
        composeRule.onNodeWithTag("taxonomy-subcategory-market").assertDoesNotExist()
        header.performClick()
        composeRule.onNodeWithTag("taxonomy-edit-subcategory-market")
            .assertContentDescriptionEquals("Edytuj podkategorię Supermarket")
            .performClick()
        composeRule.onNodeWithText("Edytuj podkategorię").assertIsDisplayed()
        composeRule.onNodeWithText("Anuluj").performClick()
        composeRule.onNodeWithTag("taxonomy-archive-subcategory-market")
            .assertContentDescriptionEquals("Archiwizuj podkategorię Supermarket")
            .performClick()
        assertEquals(TaxonomyMutation.SetSubcategoryArchived(market, true), mutations.last())
        assertEquals(2, mutations.size)
    }

    private fun showCategories(
        vararg categories: CategoryWithSubcategories,
        onMutation: (TaxonomyMutation) -> Unit = {},
    ) {
        composeRule.setContent {
            ThesaurusTheme {
                TaxonomyScreen(
                    state = TaxonomyUiState(isLoading = false, categories = categories.toList()),
                    onMutation = onMutation,
                )
            }
        }
    }

    private fun subcategory(id: String, categoryId: String, name: String, archived: Boolean = false) =
        Subcategory(id, "house", categoryId, name, archived = archived, authorId = "user", updatedById = "user")
}
