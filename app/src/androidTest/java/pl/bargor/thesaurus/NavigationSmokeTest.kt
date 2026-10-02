package pl.bargor.thesaurus

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsSelected
import androidx.compose.ui.test.assertIsNotSelected
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollToNode
import androidx.compose.ui.test.hasTestTag
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import org.junit.Assert.assertTrue
import androidx.compose.material3.Text
import androidx.compose.material3.Button
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.Modifier
import androidx.compose.foundation.layout.systemBars
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.navigation.NavHostController
import androidx.navigation.compose.rememberNavController
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import pl.bargor.thesaurus.ui.taxonomy.TaxonomyScreen
import pl.bargor.thesaurus.ui.taxonomy.TaxonomyUiState
import java.time.YearMonth
import java.time.Year
import java.time.LocalDate
import org.junit.Rule
import org.junit.Test
import pl.bargor.thesaurus.ui.summary.SummaryScreen
import pl.bargor.thesaurus.ui.summary.SummaryUiState
import pl.bargor.thesaurus.ui.entries.EntryListScreen
import pl.bargor.thesaurus.ui.entries.EntryListUiState
import pl.bargor.thesaurus.ui.entries.EntryListItem
import pl.bargor.thesaurus.data.model.LedgerEntry
import pl.bargor.thesaurus.ui.reports.ReportsScreen
import pl.bargor.thesaurus.ui.reports.ReportsUiState
import pl.bargor.thesaurus.ui.entry.EntryFormError
import pl.bargor.thesaurus.ui.entry.EntryFormUiState

class NavigationSmokeTest {
    @get:Rule
    val composeTestRule = createComposeRule()

    @Test
    fun householdScaffoldKeepsNavigationAboveSystemInsetsAndContentAboveTheBar() {
        var bottomInset = 0
        composeTestRule.setContent {
            bottomInset = WindowInsets.systemBars.getBottom(LocalDensity.current)
            ThesaurusTheme {
                Box(Modifier.fillMaxSize().testTag("household-root")) {
                    HouseholdApp(
                        entriesContent = { _, _, _, _ ->
                            Box(Modifier.fillMaxSize().testTag("household-body"))
                        },
                        summaryContent = {},
                        reportsContent = {},
                    )
                }
            }
        }
        val root = composeTestRule.onNodeWithTag("household-root").fetchSemanticsNode().boundsInRoot
        val body = composeTestRule.onNodeWithTag("household-body").fetchSemanticsNode().boundsInRoot
        val bar = composeTestRule.onNodeWithTag("bottom-navigation").fetchSemanticsNode().boundsInRoot
        assertEquals(root.bottom, bar.bottom, 1f)
        // Developer tools, when present in DEV builds, also belong below screen content.
        org.junit.Assert.assertTrue(body.bottom <= bar.top + 1f)
        Destination.entries.forEach { destination ->
            val item = composeTestRule.onNodeWithTag(destination.navigationTestTag).fetchSemanticsNode().boundsInRoot
            assertEquals(bottomInset.toFloat(), bar.bottom - item.bottom, 1f)
            org.junit.Assert.assertTrue(item.top >= bar.top && item.bottom <= root.bottom)
        }
    }

    @Test
    fun taxonomyBottomEntriesReturnsToEntriesAndDoesNotRestoreSettings() = verifyTaxonomyExit("entries")

    @Test
    fun taxonomyBackReturnsToEntriesAndDoesNotRestoreSettings() = verifyTaxonomyExit("back")

    @Test
    fun taxonomySwitchingTabsDoesNotSaveSettingsInEntriesStack() = verifyTaxonomyExit("summary")

