package pl.bargor.thesaurus.ui.importcsv

import android.graphics.Bitmap
import android.content.ContentValues
import android.os.Environment
import android.provider.MediaStore
import androidx.compose.material3.Surface
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.Density
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.After
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.cancel
import kotlinx.coroutines.runBlocking
import java.time.LocalDate
import pl.bargor.thesaurus.ThesaurusTheme
import pl.bargor.thesaurus.R
import pl.bargor.thesaurus.data.model.Category
import pl.bargor.thesaurus.data.model.Household
import pl.bargor.thesaurus.data.model.LedgerEntry
import pl.bargor.thesaurus.data.transfer.*

/** Pure Compose fixtures deliberately avoid real accounts, networking and repository writes. */
class CsvImportScreenTest {
    @get:Rule val compose = createComposeRule()
    private val actionScope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    @After fun closeActionScope() { actionScope.cancel() }
    private fun display(state: CsvImportUiState, largeFont: Boolean = false, action: (CsvImportAction) -> Unit = {}) {
        compose.setContent {
            val density = LocalDensity.current
            CompositionLocalProvider(LocalDensity provides Density(density.density, if (largeFont) 2f else density.fontScale)) {
                ThesaurusTheme(darkTheme = false) { Surface { CsvImportScreen(state, action) } }
            }
        }
    }
    private fun attention(): CsvImportUiState {
        val source = ImportRow(2, listOf("bad", "-12.50", "Nieznana", "", "Zakupy testowe"),
            listOf(ValidationIssue("INVALID_DATE", "date", 2)))
        return CsvImportUiState(phase = CsvImportPhase.ATTENTION,
            headers = listOf("date", "amount", "category", "subcategory", "title"),
            mapping = CsvColumnMapping(date = 0, amount = 1, category = 2, subcategory = 3, title = 4),
            report = CsvImportReport(listOf(CsvImportRowResult(source, CsvImportRowStatus.INVALID)),
                ImportSummary(1, 0, 1, source.issues), listOf(source)), total = 1, processed = 1, attention = 1,
            categories = listOf(Category("food", "proof", "Jedzenie", authorId = "proof", updatedById = "proof")))
    }

    @Test fun automaticMappingShowsExplicitStartAndDoesNotDispatchUntilClick() {
        val actions = mutableListOf<CsvImportAction>()
        display(attention().copy(phase = CsvImportPhase.READY), action = actions::add)
        compose.onNodeWithTag("csv_import_start").assertIsDisplayed().assertHasClickAction()
        assertTrue(actions.isEmpty())
        compose.onNodeWithTag("csv_import_start").performClick()
        assertEquals(1, actions.size)
    }

    @Test fun manualMappingIsScrollableAtLargeFontAndContainsRequiredFieldLabels() {
        display(CsvImportUiState(phase = CsvImportPhase.MAPPING, headers = listOf("Pierwsza", "Druga", "Trzecia")), largeFont = true)
        compose.onNodeWithTag("csv_import_manual_mapping").assertExists()
        compose.onNodeWithTag("csv_import_mapping_date").performScrollTo().assertIsDisplayed()
        val dateDescription = compose.onNodeWithTag("csv_import_mapping_date").fetchSemanticsNode().config[SemanticsProperties.ContentDescription].joinToString()
        assertTrue(dateDescription.contains(InstrumentationRegistry.getInstrumentation().targetContext.getString(R.string.csv_import_date)))
        compose.onNodeWithTag("csv_import_mapping_amount").performScrollTo().assertIsDisplayed()
        compose.onNodeWithTag("csv_import_mapping_category").performScrollTo().assertIsDisplayed()
        capture("manual-mapping-large-font")
    }

    @Test fun ambiguousOptionalHeadersRequireMappingConfirmationBeforeStartBecomesAvailable() {
        val actions = mutableListOf<CsvImportAction>()
        val state = CsvImportUiState(phase = CsvImportPhase.MAPPING,
            headers = listOf("date", "amount", "category", "title", "title"),
            mapping = CsvColumnMapping(date = 0, amount = 1, category = 2))
        display(state, action = actions::add)
        compose.onNodeWithTag("csv_import_start").assertDoesNotExist()
        compose.onNodeWithTag("csv_import_confirm_mapping").performScrollTo().assertIsEnabled().performClick()
        assertEquals(listOf(CsvImportAction.Mapping(state.mapping, state.dateFormat)), actions)
    }

    @Test fun rowEditorHasFieldLabelsValidationAndExplicitSaveAndBulkControls() {
        display(attention())
        capture("attention-queue")
        compose.onNodeWithTag("csv_import_edit_2").performScrollTo().assertHasClickAction().performClick()
        compose.onNodeWithTag("csv_import_editor").assertExists()
        val error = compose.onNode(hasTestTag("csv_import_issue_2_date_INVALID_DATE") and hasAnyAncestor(hasTestTag("csv_import_editor")), useUnmergedTree = true).fetchSemanticsNode().config[SemanticsProperties.Error]
        assertTrue(error.isNotEmpty())
        capture("repair-editor-field-error")
        compose.onNodeWithTag("csv_import_edit_field_0").performScrollTo().assertIsDisplayed().performTextReplacement("2026-10-09")
        compose.onNodeWithTag("csv_import_revalidate").performScrollTo().assertHasClickAction()
        compose.onNodeWithTag("csv_import_batch_mapping").performScrollTo().assertHasClickAction()
        capture("repair-editor")
        compose.onNodeWithTag("csv_import_save_repair").performScrollTo().assertExists()
    }

