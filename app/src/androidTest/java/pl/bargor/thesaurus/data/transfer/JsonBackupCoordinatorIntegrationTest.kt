package pl.bargor.thesaurus.data.transfer

import androidx.test.ext.junit.runners.AndroidJUnit4
import com.google.firebase.firestore.Source
import com.google.firebase.auth.GoogleAuthProvider
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

/** Uses only the named fixture app and isolated demo emulator selected by runner policy. */
@RunWith(AndroidJUnit4::class)
class JsonBackupCoordinatorIntegrationTest {
    @get:Rule val localNetworkPermissionRule = LocalNetworkPermissionRule()
    private val today = LocalDate.of(2026, 10, 10)
    private val now = Instant.parse("2026-10-10T10:00:00Z")
    private fun document(archived: Boolean = false, balance: Long = 0, rows: Int = 2): BackupDocument =
        BackupDocument(ExportMetadata(now, "foreign-house"), balance,
            listOf(BackupCategory("source-category", "Imported", "foreign", "foreign", "mint", archived)),
            listOf(BackupSubcategory("source-child", "source-category", "Imported child", "foreign", "foreign", archived)),
            (1..rows).map { BackupEntry("source-$it", -100L * it, today.minusDays(1), "source-category", "foreign", "foreign",
                "Entry $it", "source-child", listOf("tag"), deleted = it == 2, deletedById = "foreign".takeIf { _ -> it == 2 }) })

    @Test fun realRulesRoundtripIncludesArchivedParentsDeletedEntriesAndOpeningBalance() = runBlocking {
        val fixture = FirebaseIntegrationFixture.open("JsonBackupRoundtrip")
        try { fixture.scenario("archived ledger backup") {
            val home = createHome(fixture)
            val coordinator = JsonBackupCoordinator(FirestoreJsonBackupBackend(fixture.firestore, fixture.auth)) { today }
            val bytes = BackupJsonCodec.encode(document(archived = true, balance = -12345))
            val session = coordinator.prepare(home.id, bytes)
            assertTrue(serverEntries(fixture, home.id).isEmpty())
            val report = coordinator.commit(session)
            assertEquals(5, report.addedCount)
            assertEquals(0, report.errorCount)
            assertEquals(2L, serverHousehold(fixture, home.id).getLong("ledgerRevision"))
            assertEquals(-12345L, serverHousehold(fixture, home.id).getLong("openingBalanceGrosze"))
            val exported = BackupJsonCodec.decode(coordinator.export(home.id, now).bytes)
            val importedCategory = exported.categories.single { it.name == "Imported" }
            val importedChild = exported.subcategories.single { it.name == "Imported child" }
            assertTrue(importedCategory.archived)
            assertTrue(importedChild.archived)
            assertEquals(importedCategory.id, importedChild.categoryId)
            assertEquals(2, exported.entries.size)
            for (entry in exported.entries) {
                assertEquals(importedCategory.id, entry.categoryId)
                assertEquals(importedChild.id, entry.subcategoryId)
                assertEquals(home.uid, entry.authorId)
                assertEquals(home.uid, entry.updatedById)
                assertNotNull(entry.createdAt)
                assertNotNull(entry.updatedAt)
            }
            assertNotNull(exported.entries.single { it.deleted }.deletedAt)
            assertEquals(home.uid, exported.entries.single { it.deleted }.deletedById)
            val repeat = coordinator.commit(coordinator.prepare(home.id, bytes))
            assertEquals(0, repeat.addedCount)
            assertEquals(2, serverEntries(fixture, home.id).size)
            assertEquals(2L, serverHousehold(fixture, home.id).getLong("ledgerRevision"))
        } } finally { fixture.close() }
    }

