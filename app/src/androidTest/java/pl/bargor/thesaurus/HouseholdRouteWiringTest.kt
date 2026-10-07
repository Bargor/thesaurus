package pl.bargor.thesaurus

import androidx.compose.foundation.layout.Column
import androidx.compose.material3.Button
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.navigation.NavHostController
import androidx.navigation.compose.rememberNavController
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test

class HouseholdRouteWiringTest {
    @get:Rule val compose = createComposeRule()

    @Test fun familyAndEntryActionsReachTheirDestinationAndReturnToTheirSourceTab() {
        lateinit var controller: NavHostController
        compose.setContent {
            controller = rememberNavController()
            ThesaurusTheme {
                HouseholdApp(
                    navController = controller,
                    entriesContent = { _, _, onFamily, onEdit -> Column {
                        Button(onFamily, Modifier.testTag("fixture-family")) { Text("Rodzina") }
                        OpenEntryFixture(Destination.Entries, onEdit)
                    } },
                    browseContent = { OpenEntryFixture(Destination.Browse, it) },
                    summaryContent = { OpenEntryFixture(Destination.Summary, it) },
                    reportsContent = { OpenEntryFixture(Destination.Reports, it) },
                    familyContent = { onBack ->
                        Button(onBack, Modifier.testTag("fixture-family-back")) { Text("Wstecz") }
                    },
                    entryFormContent = { id, _ -> Text(id.orEmpty(), Modifier.testTag("fixture-entry")) },
                )
            }
        }
        compose.onNodeWithTag("fixture-family").performClick()
        compose.runOnIdle { assertEquals("family", controller.currentDestination?.route) }
        compose.onNodeWithTag("fixture-family-back").performClick()
        compose.runOnIdle { assertEquals(Destination.Entries.route, controller.currentDestination?.route) }
        for (destination in Destination.entries) {
            compose.onNodeWithTag(destination.navigationTestTag).performClick()
            compose.onNodeWithTag("fixture-open-${destination.route}").performClick()
            compose.onNodeWithTag("fixture-entry").assertIsDisplayed()
            compose.runOnIdle {
                assertEquals("edit-entry/{entryId}", controller.currentDestination?.route)
                assertEquals("entry-${destination.route}", controller.currentBackStackEntry?.arguments?.getString("entryId"))
                controller.popBackStack()
            }
            compose.onNodeWithTag("fixture-open-${destination.route}").assertIsDisplayed()
            compose.runOnIdle { assertEquals(destination.route, controller.currentDestination?.route) }
        }
    }

    @Composable private fun OpenEntryFixture(destination: Destination, onOpenEntry: (String) -> Unit) {
        Button(onClick = { onOpenEntry("entry-${destination.route}") },
            modifier = Modifier.testTag("fixture-open-${destination.route}")) { Text("Otwórz wpis") }
    }
}
