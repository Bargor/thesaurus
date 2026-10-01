package pl.bargor.thesaurus.ui.summary

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.width
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import java.time.Year
import java.time.YearMonth
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import pl.bargor.thesaurus.ThesaurusTheme

class SummaryScreenTest {
    @get:Rule val compose = createComposeRule()
    @Test fun minimalShellContainsOnlyPeriodNavigationAndNoMetricsFiltersOrList() {
        compose.setContent { ThesaurusTheme { SummaryScreen(SummaryUiState(YearMonth.of(2026, 9), Year.of(2026)), {}, {}, {}) } }
        compose.onNodeWithTag("summary-period").assertIsDisplayed().assertTextContains("Wrzesień 2026")
        listOf("summary-income", "summary-expense", "summary-net", "summary-loading", "summary-empty", "summary-error", "summary-pending", "summary-filter-category", "summary-filter-subcategory", "summary-filter-tag", "summary-sort", "summary-clear-controls", "summary-entry-count").forEach {
            compose.onNodeWithTag(it).assertDoesNotExist()
        }
        compose.onAllNodesWithText("Podsumowanie").assertCountEquals(0)
        compose.onAllNodes(hasClickAction()).assertCountEquals(3)
    }
    @Test fun periodControlsStartAtTopAndFitNarrowLargeFontWithAccessibleTargets() {
        compose.setContent {
            val density = LocalDensity.current
            CompositionLocalProvider(LocalDensity provides Density(density.density, 1.8f)) {
                ThesaurusTheme { Box(Modifier.width(320.dp).fillMaxHeight()) {
                    SummaryScreen(SummaryUiState(YearMonth.of(2026, 9), Year.of(2026)), {}, {}, {})
                } }
            }
        }
        listOf("summary-period", "summary-previous-period", "summary-next-period").forEach {
            val bounds = compose.onNodeWithTag(it).assertIsDisplayed().getUnclippedBoundsInRoot()
            assertTrue(bounds.bottom - bounds.top >= 48.dp)
            assertTrue(bounds.right - bounds.left >= 48.dp)
            assertTrue(bounds.left >= 0.dp && bounds.right <= 320.dp)
        }
        assertTrue(compose.onNodeWithTag("summary-period").getUnclippedBoundsInRoot().top <= 24.dp)
    }
    @Test fun headingAndArrowsExposeModeActionAndPolishDescriptions() {
        var state by mutableStateOf(SummaryUiState(YearMonth.of(2026, 9), Year.of(2026)))
        var previous = 0; var next = 0
        compose.setContent { ThesaurusTheme { SummaryScreen(state, { state = state.copy(mode = it) }, { previous++ }, { next++ }) } }
        val period = compose.onNodeWithTag("summary-period").assertHasClickAction()
        period.assert(SemanticsMatcher.expectValue(SemanticsProperties.Role, Role.Button))
            .assert(SemanticsMatcher.expectValue(SemanticsProperties.StateDescription, "Podsumowanie miesięczne"))
        assertEquals("Pokaż podsumowanie roczne", period.fetchSemanticsNode().config[SemanticsActions.OnClick].label)
        compose.onNodeWithTag("summary-previous-period").assertContentDescriptionEquals("Poprzedni miesiąc").performClick()
        compose.onNodeWithTag("summary-next-period").assertContentDescriptionEquals("Następny miesiąc").performClick()
        period.performClick().assertTextContains("2026").assert(SemanticsMatcher.expectValue(SemanticsProperties.StateDescription, "Podsumowanie roczne"))
        assertEquals("Pokaż podsumowanie miesięczne", period.fetchSemanticsNode().config[SemanticsActions.OnClick].label)
        compose.onNodeWithTag("summary-previous-period").assertContentDescriptionEquals("Poprzedni rok").performClick()
        compose.onNodeWithTag("summary-next-period").assertContentDescriptionEquals("Następny rok").performClick()
        assertEquals(2, previous); assertEquals(2, next)
        period.performClick().assertTextContains("Wrzesień 2026")
    }
}
