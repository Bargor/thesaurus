package pl.bargor.thesaurus.ui.browse

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.width
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toPixelMap
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import java.time.LocalDate
import java.time.Year
import java.time.YearMonth
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import pl.bargor.thesaurus.ThesaurusTheme
import pl.bargor.thesaurus.data.model.*
import pl.bargor.thesaurus.ui.entries.EntryListItem
import pl.bargor.thesaurus.ui.summary.SummaryPeriodMode
import pl.bargor.thesaurus.ui.summary.SummaryScreen
import pl.bargor.thesaurus.ui.summary.SummaryUiState

class BrowseScreenTest {
    @get:Rule val compose = createComposeRule()

    @Test fun nestedExpansionExposesLevelNameSignedAmountAndOpensSelectedEntry() {
        var state by mutableStateOf(fixture())
        var opened: String? = null
        compose.setContent { ThesaurusTheme {
            BrowseScreen(state, {}, {}, {},
                onToggleCategory = { id -> state = state.copy(expandedCategoryIds = state.expandedCategoryIds.toggle(id), expandedSubcategories = emptySet()) },
                onToggleSubcategory = { key -> state = state.copy(expandedSubcategories = state.expandedSubcategories.toggle(key)) },
                onRetry = {}, onOpenEntry = { opened = it })
        } }
        val category = compose.onNodeWithTag("browse-category-food")
        category.assertContentDescriptionEquals("Kategoria: Jedzenie. Bilans: -10,00 zł")
            .assert(SemanticsMatcher.expectValue(SemanticsProperties.StateDescription, "Zwinięta"))
            .assertHasClickAction()
        compose.onNodeWithTag("browse-subcategory-food-shop").assertDoesNotExist()
        compose.onNodeWithTag("entry-expense").assertDoesNotExist()
        category.performClick().assert(SemanticsMatcher.expectValue(SemanticsProperties.StateDescription, "Rozwinięta"))
        val subcategory = compose.onNodeWithTag("browse-subcategory-food-shop")
        subcategory.assertContentDescriptionEquals("Podkategoria: Zakupy. Bilans: -10,00 zł")
            .assert(SemanticsMatcher.expectValue(SemanticsProperties.StateDescription, "Zwinięta"))
            .performClick().assert(SemanticsMatcher.expectValue(SemanticsProperties.StateDescription, "Rozwinięta"))
        compose.onNodeWithTag("browse-date-expense").assert(SemanticsMatcher.keyIsDefined(SemanticsProperties.Heading))
        compose.onNodeWithTag("entry-expense").assertIsDisplayed()
            .assert(SemanticsMatcher("Announces entry level, date, signed amount, taxonomy, and author") { node ->
                val description = node.config.getOrNull(SemanticsProperties.ContentDescription).orEmpty().joinToString(" ")
                listOf("Wpis.", "2 września 2026", "-10,00", "Jedzenie", "Zakupy", "Autor: Anna").all { it in description }
            }).performClick()
        assertEquals("expense", opened)
        category.performClick()
        compose.onNodeWithTag("entry-expense").assertDoesNotExist()
        compose.onNodeWithTag("browse-subcategory-food-shop").assertDoesNotExist()
    }

    @Test fun persistedCategoryBorderAndEntryStripeMatchAndNetAmountTextUsesSignColors() {
        val state = fixture().copy(expandedCategoryIds = setOf("food"), expandedSubcategories = setOf(BrowseSubcategoryKey("food", "shop")))
        compose.setContent { ThesaurusTheme {
            BrowseScreen(state, {}, {}, {}, {}, {}, {}, onOpenEntry = {})
        } }
        val categoryImage = compose.onNodeWithTag("browse-category-food").captureToImage()
        assertEquals(Color(0xFFF43F5E), categoryImage.toPixelMap()[1, categoryImage.height / 2])
        val stripe = compose.onNodeWithTag("entry-category-color-expense", useUnmergedTree = true).captureToImage()
        assertEquals(Color(0xFFF43F5E), stripe.toPixelMap()[stripe.width / 2, stripe.height / 2])
        assertImageContains("browse-category-food-amount", Color(0xFFB3261E))
        assertImageContains("browse-subcategory-food-shop-amount", Color(0xFFB3261E))
    }

    @Test fun positiveAndZeroNetsUseReadableTextColorsInBothThemes() {
        var dark by mutableStateOf(false)
        var neutral = Color.Unspecified
        val entries = listOf(entry("income", 500).copy(categoryId = "income"), entry("plus", 200).copy(categoryId = "zero"), entry("minus", -200).copy(categoryId = "zero"))
        val groups = groupBrowseEntries(entries, "home", YearMonth.of(2026, 9).summaryPeriod(), listOf(category("income", "Wpływy"), category("zero", "Zero")), emptyMap())
        compose.setContent { ThesaurusTheme(darkTheme = dark) {
            neutral = MaterialTheme.colorScheme.onSurface
            BrowseScreen(fixture().copy(categories = groups), {}, {}, {}, {}, {}, {}, onOpenEntry = {})
        } }
        assertImageContains("browse-category-income-amount", Color(0xFF146C2E))
        assertImageContains("browse-category-zero-amount", neutral)
        compose.runOnIdle { dark = true }
        assertImageContains("browse-category-income-amount", Color(0xFF8FDBA1))
        assertImageContains("browse-category-zero-amount", neutral)
    }

