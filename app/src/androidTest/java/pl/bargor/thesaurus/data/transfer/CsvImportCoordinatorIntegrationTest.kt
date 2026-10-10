package pl.bargor.thesaurus.data.transfer

import androidx.test.ext.junit.runners.AndroidJUnit4
import com.google.firebase.firestore.Source
import com.google.firebase.auth.GoogleAuthProvider
import java.time.Instant
import java.time.LocalDate
import java.util.UUID
import kotlinx.coroutines.async
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

/** Every assertion reads the isolated emulator server; no fake repository or global cleanup. */
@RunWith(AndroidJUnit4::class)
class CsvImportCoordinatorIntegrationTest {
    @get:Rule val localNetworkPermissionRule = LocalNetworkPermissionRule()
    private val today = LocalDate.of(2026, 10, 10)
    private val mapping = CsvColumnMapping(date = 0, amount = 1, category = 2, title = 3, tags = 4)

    @Test fun realRulesDedupNormalizeAuditAndIncrementRevisionExactlyOnce() = runBlocking {
        val fixture = FirebaseIntegrationFixture.open("CsvImportDedup")
        try { fixture.scenario("normalized import and repair") {
            val home = createHome(fixture)
            val repository = FirestoreRepositories(fixture.firestore)
            repository.save(LedgerEntry("existing", home.id, -100, today.minusDays(1), " one ", "jedzenie",
                tags = listOf("A", "b"), authorId = home.uid, updatedById = home.uid))
            val revision = serverRevision(fixture, home.id)
            val coordinator = CsvImportCoordinator(FirestoreCsvImportBackend(fixture.firestore, fixture.auth)) { today }
            val session = coordinator.prepare(home.id, csv(
                "2026-10-09;-1; Jedzenie ;one;\"[\"\"b\"\",\"\"a\"\"]\";CSV Author",
                "2026-10-09;-2;jedzenie; new ;\"[\"\" A \"\",\"\"a\"\"]\";CSV Author",
                "2026-10-09;-2;JEDZENIE;new;\"[\"\"a\"\"]\";CSV Author",
                "bad;-3;Jedzenie;repaired;;CSV Author"), mapping)
            val report = coordinator.commit(session)
            assertEquals(1, report.summary.added)
            assertEquals(2, report.summary.duplicates)
            assertEquals(1, report.summary.invalidRows)
            assertEquals(revision + 1, serverRevision(fixture, home.id))
            val repaired = coordinator.repair(session, 5,
                listOf("2026-10-09", "-3", "Jedzenie", " repaired ", "", "CSV Author"))
            assertEquals(1, repaired.summary.repairedAndAdded)
            assertEquals(revision + 2, serverRevision(fixture, home.id))
            val entries = serverEntries(fixture, home.id)
            assertEquals(3, entries.size)
            for (entry in entries.filter { it.id.startsWith("csv_") }) {
                assertEquals(home.uid, entry.getString("authorId"))
                assertEquals(home.uid, entry.getString("updatedById"))
            }
            val newEntry = entries.single { it.getLong("amountGrosze") == -200L }
            assertEquals("new", newEntry.getString("title"))
            assertEquals(listOf("a"), newEntry.get("tags"))
            val retry = coordinator.prepare(home.id, session.parsed, mapping)
            val retried = coordinator.commit(retry)
            assertEquals(3, retried.summary.duplicates)
            assertEquals(revision + 2, serverRevision(fixture, home.id))
        } } finally { fixture.close() }
    }

