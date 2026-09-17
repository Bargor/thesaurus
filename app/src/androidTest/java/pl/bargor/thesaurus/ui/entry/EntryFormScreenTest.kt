package pl.bargor.thesaurus.ui.entry

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
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

        composeRule.onNodeWithText("Tytuł (opcjonalnie)").assertIsDisplayed()
    }
}