    @Test fun staleBalanceAndRevisionPreviewCannotWriteAndExistingDocumentsStayIdentical() = runBlocking {
        val fixture = FirebaseIntegrationFixture.open("JsonBackupStale")
        try { fixture.scenario("stale balance and ledger fences") {
            val home = createHome(fixture)
            val repository = FirestoreRepositories(fixture.firestore)
            val coordinator = JsonBackupCoordinator(FirestoreJsonBackupBackend(fixture.firestore, fixture.auth)) { today }
            val householdRef = fixture.firestore.collection("households").document(home.id)
            val balancePreview = coordinator.prepare(home.id, BackupJsonCodec.encode(document(balance = 200)))
            householdRef.update(mapOf("openingBalanceGrosze" to 100L, "updatedAt" to com.google.firebase.firestore.FieldValue.serverTimestamp())).await()
            assertTrue(runCatching { coordinator.commit(balancePreview) }.isFailure)
            assertTrue(serverEntries(fixture, home.id).isEmpty())
            val revisionPreview = coordinator.prepare(home.id, BackupJsonCodec.encode(document()))
            repository.save(LedgerEntry("existing", home.id, -999, today.minusDays(1), "Existing", "jedzenie", authorId = home.uid, updatedById = home.uid))
            val before = serverEntries(fixture, home.id).single().data
            assertTrue(runCatching { coordinator.commit(revisionPreview) }.isFailure)
            assertEquals(before, serverEntries(fixture, home.id).single().data)
            val fresh = coordinator.commit(coordinator.prepare(home.id, BackupJsonCodec.encode(document())))
            assertEquals(1, fresh.conflictCount)
            assertEquals(100L, serverHousehold(fixture, home.id).getLong("openingBalanceGrosze"))
            assertEquals(before, serverEntries(fixture, home.id).single { it.id == "existing" }.data)
        } } finally { fixture.close() }
    }

    @Test fun disconnectAfterOneEntryReportsConfirmedPartialCountsAndFreshRetryCompletes() = runBlocking {
        val fixture = FirebaseIntegrationFixture.open("JsonBackupPartial")
        try { fixture.scenario("offline partial backup merge") {
            val home = createHome(fixture)
            val real = FirestoreJsonBackupBackend(fixture.firestore, fixture.auth)
            var disconnect = true
            val backend = object : JsonBackupBackend by real {
                override suspend fun commit(snapshot: JsonBackupSnapshot, operation: JsonMergeOperation): JsonMergeWriteResult {
                    val result = real.commit(snapshot, operation)
                    if (disconnect && operation is JsonMergeOperation.NewEntry) {
                        disconnect = false
                        fixture.disableNetwork()
                    }
                    return result
                }
            }
            val coordinator = JsonBackupCoordinator(backend) { today }
            val bytes = BackupJsonCodec.encode(document(rows = 3))
            val report = coordinator.commit(coordinator.prepare(home.id, bytes))
            assertEquals(3, report.addedCount)
            assertEquals(1, report.errorCount)
            assertEquals(1, report.newCount)
            fixture.enableNetwork()
            assertEquals(1, serverEntries(fixture, home.id).size)
            val retried = coordinator.commit(coordinator.prepare(home.id, bytes))
            assertEquals(2, retried.addedCount)
            assertEquals(0, retried.errorCount)
            assertEquals(3, serverEntries(fixture, home.id).size)
            assertEquals(3L, serverHousehold(fixture, home.id).getLong("ledgerRevision"))
        } } finally { fixture.close() }
    }

