package pl.bargor.thesaurus

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.requiredSize
import androidx.compose.material3.Button
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.Density
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import java.time.LocalDate
import java.time.Year
import java.time.YearMonth
import org.junit.Rule
import org.junit.Test
import pl.bargor.thesaurus.ui.settings.SettingsAction
import pl.bargor.thesaurus.ui.reports.ReportsScreen
import pl.bargor.thesaurus.ui.reports.ReportsUiState

class SettingsNavigationTest {
    @get:Rule val compose = createComposeRule()

    @Test fun everyTabReturnsToItsOriginAndRetainsItsUnsavedState() {
        setFixture()
        Destination.entries.forEach { destination ->
            compose.onNodeWithTag(destination.navigationTestTag).performClick()
            val draft = "draft-${destination.route}"
            compose.onNodeWithTag("draft-${destination.route}").performTextReplacement(draft)
            openSettings()
            compose.onNodeWithTag("settings-back").performClick()
            compose.onNodeWithTag(destination.navigationTestTag).assertIsSelected()
            compose.onNodeWithTag("draft-${destination.route}").assertTextEquals(draft)
        }
    }

    @Test fun newAndEditedEntryDraftsSurviveSettingsChildrenAndReturnToTheSameForm() {
        setFixture()
        for (form in listOf("new", "entry-42")) {
            compose.onNodeWithTag(Destination.Entries.navigationTestTag).performClick()
            compose.onNodeWithTag(if (form == "new") "fixture-add" else "fixture-edit").performClick()
            compose.onNodeWithTag("draft-$form").performTextReplacement("Niezapisane $form")
            openSettings()
            for (child in listOf("categories", "family", "opening-balance")) {
                compose.onNodeWithTag("settings-$child").performScrollTo().performClick()
                compose.onNodeWithTag("child-$child").assertIsDisplayed()
                compose.onNodeWithTag("child-draft-$child").performTextReplacement("Niezapisane ustawienia $child")
                // The hub must return to the exact child that opened it, including its draft.
                compose.onNodeWithTag("global-open-settings").assertIsSelected().performClick()
                compose.onNodeWithTag("settings-categories").assertIsDisplayed()
                compose.onNodeWithTag("global-open-settings").performClick()
                compose.onNodeWithTag("settings-back").performClick()
                compose.onNodeWithTag("child-$child").assertIsDisplayed()
                compose.onNodeWithTag("child-draft-$child").assertTextEquals("Niezapisane ustawienia $child")
                compose.onNodeWithTag("child-back-$child").performClick()
                compose.onNodeWithTag("settings-categories").assertIsDisplayed()
            }
            compose.onNodeWithTag("settings-back").performClick()
            compose.onNodeWithTag("draft-$form").assertTextEquals("Niezapisane $form")
        }
    }

    @Test fun switchingTabsFromSettingsChildDoesNotRestoreSettingsOnTheSourceTab() {
        setFixture()
        Destination.entries.forEach { origin ->
            compose.onNodeWithTag(origin.navigationTestTag).performClick()
            openSettings()
            compose.onNodeWithTag("settings-family").performClick()
            compose.onNodeWithTag(Destination.entries.first { it != origin }.navigationTestTag).performClick()
            compose.onNodeWithTag(origin.navigationTestTag).performClick().assertIsSelected()
            compose.onNodeWithTag("draft-${origin.route}").assertIsDisplayed()
            compose.onNodeWithTag("settings-back").assertDoesNotExist()
            compose.onNodeWithTag("child-family").assertDoesNotExist()
            compose.onNodeWithTag("global-open-settings").assertIsNotSelected()
        }
    }

    private fun openSettings() {
        compose.onNodeWithTag("global-open-settings").assertIsDisplayed()
            .assertWidthIsAtLeast(48.dp).assertHeightIsAtLeast(48.dp).performClick()
        compose.onNodeWithTag("global-open-settings").assertIsSelected()
        compose.onNodeWithTag("settings-categories").assertIsDisplayed()
        compose.onNodeWithTag("settings-family").assertIsDisplayed()
        compose.onNodeWithTag("settings-opening-balance").assertIsDisplayed()
    }

