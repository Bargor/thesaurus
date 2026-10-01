package pl.bargor.thesaurus.ui

import androidx.annotation.StringRes
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import java.time.LocalDate
import java.time.Year
import java.time.YearMonth
import pl.bargor.thesaurus.R
import pl.bargor.thesaurus.ThesaurusTheme
import pl.bargor.thesaurus.data.model.Category
import pl.bargor.thesaurus.data.model.LedgerEntry
import pl.bargor.thesaurus.data.model.Member
import pl.bargor.thesaurus.data.model.MemberRole
import pl.bargor.thesaurus.data.model.SyncState
import pl.bargor.thesaurus.ui.entries.EntryListScreen
import pl.bargor.thesaurus.ui.entries.EntryListUiState
import pl.bargor.thesaurus.ui.entries.EntryListItem
import pl.bargor.thesaurus.ui.entries.EntryListError
import pl.bargor.thesaurus.ui.entry.EntryCategory
import pl.bargor.thesaurus.ui.entry.EntryFormScreen
import pl.bargor.thesaurus.ui.entry.EntryFormUiState
import pl.bargor.thesaurus.ui.entry.EntryFormError
import pl.bargor.thesaurus.ui.family.FamilyScreen
import pl.bargor.thesaurus.ui.family.FamilyUiState
import pl.bargor.thesaurus.ui.family.FamilyError
import pl.bargor.thesaurus.ui.reports.ReportsScreen
import pl.bargor.thesaurus.ui.reports.ReportsUiState
import pl.bargor.thesaurus.ui.summary.SummaryScreen
import pl.bargor.thesaurus.ui.summary.SummaryUiState
import pl.bargor.thesaurus.ui.taxonomy.CategoryWithSubcategories
import pl.bargor.thesaurus.ui.taxonomy.TaxonomyScreen
import pl.bargor.thesaurus.ui.taxonomy.TaxonomyUiState
import pl.bargor.thesaurus.ui.taxonomy.TaxonomyError

/** Exercise actual screen layouts, including pending writes while the device remains offline. */
class OfflineScreenCoverageTest {
    @get:Rule val rule = createComposeRule()
    private val category = Category("food", "house", "Jedzenie", authorId = "user", updatedById = "user")

    @Test fun entriesKeepHeaderPositionAndPendingAndErrors() = verifyScreen(
        "open-taxonomy-settings", R.string.entries_sync_pending, R.string.entries_load_error,
    ) { sync, error ->
        val entry = LedgerEntry("entry", "house", -1200, LocalDate.of(2026, 9, 15), "Zakupy", "food", authorId = "user", updatedById = "user")
        EntryListScreen(
            state = EntryListUiState(isLoading = false, entries = listOf(EntryListItem(entry, "Jedzenie", null, "Karol")),
                syncState = sync, error = if (error) EntryListError.LoadFailed else null),
            onChangeSort = {}, onLoadNextPage = {}, onRetry = {}, onOpenSettings = {}, onAddEntry = {},
        )
    }

    @Test fun summaryKeepsPeriodPositionUnderGlobalOfflineIndicator() {
        var online by mutableStateOf(true)
        rule.setContent { ThesaurusTheme { OfflineStatusHost(online, "summary-period") {
            SummaryScreen(SummaryUiState(YearMonth.of(2026, 9), Year.of(2026)), {}, {}, {})
        } } }
        val marker = rule.onNodeWithTag("summary-period").assertIsDisplayed().fetchSemanticsNode().boundsInRoot
        rule.runOnIdle { online = false }
        val indicator = rule.onNodeWithTag("offline-indicator").assertIsDisplayed().fetchSemanticsNode().boundsInRoot
        assertTrue(indicator.bottom <= marker.top)
        assertEquals(marker, rule.onNodeWithTag("summary-period").fetchSemanticsNode().boundsInRoot)
        listOf("summary-pending", "summary-error", "summary-net", "summary-empty").forEach { rule.onNodeWithTag(it).assertDoesNotExist() }
        rule.runOnIdle { online = true }
        rule.onNodeWithTag("offline-indicator").assertDoesNotExist()
        assertEquals(marker, rule.onNodeWithTag("summary-period").fetchSemanticsNode().boundsInRoot)
    }

