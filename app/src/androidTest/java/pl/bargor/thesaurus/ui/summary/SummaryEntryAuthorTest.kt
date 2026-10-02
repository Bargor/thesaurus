package pl.bargor.thesaurus.ui.summary

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toPixelMap
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.dp
import java.time.LocalDate
import java.time.Year
import java.time.YearMonth
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import pl.bargor.thesaurus.ThesaurusTheme
import pl.bargor.thesaurus.data.model.LedgerEntry
import pl.bargor.thesaurus.ui.entries.EntryCard
import pl.bargor.thesaurus.ui.entries.EntryListItem
import pl.bargor.thesaurus.ui.entries.EntryListScreen
import pl.bargor.thesaurus.ui.entries.EntryListUiState

class SummaryEntryAuthorTest {
    @get:Rule val compose = createComposeRule()

    @Test fun detailKeepsEachAuthorInsideItsCardWithDateHeadingsOutsideAndExistingContentAndColors() {
        val items = listOf(item("short", "Anna", "rose"), item("long", longAuthor, "sky", day = 15))
        compose.setContent { ThesaurusTheme { SummaryScreen(detail(items), {}, {}, {}) } }

        for ((index, item) in items.withIndex()) {
            val id = item.entry.id
            compose.onNodeWithTag("summary-detail-list", true)
                .performScrollToNode(hasTestTag("date-$id"))
            val date = compose.onNodeWithTag("date-$id", true).assertIsDisplayed()
                .getUnclippedBoundsInRoot()
            val card = compose.onNodeWithTag("entry-$id").getUnclippedBoundsInRoot()
            assertTrue("Date heading must remain outside its card", date.bottom <= card.top)
            assertAuthorInsideCard(id)
            compose.onNodeWithTag("entry-author-$id", true).assertTextEquals("Autor: ${item.authorName}")
            compose.onNodeWithText("Zakupy $id", useUnmergedTree = true).assertIsDisplayed()
            compose.onNode(hasText("#codzienne") and hasAnyAncestor(hasTestTag("entry-$id")),
                useUnmergedTree = true).assertIsDisplayed()
            compose.onNodeWithTag("entry-expense-$id", true).assertTextEquals(summaryCurrency((-500).toBigInteger()))
            val image = compose.onNodeWithTag("entry-category-color-$id", true).captureToImage()
            assertEquals(if (index == 0) Color(0xFFF43F5E) else Color(0xFF0EA5E9),
                image.toPixelMap()[image.width / 2, image.height / 2])
        }
        // Both records fit after the period header has scrolled away; author text cannot
        // spill into the next date heading or entry, even when its visible value wraps.
        compose.onNodeWithTag("summary-detail-list", true).performScrollToNode(hasTestTag("date-short"))
        val first = compose.onNodeWithTag("entry-short").getUnclippedBoundsInRoot()
        val second = compose.onNodeWithTag("entry-long").getUnclippedBoundsInRoot()
        assertTrue("Adjacent cards must not overlap", first.bottom < second.top)
    }

    @Test fun authorAreaUsesTheSameEditTargetAndReadOnlyAuthorDoesNotGainAnAction() {
        val managed = item("managed", "Anna", "rose").copy(canManage = true)
        val readOnly = item("read-only", "Jan", "sky")
        val opened = mutableListOf<String>()
        compose.setContent {
            ThesaurusTheme { SummaryScreen(detail(listOf(managed, readOnly)), {}, {}, {}, onOpenEntry = { opened += it }) }
        }
        compose.onNodeWithTag("summary-detail-list", true).performScrollToNode(hasTestTag("entry-managed"))
        compose.onNodeWithTag("entry-managed").assertHasClickAction()
        assertEquals("Edytuj wpis", compose.onNodeWithTag("entry-managed").fetchSemanticsNode().config[SemanticsActions.OnClick].label)
        tapAuthor("managed")
        assertEquals(listOf("managed"), opened)
        compose.onNodeWithTag("entry-managed").performClick()
        assertEquals(listOf("managed", "managed"), opened)
        compose.onNodeWithTag("summary-detail-list", true).performScrollToNode(hasTestTag("entry-read-only"))
        compose.onNodeWithTag("entry-read-only").assertHasNoClickAction()
        tapAuthor("read-only")
        assertEquals(listOf("managed", "managed"), opened)
        for (id in listOf("managed", "read-only")) {
            compose.onNodeWithTag("entry-author-$id", true).assertHasNoClickAction()
            compose.onNodeWithTag("entry-author-$id").assertDoesNotExist()
            val name = if (id == "managed") "Anna" else "Jan"
            assertTrue("Author belongs to the card's merged accessibility value",
                compose.onNodeWithTag("entry-$id").fetchSemanticsNode()
                    .config[SemanticsProperties.Text].joinToString { it.text }.contains("Autor: $name"))
        }
    }