    @Test fun narrowLargeFontKeepsAllTextAnd48DpTargetsInsideIndentedRows() {
        val longName = "Zakupy spożywcze na cały tydzień dla dużej rodziny"
        val item = entry("large", Long.MAX_VALUE)
        val groups = groupBrowseEntries(listOf(item), "home", YearMonth.of(2026, 9).summaryPeriod(), listOf(category("food", longName)), emptyMap())
        val state = fixture().copy(categories = groups, expandedCategoryIds = setOf("food"), expandedSubcategories = setOf(BrowseSubcategoryKey("food", null)))
        compose.setContent {
            val density = LocalDensity.current
            CompositionLocalProvider(LocalDensity provides Density(density.density, 1.8f)) {
                ThesaurusTheme { Box(Modifier.width(280.dp).fillMaxHeight()) {
                    BrowseScreen(state, {}, {}, {}, {}, {}, {}, onOpenEntry = {})
                } }
            }
        }
        listOf("browse-period", "browse-previous-period", "browse-next-period", "browse-category-food", "browse-subcategory-food-none").forEach { tag ->
            val node = compose.onNodeWithTag(tag)
            if (tag.startsWith("browse-category") || tag.startsWith("browse-subcategory")) {
                compose.onNodeWithTag("browse-list").performScrollToNode(hasTestTag(tag))
            }
            val bounds = node.assertIsDisplayed().getUnclippedBoundsInRoot()
            assertTrue("$tag target must be 48dp high", bounds.bottom - bounds.top >= 48.dp)
            assertTrue("$tag target must be 48dp wide", bounds.right - bounds.left >= 48.dp)
            assertTrue("$tag must fit narrow portrait width", bounds.left >= 0.dp && bounds.right <= 280.dp)
        }
        compose.onNodeWithTag("browse-category-food").assertTextContains(longName, substring = true)
        val amountTag = "browse-subcategory-food-none-amount"
        compose.onNodeWithTag("browse-list").performScrollToNode(hasTestTag("browse-subcategory-food-none"))
        compose.onNodeWithTag(amountTag, useUnmergedTree = true).performSemanticsAction(androidx.compose.ui.semantics.SemanticsActions.GetTextLayoutResult) { getResults ->
            val results = mutableListOf<androidx.compose.ui.text.TextLayoutResult>()
            assertTrue(getResults(results))
            results.forEach { result ->
                assertTrue("Large amount must not lose characters", !result.didOverflowHeight)
                assertEquals(result.layoutInput.text.length, result.getLineEnd(result.lineCount - 1))
                for (line in 0 until result.lineCount) assertTrue(result.getLineRight(line) <= result.size.width + 1f)
            }
        }
    }

    @Test fun loadingEmptyErrorCachedPendingAndRetryRemainDistinct() {
        var state by mutableStateOf(BrowseUiState(YearMonth.of(2026, 9), Year.of(2026)))
        var retries = 0
        compose.setContent { ThesaurusTheme { BrowseScreen(state, {}, {}, {}, {}, {}, { retries++ }) } }
        compose.onNodeWithTag("browse-loading").assertIsDisplayed().assertContentDescriptionEquals("Ładowanie danych")
        compose.onNodeWithTag("browse-empty").assertDoesNotExist()
        compose.runOnIdle { state = state.copy(isLoading = false) }
        compose.onNodeWithTag("browse-empty").assertTextEquals("Brak wpisów w wybranym miesiącu.")
        compose.runOnIdle { state = state.copy(mode = SummaryPeriodMode.YEAR) }
        compose.onNodeWithTag("browse-empty").assertTextEquals("Brak wpisów w wybranym roku.")
        compose.runOnIdle { state = state.copy(hasError = true) }
        compose.onNodeWithTag("browse-error").assertIsDisplayed()
        compose.onNodeWithTag("browse-empty").assertDoesNotExist()
        compose.onNodeWithTag("browse-retry").performClick()
        assertEquals(1, retries)
        compose.runOnIdle { state = fixture().copy(syncState = SyncState.PENDING, hasError = true) }
        compose.onNodeWithTag("browse-pending").assertIsDisplayed()
        compose.onNodeWithTag("browse-category-food").assertIsDisplayed()
        compose.runOnIdle { state = state.copy(hasError = false, syncState = SyncState.OFFLINE) }
        compose.onNodeWithTag("browse-pending").assertDoesNotExist()
        compose.onNodeWithTag("browse-category-food").assertIsDisplayed()
    }