    @Test fun unknownArchivedAndAmbiguousTaxonomyNeverAutoCreateAndExplicitOwnerCreateRetries() = runBlocking {
        val fixture = FirebaseIntegrationFixture.open("CsvImportTaxonomy")
        try { fixture.scenario("taxonomy resolution") {
            val home = createHome(fixture)
            val repository = FirestoreRepositories(fixture.firestore)
            repository.save(Category("old", home.id, "Old", authorId = home.uid, updatedById = home.uid))
            val old = fixture.firestore.collection("households").document(home.id).collection("categories")
                .document("old").get(Source.SERVER).await().toCategory(expectedHouseholdId = home.id)!!
            repository.save(old.copy(archived = true))
            repository.save(Category("ambiguous-a", home.id, "Ambiguous", authorId = home.uid, updatedById = home.uid))
            repository.save(Category("ambiguous-b", home.id, "AMBIGUOUS", authorId = home.uid, updatedById = home.uid))
            val before = fixture.firestore.collection("households").document(home.id).collection("categories").get(Source.SERVER).await().size()
            val coordinator = CsvImportCoordinator(FirestoreCsvImportBackend(fixture.firestore, fixture.auth)) { today }
            val session = coordinator.prepare(home.id, csv("2026-10-09;-1;Missing;;;csv", "2026-10-09;-2;Old;;;csv", "2026-10-09;-3;Ambiguous;;;csv"), mapping)
            val report = coordinator.commit(session)
            assertEquals(3, report.summary.invalidRows)
            assertTrue(serverEntries(fixture, home.id).isEmpty())
            assertEquals(0L, serverRevision(fixture, home.id))
            assertEquals(before, fixture.firestore.collection("households").document(home.id).collection("categories").get(Source.SERVER).await().size())
            val created = coordinator.createCategory(session, "Missing")
            assertEquals(home.uid, created.authorId)
            assertEquals(1, coordinator.commit(session).summary.added)
            assertEquals(1L, serverRevision(fixture, home.id))
            assertEquals(1, serverEntries(fixture, home.id).size)
            assertEquals(1, coordinator.commit(session).summary.added)
            assertEquals(1L, serverRevision(fixture, home.id))
        } } finally { fixture.close() }
    }

    @Test fun concurrentAndStaleSnapshotsCannotWriteDuplicateOrBypassRevisionFence() = runBlocking {
        val fixture = FirebaseIntegrationFixture.open("CsvImportConcurrent")
        try { fixture.scenario("concurrent revision fence") {
            val home = createHome(fixture)
            val backend = FirestoreCsvImportBackend(fixture.firestore, fixture.auth)
            val coordinator = CsvImportCoordinator(backend) { today }
            val session = coordinator.prepare(home.id, csv("2026-10-09;-1;Jedzenie;;;csv"), mapping)
            val stale = backend.loadSnapshot(home.id, backend.currentIdentity()!!)
            val entry = session.report.rows.single().entry!!
            val outcomes = listOf(async { runCatching { backend.commitEntry(stale, entry) } },
                async { runCatching { backend.commitEntry(stale, entry) } }).map { it.await() }
            assertEquals(1, outcomes.count { it.isSuccess })
            assertEquals(1, serverEntries(fixture, home.id).size)
            assertEquals(1L, serverRevision(fixture, home.id))
            val changed = entry.copy(amountGrosze = -200)
            val staleEntry = changed.copy(id = "csv_${csvImportFingerprint(changed)}")
            assertTrue(runCatching { backend.commitEntry(stale, staleEntry) }.isFailure)
            assertEquals(1, serverEntries(fixture, home.id).size)
            assertEquals(1, coordinator.commit(session).summary.duplicates)
            assertEquals(1L, serverRevision(fixture, home.id))
        } } finally { fixture.close() }
    }

    @Test fun offlineAfterPartialCommitStopsSafelyAndRetryRereadsBeforeAdding() = runBlocking {
        val fixture = FirebaseIntegrationFixture.open("CsvImportOffline")
        try { fixture.scenario("offline partial import and retry") {
            val home = createHome(fixture)
            val real = FirestoreCsvImportBackend(fixture.firestore, fixture.auth)
            var disconnect = true
            val backend = object : CsvImportBackend by real {
                override suspend fun commitEntry(snapshot: CsvImportSnapshot, entry: LedgerEntry): CsvImportCommitResult {
                    val committed = real.commitEntry(snapshot, entry)
                    if (disconnect) { disconnect = false; fixture.disableNetwork() }
                    return committed
                }
            }
            val coordinator = CsvImportCoordinator(backend) { today }
            val session = coordinator.prepare(home.id, csv("2026-10-09;-1;Jedzenie;;;csv", "2026-10-09;-2;Jedzenie;;;csv", "2026-10-09;-3;Jedzenie;;;csv"), mapping)
            val partial = coordinator.commit(session)
            assertEquals(1, partial.summary.added)
            assertEquals(1, partial.summary.serverErrors)
            assertEquals(1, partial.summary.pending)
            fixture.enableNetwork()
            assertEquals(1, serverEntries(fixture, home.id).size)
            assertEquals(1L, serverRevision(fixture, home.id))
            val resumed = coordinator.commit(session)
            assertEquals(3, resumed.summary.added)
            assertEquals(0, resumed.summary.serverErrors)
            assertEquals(3, serverEntries(fixture, home.id).size)
            assertEquals(3L, serverRevision(fixture, home.id))
            assertEquals(3, coordinator.commit(session).summary.added)
            assertEquals(3L, serverRevision(fixture, home.id))
        } } finally { fixture.close() }
    }

