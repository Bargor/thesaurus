package pl.bargor.thesaurus.data.firebase

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.google.firebase.FirebaseApp
import com.google.firebase.FirebaseOptions
import com.google.firebase.firestore.FieldValue
import kotlinx.coroutines.async
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.tasks.await
import kotlinx.coroutines.withTimeout
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import pl.bargor.thesaurus.data.model.EntryType
import pl.bargor.thesaurus.data.model.LedgerEntry
import pl.bargor.thesaurus.data.model.SyncState
import java.time.LocalDate
import java.util.UUID

@RunWith(AndroidJUnit4::class)
class FirestoreOfflineIntegrationTest {
    @Test
    fun offlineWriteIsPendingThenSynchronizesWhenNetworkReturns() = runBlocking {
        val suffix = UUID.randomUUID().toString()
        val app = FirebaseApp.initializeApp(
            InstrumentationRegistry.getInstrumentation().targetContext,
            FirebaseOptions.Builder()
                .setApplicationId("1:1234567890:android:test")
                .setApiKey("fake-api-key")
                .setProjectId("demo-thesaurus")
                .build(),
            "offline-$suffix",
        )
        val auth = FirebaseAuthFactory.create(app)
        FirebaseAuthFactory.connectToLocalEmulator(auth)
        val firestore = FirebaseFirestoreFactory.create(app, emulatorHost = "10.0.2.2")

        try {
            val email = "offline-$suffix@example.test"
            val uid = auth.createUserWithEmailAndPassword(email, "test-password-123")
                .await().user!!.uid
            val householdId = "household-$suffix"
            val household = firestore.collection(FirestorePaths.HOUSEHOLDS).document(householdId)
            firestore.runBatch { batch ->
                batch.set(
                    firestore.collection(FirestorePaths.USERS).document(uid),
                    mapOf(
                        "email" to email,
                        "displayName" to null,
                        "householdId" to householdId,
                        "createdAt" to FieldValue.serverTimestamp(),
                        "updatedAt" to FieldValue.serverTimestamp(),
                    ),
                )
                batch.set(
                    household,
                    mapOf(
                        "name" to "Dom testowy",
                        "ownerId" to uid,
                        "createdAt" to FieldValue.serverTimestamp(),
                        "updatedAt" to FieldValue.serverTimestamp(),
                    ),
                )
                batch.set(
                    household.collection(FirestorePaths.MEMBERS).document(uid),
                    mapOf(
                        "email" to email,
                        "displayName" to null,
                        "role" to "OWNER",
                        "invitationId" to null,
                        "joinedAt" to FieldValue.serverTimestamp(),
                    ),
                )
            }.await()

            val repository: LedgerRepository = FirestoreRepositories(firestore)
            withTimeout(15_000) {
                repository.observeEntries(householdId).first { it.state == SyncState.SYNCED }
            }

            firestore.disableNetwork().await()
            withTimeout(15_000) {
                repository.observeEntries(householdId).first { it.state == SyncState.OFFLINE }
            }

            val entry = LedgerEntry(
                id = "entry-$suffix",
                householdId = householdId,
                amountGrosze = -1_234,
                date = LocalDate.of(2026, 9, 14),
                categoryId = "food",
                authorId = uid,
                updatedById = uid,
            )
            val pendingSave = async { repository.save(entry) }
            val pending = withTimeout(15_000) {
                repository.observeEntries(householdId).first { observation ->
                    observation.state == SyncState.PENDING &&
                        observation.value.orEmpty().any { it.id == entry.id }
                }
            }
            assertEquals(EntryType.EXPENSE, pending.value!!.single { it.id == entry.id }.type)

            firestore.enableNetwork().await()
            withTimeout(15_000) { pendingSave.await() }
            val synced = withTimeout(15_000) {
                repository.observeEntries(householdId).first { observation ->
                    observation.state == SyncState.SYNCED &&
                        observation.value.orEmpty().any { it.id == entry.id }
                }
            }
            assertTrue(synced.value!!.any { it.id == entry.id && it.amountGrosze == -1_234L })
        } finally {
            runCatching { firestore.enableNetwork().await() }
            runCatching { firestore.terminate().await() }
            app.delete()
        }
    }
}
