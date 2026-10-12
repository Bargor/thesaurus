package pl.bargor.thesaurus.data.transfer

import androidx.test.ext.junit.runners.AndroidJUnit4
import com.google.firebase.auth.GoogleAuthProvider
import com.google.firebase.firestore.Source
import java.time.Instant
import java.time.LocalDate
import java.util.UUID
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.tasks.await
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import pl.bargor.thesaurus.LocalNetworkPermissionRule
import pl.bargor.thesaurus.data.firebase.*
import pl.bargor.thesaurus.data.model.*
import pl.bargor.thesaurus.testfixtures.FirebaseIntegrationFixture

/** Cross-household transfers use real exports, owner accounts, server reads and emulator rules. */
@RunWith(AndroidJUnit4::class)
class TransferEndToEndIntegrationTest {
    @get:Rule val localNetworkPermissionRule = LocalNetworkPermissionRule()
    private val today = LocalDate.of(2026, 10, 10)
    private val now = Instant.parse("2026-10-10T10:00:00Z")
    private val oldAudit = Instant.parse("2001-01-01T00:00:00Z")
    private val columns = CsvColumnMapping(date = 0, amount = 1, type = 2, title = 3,
        category = 4, subcategory = 5, tags = 6)

    @Test fun csvExportImportAcrossCleanHouseholdsPreservesActiveFinancialValues() = scenario("CsvCrossHouse") { fixture ->
        val source = source(fixture)
        val exported = json(fixture).exportCsv(source.home.id, now)
        val parsed = CsvParser.parse(exported.bytes)
        assertEquals(3, parsed.rows.size)
        val destination = home(fixture)
        csvTaxonomy(fixture, destination, source.document)
        val coordinator = csv(fixture)
        val session = coordinator.prepare(destination.id, parsed, columns)
        assertEquals(3, session.report.summary.pending)
        assertTrue(entries(fixture, destination).isEmpty())
        assertEquals(3, coordinator.commit(session).summary.added)
        val restored = exportDocument(fixture, destination)
        assertEquals(financial(source.document, activeOnly = true), financial(restored))
        assertEquals(0L, restored.openingBalanceGrosze) // CSV transfers entries only.
        assertEquals(3L, revision(fixture, destination))
        assertAudit(restored.entries, destination.uid)
        val before = state(fixture, destination)
        assertEquals(3, coordinator.commit(coordinator.prepare(destination.id, parsed, columns)).summary.duplicates)
        assertEquals(before, state(fixture, destination))
    }

    @Test fun jsonExportPreviewConfirmRestoresTaxonomyBalanceDeletedRowsAndFreshAudit() = scenario("JsonCrossHouse") { fixture ->
        val source = source(fixture)
        val bytes = json(fixture).export(source.home.id, now, "integration").bytes
        val destination = home(fixture)
        val coordinator = json(fixture)
        val before = state(fixture, destination)
        val session = coordinator.prepare(destination.id, bytes)
        assertEquals(10, session.report.newCount) // balance + 3 parents + 2 children + 4 entries.
        assertEquals(before, state(fixture, destination))
        val report = coordinator.commit(session)
        assertEquals(10, report.addedCount)
        assertEquals(0, report.errorCount)
        assertEquals(0, report.conflictCount)
        val restored = exportDocument(fixture, destination)
        assertEquals(source.document.openingBalanceGrosze, restored.openingBalanceGrosze)
        assertEquals(categories(source.document), categories(restored))
        assertEquals(children(source.document), children(restored))
        assertEquals(financial(source.document), financial(restored))
        assertEquals(4L, revision(fixture, destination))
        assertAudit(restored.entries, destination.uid)
        restored.entries.forEach { row ->
            val original = source.document.entries.single { it.title == row.title }
            assertNotEquals(original.createdAt, row.createdAt)
            assertNotEquals(original.updatedAt, row.updatedAt)
            if (row.deleted) assertNotEquals(original.deletedAt, row.deletedAt)
        }
        restored.categories.filter { it.name.startsWith("Transfer") }.forEach {
            val original = source.document.categories.single { category -> category.name == it.name }
            assertEquals(destination.uid, it.authorId)
            assertEquals(destination.uid, it.updatedById)
            assertTrue(it.createdAt!! > oldAudit)
            assertTrue(it.updatedAt!! > oldAudit)
            assertNotEquals(original.createdAt, it.createdAt)
            assertNotEquals(original.updatedAt, it.updatedAt)
        }
        restored.subcategories.filter { it.name.startsWith("Transfer") }.forEach {
            val original = source.document.subcategories.single { child -> child.name == it.name }
            assertEquals(destination.uid, it.authorId)
            assertEquals(destination.uid, it.updatedById)
            assertTrue(it.createdAt!! > oldAudit)
            assertTrue(it.updatedAt!! > oldAudit)
            assertNotEquals(original.createdAt, it.createdAt)
            assertNotEquals(original.updatedAt, it.updatedAt)
        }
        entries(fixture, destination).forEach { row ->
            assertTrue(row.get("amountGrosze") is Long)
            assertTrue(row.get("date") is String)
            assertTrue(row.get("deleted") is Boolean)
            assertTrue(row.get("tags") is List<*>)
        }
        val household = fixture.firestore.collection("households").document(destination.id).get(Source.SERVER).await()
        assertTrue(household.get("openingBalanceGrosze") is Long)
        assertTrue(household.get("ledgerRevision") is Long)
        fixture.firestore.collection("households").document(destination.id).collection("categories")
            .get(Source.SERVER).await().documents.filter { it.getString("name")!!.startsWith("Transfer") }.forEach {
                assertTrue(it.get("archived") is Boolean)
                assertTrue(it.get("color") is String)
                assertTrue(it.get("defaultEntryType") is String)
                assertTrue(it.getString("defaultEntryType") in listOf("INCOME", "EXPENSE"))
            }
        val confirmed = state(fixture, destination)
        assertEquals(0, coordinator.commit(coordinator.prepare(destination.id, bytes)).addedCount)
        assertEquals(confirmed, state(fixture, destination))
    }