    @Test fun permissionChangeFailsOneRowAndContinuesWithSafeDiagnostics() = runBlocking {
        val fixture = FirebaseIntegrationFixture.open("CsvImportPartialFailure")
        try { fixture.scenario("taxonomy race fails one row") {
            val home = createHome(fixture)
            val repository = FirestoreRepositories(fixture.firestore)
            val raced = Category("raced", home.id, "Race", authorId = home.uid, updatedById = home.uid)
            repository.save(raced)
            val persistedRaced = fixture.firestore.collection("households").document(home.id).collection("categories")
                .document(raced.id).get(Source.SERVER).await().toCategory(expectedHouseholdId = home.id)!!
            val real = FirestoreCsvImportBackend(fixture.firestore, fixture.auth)
            val backend = object : CsvImportBackend by real {
                override suspend fun commitEntry(snapshot: CsvImportSnapshot, entry: LedgerEntry): CsvImportCommitResult {
                    if (entry.amountGrosze == -200L) repository.save(persistedRaced.copy(archived = true))
                    return real.commitEntry(snapshot, entry)
                }
            }
            val coordinator = CsvImportCoordinator(backend) { today }
            val session = coordinator.prepare(home.id, csv(
                "2026-10-09;-1;Jedzenie;private one;;csv",
                "2026-10-09;-2;Race;private two;;csv",
                "2026-10-09;-3;Jedzenie;private three;;csv"), mapping)
            val report = coordinator.commit(session)
            assertEquals(2, report.summary.added)
            assertEquals(1, report.summary.serverErrors)
            assertEquals(listOf(3), report.summary.rowFailures.map { it.rowNumber })
            assertEquals(listOf("IMPORT_WRITE_FAILED"), report.summary.rowFailures.map { it.code })
            assertFalse(report.summary.issues.toString().contains("private"))
            assertEquals(2, serverEntries(fixture, home.id).size)
            assertEquals(2L, serverRevision(fixture, home.id))
        } } finally { fixture.close() }
    }

    @Test fun offlineOrUnrelatedPrincipalCannotStartImportAndWritesNothing() = runBlocking {
        val fixture = FirebaseIntegrationFixture.open("CsvImportGates")
        try { fixture.scenario("server and principal gates") {
            val home = createHome(fixture)
            val backend = FirestoreCsvImportBackend(fixture.firestore, fixture.auth)
            val coordinator = CsvImportCoordinator(backend) { today }
            val parsed = csv("2026-10-09;-1;Jedzenie;;;csv")
            fixture.disableNetwork()
            assertTrue(runCatching { coordinator.prepare(home.id, parsed, mapping) }.isFailure)
            fixture.enableNetwork()
            assertTrue(serverEntries(fixture, home.id).isEmpty())
            assertEquals(0L, serverRevision(fixture, home.id))
            val ownerEmail = fixture.auth.currentUser!!.email!!
            val session = coordinator.prepare(home.id, parsed, mapping)
            fixture.auth.signOut()
            fixture.auth.createUserWithEmailAndPassword("other-${UUID.randomUUID()}@example.test", "test-password-123").await()
            assertTrue(runCatching { coordinator.commit(session) }.isFailure)
            assertTrue(runCatching { coordinator.prepare(home.id, parsed, mapping) }.isFailure)
            fixture.auth.signOut()
            fixture.auth.signInWithEmailAndPassword(ownerEmail, "test-password-123").await()
            assertTrue(serverEntries(fixture, home.id).isEmpty())
            assertEquals(0L, serverRevision(fixture, home.id))
        } } finally { fixture.close() }
    }

