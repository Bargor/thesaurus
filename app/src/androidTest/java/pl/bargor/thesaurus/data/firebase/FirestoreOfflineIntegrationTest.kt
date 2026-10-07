package pl.bargor.thesaurus.data.firebase

import androidx.test.ext.junit.runners.AndroidJUnit4
import com.google.firebase.firestore.Source
import kotlinx.coroutines.async
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.tasks.await
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import pl.bargor.thesaurus.LocalNetworkPermissionRule
import pl.bargor.thesaurus.data.model.EntryType
import pl.bargor.thesaurus.data.model.LedgerEntry
import pl.bargor.thesaurus.data.model.SyncState
import java.time.LocalDate
import java.util.UUID
import pl.bargor.thesaurus.testfixtures.FirebaseIntegrationFixture

@RunWith(AndroidJUnit4::class)
class FirestoreOfflineIntegrationTest {
    @get:Rule
    val localNetworkPermissionRule = LocalNetworkPermissionRule()

    @Test
    fun householdOnboardingOfflineWriteAndTombstoneSurviveNetworkRecovery() = runBlocking {
        val suffix = UUID.randomUUID().toString()
        val fixture = FirebaseIntegrationFixture.open("FirestoreOfflineIntegrationTest")
        val auth = fixture.auth
        val firestore = fixture.firestore

        try {
            fixture.scenario("FirestoreOfflineIntegrationTest scenario") {
                val email = "offline-$suffix@example.test"
                val uid = auth.createUserWithEmailAndPassword(email, "test-password-123")
                    .await().user!!.uid
                val repository = FirestoreRepositories(firestore)
                val householdId = (repository.createFirstHousehold(
                    OnboardingIdentity(uid, email, "Ala"),
                    "  Dom testowy  ",
                ) as FirstHouseholdResult.Created).householdId
                val household = firestore.collection(FirestorePaths.HOUSEHOLDS).document(householdId)
                assertEquals("Dom testowy", household.get(Source.SERVER).await().getString("name"))
                assertEquals(
                    EntryType.EXPENSE.name,
                    household.collection(FirestorePaths.CATEGORIES).document("jedzenie")
                        .get(Source.SERVER).await().getString("defaultEntryType"),
                )
                fixture.operation("FirestoreOfflineIntegrationTest wait 1") {
                    repository.observeEntries(householdId).first { it.state == SyncState.SYNCED }
                }

                fixture.disableNetwork()
                fixture.operation("FirestoreOfflineIntegrationTest wait 2") {
                    repository.observeEntries(householdId).first { it.state == SyncState.OFFLINE }
                }

                val entry = LedgerEntry(
                    id = "entry-$suffix",
                    householdId = householdId,
                    amountGrosze = -1_234,
                    date = LocalDate.of(2026, 9, 14),
                    categoryId = "jedzenie",
                    authorId = uid,
                    updatedById = uid,
                )
                val pendingSave = async { repository.save(entry) }
                val pending = fixture.operation("FirestoreOfflineIntegrationTest wait 3") {
                    repository.observeEntries(householdId).first { observation ->
                        observation.state == SyncState.PENDING &&
                            observation.value.orEmpty().any { it.id == entry.id }
                    }
                }
                assertEquals(EntryType.EXPENSE, pending.value!!.single { it.id == entry.id }.type)

                fixture.enableNetwork()
                fixture.operation("FirestoreOfflineIntegrationTest wait 4") { pendingSave.await() }
                val synced = fixture.operation("FirestoreOfflineIntegrationTest wait 5") {
                    repository.observeEntries(householdId).first { observation ->
                        observation.state == SyncState.SYNCED &&
                            observation.value.orEmpty().any { it.id == entry.id }
                    }
                }
                assertTrue(synced.value!!.any { it.id == entry.id && it.amountGrosze == -1_234L })
                val serverEntry = household.collection(FirestorePaths.ENTRIES).document(entry.id)
                val serverEntryAfterSync = serverEntry.get(Source.SERVER).await()
                assertEquals(-1_234L, serverEntryAfterSync.getLong("amountGrosze"))
                assertEquals(false, serverEntryAfterSync.getBoolean("deleted"))

                repository.tombstone(householdId, entry.id, uid)
                val afterDeletion = fixture.operation("FirestoreOfflineIntegrationTest wait 6") {
                    repository.observeEntries(householdId).first { observation ->
                        observation.state == SyncState.SYNCED && observation.value.orEmpty().none { it.id == entry.id }
                    }
                }
                assertFalse(afterDeletion.value.orEmpty().any { it.id == entry.id })
                assertEquals(true, serverEntry.get(Source.SERVER).await().getBoolean("deleted"))

                val staleEdit = runCatching {
                    repository.save(entry.copy(title = "Nieaktualna edycja", updatedById = uid))
                }
                assertTrue("Tombstone must reject a stale edit", staleEdit.isFailure)
            }
        } finally {
            fixture.close()
        }
    }
}
