package pl.bargor.thesaurus.ui.backupjson

import android.os.Bundle
import android.os.Parcel
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.lifecycle.ViewModelProvider
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import pl.bargor.thesaurus.JsonBackupPreviewActivity
import pl.bargor.thesaurus.JsonBackupPreviewViewModel

/** DEV synthetic backend only: rotation must retain progress without repeating import. */
class JsonBackupActivityRecreationTest {
    @get:Rule val compose = createAndroidComposeRule<JsonBackupPreviewActivity>()

    @Test fun recreateKeepsReadOnlyPreviewAndCompletedReportWithoutSourceJsonInSavedBundle() {
        lateinit var original: JsonBackupPreviewViewModel
        compose.activityRule.scenario.onActivity { original = ViewModelProvider(it)[JsonBackupPreviewViewModel::class.java] }
        compose.onNodeWithTag("json_backup_prepare").performClick()
        compose.waitUntil(10_000) { original.controller.state.value.phase == JsonBackupPhase.PREVIEW }
        val preview = original.controller.state.value.report!!
        assertEquals(7, preview.newCount)
        assertEquals(0, preview.addedCount)
        compose.activityRule.scenario.onActivity { activity ->
            val saved = Bundle()
            InstrumentationRegistry.getInstrumentation().callActivityOnSaveInstanceState(activity, saved)
            val parcel = Parcel.obtain()
            try {
                parcel.writeBundle(saved)
                val data = parcel.marshall()
                assertFalse(data.toString(Charsets.ISO_8859_1).contains("formatVersion"))
                assertFalse(data.toString(Charsets.UTF_16LE).contains("formatVersion"))
                assertFalse(data.toString(Charsets.UTF_16LE).contains("synthetic-source"))
            } finally { parcel.recycle() }
        }
        compose.activityRule.scenario.recreate()
        compose.activityRule.scenario.onActivity { assertSame(original, ViewModelProvider(it)[JsonBackupPreviewViewModel::class.java]) }
        assertEquals(preview, original.controller.state.value.report)
        compose.onNodeWithTag("json_backup_screen").performScrollToNode(hasTestTag("json_backup_confirm"))
        compose.onNodeWithTag("json_backup_confirm").performClick()
        compose.waitUntil(10_000) { original.controller.state.value.phase == JsonBackupPhase.COMPLETE && !original.controller.state.value.busy }
        val completed = original.controller.state.value.report!!
        assertEquals(7, completed.addedCount)
        compose.activityRule.scenario.recreate()
        compose.activityRule.scenario.onActivity { assertSame(original, ViewModelProvider(it)[JsonBackupPreviewViewModel::class.java]) }
        assertEquals(completed, original.controller.state.value.report)
        compose.onNodeWithTag("json_backup_confirm").assertDoesNotExist()
        compose.onNodeWithTag("json_backup_repeat").performScrollTo().performClick()
        compose.waitUntil(10_000) { original.controller.state.value.phase == JsonBackupPhase.PREVIEW }
        assertEquals(0, original.controller.state.value.report!!.newCount)
        assertEquals(7, original.controller.state.value.report!!.skippedCount)
        compose.onNodeWithTag("json_backup_confirm").assertDoesNotExist()
    }

    @Test fun failureRetryShowsFreshPreviewAndNeedsAnotherConfirmationBeforeCompletion() {
        lateinit var preview: JsonBackupPreviewViewModel
        compose.activityRule.scenario.onActivity { preview = ViewModelProvider(it)[JsonBackupPreviewViewModel::class.java] }
        compose.onNodeWithTag("json_backup_failure").performScrollTo().performClick()
        compose.waitUntil(10_000) { preview.controller.state.value.phase == JsonBackupPhase.PREVIEW }
        compose.onNodeWithTag("json_backup_screen").performScrollToNode(hasTestTag("json_backup_confirm"))
        compose.onNodeWithTag("json_backup_confirm").performClick()
        compose.waitUntil(10_000) { preview.controller.state.value.phase == JsonBackupPhase.COMPLETE && !preview.controller.state.value.busy }
        assertEquals(5, preview.controller.state.value.report!!.addedCount)
        assertEquals(1, preview.controller.state.value.report!!.errorCount)
        compose.onNodeWithTag("json_backup_screen").performScrollToNode(hasTestTag("json_backup_retry"))
        compose.onNodeWithTag("json_backup_retry").performClick()
        compose.waitUntil(10_000) { preview.controller.state.value.phase == JsonBackupPhase.PREVIEW }
        assertEquals(2, preview.controller.state.value.report!!.newCount)
        assertEquals(5, preview.controller.state.value.report!!.skippedCount)
        compose.onNodeWithTag("json_backup_screen").performScrollToNode(hasTestTag("json_backup_confirm"))
        compose.onNodeWithTag("json_backup_confirm").performClick()
        compose.waitUntil(10_000) { preview.controller.state.value.phase == JsonBackupPhase.COMPLETE && !preview.controller.state.value.busy }
        assertEquals(2, preview.controller.state.value.report!!.addedCount)
        assertEquals(0, preview.controller.state.value.report!!.errorCount)
    }
}
