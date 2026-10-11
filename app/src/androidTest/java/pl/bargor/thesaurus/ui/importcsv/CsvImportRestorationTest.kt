package pl.bargor.thesaurus.ui.importcsv

import android.content.ContextWrapper
import androidx.test.platform.app.InstrumentationRegistry
import java.io.File
import java.time.LocalDate
import java.util.UUID
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test
import pl.bargor.thesaurus.data.model.*
import pl.bargor.thesaurus.data.transfer.*

class CsvImportRestorationTest {
    private fun parsed(vararg rows: String) = CsvParser.parse(
        ("date;amount;category;title\n" + rows.joinToString("\n")).toByteArray())
    private fun subject(backend: Backend, store: CsvImportCheckpointStore) =
        CsvImportController(CsvImportCoordinator(backend) { LocalDate.of(2026, 10, 10) }, store)
    private fun privateContext(): ContextWrapper {
        val target = InstrumentationRegistry.getInstrumentation().targetContext
        val directory = File(target.cacheDir, "issue112-restore-${UUID.randomUUID()}").apply { mkdirs() }
        return object : ContextWrapper(target) { override fun getNoBackupFilesDir() = directory }
    }

    @Test fun reconstructedControllerReadsPrivateCheckpointRetainsDraftAndDoesNotRepeatCompletedWrites() = runBlocking {
        val context = privateContext()
        try {
            val backend = Backend()
            val first = subject(backend, PrivateCsvImportCheckpointStore(context))
            first.loadParsed("house", parsed("2026-10-09;-1;Food;Completed", "bad;-2;Food;Original private draft"))
            first.start()
            first.editRow(3, listOf("2026-10-09", "-2", "Food", "Retained private draft"))
            val token = requireNotNull(first.state.value.checkpointToken)
            // Reconstruct both store and controller; no in-memory session or state is reused.
            val restored = subject(backend, PrivateCsvImportCheckpointStore(context))
            restored.restore(token, "house", "owner")
            assertEquals(listOf("2026-10-09", "-2", "Food", "Retained private draft"), restored.state.value.drafts[3])
            assertEquals(1, backend.writes.size)
            restored.revalidate(3)
            assertEquals(1, backend.writes.size)
            restored.saveRepair(3)
            assertEquals(2, backend.writes.size)
            assertEquals(2, backend.writes.map { it.id }.distinct().size)
            assertEquals(1, restored.state.value.report!!.summary.added)
            assertEquals(1, restored.state.value.report!!.summary.repairedAndAdded)
        } finally { context.noBackupFilesDir.deleteRecursively() }
    }

    @Test fun killedAfterServerCommitRequiresExplicitResumeAndDeduplicatesUnknownOutcome() = runBlocking {
        val context = privateContext()
        try {
            val backend = Backend().apply { interruptAfterWrite = true }
            val first = subject(backend, PrivateCsvImportCheckpointStore(context))
            first.loadParsed("house", parsed("2026-10-09;-1;Food;One", "2026-10-09;-2;Food;Two"))
            val token = requireNotNull(first.state.value.checkpointToken)
            try { first.start() } catch (_: CancellationException) { }
            assertEquals(1, backend.writes.size)
            backend.interruptAfterWrite = false
            val restored = subject(backend, PrivateCsvImportCheckpointStore(context))
            restored.restore(token, "house", "owner")
            assertEquals(CsvImportPhase.RESTORED, restored.state.value.phase)
            assertEquals(1, backend.writes.size)
            restored.resume()
            assertEquals(2, backend.writes.size)
            assertEquals(1, restored.state.value.report!!.summary.duplicates)
            assertEquals(1, restored.state.value.report!!.summary.added)
        } finally { context.noBackupFilesDir.deleteRecursively() }
    }

    @Test fun corruptCheckpointAndForeignPrincipalExposeOnlySafeCodes() = runBlocking {
        val context = privateContext()
        try {
            val backend = Backend()
            val original = subject(backend, PrivateCsvImportCheckpointStore(context))
            original.loadParsed("house", parsed("bad;-1;Food;SECRET source"))
            val token = requireNotNull(original.state.value.checkpointToken)
            val foreign = subject(backend, PrivateCsvImportCheckpointStore(context))
            foreign.restore(token, "house", "different")
            assertNull(foreign.state.value.report)
            assertFalse(foreign.state.value.errorCode.orEmpty().contains("SECRET"))
            File(context.noBackupFilesDir, "csv-import-checkpoints/$token.json").writeText("SECRET broken JSON {")
            val corrupted = subject(backend, PrivateCsvImportCheckpointStore(context))
            corrupted.restore(token, "house", "owner")
            assertNull(corrupted.state.value.report)
            assertNotNull(corrupted.state.value.errorCode)
            assertFalse(corrupted.state.value.errorCode.orEmpty().contains("SECRET"))
            assertTrue(backend.writes.isEmpty())
        } finally { context.noBackupFilesDir.deleteRecursively() }
    }

