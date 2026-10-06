package pl.bargor.thesaurus.ui.entry

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsSelected
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.assertIsNotFocused
import androidx.compose.ui.test.assertIsFocused
import androidx.compose.ui.test.assertTextContains
import androidx.compose.ui.test.performSemanticsAction
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.test.assert
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.test.click
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.test.junit4.StateRestorationTester
import androidx.test.platform.app.InstrumentationRegistry
import android.view.KeyEvent
import android.view.MotionEvent
import android.view.InputDevice
import android.os.SystemClock
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.width
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.onNodeWithContentDescription
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

    @Test fun minimumExpenseEditShowsExactUnsignedMagnitudeAndSelectedExpense() {
        val magnitude = pl.bargor.thesaurus.data.model.PlnMoney.entryInput(Long.MIN_VALUE)
        composeRule.setContent {
            ThesaurusTheme {
                EntryFormScreen(
                    state = EntryFormUiState(isLoading = false, editingEntryId = "minimum",
                        amount = magnitude, type = EntryType.EXPENSE),
                    onAmountChange = {}, onTitleChange = {}, onTagsChange = {}, onDateChange = {},
                    onTypeChange = {}, onCategorySelected = {}, onSubcategorySelected = {},
                    onSave = {}, onBack = {},
                )
            }
        }
        composeRule.onNodeWithTag("entry-amount").assertTextContains("92233720368547758,08")
        composeRule.onNodeWithTag("entry-type-expense").performScrollTo().assertIsSelected()
    }

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
        composeRule.onNodeWithTag("entry-category-picker").performScrollTo().performClick()
        composeRule.onNodeWithTag("entry-amount").assertIsNotFocused()
        composeRule.onNodeWithTag("entry-category-income").performClick()
        composeRule.onNodeWithTag("entry-category-menu").assertDoesNotExist()
        composeRule.onNodeWithTag("entry-subcategory-picker").performScrollTo().performClick()
        composeRule.onNodeWithTag("entry-subcategory-salary").performClick()
        composeRule.onNodeWithTag("entry-subcategory-menu").assertDoesNotExist()
        composeRule.onNodeWithTag("entry-type-expense").performScrollTo().performClick()
        composeRule.onNodeWithTag("entry-subcategory-picker").performScrollTo().performClick()
        composeRule.onNodeWithTag("entry-subcategory-salary").performScrollTo().assertIsSelected()
        InstrumentationRegistry.getInstrumentation().sendKeyDownUpSync(KeyEvent.KEYCODE_BACK)

        composeRule.onNodeWithText("Tytuł (opcjonalnie)").performScrollTo().assertIsDisplayed()
    }

    @Test
    fun dropdownKeepsOptionalActiveSubcategoriesAndSelection() {
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

        composeRule.onNodeWithTag("entry-category-food").assertDoesNotExist()
        composeRule.onNodeWithTag("entry-subcategory-picker").assertDoesNotExist()
        composeRule.onNodeWithTag("entry-category-picker").performScrollTo().performClick()
        composeRule.onNodeWithTag("entry-category-food").assertIsDisplayed()
        composeRule.onNodeWithTag("entry-category-other").assertIsDisplayed()
        composeRule.onNodeWithTag("entry-category-old").assertDoesNotExist()
        composeRule.onNodeWithTag("entry-subcategory-groceries").assertDoesNotExist()

        composeRule.onNodeWithTag("entry-category-food").performClick()
        composeRule.onNodeWithTag("entry-category-menu").assertDoesNotExist()
        composeRule.onNodeWithTag("entry-subcategory-picker").performScrollTo().performClick()
        composeRule.onNodeWithTag("entry-subcategory-none").assertIsSelected()
        composeRule.onNodeWithTag("entry-subcategory-groceries").performScrollTo().performClick()
        composeRule.onNodeWithTag("entry-subcategory-menu").assertDoesNotExist()
        composeRule.onNodeWithTag("entry-subcategory-picker").assertTextContains("Zakupy").performClick()
        composeRule.onNodeWithTag("entry-subcategory-groceries").assertIsSelected()
        composeRule.onNodeWithTag("entry-subcategory-old-sub").assertDoesNotExist()
        composeRule.runOnIdle { state = state.copy() }
        composeRule.onNodeWithTag("entry-subcategory-groceries").assertIsSelected()
        composeRule.onNodeWithTag("entry-subcategory-none").performClick()
        composeRule.runOnIdle { org.junit.Assert.assertNull(state.subcategoryId) }
        composeRule.onNodeWithTag("entry-subcategory-picker").assertTextContains("Bez podkategorii")

        composeRule.onNodeWithTag("entry-category-picker").performScrollTo().performClick()
        composeRule.onNodeWithTag("entry-category-food").assertIsSelected()
        composeRule.onNodeWithTag("entry-category-other").performClick()
        composeRule.onNodeWithTag("entry-subcategory-groceries").assertDoesNotExist()
        composeRule.onNodeWithTag("entry-subcategory-none").assertDoesNotExist()
        composeRule.onNodeWithTag("entry-subcategory-picker").performScrollTo().assertIsNotEnabled()
        composeRule.onNodeWithText("Brak aktywnych podkategorii").performScrollTo().assertIsDisplayed()
    }

    @Test
    fun editSelectionIsCollapsedAndSelectedWhenOpened() {
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
        composeRule.onNodeWithTag("entry-category-menu").assertDoesNotExist()
        composeRule.onNodeWithTag("entry-subcategory-menu").assertDoesNotExist()
        composeRule.onNodeWithTag("entry-subcategory-picker").performScrollTo().assertTextContains("Wypłata").performClick()
        composeRule.onNodeWithTag("entry-subcategory-salary").performScrollTo().assertIsSelected()
        InstrumentationRegistry.getInstrumentation().sendKeyDownUpSync(KeyEvent.KEYCODE_BACK)
        composeRule.onNodeWithTag("entry-category-picker").performScrollTo().performClick()
        composeRule.onNodeWithTag("entry-category-income").assertIsSelected()
    }

    @Test
    fun categorySelectionKeepsNonColorMarkerInLightTheme() = assertColoredCategorySelection(false)

    @Test
    fun categorySelectionKeepsNonColorMarkerInDarkTheme() = assertColoredCategorySelection(true)

    private fun assertColoredCategorySelection(darkTheme: Boolean) {
        val category = Category(
            "dom", "home", "Dom", color = "blue", authorId = "actor", updatedById = "actor",
        )
        composeRule.setContent {
            ThesaurusTheme(darkTheme = darkTheme) {
                EntryFormScreen(
                    state = EntryFormUiState(
                        isLoading = false,
                        categoryId = "dom",
                        categories = listOf(EntryCategory(category, emptyList())),
                    ),
                    onAmountChange = {}, onTitleChange = {}, onTagsChange = {}, onDateChange = {},
                    onTypeChange = {}, onCategorySelected = {}, onSubcategorySelected = {},
                    onSave = {}, onBack = {},
                )
            }
        }

        composeRule.onNodeWithTag("entry-category-picker").performScrollTo().performClick()
        composeRule.onNodeWithTag("entry-category-dom").assertIsSelected()
        composeRule.onNodeWithContentDescription("Wybrana", useUnmergedTree = true).assertIsDisplayed()
    }

    @Test
    fun emptyCategoryFieldShowsPlaceholderAndCannotOpenMenu() {
        composeRule.setContent {
            ThesaurusTheme {
                EntryFormScreen(state = EntryFormUiState(isLoading = false), onAmountChange = {},
                    onTitleChange = {}, onTagsChange = {}, onDateChange = {}, onTypeChange = {},
                    onCategorySelected = {}, onSubcategorySelected = {}, onSave = {}, onBack = {})
            }
        }
        composeRule.onNodeWithTag("entry-category-picker").performScrollTo()
            .assertIsNotEnabled().assertTextContains("Wybierz kategorię")
        composeRule.onNodeWithTag("entry-category-picker").performTouchInput { click() }
        composeRule.onNodeWithTag("entry-category-menu").assertDoesNotExist()
        composeRule.onNodeWithText("Brak aktywnych kategorii. Dodaj lub przywróć kategorię w ustawieniach.")
            .performScrollTo().assertIsDisplayed()
    }

    @Test
    fun hardwareKeyboardOpensNavigatesSelectsAndReturnsFocusToField() {
        var state by mutableStateOf(EntryFormUiState(isLoading = false,
            categories = listOf("food" to "Jedzenie", "income" to "Wpływy").map { (id, name) ->
                EntryCategory(Category(id, "home", name, authorId = "actor", updatedById = "actor"), emptyList())
            }))
        composeRule.setContent {
            ThesaurusTheme {
                EntryFormScreen(state = state, onAmountChange = {}, onTitleChange = {}, onTagsChange = {},
                    onDateChange = {}, onTypeChange = {}, onCategorySelected = { state = state.copy(categoryId = it) },
                    onSubcategorySelected = {}, onSave = {}, onBack = {})
            }
        }
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val field = composeRule.onNodeWithTag("entry-category-picker").performScrollTo()
        fun isFocused(tag: String) = composeRule.onNodeWithTag(tag).fetchSemanticsNode().config.let { config ->
            SemanticsProperties.Focused in config && config[SemanticsProperties.Focused]
        }
        var tabCount = 0
        while (!isFocused("entry-category-picker") && tabCount++ < 12) {
            instrumentation.sendKeyDownUpSync(KeyEvent.KEYCODE_TAB)
            composeRule.waitForIdle()
        }
        field.assertIsFocused()
        instrumentation.sendKeyDownUpSync(KeyEvent.KEYCODE_ENTER)
        composeRule.onNodeWithTag("entry-category-menu").assertIsDisplayed()
        composeRule.waitForIdle()
        val firstFocus = listOf("food", "income").single { isFocused("entry-category-$it") }
        instrumentation.sendKeyDownUpSync(KeyEvent.KEYCODE_DPAD_DOWN)
        composeRule.waitForIdle()
        val selectedId = listOf("food", "income").single { isFocused("entry-category-$it") }
        org.junit.Assert.assertNotEquals("Arrow navigation must move between category options", firstFocus, selectedId)
        instrumentation.sendKeyDownUpSync(KeyEvent.KEYCODE_ENTER)
        composeRule.onNodeWithTag("entry-category-menu").assertDoesNotExist()
        composeRule.runOnIdle { org.junit.Assert.assertEquals(selectedId, state.categoryId) }
        field.assertIsFocused()
        instrumentation.sendKeyDownUpSync(KeyEvent.KEYCODE_ENTER)
        composeRule.onNodeWithTag("entry-category-$selectedId").assertIsSelected()
        instrumentation.sendKeyDownUpSync(KeyEvent.KEYCODE_ESCAPE)
        composeRule.onNodeWithTag("entry-category-menu").assertDoesNotExist()
        field.assertIsFocused()
    }

    @Test
    fun wholeFieldOpensAndBackDismissesWithoutChangingSelection() {
        var selectedCalls = 0
        val category = Category("food", "home", "Jedzenie", authorId = "actor", updatedById = "actor")
        composeRule.setContent {
            ThesaurusTheme {
                EntryFormScreen(
                    state = EntryFormUiState(isLoading = false, categoryId = "food",
                        categories = listOf(EntryCategory(category, emptyList()))),
                    onAmountChange = {}, onTitleChange = {}, onTagsChange = {}, onDateChange = {},
                    onTypeChange = {}, onCategorySelected = { selectedCalls++ }, onSubcategorySelected = {},
                    onSave = {}, onBack = {},
                )
            }
        }
        composeRule.onNodeWithTag("entry-category-picker").performScrollTo()
            .assert(SemanticsMatcher.expectValue(SemanticsProperties.StateDescription, "Zwinięta"))
            .performTouchInput { click(Offset(4f, height / 2f)) }
        composeRule.onNodeWithTag("entry-category-menu").assertIsDisplayed()
        composeRule.onNodeWithTag("entry-category-picker")
            .assert(SemanticsMatcher.expectValue(SemanticsProperties.StateDescription, "Rozwinięta"))
        InstrumentationRegistry.getInstrumentation().sendKeyDownUpSync(KeyEvent.KEYCODE_BACK)
        composeRule.onNodeWithTag("entry-category-menu").assertDoesNotExist()
        composeRule.runOnIdle { org.junit.Assert.assertEquals(0, selectedCalls) }
        composeRule.onNodeWithTag("entry-category-picker").performClick()
        composeRule.onNodeWithTag("entry-category-food").assertIsSelected()
        // Inject at the window level so Android dispatches this outside tap to the popup.
        val fieldCenterY = composeRule.onNodeWithTag("entry-category-picker")
            .fetchSemanticsNode().boundsInRoot.center.y
        val eventTime = SystemClock.uptimeMillis()
        listOf(MotionEvent.ACTION_DOWN, MotionEvent.ACTION_UP).forEachIndexed { index, action ->
            val event = MotionEvent.obtain(eventTime, eventTime + index * 16L, action, 1f, fieldCenterY, 0)
            event.source = InputDevice.SOURCE_TOUCHSCREEN
            try {
                org.junit.Assert.assertTrue(InstrumentationRegistry.getInstrumentation().uiAutomation.injectInputEvent(event, true))
            } finally {
                event.recycle()
            }
        }
        composeRule.onNodeWithTag("entry-category-menu").assertDoesNotExist()
        composeRule.runOnIdle { org.junit.Assert.assertEquals(0, selectedCalls) }
    }

    @Test
    fun restoredFormKeepsSelectionButClosesTransientMenu() {
        val restoration = StateRestorationTester(composeRule)
        val category = Category("food", "home", "Jedzenie", authorId = "actor", updatedById = "actor")
        restoration.setContent {
            ThesaurusTheme {
                EntryFormScreen(
                    state = EntryFormUiState(isLoading = false, categoryId = "food",
                        categories = listOf(EntryCategory(category, emptyList()))),
                    onAmountChange = {}, onTitleChange = {}, onTagsChange = {}, onDateChange = {},
                    onTypeChange = {}, onCategorySelected = {}, onSubcategorySelected = {}, onSave = {}, onBack = {},
                )
            }
        }
        composeRule.onNodeWithTag("entry-category-picker").performScrollTo().performClick()
        composeRule.onNodeWithTag("entry-category-food").assertIsSelected()
        restoration.emulateSavedInstanceStateRestore()
        composeRule.onNodeWithTag("entry-category-menu").assertDoesNotExist()
        composeRule.onNodeWithTag("entry-category-picker").performScrollTo().performClick()
        composeRule.onNodeWithTag("entry-category-food").assertIsSelected()
    }

    @Test
    fun archivedHistoricalSelectionIsVisibleButDisabledForSelection() {
        val old = Category("old", "home", "Dawna", archived = true, authorId = "actor", updatedById = "actor")
        composeRule.setContent {
            ThesaurusTheme {
                EntryFormScreen(
                    state = EntryFormUiState(isLoading = false, editingEntryId = "existing", categoryId = "old",
                        categories = listOf(EntryCategory(old, emptyList()))),
                    onAmountChange = {}, onTitleChange = {}, onTagsChange = {}, onDateChange = {},
                    onTypeChange = {}, onCategorySelected = {}, onSubcategorySelected = {}, onSave = {}, onBack = {},
                )
            }
        }
        composeRule.onNodeWithTag("entry-category-picker").performScrollTo().performClick()
        composeRule.onNodeWithTag("entry-category-old").assertIsSelected().assertIsNotEnabled()
    }

    @Test
    fun narrowLargeTextMenuScrollsToLongNamesWithoutFilteringByDirection() {
        var state by mutableStateOf(EntryFormUiState(isLoading = false,
            categories = (1..24).map { index -> EntryCategory(Category("c$index", "home",
                "Bardzo długa nazwa kategorii numer $index do zawijania tekstu",
                defaultEntryType = if (index % 2 == 0) EntryType.INCOME else EntryType.EXPENSE,
                authorId = "actor", updatedById = "actor"), emptyList()) }))
        composeRule.setContent {
            val density = LocalDensity.current
            CompositionLocalProvider(LocalDensity provides Density(density.density, fontScale = 1.6f)) {
                Box(Modifier.width(320.dp)) {
                    ThesaurusTheme {
                        EntryFormScreen(state = state, onAmountChange = {}, onTitleChange = {}, onTagsChange = {},
                            onDateChange = {}, onTypeChange = {},
                            onCategorySelected = { state = state.copy(categoryId = it) },
                            onSubcategorySelected = {}, onSave = {}, onBack = {})
                    }
                }
            }
        }
        composeRule.onNodeWithTag("entry-category-picker").performScrollTo().performClick()
        composeRule.onNodeWithTag("entry-category-c24").performScrollTo().assertIsDisplayed()
        val longName = state.categories.last().category.name
        val menuLayouts = mutableListOf<TextLayoutResult>()
        composeRule.onNodeWithText(longName, useUnmergedTree = true)
            .performSemanticsAction(SemanticsActions.GetTextLayoutResult) { org.junit.Assert.assertTrue(it(menuLayouts)) }
        org.junit.Assert.assertEquals(longName, menuLayouts.single().layoutInput.text.text)
        org.junit.Assert.assertFalse("Long option name must be fully laid out", menuLayouts.single().hasVisualOverflow)
        org.junit.Assert.assertTrue("Fixture should wrap onto multiple lines", menuLayouts.single().lineCount > 1)
        composeRule.onNodeWithTag("entry-category-c24").performClick()
        composeRule.onNodeWithTag("entry-category-menu").assertDoesNotExist()
        composeRule.runOnIdle { org.junit.Assert.assertEquals("c24", state.categoryId) }
        val fieldLayouts = mutableListOf<TextLayoutResult>()
        composeRule.onNodeWithTag("entry-category-picker").performScrollTo()
            .performSemanticsAction(SemanticsActions.GetTextLayoutResult) { org.junit.Assert.assertTrue(it(fieldLayouts)) }
        org.junit.Assert.assertEquals(longName, fieldLayouts.single().layoutInput.text.text)
        org.junit.Assert.assertFalse("Selected long name must fit the multiline field", fieldLayouts.single().hasVisualOverflow)
        composeRule.onNodeWithTag("entry-category-picker").performClick()
        composeRule.onNodeWithTag("entry-category-c24").performScrollTo().assertIsSelected()
        composeRule.onNodeWithTag("entry-category-c1").performScrollTo().assertIsDisplayed()
    }

    @Test
    fun subcategoryWholeFieldOpensAndBackAndOutsideDismissWithoutMutation() {
        var selectedCalls = 0
        val state = subcategoryFixture().copy(subcategoryId = "s1")
        showSubcategoryForm({ state }, { selectedCalls++ })
        val field = composeRule.onNodeWithTag("entry-subcategory-picker").performScrollTo()
        field.assertTextContains("Podkategoria 1")
            .assert(SemanticsMatcher.expectValue(SemanticsProperties.StateDescription, "Zwinięta"))
            .performTouchInput { click(Offset(4f, height / 2f)) }
        composeRule.onNodeWithTag("entry-subcategory-menu").assertIsDisplayed()
        field.assert(SemanticsMatcher.expectValue(SemanticsProperties.StateDescription, "Rozwinięta"))
        composeRule.onNodeWithTag("entry-subcategory-s1").assertIsSelected()
        composeRule.onNodeWithContentDescription("Wybrana", useUnmergedTree = true).assertIsDisplayed()
        InstrumentationRegistry.getInstrumentation().sendKeyDownUpSync(KeyEvent.KEYCODE_BACK)
        composeRule.onNodeWithTag("entry-subcategory-menu").assertDoesNotExist()
        field.assertTextContains("Podkategoria 1").performClick()
        val fieldCenterY = field.fetchSemanticsNode().boundsInRoot.center.y
        val eventTime = SystemClock.uptimeMillis()
        listOf(MotionEvent.ACTION_DOWN, MotionEvent.ACTION_UP).forEachIndexed { index, action ->
            val event = MotionEvent.obtain(eventTime, eventTime + index * 16L, action, 1f, fieldCenterY, 0)
            event.source = InputDevice.SOURCE_TOUCHSCREEN
            try {
                org.junit.Assert.assertTrue(InstrumentationRegistry.getInstrumentation().uiAutomation.injectInputEvent(event, true))
            } finally {
                event.recycle()
            }
        }
        composeRule.onNodeWithTag("entry-subcategory-menu").assertDoesNotExist()
        field.assertTextContains("Podkategoria 1")
        composeRule.runOnIdle { org.junit.Assert.assertEquals(0, selectedCalls) }
    }

    @Test
    fun categoryChangeWhileSubcategoryMenuIsOpenClosesItAndShowsOnlyNewOptions() {
        val initial = subcategoryFixture().copy(subcategoryId = "s1")
        val other = Category("other", "home", "Inne", authorId = "actor", updatedById = "actor")
        var state by mutableStateOf(initial.copy(categories = initial.categories + EntryCategory(other, listOf(
            Subcategory("other-sub", "home", "other", "Inna podkategoria", authorId = "actor", updatedById = "actor"),
        ))))
        showSubcategoryForm({ state }, { state = state.copy(subcategoryId = it) })
        composeRule.onNodeWithTag("entry-subcategory-picker").performScrollTo().performClick()
        composeRule.onNodeWithTag("entry-subcategory-s1").assertIsSelected()
        // The ViewModel supplies the cleared dependent ID with the changed parent ID.
        composeRule.runOnIdle { state = state.copy(categoryId = "other", subcategoryId = null) }
        composeRule.onNodeWithTag("entry-subcategory-menu").assertDoesNotExist()
        composeRule.onNodeWithTag("entry-subcategory-picker").assertTextContains("Bez podkategorii").performClick()
        composeRule.onNodeWithTag("entry-subcategory-none").assertIsSelected()
        composeRule.onNodeWithTag("entry-subcategory-s1").assertDoesNotExist()
        composeRule.onNodeWithTag("entry-subcategory-other-sub").assertIsDisplayed().performClick()
        composeRule.runOnIdle { org.junit.Assert.assertEquals("other-sub", state.subcategoryId) }
        composeRule.onNodeWithTag("entry-subcategory-menu").assertDoesNotExist()
    }

    @Test
    fun historicalArchivedSubcategoryCanBeClearedButCannotBeAssignedAgain() {
        val initial = subcategoryFixture()
        val archived = Subcategory("old", "home", "food", "Dawna podkategoria", archived = true,
            authorId = "actor", updatedById = "actor")
        var state by mutableStateOf(initial.copy(editingEntryId = "existing", subcategoryId = "old",
            categories = listOf(initial.categories.single().copy(subcategories = listOf(archived)))))
        showSubcategoryForm({ state }, { state = state.copy(subcategoryId = it) })
        composeRule.onNodeWithTag("entry-subcategory-picker").performScrollTo()
            .assertTextContains("Dawna podkategoria").performClick()
        composeRule.onNodeWithTag("entry-subcategory-old").assertIsSelected().assertIsNotEnabled()
        composeRule.onNodeWithTag("entry-subcategory-none").performClick()
        composeRule.runOnIdle { org.junit.Assert.assertNull(state.subcategoryId) }
        composeRule.onNodeWithTag("entry-subcategory-menu").assertDoesNotExist()
        composeRule.onNodeWithTag("entry-subcategory-picker").assertTextContains("Bez podkategorii").assertIsNotEnabled()
        composeRule.onNodeWithTag("entry-subcategory-old").assertDoesNotExist()
        composeRule.onNodeWithText("Brak aktywnych podkategorii").performScrollTo().assertIsDisplayed()
    }

    @Test
    fun subcategoryMenuScrollsLongNamesAtNarrowWidthAndLargeFont() {
        var state by mutableStateOf(subcategoryFixture(count = 24, longNames = true))
        showSubcategoryForm({ state }, { state = state.copy(subcategoryId = it) }, compact = true)
        composeRule.onNodeWithTag("entry-subcategory-picker").performScrollTo().performClick()
        composeRule.onNodeWithTag("entry-subcategory-s24").performScrollTo().assertIsDisplayed()
        val longName = state.categories.single().subcategories.last().name
        val layouts = mutableListOf<TextLayoutResult>()
        composeRule.onNodeWithText(longName, useUnmergedTree = true)
            .performSemanticsAction(SemanticsActions.GetTextLayoutResult) { org.junit.Assert.assertTrue(it(layouts)) }
        org.junit.Assert.assertFalse("Long option name must be fully laid out", layouts.single().hasVisualOverflow)
        org.junit.Assert.assertTrue("Fixture should wrap onto multiple lines", layouts.single().lineCount > 1)
        composeRule.onNodeWithTag("entry-subcategory-s24").performClick()
        composeRule.onNodeWithTag("entry-subcategory-menu").assertDoesNotExist()
        composeRule.runOnIdle { org.junit.Assert.assertEquals("s24", state.subcategoryId) }
        val fieldLayouts = mutableListOf<TextLayoutResult>()
        composeRule.onNodeWithTag("entry-subcategory-picker").performScrollTo()
            .performSemanticsAction(SemanticsActions.GetTextLayoutResult) { org.junit.Assert.assertTrue(it(fieldLayouts)) }
        org.junit.Assert.assertEquals(longName, fieldLayouts.single().layoutInput.text.text)
        org.junit.Assert.assertFalse("Selected name must fit the multiline field", fieldLayouts.single().hasVisualOverflow)
        composeRule.onNodeWithTag("entry-subcategory-picker").performClick()
        composeRule.onNodeWithTag("entry-subcategory-s24").performScrollTo().assertIsSelected()
        composeRule.onNodeWithTag("entry-subcategory-s1").performScrollTo().assertIsDisplayed()
    }

    @Test
    fun subcategoryKeyboardSelectsAndEscapeReturnsFocusWithoutMutation() {
        var state by mutableStateOf(subcategoryFixture())
        showSubcategoryForm({ state }, { state = state.copy(subcategoryId = it) })
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val field = composeRule.onNodeWithTag("entry-subcategory-picker").performScrollTo()
        fun isFocused(tag: String) = composeRule.onNodeWithTag(tag).fetchSemanticsNode().config.let { config ->
            SemanticsProperties.Focused in config && config[SemanticsProperties.Focused]
        }
        var tabCount = 0
        while (!isFocused("entry-subcategory-picker") && tabCount++ < 16) {
            instrumentation.sendKeyDownUpSync(KeyEvent.KEYCODE_TAB)
            composeRule.waitForIdle()
        }
        field.assertIsFocused()
        instrumentation.sendKeyDownUpSync(KeyEvent.KEYCODE_ENTER)
        composeRule.onNodeWithTag("entry-subcategory-menu").assertIsDisplayed()
        val options = listOf("none", "s1", "s2")
        val firstFocus = options.single { isFocused("entry-subcategory-$it") }
        instrumentation.sendKeyDownUpSync(KeyEvent.KEYCODE_DPAD_DOWN)
        composeRule.waitForIdle()
        val selected = options.single { isFocused("entry-subcategory-$it") }
        org.junit.Assert.assertNotEquals(firstFocus, selected)
        instrumentation.sendKeyDownUpSync(KeyEvent.KEYCODE_ENTER)
        composeRule.onNodeWithTag("entry-subcategory-menu").assertDoesNotExist()
        composeRule.runOnIdle { org.junit.Assert.assertEquals(selected.takeUnless { it == "none" }, state.subcategoryId) }
        field.assertIsFocused()
        instrumentation.sendKeyDownUpSync(KeyEvent.KEYCODE_ENTER)
        composeRule.onNodeWithTag("entry-subcategory-$selected").assertIsSelected()
        instrumentation.sendKeyDownUpSync(KeyEvent.KEYCODE_ESCAPE)
        composeRule.onNodeWithTag("entry-subcategory-menu").assertDoesNotExist()
        field.assertIsFocused()
        composeRule.runOnIdle { org.junit.Assert.assertEquals(selected.takeUnless { it == "none" }, state.subcategoryId) }
    }

    @Test
    fun restoredSubcategoryFieldKeepsSelectedValueAndClosesTransientMenu() {
        val restoration = StateRestorationTester(composeRule)
        val state = subcategoryFixture().copy(editingEntryId = "existing", subcategoryId = "s2")
        restoration.setContent {
            ThesaurusTheme {
                EntryFormScreen(state = state, onAmountChange = {}, onTitleChange = {}, onTagsChange = {},
                    onDateChange = {}, onTypeChange = {}, onCategorySelected = {}, onSubcategorySelected = {},
                    onSave = {}, onBack = {})
            }
        }
        composeRule.onNodeWithTag("entry-subcategory-picker").performScrollTo().performClick()
        composeRule.onNodeWithTag("entry-subcategory-s2").assertIsSelected()
        restoration.emulateSavedInstanceStateRestore()
        composeRule.onNodeWithTag("entry-subcategory-menu").assertDoesNotExist()
        composeRule.onNodeWithTag("entry-subcategory-picker").performScrollTo()
            .assertTextContains("Podkategoria 2").performClick()
        composeRule.onNodeWithTag("entry-subcategory-s2").assertIsSelected()
    }

    private fun subcategoryFixture(count: Int = 2, longNames: Boolean = false) = EntryFormUiState(
        isLoading = false, categoryId = "food",
        categories = listOf(EntryCategory(
            Category("food", "home", "Jedzenie", authorId = "actor", updatedById = "actor"),
            (1..count).map { index -> Subcategory("s$index", "home", "food",
                if (longNames) "Bardzo długa nazwa podkategorii numer $index do zawijania tekstu" else "Podkategoria $index",
                authorId = "actor", updatedById = "actor") },
        )),
    )

    private fun showSubcategoryForm(state: () -> EntryFormUiState, onSelected: (String?) -> Unit, compact: Boolean = false) {
        composeRule.setContent {
            val density = LocalDensity.current
            CompositionLocalProvider(LocalDensity provides Density(density.density, fontScale = if (compact) 1.6f else density.fontScale)) {
                Box(if (compact) Modifier.width(320.dp) else Modifier) {
                    ThesaurusTheme {
                        EntryFormScreen(state = state(), onAmountChange = {}, onTitleChange = {}, onTagsChange = {},
                            onDateChange = {}, onTypeChange = {}, onCategorySelected = {},
                            onSubcategorySelected = onSelected, onSave = {}, onBack = {})
                    }
                }
            }
        }
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
