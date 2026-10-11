package pl.bargor.thesaurus.data.transfer

import java.time.Instant
import java.time.LocalDate
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test
import pl.bargor.thesaurus.data.model.*

/** Synthetic server snapshots: no Firebase connection and no local ledger mutations. */
class DataTransferReadinessTest {
    @Test fun ownerPreflightIsReadOnlyAndChecksAnAuthorizedSynchronizedSnapshot() = runBlocking {
        val backend = Backend()
        JsonBackupCoordinator(backend).checkImportReadiness("house")
        assertEquals(listOf(true), backend.ownerRequests)
        assertEquals(0, backend.writes)
        for (broken in listOf(
            Backend().apply { identity = CsvImportIdentity("member", "session") },
            Backend().apply { synchronized = false },
            Backend().apply { members = setOf("member") },
            Backend().apply { offline = true },
        )) {
            assertTrue(runCatching { JsonBackupCoordinator(broken).checkImportReadiness("house") }.isFailure)
            assertEquals(0, broken.writes)
        }
    }

    @Test fun membersCanExportBothFormatsButCannotImportAndExportsExcludeAccountData() = runBlocking {
        val backend = Backend().apply { identity = CsvImportIdentity("member", "session") }
        val coordinator = JsonBackupCoordinator(backend)
        val time = Instant.parse("2026-10-11T12:00:00Z")
        val json = coordinator.export("house", time)
        val csv = coordinator.exportCsv("house", time)
        assertTrue(json.suggestedFilename.endsWith("2026-10-11.json"))
        assertTrue(csv.suggestedFileName.endsWith("2026-10-11.csv"))
        assertEquals(listOf(false, false), backend.ownerRequests)
        assertTrue(json.bytes.toString(Charsets.UTF_8).contains("Synthetic title"))
        assertTrue(csv.bytes.toString(Charsets.UTF_8).contains("Synthetic title"))
        for (text in listOf(json.bytes, csv.bytes).map { it.toString(Charsets.UTF_8) }) {
            assertFalse(text.contains("DO-NOT-EXPORT-HOUSEHOLD-NAME"))
            assertFalse(text.contains("session"))
            assertFalse(text.contains("email"))
        }
        assertTrue(runCatching { coordinator.checkImportReadiness("house") }.isFailure)
        assertEquals(0, backend.writes)
    }

    private class Backend : JsonBackupBackend {
        var identity: CsvImportIdentity? = CsvImportIdentity("owner", "session")
        var synchronized = true
        var offline = false
        var members = setOf("owner", "member")
        var writes = 0
        val ownerRequests = mutableListOf<Boolean>()
        override fun currentIdentity() = identity
        override suspend fun loadSnapshot(householdId: String, expectedIdentity: CsvImportIdentity, requireOwner: Boolean): JsonBackupSnapshot {
            ownerRequests += requireOwner
            if (offline) error("offline")
            return JsonBackupSnapshot(expectedIdentity,
                Household("house", "DO-NOT-EXPORT-HOUSEHOLD-NAME", "owner"),
                listOf(LedgerEntry("entry", "house", -123, LocalDate.of(2026, 10, 10), categoryId = "food",
                    authorId = "owner", updatedById = "owner", title = "Synthetic title")),
                listOf(Category("food", "house", "Food", "mint", authorId = "owner", updatedById = "owner")),
                emptyList(), members, synchronized)
        }
        override suspend fun commit(snapshot: JsonBackupSnapshot, operation: JsonMergeOperation): JsonMergeWriteResult {
            writes++
            error("Read-only test must never commit")
        }
    }
}