    @Test fun householdMemberWithReadAccessCannotImportOrCreateTaxonomy() = runBlocking {
        val fixture = FirebaseIntegrationFixture.open("CsvImportMember")
        try { fixture.scenario("member cannot invoke owner import") {
            val home = createHome(fixture)
            val repository = FirestoreRepositories(fixture.firestore)
            val coordinator = CsvImportCoordinator(FirestoreCsvImportBackend(fixture.firestore, fixture.auth)) { today }
            val parsed = csv("2026-10-09;-1;Jedzenie;;;csv")
            val ownerSession = coordinator.prepare(home.id, parsed, mapping)
            val email = "member-${UUID.randomUUID()}@example.test"
            val invitation = Invitation(UUID.randomUUID().toString(), home.id, email, home.uid,
                Instant.now().plusSeconds(86_400))
            repository.create(invitation)
            fixture.auth.signOut()
            val token = """{"sub":"${UUID.randomUUID()}","email":"$email","email_verified":true}"""
            val memberId = fixture.auth.signInWithCredential(GoogleAuthProvider.getCredential(token, null)).await().user!!.uid
            repository.accept(invitation, Member(memberId, email, "Member", MemberRole.MEMBER, invitation.id))
            assertEquals(MemberRole.MEMBER.name, fixture.firestore.collection("households").document(home.id)
                .collection("members").document(memberId).get(Source.SERVER).await().getString("role"))
            assertTrue(runCatching { coordinator.prepare(home.id, parsed, mapping) }.isFailure)
            assertTrue(runCatching { coordinator.commit(ownerSession) }.isFailure)
            assertTrue(runCatching { coordinator.createCategory(ownerSession, "Forbidden") }.isFailure)
            assertTrue(serverEntries(fixture, home.id).isEmpty())
            assertEquals(0L, serverRevision(fixture, home.id))
        } } finally { fixture.close() }
    }

    @Test fun explicitSubcategoryCreationResolvesRowAndDeletedImportsNeverResurrect() = runBlocking {
        val fixture = FirebaseIntegrationFixture.open("CsvImportSubcategory")
        try { fixture.scenario("subcategory repair and tombstone replay") {
            val home = createHome(fixture)
            val coordinator = CsvImportCoordinator(FirestoreCsvImportBackend(fixture.firestore, fixture.auth)) { today }
            val parsed = CsvParser.parse("date;amount;category;subcategory\n2026-10-09;-1;Jedzenie;New subcategory".toByteArray())
            val columns = CsvColumnMapping(date = 0, amount = 1, category = 2, subcategory = 3)
            val session = coordinator.prepare(home.id, parsed, columns)
            assertEquals(1, coordinator.commit(session).summary.invalidRows)
            assertTrue(serverEntries(fixture, home.id).isEmpty())
            val created = coordinator.createSubcategory(session, "jedzenie", "New subcategory")
            assertEquals(home.uid, created.authorId)
            val imported = coordinator.commit(session)
            assertEquals(1, imported.summary.added)
            assertEquals(created.id, imported.rows.single().entry!!.subcategoryId)
            val entryId = serverEntries(fixture, home.id).single().id
            FirestoreRepositories(fixture.firestore).tombstone(home.id, entryId, home.uid)
            assertEquals(2L, serverRevision(fixture, home.id))
            val replay = coordinator.commit(coordinator.prepare(home.id, parsed, columns))
            assertEquals(1, replay.summary.duplicates)
            assertEquals(2L, serverRevision(fixture, home.id))
            assertEquals(true, serverEntries(fixture, home.id).single().getBoolean("deleted"))
        } } finally { fixture.close() }
    }

    private fun csv(vararg rows: String) = CsvParser.parse(
        ("date;amount;category;title;tags;author\n" + rows.joinToString("\n")).toByteArray())
    private data class Home(val id: String, val uid: String)
    private suspend fun createHome(fixture: FirebaseIntegrationFixture): Home {
        val email = "csv-${UUID.randomUUID()}@example.test"
        val uid = fixture.auth.createUserWithEmailAndPassword(email, "test-password-123").await().user!!.uid
        val result = FirestoreRepositories(fixture.firestore).createFirstHousehold(OnboardingIdentity(uid, email, "Importer"), "CSV Home")
        return Home((result as FirstHouseholdResult.Created).householdId, uid)
    }
    private suspend fun serverRevision(fixture: FirebaseIntegrationFixture, id: String) =
        fixture.firestore.collection("households").document(id).get(Source.SERVER).await().getLong("ledgerRevision") ?: 0L
    private suspend fun serverEntries(fixture: FirebaseIntegrationFixture, id: String) =
        fixture.firestore.collection("households").document(id).collection("entries").get(Source.SERVER).await().documents
}