    @Test fun attentionAndCompletedSummaryExposeDistinctCounts() {
        val state = attention().copy(phase = CsvImportPhase.COMPLETE,
            report = CsvImportReport(emptyList(), ImportSummary(5, 4, 1, added = 1, repairedAndAdded = 1, duplicates = 2, discarded = 1)))
        display(state)
        compose.onNodeWithTag("csv_import_summary").assertExists()
        val counts = InstrumentationRegistry.getInstrumentation().targetContext.getString(R.string.csv_import_summary_counts, 1, 1, 2, 1, 0)
        compose.onNodeWithText(counts).performScrollTo().assertIsDisplayed()
    }

    @Test fun controllerRouteEditValidateAndSaveWritesOnceAndClosesAttentionQueue() {
        val writes = mutableListOf<LedgerEntry>()
        val backend = object : CsvImportBackend {
            override fun currentIdentity() = CsvImportIdentity("proof", "session")
            override suspend fun loadSnapshot(householdId: String, expectedIdentity: CsvImportIdentity) = CsvImportSnapshot(
                expectedIdentity, Household("proof", "Synthetic", "proof"), writes.toList(),
                listOf(Category("food", "proof", "Jedzenie", authorId = "proof", updatedById = "proof")), emptyList())
            override suspend fun commitEntry(snapshot: CsvImportSnapshot, entry: LedgerEntry): CsvImportCommitResult {
                writes += entry
                return CsvImportCommitResult(writes.size.toLong(), true)
            }
        }
        val controller = CsvImportController(CsvImportCoordinator(backend) { LocalDate.of(2026, 10, 10) }, InMemoryCsvImportCheckpointStore())
        runBlocking { controller.load("proof", "date;amount;category;title\nbad;-12.50;Jedzenie;Test".toByteArray()) }
        compose.setContent { ThesaurusTheme(darkTheme = false) { Surface { CsvImportRoute(controller, actionScope) } } }
        compose.onNodeWithTag("csv_import_start").performScrollTo().performClick()
        compose.waitUntil(10000) { controller.state.value.phase == CsvImportPhase.ATTENTION && !controller.state.value.busy }
        compose.onNodeWithTag("csv_import_edit_2").performScrollTo().performClick()
        compose.onNodeWithTag("csv_import_edit_field_0").performScrollTo().performTextReplacement("2026-10-09")
        compose.waitUntil(10000) { controller.state.value.drafts[2]?.first() == "2026-10-09" && !controller.state.value.busy }
        compose.onNodeWithTag("csv_import_revalidate").performScrollTo().performClick()
        compose.waitUntil(10000) { controller.state.value.report!!.rows.single().status == CsvImportRowStatus.PENDING && !controller.state.value.busy }
        assertTrue(writes.isEmpty())
        compose.onNodeWithTag("csv_import_save_repair").performScrollTo().performClick()
        compose.waitUntil(10000) { controller.state.value.phase == CsvImportPhase.COMPLETE && !controller.state.value.busy }
        assertEquals(1, writes.size)
        assertEquals(1, controller.state.value.report!!.summary.repairedAndAdded)
        compose.onNodeWithTag("csv_import_summary").performScrollTo().assertIsDisplayed()
        capture("completed-summary")
    }

    private fun capture(name: String) {
        if (InstrumentationRegistry.getArguments().getString("screenshotEvidence") != "true") return
        compose.waitForIdle()
        val editor = compose.onAllNodesWithTag("csv_import_editor").fetchSemanticsNodes().isNotEmpty()
        val bitmap = (if (editor) compose.onNodeWithTag("csv_import_editor") else compose.onRoot()).captureToImage().asAndroidBitmap()
        val resolver = InstrumentationRegistry.getInstrumentation().targetContext.contentResolver
        val values = ContentValues().apply {
            put(MediaStore.MediaColumns.DISPLAY_NAME, "$name.png")
            put(MediaStore.MediaColumns.MIME_TYPE, "image/png")
            put(MediaStore.MediaColumns.RELATIVE_PATH, "${Environment.DIRECTORY_PICTURES}/ThesaurusTestEvidence/issue112/")
            put(MediaStore.MediaColumns.IS_PENDING, 1)
        }
        val uri = requireNotNull(resolver.insert(MediaStore.Images.Media.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY), values))
        try {
            requireNotNull(resolver.openOutputStream(uri)).use { assertTrue(bitmap.compress(Bitmap.CompressFormat.PNG, 100, it)) }
            check(resolver.update(uri, ContentValues().apply { put(MediaStore.MediaColumns.IS_PENDING, 0) }, null, null) == 1)
        } catch (error: Throwable) {
            resolver.delete(uri, null, null)
            throw error
        }
    }
}