    @Test fun partialCsvWritesGoodRowsThenRepairsDiscardsAndDeduplicatesWithoutRewriting() = scenario("CsvRepairDiscard") { fixture ->
        val destination = home(fixture)
        val text = "date;amount;type;title;category;subcategory;tags\n" +
            "2026-10-09;-1;expense;żółć;Jedzenie;;[]\n" +
            "2026-10-09;-1;expense;żółć;Jedzenie;;[]\n" +
            "bad;-2;expense;repair;Jedzenie;;[]\n" +
            "2026-10-09;0;expense;discard;Jedzenie;;[]"
        val parsed = CsvParser.parse(text.toByteArray())
        val coordinator = csv(fixture)
        val session = coordinator.prepare(destination.id, parsed, columns)
        val partial = coordinator.commit(session)
        assertEquals(1, partial.summary.added)
        assertEquals(1, partial.summary.duplicates)
        assertEquals(2, partial.summary.invalidRows)
        val good = entries(fixture, destination).single()
        assertEquals(1L, revision(fixture, destination))
        val repaired = coordinator.repair(session, 4,
            listOf("2026-10-09", "-2", "expense", "repair", "Jedzenie", "", "[]"))
        assertEquals(1, repaired.summary.repairedAndAdded)
        val completed = coordinator.discard(session, 5)
        assertEquals(1, completed.summary.discarded)
        assertEquals(CsvImportRowStatus.DISCARDED, completed.rows.single { it.source.rowNumber == 5 }.status)
        assertEquals("bad", completed.originalRows.single { it.rowNumber == 4 }.cells[0])
        assertEquals(good.data, entries(fixture, destination).single { it.id == good.id }.data)
        assertEquals(setOf(-100L, -200L), entries(fixture, destination).map { it.getLong("amountGrosze") }.toSet())
        assertEquals(2L, revision(fixture, destination))
        val before = state(fixture, destination)
        assertEquals(1, coordinator.commit(session).summary.discarded)
        assertEquals(2, coordinator.commit(coordinator.prepare(destination.id, parsed, columns)).summary.duplicates)
        assertEquals(before, state(fixture, destination))
    }

