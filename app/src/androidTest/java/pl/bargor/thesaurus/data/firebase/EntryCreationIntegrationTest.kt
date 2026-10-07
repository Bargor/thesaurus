package pl.bargor.thesaurus.data.firebase

import androidx.test.ext.junit.runners.AndroidJUnit4
import com.google.firebase.firestore.FieldValue
import java.time.LocalDate
import java.util.UUID
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.tasks.await
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import pl.bargor.thesaurus.LocalNetworkPermissionRule
import pl.bargor.thesaurus.data.model.EntryType
import pl.bargor.thesaurus.data.model.LedgerEntry
import pl.bargor.thesaurus.data.model.SyncState
import pl.bargor.thesaurus.testfixtures.FirebaseIntegrationFixture

@RunWith(AndroidJUnit4::class)
class EntryCreationIntegrationTest {
    @get:Rule val localNetworkPermissionRule = LocalNetworkPermissionRule()

    @Test
    fun onlineCreationPersistsSignedAmountAndNormalizedOptionalFields() = runBlocking {
        val suffix = UUID.randomUUID().toString()
        val fixture = FirebaseIntegrationFixture.open("EntryCreationIntegrationTest")
        val auth = fixture.auth
        val firestore = fixture.firestore
        try {
            fixture.scenario("EntryCreationIntegrationTest scenario") {
                val email = "entry-$suffix@example.test"
                val uid = auth.createUserWithEmailAndPassword(email, "test-password-123").await().user!!.uid
                val householdId = "household-$suffix"
                val household = firestore.collection(FirestorePaths.HOUSEHOLDS).document(householdId)
                firestore.runBatch { batch ->
                    batch.set(firestore.collection(FirestorePaths.USERS).document(uid), mapOf(
                        "email" to email, "displayName" to null, "householdId" to householdId,
                        "createdAt" to FieldValue.serverTimestamp(), "updatedAt" to FieldValue.serverTimestamp(),
                    ))
                    batch.set(household, mapOf(
                        "name" to "Dom testowy", "ownerId" to uid,
                        "createdAt" to FieldValue.serverTimestamp(), "updatedAt" to FieldValue.serverTimestamp(),
                    ))
                    batch.set(household.collection(FirestorePaths.MEMBERS).document(uid), mapOf(
                        "email" to email, "displayName" to null, "role" to "OWNER", "invitationId" to null,
                        "joinedAt" to FieldValue.serverTimestamp(),
                    ))
                    batch.set(household.collection(FirestorePaths.CATEGORIES).document("income"), mapOf(
                        "householdId" to householdId, "name" to "Wpływy", "color" to "green", "archived" to false,
                        "defaultEntryType" to EntryType.INCOME.name, "authorId" to uid, "updatedById" to uid,
                        "createdAt" to FieldValue.serverTimestamp(), "updatedAt" to FieldValue.serverTimestamp(),
                    ))
                }.await()

                val repository: LedgerRepository = FirestoreRepositories(firestore)
                val entry = LedgerEntry(
                    id = "online-$suffix", householdId = householdId, amountGrosze = 12_500,
                    date = LocalDate.of(2026, 9, 16), title = "  Premia  ", categoryId = "income",
                    tags = listOf(" Praca ", "PRACA", "premia"), authorId = uid, updatedById = uid,
                )
                repository.save(entry)
                val saved = fixture.operation("EntryCreationIntegrationTest wait 1") {
                    repository.observeEntries(householdId).first { observation ->
                        observation.state == SyncState.SYNCED && observation.value.orEmpty().any { it.id == entry.id }
                    }.value!!.single { it.id == entry.id }
                }
                assertEquals(12_500L, saved.amountGrosze)
                assertEquals(EntryType.INCOME, saved.type)
                assertEquals("Premia", saved.title)
                assertEquals(listOf("praca", "premia"), saved.tags)
            }
        } finally {
            fixture.close()
        }
    }
}
