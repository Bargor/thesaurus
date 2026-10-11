package pl.bargor.thesaurus.ui.backupjson

import java.time.Instant
import java.time.LocalDate
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test
import pl.bargor.thesaurus.data.model.*
import pl.bargor.thesaurus.data.transfer.*

class JsonBackupControllerTest {
    private val today = LocalDate.of(2026, 10, 10)
    private val now = Instant.parse("2026-10-10T10:00:00Z")
    private fun bytes() = BackupJsonCodec.encode(BackupDocument(ExportMetadata(now, "source"), 0,
        listOf(BackupCategory("food", "Food", "foreign", "foreign", "mint")), entries = listOf(
            BackupEntry("one", -100, today, "food", "foreign", "foreign"),
            BackupEntry("two", -200, today, "food", "foreign", "foreign"))))
    private fun controller(backend: Backend) = JsonBackupController(JsonBackupCoordinator(backend) { today })

    @Test fun previewNeverWritesAndExplicitConfirmReportsExactProgressAndIgnoresRepeatConfirm() = runBlocking {
        val backend = Backend()
        val subject = controller(backend)
        subject.prepare("house", bytes())
        assertEquals(JsonBackupPhase.PREVIEW, subject.state.value.phase)
        assertEquals(3, subject.state.value.total)
        assertEquals(3, subject.state.value.report!!.newCount)
        assertTrue(backend.writes.isEmpty())
        subject.confirm()
        assertEquals(JsonBackupPhase.COMPLETE, subject.state.value.phase)
        assertEquals(3, subject.state.value.processed)
        assertEquals(3, subject.state.value.report!!.addedCount)
        assertFalse(subject.state.value.busy)
        subject.confirm()
        assertEquals(3, backend.writes.size)
    }

    @Test fun partialRetryPreparesFreshPreviewAndRequiresSecondConfirmation() = runBlocking {
        val backend = Backend().apply { fail = true }
        val subject = controller(backend)
        subject.prepare("house", bytes())
        subject.confirm()
        assertEquals(1, subject.state.value.report!!.addedCount)
        assertEquals(1, subject.state.value.report!!.errorCount)
        assertEquals(1, subject.state.value.report!!.newCount)
        assertEquals("JSON_IMPORT_WRITE_FAILED", subject.state.value.errorCode)
        backend.fail = false
        val readCount = backend.reads
        subject.retry()
        assertTrue(backend.reads > readCount)
        assertEquals(JsonBackupPhase.PREVIEW, subject.state.value.phase)
        assertEquals(1, backend.writes.size)
        assertEquals(2, subject.state.value.total)
        subject.confirm()
        assertEquals(3, backend.writes.size)
        assertEquals(2, subject.state.value.report!!.addedCount)
    }

    @Test fun stalePreviewShowsSafeErrorAndRetryDoesNotCommitAutomatically() = runBlocking {
        val backend = Backend()
        val subject = controller(backend)
        subject.prepare("house", bytes())
        backend.household = backend.household.copy(openingBalanceGrosze = 10)
        subject.confirm()
        assertEquals("JSON_PREVIEW_STALE", subject.state.value.errorCode)
        assertTrue(backend.writes.isEmpty())
        subject.retry()
        assertEquals(JsonBackupPhase.PREVIEW, subject.state.value.phase)
        assertEquals(1, subject.state.value.report!!.conflictCount)
        assertTrue(backend.writes.isEmpty())
    }

    @Test fun invalidInputShowsOnlyContractCodeAndCannotConfirm() = runBlocking {
        val backend = Backend()
        val subject = controller(backend)
        subject.prepare("house", "SECRET private source".toByteArray())
        assertEquals(JsonBackupPhase.EMPTY, subject.state.value.phase)
        assertEquals("MALFORMED_JSON", subject.state.value.errorCode)
        assertFalse(subject.state.value.toString().contains("SECRET"))
        subject.confirm()
        assertTrue(backend.writes.isEmpty())
    }

    @Test fun retainedInputIsDefensivelyCopiedAndRetryKeepsPreviewReadOnly() = runBlocking {
        val backend = Backend()
        val subject = controller(backend)
        val input = bytes()
        subject.prepare("house", input)
        input.fill(0)
        subject.retry()
        assertEquals(JsonBackupPhase.PREVIEW, subject.state.value.phase)
        assertNull(subject.state.value.errorCode)
        assertTrue(backend.writes.isEmpty())
    }

    @Test fun exportReturnsWholeValidatedArtifactWithoutWriting() = runBlocking {
        val backend = Backend()
        val subject = controller(backend)
        val artifact = subject.export("house", now, "test")!!
        assertSame(artifact, subject.state.value.exportedArtifact)
        assertEquals(ExportMetadata(now, "house", "test"), BackupJsonCodec.decode(artifact.bytes).metadata)
        assertFalse(subject.state.value.busy)
        assertTrue(backend.writes.isEmpty())
    }

    private class Backend : JsonBackupBackend {
        var household = Household("house", "Synthetic", "owner")
        val identity = CsvImportIdentity("owner", "session")
        val categories = mutableListOf<Category>()
        val entries = mutableListOf<LedgerEntry>()
        val writes = mutableListOf<JsonMergeOperation>()
        var reads = 0
        var fail = false
        override fun currentIdentity() = identity
        override suspend fun loadSnapshot(householdId: String, expectedIdentity: CsvImportIdentity, requireOwner: Boolean): JsonBackupSnapshot {
            reads++
            return JsonBackupSnapshot(identity, household, entries.toList(), categories.toList(), emptyList(), setOf("owner"))
        }
        override suspend fun commit(snapshot: JsonBackupSnapshot, operation: JsonMergeOperation): JsonMergeWriteResult {
            if (fail && operation is JsonMergeOperation.NewEntry) error("SECRET private source")
            writes += operation
            when (operation) {
                is JsonMergeOperation.NewCategory -> categories += operation.category
                is JsonMergeOperation.NewEntry -> { entries += operation.entry; household = household.copy(ledgerRevision = household.ledgerRevision + 1) }
                else -> error("Unexpected operation")
            }
            return JsonMergeWriteResult(household.ledgerRevision, JsonMergeWriteOutcome.ADDED)
        }
    }
}