    @Test fun csvCrossHouseDisconnectAndNewSessionResumeNeverDuplicateAcknowledgedEntry() = scenario("CsvCrossHouseResume") { fixture ->
        val source = source(fixture)
        val parsed = CsvParser.parse(json(fixture).exportCsv(source.home.id, now).bytes)
        val destination = home(fixture)
        csvTaxonomy(fixture, destination, source.document)
        val real = FirestoreCsvImportBackend(fixture.firestore, fixture.auth)
        var disconnect = true
        val interrupted = CsvImportCoordinator(object : CsvImportBackend by real {
            override suspend fun commitEntry(snapshot: CsvImportSnapshot, entry: LedgerEntry): CsvImportCommitResult {
                val result = real.commitEntry(snapshot, entry)
                if (disconnect) { disconnect = false; fixture.disableNetwork() }
                return result
            }
        }) { today }
        val partial = interrupted.commit(interrupted.prepare(destination.id, parsed, columns))
        assertEquals(1, partial.summary.added)
        assertEquals(1, partial.summary.serverErrors)
        assertEquals(1, partial.summary.pending)
        fixture.enableNetwork()
        val acknowledged = entries(fixture, destination).single()
        assertEquals(1L, revision(fixture, destination))
        val resumed = csv(fixture).let { it.commit(it.prepare(destination.id, parsed, columns)) }
        assertEquals(2, resumed.summary.added)
        assertEquals(1, resumed.summary.duplicates)
        assertEquals(0, resumed.summary.serverErrors)
        assertEquals(acknowledged.data, entries(fixture, destination).single { it.id == acknowledged.id }.data)
        assertEquals(financial(source.document, activeOnly = true), financial(exportDocument(fixture, destination)))
        assertEquals(3L, revision(fixture, destination))
    }

    @Test fun jsonCrossHouseDisconnectFreshPreviewResumesConfirmedTaxonomyAndEntries() = scenario("JsonCrossHouseResume") { fixture ->
        val source = source(fixture)
        val bytes = json(fixture).export(source.home.id, now).bytes
        val destination = home(fixture)
        val real = FirestoreJsonBackupBackend(fixture.firestore, fixture.auth)
        var disconnect = true
        val interrupted = JsonBackupCoordinator(object : JsonBackupBackend by real {
            override suspend fun commit(snapshot: JsonBackupSnapshot, operation: JsonMergeOperation): JsonMergeWriteResult {
                val result = real.commit(snapshot, operation)
                if (disconnect && operation is JsonMergeOperation.NewEntry) {
                    disconnect = false
                    fixture.disableNetwork()
                }
                return result
            }
        }) { today }
        val partial = interrupted.commit(interrupted.prepare(destination.id, bytes))
        assertEquals(7, partial.addedCount)
        assertEquals(1, partial.errorCount)
        assertEquals(2, partial.newCount)
        fixture.enableNetwork()
        val acknowledged = entries(fixture, destination).single()
        assertEquals(1L, revision(fixture, destination))
        val coordinator = json(fixture)
        val resumed = coordinator.commit(coordinator.prepare(destination.id, bytes))
        assertEquals(3, resumed.addedCount)
        assertEquals(0, resumed.errorCount)
        assertEquals(0, resumed.conflictCount)
        assertEquals(acknowledged.data, entries(fixture, destination).single { it.id == acknowledged.id }.data)
        val restored = exportDocument(fixture, destination)
        assertEquals(financial(source.document), financial(restored))
        assertEquals(categories(source.document), categories(restored))
        assertEquals(children(source.document), children(restored))
        assertEquals(source.document.openingBalanceGrosze, restored.openingBalanceGrosze)
        assertEquals(4L, revision(fixture, destination))
    }

    @Test fun malformedFilesAndOfflinePreparationLeaveDestinationExactlyUnchanged() = scenario("TransferFileGates") { fixture ->
        val source = source(fixture)
        val coordinator = json(fixture)
        val jsonBytes = coordinator.export(source.home.id, now).bytes
        val csvBytes = coordinator.exportCsv(source.home.id, now).bytes
        val destination = home(fixture)
        csvTaxonomy(fixture, destination, source.document)
        val before = state(fixture, destination)
        assertTrue(runCatching { coordinator.prepare(destination.id, jsonBytes.copyOf(jsonBytes.size / 2)) }.isFailure)
        assertTrue(runCatching { CsvParser.parse("date,amount\n\"unterminated".toByteArray()) }.isFailure)
        assertTrue(runCatching { coordinator.prepare(destination.id, byteArrayOf(0xC3.toByte(), 0x28)) }.isFailure)
        assertEquals(before, state(fixture, destination))
        fixture.disableNetwork()
        assertTrue(runCatching { coordinator.prepare(destination.id, jsonBytes) }.isFailure)
        assertTrue(runCatching { csv(fixture).prepare(destination.id, CsvParser.parse(csvBytes), columns) }.isFailure)
        fixture.enableNetwork()
        fixture.firestore.waitForPendingWrites().await()
        assertEquals(before, state(fixture, destination))
    }

