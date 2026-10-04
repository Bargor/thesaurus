package pl.bargor.thesaurus.ui.settings

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.requiredSize
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import pl.bargor.thesaurus.ThesaurusTheme

class OpeningBalanceScreenTest {
    @get:Rule val compose = createComposeRule()

    @Test fun compactLargeFontFormKeepsSignedInputModesAndSaveReachable() {
        val state = mutableStateOf(OpeningBalanceUiState(loading = false, isOwner = true, amount = "250,00"))
        var saves = 0
        compose.setContent {
            val deviceDensity = LocalDensity.current.density
            Box(Modifier.requiredSize((320f / deviceDensity).dp, (640f / deviceDensity).dp).testTag("balance-form-viewport")) {
                CompositionLocalProvider(LocalDensity provides Density(1.1f, 1.8f)) {
                    ThesaurusTheme {
                        OpeningBalanceScreen(state.value,
                            onAmountChange = { state.value = state.value.copy(amount = it) },
                            onModeChange = { state.value = state.value.copy(mode = it) },
                            onSave = { saves++ }, onRetry = {}, onBack = {})
                    }
                }
            }
        }
        for (mode in listOf("opening", "current")) {
            compose.onNodeWithTag("opening-balance-mode-$mode").performScrollTo().performClick().assertIsSelected()
            for (input in listOf("123,45", "-123,45", "0", "1 234 567 890,12", "błędna kwota")) {
                compose.onNodeWithTag("opening-balance-amount").performScrollTo().performTextReplacement(input)
                compose.onNodeWithTag("opening-balance-amount").assertTextContains(input)
                compose.onNodeWithTag("opening-balance-save").performScrollTo().assertIsDisplayed()
                    .assertHeightIsAtLeast(48.dp).performClick()
            }
        }
        compose.runOnIdle { assertEquals(10, saves) }
        val viewport = compose.onNodeWithTag("balance-form-viewport").fetchSemanticsNode().boundsInRoot
        val save = compose.onNodeWithTag("opening-balance-save").fetchSemanticsNode().boundsInRoot
        assertTrue(save.width > 0f && save.height > 0f)
        assertTrue(save.left >= viewport.left && save.right <= viewport.right && save.bottom <= viewport.bottom + 1f)
    }

    @Test fun readOnlyLoadingAndSavingPreventMutationAndErrorsRemainActionable() {
        val state = mutableStateOf(OpeningBalanceUiState(loading = false, isOwner = false, amount = "250,00"))
        var retries = 0
        compose.setContent { ThesaurusTheme {
            OpeningBalanceScreen(state.value, {}, {}, {}, { retries++ }, {})
        } }
        compose.onNodeWithTag("opening-balance-read-only").assertIsDisplayed()
        assertDisabled()
        compose.runOnIdle { state.value = state.value.copy(loading = true, isOwner = true) }
        compose.onNodeWithTag("opening-balance-loading").assertIsDisplayed(); assertDisabled()
        compose.runOnIdle { state.value = state.value.copy(loading = false, saving = true) }
        compose.onNodeWithTag("opening-balance-saving").performScrollTo().assertIsDisplayed(); assertDisabled()
        for (error in OpeningBalanceError.entries) {
            compose.runOnIdle { state.value = state.value.copy(saving = false, error = error) }
            compose.onNodeWithTag("opening-balance-error").performScrollTo().assertIsDisplayed()
            if (error == OpeningBalanceError.LOAD) {
                compose.onNodeWithTag("opening-balance-retry").performScrollTo().assertIsDisplayed().performClick()
            }
        }
        compose.runOnIdle { assertEquals(1, retries); state.value = state.value.copy(error = null, saved = true, queuedOffline = true) }
        compose.onNodeWithTag("opening-balance-pending").performScrollTo().assertIsDisplayed()
        compose.onNodeWithTag("opening-balance-saved").assertDoesNotExist(); assertDisabled()
        compose.runOnIdle { state.value = state.value.copy(queuedOffline = false) }
        compose.onNodeWithTag("opening-balance-saved").performScrollTo().assertIsDisplayed()
    }

    @Test fun explicitSignActionMakesNegativeAndPositivePolishAmountsAccessible() {
        val state = mutableStateOf(OpeningBalanceUiState(loading = false, isOwner = true, amount = "123,45"))
        compose.setContent { ThesaurusTheme {
            OpeningBalanceScreen(state.value, { state.value = state.value.copy(amount = it) }, {}, {}, {}, {})
        } }
        compose.onNodeWithTag("opening-balance-sign").performScrollTo().assertIsDisplayed()
            .assertWidthIsAtLeast(48.dp).assertHeightIsAtLeast(48.dp).performClick()
        compose.onNodeWithTag("opening-balance-amount").assertTextContains("-123,45")
        compose.onNodeWithTag("opening-balance-sign").assert(
            SemanticsMatcher.expectValue(androidx.compose.ui.semantics.SemanticsProperties.StateDescription, "Kwota ujemna"))
        compose.onNodeWithTag("opening-balance-sign").performClick()
        compose.onNodeWithTag("opening-balance-amount").assertTextContains("123,45")
        compose.onNodeWithTag("opening-balance-sign").assert(
            SemanticsMatcher.expectValue(androidx.compose.ui.semantics.SemanticsProperties.StateDescription, "Kwota nieujemna"))
        compose.runOnIdle { state.value = state.value.copy(amount = "+0,00") }
        compose.onNodeWithTag("opening-balance-sign").performClick()
        compose.onNodeWithTag("opening-balance-amount").assertTextContains("-0,00")
    }

    private fun assertDisabled() {
        for (tag in listOf("amount", "sign", "mode-opening", "mode-current", "save")) {
            compose.onNodeWithTag("opening-balance-$tag").assertIsNotEnabled()
        }
    }
}