    @Test fun invalidFilesOfflineAndChangedPrincipalNeverQueueWrites() = runBlocking {
        val fixture = FirebaseIntegrationFixture.open("JsonBackupGates")
        try { fixture.scenario("file server and session gates") {
            val home = createHome(fixture)
            val coordinator = JsonBackupCoordinator(FirestoreJsonBackupBackend(fixture.firestore, fixture.auth)) { today }
            val bytes = BackupJsonCodec.encode(document())
            val invalid = listOf(bytes.toString(Charsets.UTF_8).replace("\"formatVersion\":1", "\"formatVersion\":2").toByteArray(),
                bytes.toString(Charsets.UTF_8).replace("\"categoryId\":\"source-category\"", "\"categoryId\":\"missing\"").toByteArray(),
                ByteArray(BackupLimits.MAX_BYTES + 1), BackupJsonCodec.encode(document().let { it.copy(entries = it.entries.map { row -> row.copy(date = today.plusDays(1)) }) }))
            for (input in invalid) assertTrue(runCatching { coordinator.prepare(home.id, input) }.isFailure)
            fixture.disableNetwork()
            assertTrue(runCatching { coordinator.prepare(home.id, bytes) }.isFailure)
            fixture.enableNetwork()
            val preview = coordinator.prepare(home.id, bytes)
            val email = fixture.auth.currentUser!!.email!!
            fixture.auth.signOut()
            fixture.auth.createUserWithEmailAndPassword("other-${UUID.randomUUID()}@example.test", "test-password-123").await()
            assertTrue(runCatching { coordinator.commit(preview) }.isFailure)
            assertTrue(runCatching { coordinator.prepare(home.id, bytes) }.isFailure)
            fixture.auth.signOut()
            fixture.auth.signInWithEmailAndPassword(email, "test-password-123").await()
            assertTrue(serverEntries(fixture, home.id).isEmpty())
            assertEquals(0L, serverHousehold(fixture, home.id).toHousehold()!!.ledgerRevision)
        } } finally { fixture.close() }
    }

    @Test fun changedTaxonomyAfterSnapshotFailsBoundedTransactionWithoutEntries() = runBlocking {
        val fixture = FirebaseIntegrationFixture.open("JsonBackupTaxonomyRace")
        try { fixture.scenario("taxonomy transaction fence") {
            val home = createHome(fixture)
            val real = FirestoreJsonBackupBackend(fixture.firestore, fixture.auth)
            var race = true
            val backend = object : JsonBackupBackend by real {
                override suspend fun commit(snapshot: JsonBackupSnapshot, operation: JsonMergeOperation): JsonMergeWriteResult {
                    if (race && operation is JsonMergeOperation.NewEntry) {
                        race = false
                        fixture.firestore.collection("households").document(home.id).collection("categories")
                            .document(operation.entry.categoryId).update(mapOf("name" to "Changed", "updatedById" to home.uid,
                                "updatedAt" to com.google.firebase.firestore.FieldValue.serverTimestamp())).await()
                    }
                    return real.commit(snapshot, operation)
                }
            }
            val coordinator = JsonBackupCoordinator(backend) { today }
            val report = coordinator.commit(coordinator.prepare(home.id, BackupJsonCodec.encode(document())))
            assertEquals(2, report.addedCount)
            assertEquals(1, report.errorCount)
            assertEquals(1, report.newCount)
            assertTrue(serverEntries(fixture, home.id).isEmpty())
            assertEquals(0L, serverHousehold(fixture, home.id).toHousehold()!!.ledgerRevision)
        } } finally { fixture.close() }
    }

    @Test fun memberCannotImportButOwnerMayPreserveCurrentMemberAttribution() = runBlocking {
        val fixture = FirebaseIntegrationFixture.open("JsonBackupMember")
        try { fixture.scenario("member attribution and owner gate") {
            val home = createHome(fixture)
            val ownerEmail = fixture.auth.currentUser!!.email!!
            val member = joinMember(fixture, home)
            val coordinator = JsonBackupCoordinator(FirestoreJsonBackupBackend(fixture.firestore, fixture.auth)) { today }
            val source = document(rows = 1).let { it.copy(entries = it.entries.map { row -> row.copy(authorId = member) }) }
            val bytes = BackupJsonCodec.encode(source)
            assertTrue(runCatching { coordinator.prepare(home.id, bytes) }.isFailure)
            assertTrue(serverEntries(fixture, home.id).isEmpty())
            fixture.auth.signOut()
            fixture.auth.signInWithEmailAndPassword(ownerEmail, "test-password-123").await()
            val report = coordinator.commit(coordinator.prepare(home.id, bytes))
            assertEquals(3, report.addedCount)
            val entry = serverEntries(fixture, home.id).single()
            assertEquals(member, entry.getString("authorId"))
            assertEquals(home.uid, entry.getString("updatedById"))
        } } finally { fixture.close() }
    }