    @Test fun memberCanExportButCannotConfirmOwnerPreviewsOrCreateImportTaxonomy() = scenario("TransferMemberGates") { fixture ->
        val source = source(fixture)
        val bytes = json(fixture).export(source.home.id, now).bytes
        val parsed = CsvParser.parse(json(fixture).exportCsv(source.home.id, now).bytes)
        val destination = home(fixture)
        csvTaxonomy(fixture, destination, source.document)
        val json = json(fixture)
        val csv = csv(fixture)
        val jsonPreview = json.prepare(destination.id, bytes)
        val csvPreview = csv.prepare(destination.id, parsed, columns)
        val before = state(fixture, destination)
        val email = "transfer-member-${UUID.randomUUID()}@example.test"
        val invitation = Invitation(UUID.randomUUID().toString(), destination.id, email, destination.uid, Instant.now().plusSeconds(86_400))
        val repository = FirestoreRepositories(fixture.firestore)
        repository.create(invitation)
        fixture.auth.signOut()
        val token = """{"sub":"${UUID.randomUUID()}","email":"$email","email_verified":true}"""
        val member = fixture.auth.signInWithCredential(GoogleAuthProvider.getCredential(token, null)).await().user!!.uid
        repository.accept(invitation, Member(member, email, "Member", MemberRole.MEMBER, invitation.id))
        assertTrue(BackupJsonCodec.decode(json.export(destination.id, now).bytes).entries.isEmpty())
        assertTrue(CsvParser.parse(json.exportCsv(destination.id, now).bytes).rows.isEmpty())
        assertTrue(runCatching { json.prepare(destination.id, bytes) }.isFailure)
        assertTrue(runCatching { csv.prepare(destination.id, parsed, columns) }.isFailure)
        assertTrue(runCatching { json.commit(jsonPreview) }.isFailure)
        assertTrue(runCatching { csv.commit(csvPreview) }.isFailure)
        assertTrue(runCatching { csv.createCategory(csvPreview, "Forbidden") }.isFailure)
        assertEquals(before, state(fixture, destination))
        signIn(fixture, destination)
        assertEquals(3, csv.commit(csv.prepare(destination.id, parsed, columns)).summary.added)
        assertEquals(3L, revision(fixture, destination))
    }

    private fun scenario(label: String, block: suspend (FirebaseIntegrationFixture) -> Unit) = runBlocking {
        val fixture = FirebaseIntegrationFixture.open(label)
        try { fixture.scenario(label) { block(fixture) } } finally { fixture.close() }
    }

    private data class Home(val id: String, val uid: String, val email: String)
    private data class SourceHome(val home: Home, val document: BackupDocument)
    private suspend fun home(fixture: FirebaseIntegrationFixture): Home {
        fixture.auth.signOut()
        val email = "transfer-${UUID.randomUUID()}@example.test"
        val uid = fixture.auth.createUserWithEmailAndPassword(email, "test-password-123").await().user!!.uid
        val result = FirestoreRepositories(fixture.firestore).createFirstHousehold(OnboardingIdentity(uid, email, "Transfer owner"), "Transfer house")
        return Home((result as FirstHouseholdResult.Created).householdId, uid, email)
    }

    private suspend fun signIn(fixture: FirebaseIntegrationFixture, home: Home) {
        fixture.auth.signOut()
        fixture.auth.signInWithEmailAndPassword(home.email, "test-password-123").await()
    }

    private fun json(fixture: FirebaseIntegrationFixture) = JsonBackupCoordinator(FirestoreJsonBackupBackend(fixture.firestore, fixture.auth)) { today }
    private fun csv(fixture: FirebaseIntegrationFixture) = CsvImportCoordinator(FirestoreCsvImportBackend(fixture.firestore, fixture.auth)) { today }

