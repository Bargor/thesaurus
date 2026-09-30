package pl.bargor.thesaurus.ui

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.material3.Button
import androidx.compose.material3.Text
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.assert
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertHeightIsAtLeast
import androidx.compose.ui.test.assertWidthIsAtLeast
import androidx.compose.ui.test.click
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTouchInput
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import pl.bargor.thesaurus.HouseholdApp
import pl.bargor.thesaurus.ThesaurusTheme
import androidx.compose.ui.unit.dp
import androidx.navigation.NavHostController
import androidx.navigation.compose.rememberNavController

class OfflineStatusHostTest {
    @get:Rule val rule = createComposeRule()

    @Test fun transitionsKeepContentAndIndicatorPositionStableWithoutCoveringControls() {
        var online by mutableStateOf(true)
        rule.setContent {
            ThesaurusTheme {
                OfflineStatusHost(online, "entries") {
                    Box(Modifier.fillMaxSize().testTag("content"))
                }
            }
        }
        val initial = rule.onNodeWithTag("content").fetchSemanticsNode().boundsInRoot
        rule.onNodeWithTag("offline-indicator").assertDoesNotExist()
        rule.runOnIdle { online = false }
        val icon = rule.onNodeWithTag("offline-indicator").assertIsDisplayed().fetchSemanticsNode().boundsInRoot
        val root = rule.onNodeWithTag("offline-status-host").fetchSemanticsNode().boundsInRoot
        assertTrue(icon.right <= root.right && icon.left > root.center.x)
        assertTrue(icon.bottom <= initial.top)
        assertEquals(initial, rule.onNodeWithTag("content").fetchSemanticsNode().boundsInRoot)
        rule.onNodeWithTag("offline-indicator").performClick()
        rule.onNodeWithTag("offline-tooltip").assertIsDisplayed()
        rule.runOnIdle { online = true }
        rule.onNodeWithTag("offline-indicator").assertDoesNotExist()
        rule.onNodeWithTag("offline-tooltip").assertDoesNotExist()
        assertEquals(initial, rule.onNodeWithTag("content").fetchSemanticsNode().boundsInRoot)
        rule.runOnIdle { online = false }
        assertEquals(icon, rule.onNodeWithTag("offline-indicator").fetchSemanticsNode().boundsInRoot)
        rule.onNodeWithTag("offline-tooltip").assertDoesNotExist()
    }

    @Test fun tooltipHasPolishDescriptionAndLiveAnnouncementAndExpiresAfterFiveSeconds() {
        rule.mainClock.autoAdvance = false
        rule.setContent { ThesaurusTheme { OfflineStatusHost(false, "entries") {} } }
        rule.onNodeWithContentDescription("Brak połączenia z internetem. Pokaż informacje o trybie offline").assertIsDisplayed()
        rule.onNodeWithTag("offline-indicator").assertHeightIsAtLeast(48.dp).assertWidthIsAtLeast(48.dp)
        rule.onNodeWithTag("offline-indicator").performClick()
        rule.mainClock.advanceTimeBy(32)
        rule.onNodeWithTag("offline-tooltip").assert(SemanticsMatcher.expectValue(SemanticsProperties.LiveRegion, LiveRegionMode.Polite))
        rule.onNodeWithText("Brak połączenia z internetem. Możesz korzystać z zapisanych danych. Zmiany zsynchronizują się po odzyskaniu połączenia.").assertIsDisplayed()
        rule.mainClock.advanceTimeBy(4_800)
        rule.onNodeWithTag("offline-tooltip").assertIsDisplayed()
        rule.mainClock.advanceTimeBy(300)
        rule.onNodeWithTag("offline-tooltip").assertDoesNotExist()
    }

    @Test fun nextTapDismissesAndStillActivatesDestinationControl() {
        var clicks = 0
        rule.setContent {
            ThesaurusTheme {
                OfflineStatusHost(false, "entries") {
                    Button(onClick = { clicks++ }, modifier = Modifier.testTag("destination")) { Text("Cel") }
                }
            }
        }
        rule.onNodeWithTag("offline-indicator").performTouchInput { click() }
        rule.onNodeWithTag("offline-tooltip").assertIsDisplayed()
        rule.onNodeWithTag("destination").performTouchInput { click() }
        rule.onNodeWithTag("offline-tooltip").assertDoesNotExist()
        assertEquals(1, clicks)
    }

    @Test fun tappingInsideTooltipOrEmptySpaceDismissesIt() {
        rule.setContent { ThesaurusTheme { OfflineStatusHost(false, "entries") { Box(Modifier.fillMaxSize().testTag("content")) } } }
        rule.onNodeWithTag("offline-indicator").performTouchInput { click() }
        rule.onNodeWithTag("offline-tooltip").performTouchInput { click() }
        rule.onNodeWithTag("offline-tooltip").assertDoesNotExist()
        rule.onNodeWithTag("offline-indicator").performTouchInput { click() }
        rule.onNodeWithTag("content").performTouchInput { click(center) }
        rule.onNodeWithTag("offline-tooltip").assertDoesNotExist()
    }