    private fun verifyTaxonomyExit(exit: String) {
        lateinit var controller: NavHostController
        composeTestRule.setContent {
            controller = rememberNavController()
            ThesaurusTheme {
                HouseholdApp(
                    navController = controller,
                    entriesContent = { onOpenSettings, _, _, _ ->
                        Button(modifier = Modifier.testTag("open-taxonomy"), onClick = onOpenSettings) { Text("Kategorie") }
                    },
                    summaryContent = { Text("Podsumowanie testowe") },
                    reportsContent = { Text("Raporty testowe") },
                    taxonomyContent = { onBack ->
                        TaxonomyScreen(TaxonomyUiState(isLoading = false), onMutation = {}, onBack = onBack)
                    },
                )
            }
        }
        composeTestRule.onNodeWithTag("open-taxonomy").performClick()
        composeTestRule.onNodeWithTag("taxonomy-back").assertIsDisplayed()
        composeTestRule.onNodeWithTag(Destination.Entries.navigationTestTag).assertIsNotSelected()
        when (exit) {
            "back" -> composeTestRule.onNodeWithTag("taxonomy-back").performClick()
            "summary" -> composeTestRule.onNodeWithTag(Destination.Summary.navigationTestTag).performClick()
            else -> composeTestRule.onNodeWithTag(Destination.Entries.navigationTestTag).performClick()
        }
        if (exit == "summary") {
            composeTestRule.onNodeWithText("Podsumowanie testowe").assertIsDisplayed()
            composeTestRule.onNodeWithTag(Destination.Entries.navigationTestTag).performClick()
        }
        composeTestRule.onNodeWithTag("open-taxonomy").assertIsDisplayed()
        composeTestRule.onNodeWithTag(Destination.Entries.navigationTestTag).assertIsSelected()
        composeTestRule.onNodeWithTag("taxonomy-back").assertDoesNotExist()
        composeTestRule.onNodeWithTag(Destination.Reports.navigationTestTag).performClick()
        composeTestRule.onNodeWithTag(Destination.Entries.navigationTestTag).performClick()
        composeTestRule.onNodeWithTag("open-taxonomy").assertIsDisplayed()
        composeTestRule.runOnIdle {
            assertEquals(Destination.Entries.route, controller.currentDestination?.route)
            assertNull(controller.previousBackStackEntry)
        }
    }

    @Test
    fun persistentAddEntryFitsAboveNavigationAndSystemInsetsAtLargeFontAndDensity() {
        composeTestRule.setContent {
            val density = LocalDensity.current
            CompositionLocalProvider(LocalDensity provides Density(density.density * 1.1f, 1.8f)) {
                ThesaurusTheme {
                    // Reserve an additional system inset independently of the real device's bars.
                    Box(Modifier.fillMaxSize().testTag("inset-root").windowInsetsPadding(WindowInsets(bottom = 32.dp))) {
                        HouseholdApp(
                            entriesContent = { onOpenSettings, onAddEntry, onOpenFamily, _ ->
                                EntryListScreen(
                                    state = EntryListUiState(isLoading = false, entries = (1..20).map { index ->
                                        EntryListItem(
                                            LedgerEntry(
                                                id = "inset-$index", householdId = "home", amountGrosze = -100,
                                                date = LocalDate.of(2026, 9, 16), categoryId = "food", authorId = "creator", updatedById = "creator",
                                            ), "Jedzenie", null, "creator@example.test",
                                        )
                                    }),
                                    onChangeSort = {}, onLoadNextPage = {}, onRetry = {},
                                    onOpenSettings = onOpenSettings, onAddEntry = onAddEntry, onOpenFamily = onOpenFamily,
                                )
                            },
                            summaryContent = {}, reportsContent = {},
                            entryFormContent = { _, _ -> Text("Nowy wpis") },
                        )
                    }
                }
            }
        }
        val root = composeTestRule.onNodeWithTag("inset-root").fetchSemanticsNode().boundsInRoot
        val add = composeTestRule.onNodeWithTag("add-entry").assertIsDisplayed().fetchSemanticsNode().boundsInRoot
        val navigation = composeTestRule.onNodeWithTag(Destination.Entries.navigationTestTag).assertIsDisplayed().fetchSemanticsNode().boundsInRoot
        assertTrue("Add action must remain above bottom navigation", add.bottom <= navigation.top)
        assertTrue("Navigation must leave the reserved bottom system inset", navigation.bottom < root.bottom)
        assertTrue("Add action fits horizontally", add.left >= root.left && add.right <= root.right)
        composeTestRule.onNodeWithTag("entries-list").performScrollToNode(hasTestTag("entry-inset-20"))
        composeTestRule.onNodeWithTag("add-entry").assertIsDisplayed().performClick()
        composeTestRule.onNodeWithText("Nowy wpis").assertIsDisplayed()
    }

