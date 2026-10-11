package pl.bargor.thesaurus.ui.backupjson

import android.content.ContentValues
import android.graphics.Bitmap
import android.os.Environment
import android.provider.MediaStore
import androidx.compose.material3.Surface
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.unit.Density
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import pl.bargor.thesaurus.R
import pl.bargor.thesaurus.ThesaurusTheme
import pl.bargor.thesaurus.data.transfer.*

/** Synthetic data only; optional MediaStore evidence contains no account or live ledger. */
class JsonBackupScreenTest {
    @get:Rule val compose = createComposeRule()
    private fun preview() = JsonBackupUiState(phase = JsonBackupPhase.PREVIEW, total = 2, canRetry = true,
        report = JsonMergeReport(listOf(
            JsonMergeItem(JsonMergeItemKind.OPENING_BALANCE, null, null, JsonMergeStatus.CONFLICT, "JSON_OPENING_BALANCE_CONFLICT"),
            JsonMergeItem(JsonMergeItemKind.CATEGORY, "synthetic-category", "json_category", JsonMergeStatus.NEW),
            JsonMergeItem(JsonMergeItemKind.SUBCATEGORY, "synthetic-child", "json_child", JsonMergeStatus.SKIPPED),
            JsonMergeItem(JsonMergeItemKind.ENTRY, "synthetic-entry", "json_entry", JsonMergeStatus.NEW))))
    private fun display(state: JsonBackupUiState, largeFont: Boolean = false, action: (JsonBackupAction) -> Unit = {}) {
        compose.setContent {
            val density = LocalDensity.current
            CompositionLocalProvider(LocalDensity provides Density(density.density, if (largeFont) 2f else density.fontScale)) {
                ThesaurusTheme(darkTheme = false) { Surface { JsonBackupScreen(state, action) } }
            }
        }
    }

    @Test fun previewIncludesCountsAndConflictsAndRequiresExplicitConfirmation() {
        val actions = mutableListOf<JsonBackupAction>()
        display(preview(), action = actions::add)
        compose.onNodeWithTag("json_backup_preview").assertExists()
        val counts = InstrumentationRegistry.getInstrumentation().targetContext.getString(R.string.json_backup_counts, 2, 1, 1)
        compose.onNodeWithText(counts).assertIsDisplayed()
        assertTrue(actions.isEmpty())
        compose.onNodeWithTag("json_backup_screen").performScrollToNode(hasTestTag("json_backup_conflict_reason"))
        val error = compose.onNodeWithTag("json_backup_conflict_reason").fetchSemanticsNode().config[SemanticsProperties.Error]
        assertTrue(error.isNotEmpty())
        capture("preview-with-conflicts")
        compose.onNodeWithTag("json_backup_screen").performScrollToNode(hasTestTag("json_backup_confirm"))
        compose.onNodeWithTag("json_backup_confirm").assertIsEnabled().performClick()
        assertEquals(listOf(JsonBackupAction.CONFIRM), actions)
    }

    @Test fun completedSummaryShowsAddedSkippedConflictErrorAndPendingCounts() {
        val state = preview().copy(phase = JsonBackupPhase.COMPLETE, errorCode = "JSON_IMPORT_WRITE_FAILED",
            report = JsonMergeReport(listOf(
                JsonMergeItem(JsonMergeItemKind.ENTRY, "added", "a", JsonMergeStatus.ADDED),
                JsonMergeItem(JsonMergeItemKind.ENTRY, "skipped", "b", JsonMergeStatus.SKIPPED),
                JsonMergeItem(JsonMergeItemKind.ENTRY, "conflict", "c", JsonMergeStatus.CONFLICT, "JSON_DELETION_CONFLICT"),
                JsonMergeItem(JsonMergeItemKind.ENTRY, "error", "d", JsonMergeStatus.SERVER_ERROR, "JSON_IMPORT_WRITE_FAILED"),
                JsonMergeItem(JsonMergeItemKind.ENTRY, "pending", "e", JsonMergeStatus.NEW))))
        display(state)
        compose.onNodeWithTag("json_backup_summary").assertExists()
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val counts = context.getString(R.string.json_backup_kind_counts, context.getString(R.string.json_backup_entries), 1, 1, 1, 1, 1)
        compose.onNodeWithText(counts).performScrollTo().assertIsDisplayed()
        compose.onNodeWithTag("json_backup_confirm").assertDoesNotExist()
        capture("completed-summary")
    }