    @Test fun reportsFilterRemainsImmediatelyLeftOfAccessibleGlobalGear() {
        var filters = 0
        compose.setContent { ThesaurusTheme {
            HouseholdApp(
                entriesContent = { _, _, _, _ -> Text("Wpisy") },
                browseContent = {}, summaryContent = {},
                reportsContent = {
                    ReportsScreen(ReportsUiState(LocalDate.of(2026, 9, 30), YearMonth.of(2026, 9), Year.of(2026), isLoading = false),
                        {}, {}, {}, {}, {}, {}, {}, {}, {}, onOpenFilters = { filters++ })
                },
            )
        } }
        compose.onNodeWithTag(Destination.Reports.navigationTestTag).performClick()
        val filter = compose.onNodeWithTag("reports-open-filters").assertIsDisplayed()
            .assertWidthIsAtLeast(48.dp).assertHeightIsAtLeast(48.dp).fetchSemanticsNode().boundsInRoot
        val gear = compose.onNodeWithTag("global-open-settings").assertIsDisplayed()
            .assertWidthIsAtLeast(48.dp).assertHeightIsAtLeast(48.dp).fetchSemanticsNode().boundsInRoot
        assertEquals(filter.right, gear.left, 1f)
        assertEquals(filter.center.y, gear.center.y, 1f)
        compose.onNodeWithTag("reports-open-filters").performClick()
        compose.runOnIdle { assertEquals(1, filters) }
        openSettings()
        compose.onNodeWithTag("settings-back").performClick()
        compose.onNodeWithTag(Destination.Reports.navigationTestTag).assertIsSelected()
        compose.onNodeWithTag("reports-open-filters").assertIsDisplayed()
    }

    @Test fun compactLargeFontReportsKeepsSeparateFilterAndGearTargetsInsideTheViewport() {
        var filters = 0
        var settings = 0
        compose.setContent {
            val deviceDensity = LocalDensity.current.density
            Box(Modifier.requiredSize((320f / deviceDensity).dp, (640f / deviceDensity).dp)
                .testTag("compact-reports-viewport")) {
                CompositionLocalProvider(LocalDensity provides Density(1.1f, 1.8f)) {
                    ThesaurusTheme {
                        ReportsScreen(ReportsUiState(LocalDate.of(2026, 9, 30), YearMonth.of(2026, 9), Year.of(2026), isLoading = false),
                            {}, {}, {}, {}, {}, {}, {}, {}, {},
                            onOpenFilters = { filters++ }, onOpenSettings = { settings++ })
                    }
                }
            }
        }
        val viewport = compose.onNodeWithTag("compact-reports-viewport").fetchSemanticsNode().boundsInRoot
        assertEquals(320f, viewport.width, 1f); assertEquals(640f, viewport.height, 1f)
        val filter = compose.onNodeWithTag("reports-open-filters").assertIsDisplayed().assertHasClickAction()
            .assertContentDescriptionEquals("Otwórz filtry raportów")
            .assertWidthIsAtLeast(48.dp).assertHeightIsAtLeast(48.dp).fetchSemanticsNode().boundsInRoot
        val gear = compose.onNodeWithTag("global-open-settings").assertIsDisplayed().assertHasClickAction()
            .assertContentDescriptionEquals("Otwórz ustawienia")
            .assertWidthIsAtLeast(48.dp).assertHeightIsAtLeast(48.dp).fetchSemanticsNode().boundsInRoot
        for (target in listOf(filter, gear)) {
            assertTrue("Compact header target must fit fully: $target in $viewport",
                target.left >= viewport.left && target.right <= viewport.right + 1f &&
                    target.top >= viewport.top && target.bottom <= viewport.bottom + 1f)
            assertTrue("Target must retain 48 dp at the fixture density", target.width >= 48f * 1.1f - 1f && target.height >= 48f * 1.1f - 1f)
        }
        assertEquals(filter.right, gear.left, 1f)
        assertEquals(filter.center.y, gear.center.y, 1f)
        compose.onNodeWithTag("reports-open-filters").performClick()
        compose.onNodeWithTag("global-open-settings").performClick()
        compose.runOnIdle { assertEquals(1, filters); assertEquals(1, settings) }
    }

    private fun setFixture() = compose.setContent {
        ThesaurusTheme {
            HouseholdApp(
                entriesContent = { _, add, _, edit ->
                    Column {
                        Draft("entries")
                        Button(onClick = add, modifier = Modifier.testTag("fixture-add")) { Text("Dodaj") }
                        Button(onClick = { edit("entry-42") }, modifier = Modifier.testTag("fixture-edit")) { Text("Edytuj") }
                    }
                },
                browseContent = { Draft("browse") },
                summaryContent = { Draft("summary") },
                reportsContent = { Draft("reports") },
                entryFormContent = { id, _ -> Draft(id ?: "new") },
                taxonomyContent = { back -> Child("categories", back) },
                familyContent = { back -> Child("family", back) },
                openingBalanceContent = { back -> Child("opening-balance", back) },
            )
        }
    }

    @Composable private fun Draft(name: String) {
        var text by rememberSaveable { mutableStateOf("") }
        Column {
            SettingsAction()
            OutlinedTextField(value = text, onValueChange = { text = it }, modifier = Modifier.testTag("draft-$name"))
        }
    }

    @Composable private fun Child(name: String, back: () -> Unit) {
        var draft by rememberSaveable { mutableStateOf("") }
        Column {
            SettingsAction()
            Text(name, Modifier.testTag("child-$name"))
            OutlinedTextField(value = draft, onValueChange = { draft = it }, modifier = Modifier.testTag("child-draft-$name"))
            Button(onClick = back, modifier = Modifier.testTag("child-back-$name")) { Text("Wstecz") }
        }
    }
}