    @Test fun completedOutcomesRemainTerminalAfterLegitimateLedgerEditsAndTaxonomyRename() = runBlocking {
        val context = privateContext()
        try {
            val backend = Backend()
            val original = subject(backend, PrivateCsvImportCheckpointStore(context))
            original.loadParsed("house", parsed("2026-10-09;-1;Food;Original", "2026-10-09;-1;Food;Original"))
            original.start()
            assertEquals(1, backend.commitCalls)
            assertEquals(CsvImportPhase.COMPLETE, original.state.value.phase)
            val token = requireNotNull(original.state.value.checkpointToken)
            // Normal post-import editing makes the original fingerprint disappear.
            backend.writes[0] = backend.writes[0].copy(amountGrosze = -900, title = "Legitimately edited")
            backend.categoryName = "Renamed Food"
            val restored = subject(backend, PrivateCsvImportCheckpointStore(context))
            restored.restore(token, "house", "owner")
            assertEquals(CsvImportPhase.COMPLETE, restored.state.value.phase)
            assertEquals(listOf(CsvImportRowStatus.ADDED, CsvImportRowStatus.DUPLICATE), restored.state.value.report!!.rows.map { it.status })
            restored.resume()
            restored.start()
            assertEquals(1, backend.commitCalls)
            assertEquals(-900L, backend.writes.single().amountGrosze)
            assertEquals("Legitimately edited", backend.writes.single().title)
        } finally { context.noBackupFilesDir.deleteRecursively() }
    }

    @Test fun malformedUtf8InsideOtherwiseValidCheckpointIsRejectedWithoutPartialState() = runBlocking {
        val context = privateContext()
        try {
            val backend = Backend()
            val original = subject(backend, PrivateCsvImportCheckpointStore(context))
            original.loadParsed("house", parsed("bad;-1;Food;SECRET source"))
            val token = requireNotNull(original.state.value.checkpointToken)
            val file = File(context.noBackupFilesDir, "csv-import-checkpoints/$token.json")
            val bytes = file.readBytes()
            val text = String(bytes, Charsets.UTF_8)
            val offset = text.indexOf("SECRET source")
            assertTrue(offset >= 0)
            // Fixture prefix is ASCII, so character and byte offset are equal.
            bytes[offset] = 0xc3.toByte()
            bytes[offset + 1] = 0x28
            file.writeBytes(bytes)
            val restored = subject(backend, PrivateCsvImportCheckpointStore(context))
            restored.restore(token, "house", "owner")
            assertNull(restored.state.value.report)
            assertNotNull(restored.state.value.errorCode)
            assertFalse(restored.state.value.errorCode.orEmpty().contains("SECRET"))
            assertTrue(backend.writes.isEmpty())
        } finally { context.noBackupFilesDir.deleteRecursively() }
    }

    @Test fun unsupportedCheckpointVersionAndOversizedJsonScalarAreRejectedBeforePublishingRows() = runBlocking {
        val context = privateContext()
        try {
            val backend = Backend()
            val original = subject(backend, PrivateCsvImportCheckpointStore(context))
            original.loadParsed("house", parsed("bad;-1;Food;SECRET source"))
            val token = requireNotNull(original.state.value.checkpointToken)
            val file = File(context.noBackupFilesDir, "csv-import-checkpoints/$token.json")
            val valid = file.readText()
            assertTrue(valid.contains("\"formatVersion\":1"))
            for (corrupt in listOf(valid.replace("\"formatVersion\":1", "\"formatVersion\":999"),
                valid.replace("SECRET source", "x".repeat(BackupLimits.MAX_RAW_FIELD_CHARS + 1)))) {
                file.writeText(corrupt)
                val restored = subject(backend, PrivateCsvImportCheckpointStore(context))
                restored.restore(token, "house", "owner")
                assertNull(restored.state.value.report)
                assertNotNull(restored.state.value.errorCode)
                assertFalse(restored.state.value.errorCode.orEmpty().contains("SECRET"))
            }
            assertTrue(backend.writes.isEmpty())
        } finally { context.noBackupFilesDir.deleteRecursively() }
    }

    private class Backend : CsvImportBackend {
        val identity = CsvImportIdentity("owner", "session")
        var household = Household("house", "Synthetic", "owner")
        val writes = mutableListOf<LedgerEntry>()
        var commitCalls = 0
        var categoryName = "Food"
        var interruptAfterWrite = false
        override fun currentIdentity() = identity
        override suspend fun loadSnapshot(householdId: String, expectedIdentity: CsvImportIdentity) =
            CsvImportSnapshot(identity, household, writes.toList(),
                listOf(Category("food", "house", categoryName, authorId = "owner", updatedById = "owner")), emptyList())
        override suspend fun commitEntry(snapshot: CsvImportSnapshot, entry: LedgerEntry): CsvImportCommitResult {
            commitCalls++
            writes += entry
            household = household.copy(ledgerRevision = household.ledgerRevision + 1)
            if (interruptAfterWrite) throw CancellationException("process killed after acknowledged server write")
            return CsvImportCommitResult(household.ledgerRevision, true)
        }
    }
}