    @Test fun tappingTooltipAlsoActivatesButtonDirectlyUnderIt() {
        var clicks = 0
        rule.setContent {
            ThesaurusTheme {
                OfflineStatusHost(false, "entries") {
                    Button(
                        onClick = { clicks++ },
                        modifier = Modifier.fillMaxWidth().height(160.dp).testTag("under-tooltip"),
                    ) { Text("Przycisk pod informacją") }
                }
            }
        }
        rule.onNodeWithTag("offline-indicator").performTouchInput { click() }
        val tooltip = rule.onNodeWithTag("offline-tooltip").fetchSemanticsNode().boundsInRoot
        val button = rule.onNodeWithTag("under-tooltip").fetchSemanticsNode().boundsInRoot
        assertTrue(button.contains(tooltip.center))
        rule.onNodeWithTag("offline-tooltip").performTouchInput { click() }
        rule.onNodeWithTag("offline-tooltip").assertDoesNotExist()
        assertEquals(1, clicks)
    }

    @Test fun repeatedIconTapsToggleSingleTooltipAndCancelOldTimer() {
        rule.mainClock.autoAdvance = false
        rule.setContent { ThesaurusTheme { OfflineStatusHost(false, "entries") {} } }
        rule.onNodeWithTag("offline-indicator").performTouchInput { click() }
        rule.mainClock.advanceTimeBy(32)
        rule.onAllNodesWithTag("offline-tooltip").assertCountEquals(1)
        rule.mainClock.advanceTimeBy(3_000)
        rule.onNodeWithTag("offline-indicator").performTouchInput { click() }
        rule.mainClock.advanceTimeBy(32)
        rule.onNodeWithTag("offline-tooltip").assertDoesNotExist()
        rule.onNodeWithTag("offline-indicator").performTouchInput { click() }
        rule.mainClock.advanceTimeBy(32)
        rule.mainClock.advanceTimeBy(2_200)
        rule.onAllNodesWithTag("offline-tooltip").assertCountEquals(1)
        rule.mainClock.advanceTimeBy(3_000)
        rule.onNodeWithTag("offline-tooltip").assertDoesNotExist()
    }

    @Test fun changingRootScreenDismissesTooltipAndCancelsOldTimer() {
        var screen by mutableStateOf("auth")
        rule.setContent { ThesaurusTheme { OfflineStatusHost(false, screen) {} } }
        rule.onNodeWithTag("offline-indicator").performClick()
        rule.runOnIdle { screen = "invitation" }
        rule.onNodeWithTag("offline-tooltip").assertDoesNotExist()
        rule.onNodeWithTag("offline-indicator").performClick()
        rule.runOnIdle { screen = "household" }
        rule.onNodeWithTag("offline-tooltip").assertDoesNotExist()
    }

    @Test fun householdNavigationDismissesTooltipEvenWhenNavigationUsesSemantics() {
        rule.setContent {
            ThesaurusTheme {
                OfflineStatusHost(false, "household") {
                    HouseholdApp(
                        entriesContent = { _, _, _, _ -> Text("Wpisy", Modifier.testTag("entries-body")) },
                        summaryContent = { Text("Podsumowanie", Modifier.testTag("summary-body")) },
                        reportsContent = { Text("Raporty") },
                    )
                }
            }
        }
        rule.onNodeWithTag("offline-indicator").performClick()
        rule.onNodeWithTag("navigation-summary").performClick()
        rule.onNodeWithTag("summary-body").assertIsDisplayed()
        rule.onNodeWithTag("offline-tooltip").assertDoesNotExist()
        rule.onNodeWithTag("offline-indicator").assertIsDisplayed()
    }

    @Test fun settingsAddEditReportsAndBackNavigationDismissTooltip() {
        lateinit var navigation: NavHostController
        rule.setContent {
            navigation = rememberNavController()
            ThesaurusTheme {
                OfflineStatusHost(false, "household") {
                    HouseholdApp(
                        navController = navigation,
                        entriesContent = { _, _, _, _ -> Text("Wpisy") },
                        summaryContent = { Text("Podsumowanie") },
                        reportsContent = { Text("Raporty") },
                        taxonomyContent = { Text("Kategorie") },
                        entryFormContent = { _, _ -> Text("Formularz") },
                    )
                }
            }
        }
        listOf("settings", "add-entry", "edit-entry/existing", "reports").forEach { route ->
            rule.onNodeWithTag("offline-indicator").performClick()
            rule.runOnIdle { navigation.navigate(route) }
            rule.onNodeWithTag("offline-tooltip").assertDoesNotExist()
            rule.onNodeWithTag("offline-indicator").performClick()
            rule.runOnIdle { navigation.popBackStack() }
            rule.onNodeWithTag("offline-tooltip").assertDoesNotExist()
        }
    }
}
