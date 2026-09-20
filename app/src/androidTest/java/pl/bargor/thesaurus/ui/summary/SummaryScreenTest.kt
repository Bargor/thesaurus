package pl.bargor.thesaurus.ui.summary

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import java.math.BigInteger
import java.time.YearMonth
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import pl.bargor.thesaurus.ThesaurusTheme
import pl.bargor.thesaurus.data.model.SummaryTotals
import pl.bargor.thesaurus.data.model.SyncState

class SummaryScreenTest {
    @get:Rule val composeRule = createComposeRule()

    @Test fun loadingEmptyOfflineErrorAndPendingStates() {
        var state by mutableStateOf(SummaryUiState(month = YearMonth.of(2026, 9)))
        var retries = 0
        composeRule.setContent {
            ThesaurusTheme {
                SummaryScreen(state, {}, {}, { retries++ })
            }
        }
        composeRule.onNodeWithTag("summary-loading").assertIsDisplayed()
        composeRule.runOnIdle { state = state.copy(isLoading = false) }
        composeRule.onNodeWithTag("summary-empty").assertIsDisplayed()
        composeRule.onNodeWithTag("summary-income").assertIsDisplayed()
        composeRule.runOnIdle { state = state.copy(syncState = SyncState.OFFLINE) }
        composeRule.onNodeWithTag("summary-offline").assertIsDisplayed()
        composeRule.runOnIdle { state = state.copy(syncState = SyncState.ERROR, hasError = true) }
        composeRule.onNodeWithTag("summary-error").assertIsDisplayed()
        composeRule.onNodeWithTag("summary-empty").assertDoesNotExist()
        composeRule.onNodeWithTag("summary-retry").performClick()
        assertEquals(1, retries)
        composeRule.runOnIdle {
            state = state.copy(
                hasError = false, syncState = SyncState.PENDING,
                totals = SummaryTotals(BigInteger.valueOf(1_200), BigInteger.valueOf(500), 2),
            )
        }
        composeRule.onNodeWithTag("summary-pending").assertIsDisplayed()
        composeRule.onNodeWithTag("summary-net").assertIsDisplayed()
    }

    @Test fun periodControlsNavigate() {
        var previous = 0
        var next = 0
        composeRule.setContent {
            ThesaurusTheme {
                SummaryScreen(SummaryUiState(YearMonth.of(2026, 9), isLoading = false),
                    onPreviousMonth = { previous++ }, onNextMonth = { next++ }, onRetry = {})
            }
        }
        composeRule.onNodeWithTag("summary-previous-period").performClick()
        composeRule.onNodeWithTag("summary-next-period").performClick()
        assertEquals(1, previous)
        assertEquals(1, next)
    }
}