    @Test
    fun bottomNavigationShowsEveryDestination() {
        composeTestRule.setContent {
            ThesaurusTheme {
                HouseholdApp(
                    entriesContent = { onOpenSettings, onAddEntry, onOpenFamily, _ ->
                        EntryListScreen(
                            state = EntryListUiState(isLoading = false),
                            onChangeSort = {}, onLoadNextPage = {}, onRetry = {},
                            onOpenSettings = onOpenSettings, onAddEntry = onAddEntry, onOpenFamily = onOpenFamily,
                        )
                    },
                    summaryContent = {
                        SummaryScreen(
                            state = SummaryUiState(month = YearMonth.of(2026, 9), year = Year.of(2026)),
                            onSelectPeriodMode = {}, onPreviousPeriod = {}, onNextPeriod = {},
                        )
                    },
                    reportsContent = {
                        ReportsScreen(
                            state = ReportsUiState(
                                today = LocalDate.of(2026, 9, 30), month = YearMonth.of(2026, 9),
                                year = Year.of(2026), isLoading = false,
                            ),
                            onSelectPeriodMode = {}, onPreviousPeriod = {}, onNextPeriod = {},
                            onSelectType = {}, onCustomFromChange = {}, onCustomToChange = {},
                            onApplyCustomPeriod = {}, onOpenEntry = {}, onRetry = {},
                        )
                    },
                )
            }
        }
        composeTestRule.onNodeWithText("Nie ma jeszcze żadnych wpisów.").assertIsDisplayed()
        composeTestRule.onNodeWithTag(Destination.Entries.navigationTestTag).assertIsSelected()
        composeTestRule.onNodeWithTag(Destination.Summary.navigationTestTag).assertIsNotSelected()
        composeTestRule.onNodeWithTag(Destination.Reports.navigationTestTag).assertIsNotSelected()
        composeTestRule.onAllNodesWithText("Wpisy", useUnmergedTree = true).assertCountEquals(1)
        composeTestRule.onNodeWithTag(Destination.Summary.navigationTestTag).performClick()
        composeTestRule.onNodeWithTag("summary-period").assertIsDisplayed()
        composeTestRule.onNodeWithText("Brak wpisów w wybranym miesiącu.").assertDoesNotExist()
        composeTestRule.onNodeWithTag(Destination.Summary.navigationTestTag).assertIsSelected()
        composeTestRule.onNodeWithTag(Destination.Entries.navigationTestTag).assertIsNotSelected()
        composeTestRule.onNodeWithTag(Destination.Reports.navigationTestTag).assertIsNotSelected()
        composeTestRule.onAllNodesWithText("Podsumowanie", useUnmergedTree = true).assertCountEquals(1)
        composeTestRule.onNodeWithTag(Destination.Reports.navigationTestTag).performClick()
        composeTestRule.onNodeWithText("Brak wpisów w wybranym okresie.").assertIsDisplayed()
        composeTestRule.onNodeWithTag(Destination.Reports.navigationTestTag).assertIsSelected()
        composeTestRule.onNodeWithTag(Destination.Entries.navigationTestTag).assertIsNotSelected()
        composeTestRule.onNodeWithTag(Destination.Summary.navigationTestTag).assertIsNotSelected()
        composeTestRule.onAllNodesWithTag("navigation-reports-label", useUnmergedTree = true).assertCountEquals(1)
        composeTestRule.onNodeWithTag("reports-open-filters").assertIsDisplayed()
    }

    @Test
    fun reportEntryDrillDownNavigatesToTheSelectedEntry() {
        composeTestRule.setContent {
            ThesaurusTheme {
                HouseholdApp(
                    entriesContent = { _, _, _, _ -> Text(stringResource(R.string.empty_entries)) },
                    summaryContent = { Text(stringResource(R.string.empty_summary)) },
                    reportsContent = { onOpenEntry ->
                        Button(
                            modifier = Modifier.testTag("open-report-entry"),
                            onClick = { onOpenEntry("entry-42") },
                        ) { Text("Otwórz wpis") }
                    },
                    entryFormContent = { entryId, _ -> Text("Wybrany wpis: $entryId") },
                )
            }
        }

        composeTestRule.onNodeWithTag(Destination.Reports.navigationTestTag).performClick()
        composeTestRule.onNodeWithTag("open-report-entry").performClick()
        composeTestRule.onNodeWithText("Wybrany wpis: entry-42").assertIsDisplayed()
    }