    @Test fun browseAndSummarySharePeriodTextStateAndToggleAction() {
        var showBrowse by mutableStateOf(true)
        var mode by mutableStateOf(SummaryPeriodMode.MONTH)
        var month by mutableStateOf(YearMonth.of(2026, 9))
        var year by mutableStateOf(Year.of(2026))
        val moves = mutableListOf<Pair<SummaryPeriodMode, Int>>()
        fun move(delta: Int) {
            moves += mode to delta
            if (mode == SummaryPeriodMode.MONTH) {
                month = month.plusMonths(delta.toLong()); year = Year.of(month.year)
            } else {
                year = year.plusYears(delta.toLong()); month = YearMonth.of(year.value, month.monthValue)
            }
        }
        compose.setContent { ThesaurusTheme {
            if (showBrowse) BrowseScreen(fixture().copy(mode = mode, month = month, year = year), { mode = it }, { move(-1) }, { move(1) }, {}, {}, {})
            else SummaryScreen(SummaryUiState(month, year, mode = mode, isLoading = false), { mode = it }, { move(-1) }, { move(1) }, {})
        } }
        val browse = compose.onNodeWithTag("browse-period")
        browse.assertTextContains("Wrzesień 2026")
        val monthDescription = browse.fetchSemanticsNode().config[SemanticsProperties.StateDescription]
        compose.onNodeWithTag("browse-previous-period").assertContentDescriptionEquals("Poprzedni miesiąc").performClick()
        browse.assertTextContains("Sierpień 2026")
        compose.onNodeWithTag("browse-next-period").assertContentDescriptionEquals("Następny miesiąc").performClick()
        browse.assertTextContains("Wrzesień 2026")
        browse.performClick().assertTextContains("2026")
        val yearDescription = browse.fetchSemanticsNode().config[SemanticsProperties.StateDescription]
        compose.onNodeWithTag("browse-previous-period").assertContentDescriptionEquals("Poprzedni rok").performClick()
        browse.assertTextContains("2025")
        compose.onNodeWithTag("browse-next-period").assertContentDescriptionEquals("Następny rok").performClick()
        browse.assertTextContains("2026")
        compose.runOnIdle { showBrowse = false }
        val summary = compose.onNodeWithTag("summary-period")
        summary.assertTextContains("2026").assert(SemanticsMatcher.expectValue(SemanticsProperties.StateDescription, yearDescription))
        compose.onNodeWithTag("summary-previous-period").assertContentDescriptionEquals("Poprzedni rok").performClick()
        summary.assertTextContains("2025")
        compose.onNodeWithTag("summary-next-period").assertContentDescriptionEquals("Następny rok").performClick()
        summary.assertTextContains("2026")
        summary.performClick().assertTextContains("Wrzesień 2026")
            .assert(SemanticsMatcher.expectValue(SemanticsProperties.StateDescription, monthDescription))
        compose.onNodeWithTag("summary-previous-period").assertContentDescriptionEquals("Poprzedni miesiąc").performClick()
        summary.assertTextContains("Sierpień 2026")
        compose.onNodeWithTag("summary-next-period").assertContentDescriptionEquals("Następny miesiąc").performClick()
        summary.assertTextContains("Wrzesień 2026")
        assertEquals(listOf(SummaryPeriodMode.MONTH to -1, SummaryPeriodMode.MONTH to 1,
            SummaryPeriodMode.YEAR to -1, SummaryPeriodMode.YEAR to 1,
            SummaryPeriodMode.YEAR to -1, SummaryPeriodMode.YEAR to 1,
            SummaryPeriodMode.MONTH to -1, SummaryPeriodMode.MONTH to 1), moves)
    }

    private fun fixture(): BrowseUiState {
        val item = entry("expense", -1000).copy(subcategoryId = "shop")
        val groups = groupBrowseEntries(listOf(item), "home", YearMonth.of(2026, 9).summaryPeriod(), listOf(category("food", "Jedzenie")), mapOf("food" to listOf(Subcategory("shop", "home", "food", "Zakupy", authorId = "actor", updatedById = "actor"))))
        return BrowseUiState(YearMonth.of(2026, 9), Year.of(2026), categories = groups, isLoading = false,
            entryItems = mapOf(item.id to EntryListItem(item, "Jedzenie", "Zakupy", "Anna", canManage = true, categoryColor = "rose")))
    }
    private fun entry(id: String, amount: Long) = LedgerEntry(id, "home", amount, LocalDate.of(2026, 9, 2), categoryId = "food", authorId = "actor", updatedById = "actor")
    private fun category(id: String, name: String) = Category(id, "home", name, color = "rose", authorId = "actor", updatedById = "actor")
    private fun <T> Set<T>.toggle(value: T) = if (value in this) this - value else this + value
    private fun assertImageContains(tag: String, color: Color) {
        val image = compose.onNodeWithTag(tag, useUnmergedTree = true).captureToImage()
        val pixels = image.toPixelMap()
        assertTrue("$tag must render $color", (0 until image.width).any { x -> (0 until image.height).any { y -> pixels[x, y] == color } })
    }
}