    @Test fun memberRemovalDuringCommitRejectsAttributedEntryAndKeepsConfirmedTaxonomy() = runBlocking {
        val fixture = FirebaseIntegrationFixture.open("JsonBackupMembershipRace")
        try { fixture.scenario("current author membership transaction fence") {
            val home = createHome(fixture)
            val ownerEmail = fixture.auth.currentUser!!.email!!
            val member = joinMember(fixture, home)
            fixture.auth.signOut()
            fixture.auth.signInWithEmailAndPassword(ownerEmail, "test-password-123").await()
            val real = FirestoreJsonBackupBackend(fixture.firestore, fixture.auth)
            var remove = true
            val backend = object : JsonBackupBackend by real {
                override suspend fun commit(snapshot: JsonBackupSnapshot, operation: JsonMergeOperation): JsonMergeWriteResult {
                    if (remove && operation is JsonMergeOperation.NewEntry) {
                        remove = false
                        fixture.firestore.collection("households").document(home.id).collection("members").document(member).delete().await()
                    }
                    return real.commit(snapshot, operation)
                }
            }
            val source = document(rows = 1).let { it.copy(entries = it.entries.map { row -> row.copy(authorId = member) }) }
            val coordinator = JsonBackupCoordinator(backend) { today }
            val report = coordinator.commit(coordinator.prepare(home.id, BackupJsonCodec.encode(source)))
            assertEquals(2, report.addedCount)
            assertEquals(1, report.errorCount)
            assertTrue(serverEntries(fixture, home.id).isEmpty())
            assertEquals(0L, serverHousehold(fixture, home.id).toHousehold()!!.ledgerRevision)
            val retry = coordinator.commit(coordinator.prepare(home.id, BackupJsonCodec.encode(source)))
            assertEquals(1, retry.addedCount)
            assertEquals(home.uid, serverEntries(fixture, home.id).single().getString("authorId"))
        } } finally { fixture.close() }
    }

    @Test fun identicalFinancialRowsRestoreBothAuthorsAndTombstoneWithoutCollapsingMultiplicity() = runBlocking {
        val fixture = FirebaseIntegrationFixture.open("JsonBackupMultiplicity")
        try { fixture.scenario("identical rows are distinct ledger events") {
            val home = createHome(fixture)
            val ownerEmail = fixture.auth.currentUser!!.email!!
            val member = joinMember(fixture, home)
            fixture.auth.signOut()
            fixture.auth.signInWithEmailAndPassword(ownerEmail, "test-password-123").await()
            val base = document(rows = 1).entries.single()
            val source = document(rows = 1).copy(entries = listOf(base.copy(id = "owner-row", authorId = home.uid),
                base.copy(id = "member-row", authorId = member),
                base.copy(id = "deleted-row", authorId = member, deleted = true, deletedById = member)))
            val bytes = BackupJsonCodec.encode(source)
            val coordinator = JsonBackupCoordinator(FirestoreJsonBackupBackend(fixture.firestore, fixture.auth)) { today }
            val report = coordinator.commit(coordinator.prepare(home.id, bytes))
            assertEquals(3, report.items.count { it.kind == JsonMergeItemKind.ENTRY && it.status == JsonMergeStatus.ADDED })
            val rows = serverEntries(fixture, home.id)
            assertEquals(3, rows.size)
            val active = rows.filter { it.getBoolean("deleted") == false }
            assertEquals(2, active.size)
            assertEquals(-200L, active.sumOf { it.getLong("amountGrosze")!! })
            assertEquals(setOf(home.uid, member), active.map { it.getString("authorId") }.toSet())
            assertEquals(1, rows.count { it.getBoolean("deleted") == true })
            val exported = BackupJsonCodec.decode(coordinator.export(home.id, now).bytes)
            assertEquals(3, exported.entries.size)
            assertEquals(2, exported.entries.count { !it.deleted })
            val before = rows.associate { it.id to it.data }
            val repeated = coordinator.commit(coordinator.prepare(home.id, bytes))
            assertEquals(0, repeated.addedCount)
            assertEquals(3, repeated.items.count { it.kind == JsonMergeItemKind.ENTRY && it.status == JsonMergeStatus.SKIPPED })
            assertEquals(before, serverEntries(fixture, home.id).associate { it.id to it.data })
            assertEquals(3L, serverHousehold(fixture, home.id).getLong("ledgerRevision"))
        } } finally { fixture.close() }
    }