    @Test
    fun fourthBrowseTabOpensEntriesAndReturningRestoresItsScreen() {
        composeTestRule.setContent {
            ThesaurusTheme {
                HouseholdApp(
                    entriesContent = { _, _, _, _ -> Text("Wpisy testowe") },
                    summaryContent = { Text("Podsumowanie testowe") },
                    reportsContent = { Text("Raporty testowe") },
                    browseContent = { onOpenEntry ->
                        Button(modifier = Modifier.testTag("browse-open-entry"), onClick = { onOpenEntry("browse-42") }) {
                            Text("Przegląd testowy")
                        }
                    },
                    entryFormContent = { entryId, _ -> Text("Wybrany wpis: $entryId") },
                )
            }
        }
        composeTestRule.onNodeWithTag(Destination.Browse.navigationTestTag).assertIsNotSelected().performClick().assertIsSelected()
        composeTestRule.onNodeWithText("Przegląd testowy").assertIsDisplayed()
        composeTestRule.onNodeWithTag("browse-open-entry").performClick()
        composeTestRule.onNodeWithText("Wybrany wpis: browse-42").assertIsDisplayed()
        composeTestRule.onNodeWithTag(Destination.Browse.navigationTestTag).performClick().assertIsSelected()
        composeTestRule.onNodeWithText("Przegląd testowy").assertIsDisplayed()
        composeTestRule.onNodeWithTag(Destination.Reports.navigationTestTag).performClick()
        composeTestRule.onNodeWithText("Raporty testowe").assertIsDisplayed()
        composeTestRule.onNodeWithTag(Destination.Browse.navigationTestTag).performClick()
        composeTestRule.onNodeWithText("Przegląd testowy").assertIsDisplayed()
        composeTestRule.onNodeWithTag("browse-open-entry").performClick()
        composeTestRule.onNodeWithText("Wybrany wpis: browse-42").assertIsDisplayed()
        composeTestRule.onNodeWithTag(Destination.Summary.navigationTestTag).performClick()
        composeTestRule.onNodeWithText("Podsumowanie testowe").assertIsDisplayed()
        composeTestRule.onNodeWithTag(Destination.Browse.navigationTestTag).performClick().assertIsSelected()
        composeTestRule.onNodeWithText("Przegląd testowy").assertIsDisplayed()
        composeTestRule.onNodeWithText("Wybrany wpis: browse-42").assertDoesNotExist()
    }

    @Test
    fun successfulCreationReturnsToEntriesOnceEvenAfterRecomposition() = verifyCreationReturn(queuedOffline = false)

    @Test
    fun pendingOfflineCreationReturnsToEntriesOnce() = verifyCreationReturn(queuedOffline = true)

    private fun verifyCreationReturn(queuedOffline: Boolean) {
        val formState = mutableStateOf(EntryFormUiState(isLoading = false))
        val recomposition = mutableStateOf(0)
        var navigationCount = 0
        composeTestRule.setContent {
            recomposition.value
            ThesaurusTheme {
                HouseholdApp(
                    entriesContent = { _, onAddEntry, _, _ ->
                        Button(modifier = Modifier.testTag("add-entry"), onClick = onAddEntry) { Text("Dodaj wpis") }
                    },
                    summaryContent = {},
                    reportsContent = {},
                    entryFormContent = { entryId, onCreated ->
                        EntryFormCompletion(formState.value) {
                            navigationCount++
                            onCreated()
                        }
                        Text("Formularz: $entryId")
                    },
                )
            }
        }

        composeTestRule.onNodeWithTag("add-entry").performClick()
        composeTestRule.onNodeWithText("Formularz: null").assertIsDisplayed()
        composeTestRule.runOnIdle {
            formState.value = EntryFormUiState(isLoading = false, saved = true, queuedOffline = queuedOffline)
        }
        composeTestRule.onNodeWithTag(Destination.Entries.navigationTestTag).assertIsSelected()
        composeTestRule.onNodeWithText("Formularz: null").assertDoesNotExist()
        composeTestRule.runOnIdle { recomposition.value++ }
        composeTestRule.waitForIdle()
        composeTestRule.runOnIdle { org.junit.Assert.assertEquals(1, navigationCount) }
    }

    @Test
    fun failedCreationStaysOnForm() {
        val formState = mutableStateOf(EntryFormUiState(isLoading = false))
        var navigationCount = 0
        composeTestRule.setContent {
            ThesaurusTheme {
                HouseholdApp(
                    entriesContent = { _, onAddEntry, _, _ ->
                        Button(modifier = Modifier.testTag("add-entry"), onClick = onAddEntry) { Text("Dodaj wpis") }
                    },
                    summaryContent = {},
                    reportsContent = {},
                    entryFormContent = { _, onCreated ->
                        EntryFormCompletion(formState.value) {
                            navigationCount++
                            onCreated()
                        }
                        Text("Formularz")
                    },
                )
            }
        }

        composeTestRule.onNodeWithTag("add-entry").performClick()
        composeTestRule.runOnIdle {
            formState.value = EntryFormUiState(isLoading = false, error = EntryFormError.SaveFailed)
        }
        composeTestRule.onNodeWithText("Formularz").assertIsDisplayed()
        composeTestRule.runOnIdle { org.junit.Assert.assertEquals(0, navigationCount) }
    }
}
