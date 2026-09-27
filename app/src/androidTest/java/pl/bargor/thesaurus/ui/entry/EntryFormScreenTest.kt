package pl.bargor.thesaurus.ui.entry

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsSelected
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performTextInput
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import java.time.LocalDate
import org.junit.Rule
import org.junit.Test
import pl.bargor.thesaurus.ThesaurusTheme
import pl.bargor.thesaurus.data.model.Category
import pl.bargor.thesaurus.data.model.EntryType
import pl.bargor.thesaurus.data.model.Subcategory

class EntryFormScreenTest {
    @get:Rule val composeRule = createComposeRule()

    @Test
    fun activeTaxonomyCanBeSelectedAndDirectionCanBeOverridden() {
        val category = Category(
            id = "income", householdId = "home", name = "Wpływy", defaultEntryType = EntryType.INCOME,
            authorId = "actor", updatedById = "actor",
        )
        composeRule.setContent {
            var state by remember {
                mutableStateOf(
                    EntryFormUiState(
                        isLoading = false,
                        date = LocalDate.of(2026, 9, 16),
                        categories = listOf(EntryCategory(category, listOf(
                            Subcategory("salary", "home", "income", "Wypłata", authorId = "actor", updatedById = "actor"),
                        ))),
                    ),
                )
            }
            ThesaurusTheme {
                EntryFormScreen(
                    state = state,
                    onAmountChange = { state = state.copy(amount = it) }, onTitleChange = {}, onTagsChange = {}, onDateChange = {},
                    onTypeChange = { state = state.copy(type = it) },
                    onCategorySelected = { state = state.copy(categoryId = it, type = category.defaultEntryType) },
                    onSubcategorySelected = { state = state.copy(subcategoryId = it) }, onSave = {}, onBack = {},
                )
            }
        }

        composeRule.onNodeWithTag("entry-amount").performTextInput("12,50")
        composeRule.onNodeWithTag("entry-category-income").performClick()
        composeRule.onNodeWithTag("entry-subcategory-salary").performClick()
        composeRule.onNodeWithTag("entry-type-expense").performClick()

        composeRule.onNodeWithText("Tytuł (opcjonalnie)").performScrollTo().assertIsDisplayed()
    }

    @Test
    fun categoriesExpandInlineWithOptionalActiveSubcategoriesAndRestoreSelection() {
        val food = Category("food", "home", "Jedzenie", authorId = "actor", updatedById = "actor")
        val other = Category("other", "home", "Inne", authorId = "actor", updatedById = "actor")
        val archived = Category("old", "home", "Dawna", archived = true, authorId = "actor", updatedById = "actor")
        var state by mutableStateOf(
            EntryFormUiState(
                isLoading = false,
                categories = listOf(
                    EntryCategory(food, listOf(
                        Subcategory("groceries", "home", "food", "Zakupy", authorId = "actor", updatedById = "actor"),
                        Subcategory("old-sub", "home", "food", "Dawne zakupy", archived = true, authorId = "actor", updatedById = "actor"),
                    )),
                    EntryCategory(other, emptyList()),
                    EntryCategory(archived, emptyList()),
                ),
            ),
        )
        composeRule.setContent {
            ThesaurusTheme {
                EntryFormScreen(
                    state = state,
                    onAmountChange = {}, onTitleChange = {}, onTagsChange = {}, onDateChange = {},
                    onTypeChange = { state = state.copy(type = it) },
                    onCategorySelected = { id ->
                        state = state.copy(categoryId = id, subcategoryId = null,
                            type = state.categories.first { it.category.id == id }.category.defaultEntryType)
                    },
                    onSubcategorySelected = { state = state.copy(subcategoryId = it) },
                    onSave = {}, onBack = {},
                )
            }
        }

        composeRule.onNodeWithTag("entry-category-food").assertIsDisplayed()
        composeRule.onNodeWithTag("entry-category-other").assertIsDisplayed()
        composeRule.onNodeWithTag("entry-category-old").assertDoesNotExist()
        composeRule.onNodeWithTag("entry-subcategory-groceries").assertDoesNotExist()

        composeRule.onNodeWithTag("entry-category-food").performClick().assertIsSelected()
        composeRule.onNodeWithTag("entry-subcategory-none").assertIsSelected()
        composeRule.onNodeWithTag("entry-subcategory-groceries").performClick().assertIsSelected()
        composeRule.onNodeWithTag("entry-subcategory-old-sub").assertDoesNotExist()
        composeRule.runOnIdle { state = state.copy() }
        composeRule.onNodeWithTag("entry-category-food").assertIsSelected()
        composeRule.onNodeWithTag("entry-subcategory-groceries").assertIsSelected()

        composeRule.onNodeWithTag("entry-category-other").performScrollTo().performClick().assertIsSelected()
        composeRule.onNodeWithTag("entry-subcategory-groceries").assertDoesNotExist()
        composeRule.onNodeWithTag("entry-subcategory-none").assertDoesNotExist()
        composeRule.onNodeWithText("Brak aktywnych podkategorii").assertIsDisplayed()
    }

    @Test
    fun editSelectionIsExpandedWhenFormIsRestored() {
        val category = Category("income", "home", "Wpływy", defaultEntryType = EntryType.INCOME,
            authorId = "actor", updatedById = "actor")
        composeRule.setContent {
            ThesaurusTheme {
                EntryFormScreen(
                    state = EntryFormUiState(
                        isLoading = false, editingEntryId = "existing", categoryId = "income", subcategoryId = "salary",
                        type = EntryType.INCOME,
                        categories = listOf(EntryCategory(category, listOf(
                            Subcategory("salary", "home", "income", "Wypłata", authorId = "actor", updatedById = "actor"),
                        ))),
                    ),
                    onAmountChange = {}, onTitleChange = {}, onTagsChange = {}, onDateChange = {},
                    onTypeChange = {}, onCategorySelected = {}, onSubcategorySelected = {},
                    onSave = {}, onBack = {},
                )
            }
        }
        composeRule.onNodeWithTag("entry-category-income").assertIsSelected()
        composeRule.onNodeWithTag("entry-subcategory-salary").assertIsSelected()
    }

    @Test
    fun creationHasNoSuccessMessageButEditingKeepsItAndFailureRemainsActionable() {
        var state by mutableStateOf(EntryFormUiState(isLoading = false, saved = true))
        composeRule.setContent {
            ThesaurusTheme {
                EntryFormScreen(
                    state = state,
                    onAmountChange = {}, onTitleChange = {}, onTagsChange = {}, onDateChange = {},
                    onTypeChange = {}, onCategorySelected = {}, onSubcategorySelected = {},
                    onSave = {}, onBack = {},
                )
            }
        }

        composeRule.onNodeWithText("Wpis zapisany.").assertDoesNotExist()
        composeRule.runOnIdle { state = state.copy(queuedOffline = true) }
        composeRule.onNodeWithText("Wpis zapisany lokalnie i oczekuje na synchronizację.").assertDoesNotExist()
        composeRule.runOnIdle { state = state.copy(editingEntryId = "existing", queuedOffline = false) }
        composeRule.onNodeWithText("Wpis zapisany.").performScrollTo().assertIsDisplayed()
        composeRule.runOnIdle { state = state.copy(saved = false, error = EntryFormError.SaveFailed) }
        composeRule.onNodeWithText("Nie udało się zapisać wpisu. Spróbuj ponownie po odzyskaniu połączenia.")
            .performScrollTo().assertIsDisplayed()
    }
}
