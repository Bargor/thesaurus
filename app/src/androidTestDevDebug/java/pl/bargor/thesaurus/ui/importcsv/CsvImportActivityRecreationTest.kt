package pl.bargor.thesaurus.ui.importcsv

import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.lifecycle.ViewModelProvider
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import pl.bargor.thesaurus.CsvImportPreviewActivity
import pl.bargor.thesaurus.CsvImportPreviewViewModel

/** Launches the DEV synthetic preview, which has no Firebase/auth/repository dependencies. */
class CsvImportActivityRecreationTest {
    @get:Rule val compose = createAndroidComposeRule<CsvImportPreviewActivity>()

    @Test fun activityRecreateRetainsControllerDraftAndOpenRowEditor() {
        lateinit var original: CsvImportPreviewViewModel
        compose.activityRule.scenario.onActivity { original = ViewModelProvider(it)[CsvImportPreviewViewModel::class.java] }
        compose.waitForIdle()
        compose.waitUntil(10000) { !original.controller.state.value.busy }
        runBlocking { original.controller.reset() }
        compose.onNodeWithTag("csv_import_preview_auto").performClick()
        compose.waitUntil(10000) { original.controller.state.value.phase == CsvImportPhase.READY && !original.controller.state.value.busy }
        compose.onNodeWithTag("csv_import_start").performScrollTo().performClick()
        compose.waitUntil(10000) { original.controller.state.value.phase == CsvImportPhase.ATTENTION && !original.controller.state.value.busy }
        compose.onNodeWithTag("csv_import_edit_4").performScrollTo().performClick()
        compose.onNodeWithTag("csv_import_edit_field_1").performScrollTo().performTextReplacement("-30.00")
        compose.waitUntil(10000) { original.controller.state.value.drafts[4]?.get(1) == "-30.00" && !original.controller.state.value.busy }
        val summary = original.controller.state.value.report!!.summary
        compose.activityRule.scenario.recreate()
        compose.activityRule.scenario.onActivity {
            assertSame(original, ViewModelProvider(it)[CsvImportPreviewViewModel::class.java])
        }
        compose.onNodeWithTag("csv_import_editor").assertExists()
        compose.onNodeWithTag("csv_import_edit_field_1").performScrollTo().assertTextContains("-30.00")
        assertEquals(summary, original.controller.state.value.report!!.summary)
        assertEquals("-30.00", original.controller.state.value.drafts[4]!![1])
    }
}