    @Test fun narrowScaledCardRetainsTheFullAuthorInOneAccessibleEntryAndFooterLongPressUsesExistingActions() {
        val entry = item("scaled", longAuthor, "rose").copy(canManage = true)
        var edits = 0
        var actions = 0
        var expectedAuthorSize = TextUnit.Unspecified
        compose.setContent {
            val native = LocalDensity.current
            CompositionLocalProvider(LocalDensity provides Density(native.density * 1.1f, 1.8f)) {
                ThesaurusTheme {
                    expectedAuthorSize = MaterialTheme.typography.labelSmall.fontSize
                    Box(Modifier.width(320.dp).fillMaxHeight().padding(16.dp)) {
                        EntryCard(entry, { edits++ }, onOpenActions = { actions++ }, showAuthor = true)
                    }
                }
            }
        }
        val card = compose.onNodeWithTag("entry-scaled").assertIsDisplayed().assertHasClickAction()
        val text = card.fetchSemanticsNode().config[SemanticsProperties.Text].joinToString { it.text }
        assertTrue("Merged card must announce the full author", text.contains("Autor: $longAuthor"))
        compose.onNodeWithTag("entry-author-scaled").assertDoesNotExist()
        assertAuthorInsideCard("scaled")
        val layouts = mutableListOf<TextLayoutResult>()
        compose.onNodeWithTag("entry-author-scaled", true)
            .performSemanticsAction(SemanticsActions.GetTextLayoutResult) { assertTrue(it(layouts)) }
        assertEquals("Layout must retain the original author for accessibility", "Autor: $longAuthor", layouts.single().layoutInput.text.text)
        assertEquals("Author retains the secondary label typography", expectedAuthorSize, layouts.single().layoutInput.style.fontSize)
        assertTrue("Footer stays bounded at accessibility sizes", layouts.single().lineCount <= 2)
        assertTrue("Fixture must exercise visible ellipsis", layouts.single().isLineEllipsized(layouts.single().lineCount - 1))
        val author = compose.onNodeWithTag("entry-author-scaled", true).fetchSemanticsNode().boundsInRoot
        val bounds = card.fetchSemanticsNode().boundsInRoot
        card.performTouchInput { longClick(Offset(author.center.x - bounds.left, author.center.y - bounds.top)) }
        assertEquals(1, actions)
        assertEquals(0, edits)
        tapAuthor("scaled")
        assertEquals(1, edits)
    }

    @Test fun mainEntriesScreenStillOmitsAuthorFooter() {
        compose.setContent {
            ThesaurusTheme {
                EntryListScreen(EntryListUiState(isLoading = false, entries = listOf(item("main", "Anna", "rose"))),
                    {}, {}, {}, {}, {})
            }
        }
        compose.onNodeWithTag("entry-main").assertIsDisplayed()
        compose.onNodeWithTag("entry-author-main", true).assertDoesNotExist()
        compose.onNodeWithText("Autor: Anna", useUnmergedTree = true).assertDoesNotExist()
    }

    private fun assertAuthorInsideCard(id: String) {
        val card = compose.onNodeWithTag("entry-$id").getUnclippedBoundsInRoot()
        val author = compose.onNodeWithTag("entry-author-$id", true).getUnclippedBoundsInRoot()
        assertTrue("Author must have usable layout space", author.right - author.left > 0.dp && author.bottom - author.top > 0.dp)
        assertTrue("Author must be padded inside its own border: $author / $card",
            author.left > card.left && author.right < card.right && author.top > card.top && author.bottom < card.bottom)
    }

    private fun tapAuthor(id: String) {
        val card = compose.onNodeWithTag("entry-$id")
        val bounds = card.fetchSemanticsNode().boundsInRoot
        val author = compose.onNodeWithTag("entry-author-$id", true).assertIsDisplayed().fetchSemanticsNode().boundsInRoot
        card.performTouchInput { click(Offset(author.center.x - bounds.left, author.center.y - bounds.top)) }
    }

    private fun detail(items: List<EntryListItem>): SummaryUiState {
        val cards = prepareSummaryOverview(items.map { it.entry }, "home", Year.of(2026),
            SummaryPeriodMode.MONTH, LocalDate.of(2026, 9, 30))
        return SummaryUiState(YearMonth.of(2026, 9), Year.of(2026), cards = cards, isLoading = false,
            detailCard = cards.last(), detailEntries = items)
    }

    private fun item(id: String, author: String, color: String, day: Int = 16) = EntryListItem(
        LedgerEntry(id, "home", -500, LocalDate.of(2026, 9, day), title = "Zakupy $id",
            categoryId = "food", tags = listOf("codzienne"), authorId = "actor", updatedById = "actor"),
        categoryName = "Jedzenie", subcategoryName = null, authorName = author, categoryColor = color,
    )

    private val longAuthor = "Aleksandra Maria Katarzyna Nowak-Kowalska o bardzo długim imieniu i nazwisku " +
        "z dodatkowym opisem konta rodzinnego oraz informacją, która musi pozostać dostępna dla czytnika ekranu"
}
