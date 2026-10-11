package pl.bargor.thesaurus.ui.data

import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import pl.bargor.thesaurus.ThesaurusTheme

class DataScreenTest {
    @get:Rule val compose = createComposeRule()

    @Test fun ownerSeesAllFourActionsAndEachDispatchesItsFormat() {
        val exports = mutableListOf<DataFormat>()
        val imports = mutableListOf<DataFormat>()
        display(DataUiState(canExport = true, importEnabled = true, importBlockCode = null), exports::add, imports::add)
        for (format in DataFormat.entries) {
            val name = format.name.lowercase()
            compose.onNodeWithTag("data-export-$name").performScrollTo().assertIsEnabled().performClick()
            compose.onNodeWithTag("data-import-$name").performScrollTo().assertIsEnabled().performClick()
        }
        assertEquals(DataFormat.entries, exports)
        assertEquals(DataFormat.entries, imports)
    }

    @Test fun memberHasExportsAndHiddenImportsWithAccessibleReason() {
        display(DataUiState(canExport = true, importBlockCode = "DATA_OWNER_REQUIRED"))
        for (format in DataFormat.entries) {
            compose.onNodeWithTag("data-export-${format.name.lowercase()}").performScrollTo().assertIsEnabled()
            compose.onNodeWithTag("data-import-${format.name.lowercase()}").assertDoesNotExist()
        }
        compose.onNodeWithTag("data-import-blocked").performScrollTo().assertIsDisplayed()
    }

    @Test fun csvExportRequiresAnExplicitPrivacyConfirmation() {
        var confirms = 0
        var cancels = 0
        display(DataUiState(canExport = true, warning = DataFormat.CSV), confirm = { confirms++ }, cancel = { cancels++ })
        compose.onNodeWithTag("data-privacy-warning").assertIsDisplayed()
        assertEquals(0, confirms)
        compose.onNodeWithTag("data-privacy-confirm").performClick()
        assertEquals(1, confirms)
        compose.onNodeWithTag("data-privacy-cancel").performClick()
        assertEquals(1, cancels)
    }

    @Test fun jsonExportShowsThePrivacyWarningBeforeAnyConfirmation() {
        var confirms = 0
        display(DataUiState(canExport = true, warning = DataFormat.JSON), confirm = { confirms++ })
        compose.onNodeWithTag("data-privacy-warning").assertIsDisplayed()
        assertEquals(0, confirms)
        compose.onNodeWithTag("data-privacy-confirm").performClick()
        assertEquals(1, confirms)
    }

    @Test fun busyOperationDisablesAllFourActions() {
        display(DataUiState(canExport = true, importEnabled = true, busy = true))
        for (format in DataFormat.entries) {
            compose.onNodeWithTag("data-export-${format.name.lowercase()}").performScrollTo().assertIsNotEnabled()
            compose.onNodeWithTag("data-import-${format.name.lowercase()}").performScrollTo().assertIsNotEnabled()
        }
        compose.onNodeWithTag("data-progress").assertExists()
    }

    private fun display(state: DataUiState, export: (DataFormat) -> Unit = {}, import: (DataFormat) -> Unit = {},
        confirm: () -> Unit = {}, cancel: () -> Unit = {}) {
        compose.setContent { ThesaurusTheme { DataScreen(state, export, import, confirm, cancel, {}) } }
    }
}