    private suspend fun source(fixture: FirebaseIntegrationFixture): SourceHome {
        val home = home(fixture)
        val input = BackupDocument(ExportMetadata(oldAudit, "old-foreign-house"), -9_007_199_254_740_991L,
            listOf(
                BackupCategory("expense", "Transfer Żywność, \"dom\"", "old", "old", "mint", createdAt = oldAudit, updatedAt = oldAudit),
                BackupCategory("income", "Transfer Przychód", "old", "old", "blue", defaultEntryType = TransferEntryType.INCOME),
                BackupCategory("archive", "Transfer Archiwum", "old", "old", "rose", archived = true)),
            listOf(BackupSubcategory("child", "expense", "Transfer Ćwikła, \"słoik\"", "old", "old"),
                BackupSubcategory("archived-child", "archive", "Transfer Zamknięte", "old", "old", archived = true)),
            listOf(
                BackupEntry("a", -9_007_199_254_740_993L, today.minusDays(3), "expense", "old", "old",
                    "Łódź, \"zakupy\"\n第二行", "child", listOf("żółć", "a,b", "quote\"tag"), oldAudit, oldAudit),
                BackupEntry("b", 9_007_199_254_740_991L, today.minusDays(2), "income", "old", "old"),
                BackupEntry("c", -101, today.minusDays(1), "expense", "old", "old", "mała kwota", tags = listOf("tag")),
                BackupEntry("d", -777, today.minusDays(4), "archive", "old", "old", "usunięty", "archived-child",
                    createdAt = oldAudit, updatedAt = oldAudit, deleted = true, deletedAt = oldAudit, deletedById = "old")))
        val coordinator = json(fixture)
        val report = coordinator.commit(coordinator.prepare(home.id, BackupJsonCodec.encode(input)))
        assertEquals(10, report.addedCount)
        assertEquals(0, report.errorCount)
        return SourceHome(home, exportDocument(fixture, home))
    }

    private suspend fun csvTaxonomy(fixture: FirebaseIntegrationFixture, home: Home, source: BackupDocument) {
        val repository = FirestoreRepositories(fixture.firestore)
        val parentIds = source.entries.filterNot { it.deleted }.map { it.categoryId }.toSet()
        source.categories.filter { it.id in parentIds }.forEach {
            repository.save(Category(it.id, home.id, it.name, it.color, false, EntryType.valueOf(it.defaultEntryType.name), home.uid, home.uid))
        }
        source.subcategories.filter { it.categoryId in parentIds && !it.archived }.forEach {
            repository.save(Subcategory(it.id, home.id, it.categoryId, it.name, authorId = home.uid, updatedById = home.uid))
        }
    }

    private suspend fun exportDocument(fixture: FirebaseIntegrationFixture, home: Home) = BackupJsonCodec.decode(json(fixture).export(home.id, now).bytes)
    private suspend fun entries(fixture: FirebaseIntegrationFixture, home: Home) = fixture.firestore.collection("households").document(home.id)
        .collection("entries").get(Source.SERVER).await().documents
    private suspend fun revision(fixture: FirebaseIntegrationFixture, home: Home) = fixture.firestore.collection("households").document(home.id)
        .get(Source.SERVER).await().getLong("ledgerRevision")!!

    /** Include raw values and timestamps so a replay or rejected preview cannot hide metadata writes. */
    private suspend fun state(fixture: FirebaseIntegrationFixture, home: Home): Map<String, Map<String, Any>?> {
        val root = fixture.firestore.collection("households").document(home.id)
        val documents = mutableListOf(root.get(Source.SERVER).await())
        documents += root.collection("entries").get(Source.SERVER).await().documents
        val parents = root.collection("categories").get(Source.SERVER).await().documents
        documents += parents
        parents.forEach { documents += it.reference.collection("subcategories").get(Source.SERVER).await().documents }
        return documents.associate { it.reference.path to it.data }
    }

    private fun categories(document: BackupDocument) = document.categories.map {
        listOf(it.name, it.color, it.archived, it.defaultEntryType)
    }.sortedBy { it[0].toString() }
    private fun children(document: BackupDocument) = document.subcategories.map {
        listOf(document.categories.single { category -> category.id == it.categoryId }.name, it.name, it.archived)
    }.sortedBy { it.toString() }
    private fun financial(document: BackupDocument, activeOnly: Boolean = false) = document.entries.filter { !activeOnly || !it.deleted }.map { entry ->
        listOf(entry.amountGrosze, entry.date, entry.title, document.categories.single { it.id == entry.categoryId }.name,
            entry.subcategoryId?.let { id -> document.subcategories.single { it.categoryId == entry.categoryId && it.id == id }.name },
            entry.tags, entry.deleted)
    }.sortedBy { it.toString() }

    private fun assertAudit(entries: List<BackupEntry>, owner: String) = entries.forEach {
        assertEquals(owner, it.authorId)
        assertEquals(owner, it.updatedById)
        assertTrue(it.createdAt!! > oldAudit)
        assertTrue(it.updatedAt!! > oldAudit)
        if (it.deleted) {
            assertEquals(owner, it.deletedById)
            assertTrue(it.deletedAt!! > oldAudit)
        } else {
            assertNull(it.deletedById)
            assertNull(it.deletedAt)
        }
    }
}
