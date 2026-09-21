package pl.bargor.thesaurus

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsSelected
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.material3.Text
import androidx.compose.ui.res.stringResource
import java.time.YearMonth
import java.time.Year
import org.junit.Rule
import org.junit.Test
import pl.bargor.thesaurus.ui.summary.SummaryScreen
import pl.bargor.thesaurus.ui.summary.SummaryUiState

class NavigationSmokeTest {
    @get:Rule
    val composeTestRule = createComposeRule()

    @Test
    fun bottomNavigationShowsEveryDestination() {
        composeTestRule.setContent {
            ThesaurusTheme {
                HouseholdApp(
                    entriesContent = { _, _, _, _ -> Text(stringResource(R.string.empty_entries)) },
                    summaryContent = {
                        SummaryScreen(
                            state = SummaryUiState(month = YearMonth.of(2026, 9), year = Year.of(2026), isLoading = false),
                            onSelectPeriodMode = {}, onPreviousPeriod = {}, onNextPeriod = {}, onRetry = {},
                        )
                    },
                )
            }
        }
        composeTestRule.onNodeWithText("Nie ma jeszcze żadnych wpisów.").assertIsDisplayed()
        composeTestRule.onNodeWithTag(Destination.Entries.navigationTestTag).assertIsSelected()
        composeTestRule.onNodeWithTag(Destination.Summary.navigationTestTag).performClick()
        composeTestRule.onNodeWithText("Brak wpisów w wybranym miesiącu.").assertIsDisplayed()
        composeTestRule.onNodeWithTag(Destination.Summary.navigationTestTag).assertIsSelected()
        composeTestRule.onNodeWithTag(Destination.Reports.navigationTestTag).performClick()
        composeTestRule.onNodeWithText("Raporty będą dostępne wkrótce.").assertIsDisplayed()
        composeTestRule.onNodeWithTag(Destination.Reports.navigationTestTag).assertIsSelected()
    }
}
