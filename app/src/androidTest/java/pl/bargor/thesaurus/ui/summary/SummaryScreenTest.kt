package pl.bargor.thesaurus.ui.summary

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsNotSelected
import androidx.compose.ui.test.assertIsSelected
import androidx.compose.ui.test.assertTextContains
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import java.math.BigInteger
import java.time.Year
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
        var state by mutableStateOf(SummaryUiState(month = YearMonth.of(2026, 9), year = Year.of(2026)))
        var retries = 0
        composeRule.setContent {
            ThesaurusTheme {
                SummaryScreen(state, {}, {}, {}, { retries++ })
            }
        }
        composeRule.onNodeWithTag("summary-loading").assertIsDisplayed()
        composeRule.onNodeWithContentDescription("Ładowanie danych").assertIsDisplayed()
        composeRule.runOnIdle { state = state.copy(isLoading = false) }
        composeRule.onNodeWithTag("summary-empty").assertIsDisplayed()
        composeRule.onNodeWithTag("summary-income").assertIsDisplayed()
        composeRule.runOnIdle { state = state.copy(syncState = SyncState.OFFLINE) }
        composeRule.onNodeWithTag("summary-offline").performScrollTo().assertIsDisplayed()
        composeRule.runOnIdle { state = state.copy(syncState = SyncState.ERROR, hasError = true) }
        composeRule.onNodeWithTag("summary-error").performScrollTo().assertIsDisplayed()
        composeRule.onNodeWithTag("summary-empty").assertDoesNotExist()
        composeRule.onNodeWithTag("summary-retry").performClick()
        assertEquals(1, retries)
        composeRule.runOnIdle {
            state = state.copy(
                hasError = false, syncState = SyncState.PENDING,
                totals = SummaryTotals(BigInteger.valueOf(1_200), BigInteger.valueOf(500), 2),
            )
        }
        composeRule.onNodeWithTag("summary-pending").performScrollTo().assertIsDisplayed()
        composeRule.onNodeWithTag("summary-net").performScrollTo().assertIsDisplayed()
        composeRule.onNodeWithTag("summary-net").assertTextContains("7,00", substring = true)
        composeRule.onNodeWithContentDescription("Bilans: +7,00 zł").assertIsDisplayed()
    }

    @Test fun modeSelectorAndPeriodControlsNavigate() {
        var previous = 0
        var next = 0
        var state by mutableStateOf(
            SummaryUiState(month = YearMonth.of(2026, 9), year = Year.of(2026), isLoading = false),
        )
        composeRule.setContent {
            ThesaurusTheme {
                SummaryScreen(
                    state = state,
                    onSelectPeriodMode = { state = state.copy(mode = it) },
                    onPreviousPeriod = { previous++ },
                    onNextPeriod = { next++ },
                    onRetry = {},
                )
            }
        }
        composeRule.onNodeWithTag("summary-mode-month").assertIsSelected()
        composeRule.onNodeWithTag("summary-mode-year").assertIsNotSelected()
        composeRule.onNodeWithTag("summary-mode-year").performClick()
        composeRule.onNodeWithTag("summary-mode-year").assertIsSelected()
        composeRule.onNodeWithTag("summary-mode-month").assertIsNotSelected()
        composeRule.onNodeWithTag("summary-period").assertTextContains("2026")
        composeRule.onNodeWithTag("summary-previous-period").performClick()
        composeRule.onNodeWithTag("summary-next-period").performClick()
        assertEquals(1, previous)
        assertEquals(1, next)
        composeRule.onNodeWithTag("summary-mode-month").performClick()
        composeRule.onNodeWithTag("summary-mode-month").assertIsSelected()
        composeRule.onNodeWithTag("summary-period").assertTextContains("Wrzesień 2026")
    }
}