    @Test fun largeFontPreviewAndActionsAreScrollableAndLabeled() {
        display(preview(), largeFont = true)
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        compose.onNodeWithText(context.getString(R.string.json_backup_preview)).performScrollTo().assertIsDisplayed()
        compose.onNodeWithTag("json_backup_screen").performScrollToNode(hasTestTag("json_backup_confirm"))
        compose.onNodeWithTag("json_backup_confirm").assertIsDisplayed().assertTextContains(context.getString(R.string.json_backup_confirm))
        capture("large-font-confirmation")
        compose.onNodeWithTag("json_backup_screen").performScrollToNode(hasTestTag("json_backup_retry"))
        compose.onNodeWithTag("json_backup_retry").assertIsDisplayed().assertHasClickAction()
        compose.onNodeWithTag("json_backup_screen").performScrollToNode(hasTestTag("json_backup_export"))
        compose.onNodeWithTag("json_backup_export").assertIsDisplayed().assertTextContains(context.getString(R.string.json_backup_export))
    }

    @Test fun busyImportDisablesConfirmationAndExportAndExposesProgress() {
        display(preview().copy(phase = JsonBackupPhase.IMPORTING, busy = true, processed = 1))
        compose.onNodeWithTag("json_backup_progress").assertExists()
        compose.onNodeWithTag("json_backup_screen").performScrollToNode(hasTestTag("json_backup_confirm"))
        compose.onNodeWithTag("json_backup_confirm").assertIsNotEnabled()
        compose.onNodeWithTag("json_backup_retry").assertDoesNotExist()
        compose.onNodeWithTag("json_backup_screen").performScrollToNode(hasTestTag("json_backup_export"))
        compose.onNodeWithTag("json_backup_export").assertIsNotEnabled()
    }

    @Test fun allSkippedPreviewHasNoConfirmAndRetryAndExportDispatchOnlyWhenClicked() {
        val actions = mutableListOf<JsonBackupAction>()
        val report = JsonMergeReport(listOf(JsonMergeItem(JsonMergeItemKind.ENTRY, "entry", "target", JsonMergeStatus.SKIPPED)))
        display(preview().copy(total = 0, report = report), action = actions::add)
        compose.onNodeWithTag("json_backup_confirm").assertDoesNotExist()
        assertTrue(actions.isEmpty())
        compose.onNodeWithTag("json_backup_screen").performScrollToNode(hasTestTag("json_backup_retry"))
        compose.onNodeWithTag("json_backup_retry").performClick()
        compose.onNodeWithTag("json_backup_screen").performScrollToNode(hasTestTag("json_backup_export"))
        compose.onNodeWithTag("json_backup_export").performClick()
        assertEquals(listOf(JsonBackupAction.RETRY, JsonBackupAction.EXPORT), actions)
    }

    @Test fun allConflictPreviewShowsNoNewDataMessageInsteadOfAllSkippedAndHasNoConfirm() {
        val report = JsonMergeReport(listOf(JsonMergeItem(JsonMergeItemKind.OPENING_BALANCE, null, null,
            JsonMergeStatus.CONFLICT, "JSON_OPENING_BALANCE_CONFLICT")))
        display(preview().copy(total = 0, report = report))
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        compose.onNodeWithTag("json_backup_screen").performScrollToNode(hasText(context.getString(R.string.json_backup_no_new)))
        compose.onNodeWithText(context.getString(R.string.json_backup_no_new)).assertIsDisplayed()
        compose.onNodeWithText(context.getString(R.string.json_backup_all_skipped)).assertDoesNotExist()
        compose.onNodeWithTag("json_backup_confirm").assertDoesNotExist()
    }

    private fun capture(name: String) {
        if (InstrumentationRegistry.getArguments().getString("screenshotEvidence") != "true") return
        compose.waitForIdle()
        val bitmap = compose.onRoot().captureToImage().asAndroidBitmap()
        val resolver = InstrumentationRegistry.getInstrumentation().targetContext.contentResolver
        val values = ContentValues().apply {
            put(MediaStore.MediaColumns.DISPLAY_NAME, "$name.png")
            put(MediaStore.MediaColumns.MIME_TYPE, "image/png")
            put(MediaStore.MediaColumns.RELATIVE_PATH, "${Environment.DIRECTORY_PICTURES}/ThesaurusTestEvidence/issue113/")
            put(MediaStore.MediaColumns.IS_PENDING, 1)
        }
        val uri = requireNotNull(resolver.insert(MediaStore.Images.Media.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY), values))
        try {
            requireNotNull(resolver.openOutputStream(uri)).use { assertTrue(bitmap.compress(Bitmap.CompressFormat.PNG, 100, it)) }
            check(resolver.update(uri, ContentValues().apply { put(MediaStore.MediaColumns.IS_PENDING, 0) }, null, null) == 1)
        } catch (error: Throwable) { resolver.delete(uri, null, null); throw error }
    }
}