    @Test fun reportsKeepPeriodPositionAndPendingAndErrors() = verifyScreen(
        "reports-mode-month", R.string.reports_sync_pending, R.string.reports_load_error,
    ) { sync, error ->
        ReportsScreen(ReportsUiState(LocalDate.of(2026, 9, 15), YearMonth.of(2026, 9), Year.of(2026), isLoading = false,
            syncState = sync, hasError = error), {}, {}, {}, {}, {}, {}, {}, {}, {})
    }

    @Test fun taxonomyKeepsActionsPositionAndPendingAndValidationError() = verifyScreen(
        "taxonomy-add-category", R.string.taxonomy_sync_pending, R.string.taxonomy_name_error,
    ) { sync, error ->
        TaxonomyScreen(TaxonomyUiState(isLoading = false, categories = listOf(CategoryWithSubcategories(category, emptyList())),
            syncState = sync, error = if (error) TaxonomyError.InvalidName else null), {})
    }

    @Test fun entryFormKeepsBackPositionAndPendingAndSaveError() = verifyScreen(
        "entry-back", R.string.entry_sync_pending, R.string.entry_save_error,
    ) { sync, error ->
        EntryFormScreen(EntryFormUiState(isLoading = false, categories = listOf(EntryCategory(category, emptyList())),
            syncState = sync, error = if (error) EntryFormError.SaveFailed else null), {}, {}, {}, {}, {}, {}, {}, {}, {})
    }

    @Test fun familyKeepsBackPositionAndPendingAndLoadError() = verifyScreen(
        "family-back", R.string.family_sync_pending, R.string.family_load_error,
    ) { sync, error ->
        FamilyScreen(FamilyUiState(loading = false, members = listOf(Member("user", "karol@example.test", "Karol", MemberRole.OWNER)),
            isOwner = true, syncState = sync, error = if (error) FamilyError.LOAD else null), {}, {}, {}, {}, {})
    }

    private fun verifyScreen(
        markerTag: String,
        @StringRes pendingMessage: Int,
        @StringRes errorMessage: Int,
        content: @Composable (SyncState, Boolean) -> Unit,
    ) {
        var online by mutableStateOf(true)
        var sync by mutableStateOf(SyncState.SYNCED)
        var error by mutableStateOf(false)
        rule.setContent { ThesaurusTheme { OfflineStatusHost(online, markerTag) { content(sync, error) } } }
        val marker = rule.onNodeWithTag(markerTag).assertIsDisplayed().fetchSemanticsNode().boundsInRoot
        rule.runOnIdle { online = false; sync = SyncState.OFFLINE }
        val indicator = rule.onNodeWithTag("offline-indicator").assertIsDisplayed().fetchSemanticsNode().boundsInRoot
        assertTrue(indicator.bottom <= marker.top)
        assertEquals(marker, rule.onNodeWithTag(markerTag).fetchSemanticsNode().boundsInRoot)
        rule.onAllNodes(hasText("Tryb offline", substring = true)).assertCountEquals(0)
        rule.runOnIdle { online = true; sync = SyncState.SYNCED }
        rule.onNodeWithTag("offline-indicator").assertDoesNotExist()
        assertEquals(marker, rule.onNodeWithTag(markerTag).fetchSemanticsNode().boundsInRoot)
        rule.runOnIdle { online = false; sync = SyncState.PENDING }
        rule.onNodeWithTag("offline-indicator").assertIsDisplayed()
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        rule.onNodeWithText(context.getString(pendingMessage)).assertExists()
        rule.runOnIdle { error = true }
        rule.onNodeWithText(context.getString(errorMessage)).assertExists()
        rule.onNodeWithTag("offline-indicator").assertIsDisplayed()
    }
}
