package pl.bargor.thesaurus

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsSelected
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.material3.Text
import androidx.compose.ui.res.stringResource
import org.junit.Rule
import org.junit.Test

class NavigationSmokeTest {
    @get:Rule
    val composeTestRule = createComposeRule()

    @Test
    fun bottomNavigationShowsEveryDestination() {
        composeTestRule.setContent {
            ThesaurusTheme {
                HouseholdApp(entriesContent = { _, _, _ -> Text(stringResource(R.string.empty_entries)) })
            }
        }
        composeTestRule.onNodeWithText("Nie ma jeszcze żadnych wpisów.").assertIsDisplayed()
        composeTestRule.onNodeWithTag(Destination.Entries.navigationTestTag).assertIsSelected()
        composeTestRule.onNodeWithTag(Destination.Summary.navigationTestTag).performClick()
        composeTestRule.onNodeWithText("Podsumowanie pojawi się po dodaniu wpisów.").assertIsDisplayed()
        composeTestRule.onNodeWithTag(Destination.Summary.navigationTestTag).assertIsSelected()
        composeTestRule.onNodeWithTag(Destination.Reports.navigationTestTag).performClick()
        composeTestRule.onNodeWithText("Raporty będą dostępne wkrótce.").assertIsDisplayed()
        composeTestRule.onNodeWithTag(Destination.Reports.navigationTestTag).assertIsSelected()
    }
}
