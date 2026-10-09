package pl.bargor.thesaurus

import android.content.ContentValues
import android.graphics.Bitmap
import android.graphics.Color
import android.os.Environment
import android.provider.MediaStore
import androidx.compose.material3.Surface
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.test.SemanticsNodeInteraction
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertTextEquals
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.onRoot
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModelStore
import androidx.test.platform.app.InstrumentationRegistry
import java.time.Clock
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneOffset
import kotlin.math.ceil
import kotlin.math.floor
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.flowOf
import org.junit.Assert.assertTrue
import org.junit.Assert.assertNull
import org.junit.Rule
import org.junit.Test
import pl.bargor.thesaurus.data.firebase.*
import pl.bargor.thesaurus.data.model.*
import pl.bargor.thesaurus.ui.reports.ReportsScreen
import pl.bargor.thesaurus.ui.reports.ReportsViewModel

/** Synthetic listener transitions prove the actual ViewModel/error screen without Firebase access. */
class MalformedFinancialVisualEvidenceTest {
    @get:Rule val compose = createComposeRule()

    @Test fun validCorruptAndRepairedSnapshotsProduceUnavailableFinancialEvidence() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val store = ViewModelStore()
        val sources = Sources()
        lateinit var reports: ReportsViewModel
        instrumentation.runOnMainSync {
            reports = ReportsViewModel(sources, sources,
                Clock.fixed(Instant.parse("2026-09-30T12:00:00Z"), ZoneOffset.UTC), SavedStateHandle(), sources)
            store.put("proof", reports)
            reports.start("proof")
        }
        try {
            compose.setContent {
                val state by reports.state.collectAsState()
                ThesaurusTheme(darkTheme = false) {
                    Surface {
                        ReportsScreen(state, reports::selectPeriodMode, reports::previousPeriod, reports::nextPeriod,
                            reports::selectType, reports::updateCustomFrom, reports::updateCustomTo,
                            reports::applyCustomPeriod, {}, reports::retry)
                    }
                }
            }
            compose.waitUntil(15_000) { !reports.state.value.isLoading && reports.state.value.entries.size == 1 }
            compose.onNodeWithTag("reports-total-cards").assertIsDisplayed()
            compose.onNodeWithTag("reports-error").assertDoesNotExist()
            compose.onNodeWithTag("reports-expense", useUnmergedTree = true)
                .assertTextEquals(PlnMoney.currency(1250.toBigInteger()))
            capture("reports-valid", "reports-expense")
            compose.runOnIdle {
                sources.entries.value = SyncObservation(state = SyncState.ERROR,
                    error = FirestoreDecodeException(FirestoreDocumentType.LEDGER_ENTRY,
                        "amountGrosze", FirestoreDecodeReason.WRONG_TYPE))
            }
            compose.waitUntil(15_000) { reports.state.value.hasError && reports.state.value.entries.isEmpty() }
            compose.onNodeWithTag("reports-error").assertIsDisplayed()
            compose.onNodeWithText(instrumentation.targetContext.getString(R.string.reports_load_error)).assertIsDisplayed()
            compose.onNodeWithTag("reports-retry").assertIsDisplayed()
            compose.onNodeWithTag("reports-total-cards").assertDoesNotExist()
            compose.onNodeWithTag("reports-balance-section").assertDoesNotExist()
            compose.onNodeWithTag("report-entry-synthetic").assertDoesNotExist()
            assertTrue(reports.state.value.aggregation.totals.isEmpty)
            assertNull(reports.state.value.balanceTrend)
            capture("reports-malformed-unavailable", "reports-error")
            compose.runOnIdle { sources.entries.value = SyncObservation(listOf(sources.entry.copy(amountGrosze = -1800)), SyncState.SYNCED) }
            compose.waitUntil(15_000) { !reports.state.value.hasError && reports.state.value.entries.size == 1 }
            compose.onNodeWithTag("reports-total-cards").assertIsDisplayed()
            compose.onNodeWithTag("reports-error").assertDoesNotExist()
            assertTrue(reports.state.value.aggregation.totals.netGrosze == (-1800).toBigInteger())
            compose.onNodeWithTag("reports-expense", useUnmergedTree = true)
                .assertTextEquals(PlnMoney.currency(1800.toBigInteger()))
            capture("reports-repaired", "reports-expense")
        } finally {
            instrumentation.runOnMainSync { store.clear() }
        }
    }

    private fun capture(name: String, proofTag: String) {
        compose.waitForIdle()
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val root = compose.onRoot()
        val rootBounds = root.fetchSemanticsNode().boundsInRoot
        val header = compose.onNodeWithText(instrumentation.targetContext.getString(R.string.navigation_reports))
        val proof = compose.onNodeWithTag(proofTag, useUnmergedTree = true)
        header.assertIsDisplayed()
        proof.assertIsDisplayed()
        // Compose capture synchronizes layout/drawing and PixelCopy for this actual root.
        // Semantics alone can pass before the system screenshot has caught up to the frame.
        val bitmap = root.captureToImage().asAndroidBitmap()
        try {
            assertPaintedText(bitmap, rootBounds, header, "$name header")
            assertPaintedText(bitmap, rootBounds, proof, "$name $proofTag")
            val resolver = instrumentation.targetContext.contentResolver
            val values = ContentValues().apply {
                put(MediaStore.MediaColumns.DISPLAY_NAME, "$name.png")
                put(MediaStore.MediaColumns.MIME_TYPE, "image/png")
                put(MediaStore.MediaColumns.RELATIVE_PATH, "${Environment.DIRECTORY_PICTURES}/ThesaurusTestEvidence/issue96/")
                put(MediaStore.MediaColumns.IS_PENDING, 1)
            }
            val collection = MediaStore.Images.Media.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY)
            val image = requireNotNull(resolver.insert(collection, values)) { "CI media insertion failed: $name" }
            try {
                requireNotNull(resolver.openOutputStream(image)) { "CI media stream unavailable: $name" }.use {
                    assertTrue("PNG capture failed: $name", bitmap.compress(Bitmap.CompressFormat.PNG, 100, it))
                }
                val published = ContentValues().apply { put(MediaStore.MediaColumns.IS_PENDING, 0) }
                check(resolver.update(image, published, null, null) == 1) { "CI media publication failed: $name" }
            } catch (error: Throwable) {
                runCatching { resolver.delete(image, null, null) }
                throw error
            }
        } finally { bitmap.recycle() }
    }

    private fun assertPaintedText(bitmap: Bitmap, rootBounds: Rect, node: SemanticsNodeInteraction, label: String) {
        val bounds = node.fetchSemanticsNode().boundsInRoot
        val left = floor(bounds.left - rootBounds.left).toInt()
        val top = floor(bounds.top - rootBounds.top).toInt()
        val right = ceil(bounds.right - rootBounds.left).toInt()
        val bottom = ceil(bounds.bottom - rootBounds.top).toInt()
        assertTrue("Captured text bounds must be fully visible: $label",
            left >= 0 && top >= 0 && right <= bitmap.width && bottom <= bitmap.height && right > left && bottom > top)
        val width = right - left
        val pixels = IntArray(width * (bottom - top))
        bitmap.getPixels(pixels, 0, width, left, top, width, bottom - top)
        fun brightness(pixel: Int) = Color.red(pixel) + Color.green(pixel) + Color.blue(pixel)
        val backgroundBrightness = pixels.maxOf(::brightness)
        // Actual foreground glyph pixels must appear in the exact text bounds of the saved
        // frame; a blank or stale background cannot satisfy a visible semantics assertion.
        val glyphPixels = pixels.count { backgroundBrightness - brightness(it) >= 96 }
        assertTrue("Captured frame must paint text glyphs: $label ($glyphPixels pixels)", glyphPixels >= 20)
    }

    private class Sources : LedgerRepository, TaxonomyRepository, HouseholdRepository {
        val entry = LedgerEntry("synthetic", "proof", -1250, LocalDate.of(2026, 9, 12),
            title = "Zakupy testowe", categoryId = "food", authorId = "proof", updatedById = "proof")
        val entries = MutableStateFlow(SyncObservation(listOf(entry), SyncState.SYNCED))
        override fun observeEntries(householdId: String, includeDeleted: Boolean): Flow<SyncObservation<List<LedgerEntry>>> = entries
        override fun observeCategories(householdId: String): Flow<SyncObservation<List<Category>>> =
            flowOf(SyncObservation(listOf(Category("food", "proof", "Jedzenie", color = "amber",
                authorId = "proof", updatedById = "proof")), SyncState.SYNCED))
        override fun observeSubcategories(householdId: String, categoryId: String): Flow<SyncObservation<List<Subcategory>>> =
            flowOf(SyncObservation(emptyList(), SyncState.SYNCED))
        override fun observeHousehold(householdId: String): Flow<SyncObservation<Household>> =
            flowOf(SyncObservation(Household("proof", "Dom testowy", "proof"), SyncState.SYNCED))
        override fun observeMembers(householdId: String): Flow<SyncObservation<List<Member>>> =
            flowOf(SyncObservation(emptyList(), SyncState.SYNCED))
        override suspend fun save(entry: LedgerEntry) = Unit
        override suspend fun tombstone(householdId: String, entryId: String, actorId: String) = Unit
        override suspend fun save(category: Category) = Unit
        override suspend fun save(subcategory: Subcategory) = Unit
        override suspend fun saveHousehold(household: Household) = Unit
        override suspend fun removeMember(householdId: String, memberId: String) = Unit
    }
}