    @Test fun oldBackupReplayPreservesEditedImportedLineageAndNativeRestoreKeepsTombstone() = runBlocking {
        val fixture = FirebaseIntegrationFixture.open("JsonBackupLineage")
        try { fixture.scenario("lineage protects edits and tombstones") {
            val home = createHome(fixture)
            val coordinator = JsonBackupCoordinator(FirestoreJsonBackupBackend(fixture.firestore, fixture.auth)) { today }
            val bytes = BackupJsonCodec.encode(document(rows = 1))
            coordinator.commit(coordinator.prepare(home.id, bytes))
            val original = serverEntries(fixture, home.id).single().toLedgerEntry(expectedHouseholdId = home.id)!!
            FirestoreRepositories(fixture.firestore).save(original.copy(amountGrosze = -777, title = "User edit", updatedById = home.uid))
            val edited = serverEntries(fixture, home.id).single().data
            val replay = coordinator.commit(coordinator.prepare(home.id, bytes))
            assertEquals(1, replay.items.count { it.kind == JsonMergeItemKind.ENTRY && it.status == JsonMergeStatus.CONFLICT })
            assertEquals(0, replay.addedCount)
            assertEquals(edited, serverEntries(fixture, home.id).single().data)

            val nativeBytes = coordinator.export(home.id, now).bytes
            FirestoreRepositories(fixture.firestore).tombstone(home.id, original.id, home.uid)
            val tombstone = serverEntries(fixture, home.id).single().data
            val native = coordinator.commit(coordinator.prepare(home.id, nativeBytes))
            val item = native.items.single { it.kind == JsonMergeItemKind.ENTRY }
            assertEquals(original.id, item.targetId)
            assertEquals(JsonMergeStatus.SKIPPED, item.status)
            assertEquals(tombstone, serverEntries(fixture, home.id).single().data)
            assertEquals(3L, serverHousehold(fixture, home.id).getLong("ledgerRevision"))
        } } finally { fixture.close() }
    }

    private data class Home(val id: String, val uid: String)
    private suspend fun createHome(fixture: FirebaseIntegrationFixture): Home {
        val email = "json-${UUID.randomUUID()}@example.test"
        val uid = fixture.auth.createUserWithEmailAndPassword(email, "test-password-123").await().user!!.uid
        val result = FirestoreRepositories(fixture.firestore).createFirstHousehold(OnboardingIdentity(uid, email, "Importer"), "JSON Home")
        return Home((result as FirstHouseholdResult.Created).householdId, uid)
    }
    private suspend fun joinMember(fixture: FirebaseIntegrationFixture, home: Home): String {
        val repository = FirestoreRepositories(fixture.firestore)
        val email = "member-${UUID.randomUUID()}@example.test"
        val invitation = Invitation(UUID.randomUUID().toString(), home.id, email, home.uid, Instant.now().plusSeconds(86_400))
        repository.create(invitation)
        fixture.auth.signOut()
        val token = """{"sub":"${UUID.randomUUID()}","email":"$email","email_verified":true}"""
        val uid = fixture.auth.signInWithCredential(GoogleAuthProvider.getCredential(token, null)).await().user!!.uid
        repository.accept(invitation, Member(uid, email, "Member", MemberRole.MEMBER, invitation.id))
        return uid
    }
    private suspend fun serverHousehold(fixture: FirebaseIntegrationFixture, id: String) =
        fixture.firestore.collection("households").document(id).get(Source.SERVER).await()
    private suspend fun serverEntries(fixture: FirebaseIntegrationFixture, id: String) =
        fixture.firestore.collection("households").document(id).collection("entries").get(Source.SERVER).await().documents
}
