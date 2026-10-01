package pl.bargor.thesaurus.data.firebase

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.google.firebase.FirebaseApp
import com.google.firebase.FirebaseOptions
import com.google.firebase.firestore.FieldValue
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.tasks.await
import kotlinx.coroutines.withTimeout
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import pl.bargor.thesaurus.LocalNetworkPermissionRule
import pl.bargor.thesaurus.data.model.Category
import pl.bargor.thesaurus.data.model.CategoryOrder
import pl.bargor.thesaurus.data.model.EntryType
import pl.bargor.thesaurus.data.model.Subcategory
import pl.bargor.thesaurus.data.model.SyncState
import java.util.UUID

@RunWith(AndroidJUnit4::class)
class TaxonomyRepositoryIntegrationTest {
    @get:Rule
    val localNetworkPermissionRule = LocalNetworkPermissionRule()

    @Test
    fun ownerCanCreateUpdateAndArchiveTaxonomyWithStableIds() = runBlocking {
        val suffix = UUID.randomUUID().toString()
        val app = FirebaseApp.initializeApp(
            InstrumentationRegistry.getInstrumentation().targetContext,
            FirebaseOptions.Builder()
                .setApplicationId("1:1234567890:android:taxonomy-$suffix")
                .setApiKey("fake-api-key-$suffix")
                .setProjectId("demo-thesaurus")
                .build(),
            "taxonomy-$suffix",
        )
        val auth = FirebaseAuthFactory.create(app)
        FirebaseAuthFactory.connectToLocalEmulator(auth)
        val firestore = FirebaseFirestoreFactory.create(app, emulatorHost = "10.0.2.2")

        try {
            val email = "taxonomy-$suffix@example.test"
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
            }.await()

            val repository: TaxonomyRepository = FirestoreRepositories(firestore)
            val category = Category(
                id = "category-$suffix", householdId = householdId, name = "  Zwierzęta  ",
                color = "rose", defaultEntryType = EntryType.INCOME, authorId = uid, updatedById = uid,
            )
            repository.save(category)
            val savedCategory = withTimeout(15_000) {
                repository.observeCategories(householdId).first { observation ->
                    observation.state == SyncState.SYNCED && observation.value.orEmpty().any { it.id == category.id }
                }.value!!.single { it.id == category.id }
            }
            assertEquals("Zwierzęta", savedCategory.name)
            assertEquals(EntryType.INCOME, savedCategory.defaultEntryType)
            assertEquals("rose", savedCategory.color)

            val secondCategory = category.copy(id = "second-$suffix", name = "Druga")
            repository.save(secondCategory)
            repository.saveCategoryOrder(
                CategoryOrder(householdId, uid, listOf(secondCategory.id, category.id)),
            )
            val ordered = withTimeout(15_000) {
                repository.observeOrderedCategories(householdId, uid).first { observation ->
                    observation.state == SyncState.SYNCED && observation.value.orEmpty().size == 2
                }
            }
            assertEquals(listOf(secondCategory.id, category.id), ordered.value!!.map(Category::id))

            firestore.disableNetwork().await()
            val offlineOrder = CategoryOrder(householdId, uid, listOf(category.id, secondCategory.id))
            val pendingWrite = launch { repository.saveCategoryOrder(offlineOrder) }
            val pending = withTimeout(15_000) {
                repository.observeCategoryOrder(householdId, uid).first { it.state == SyncState.PENDING }
            }
            assertEquals(offlineOrder.categoryIds, pending.value!!.categoryIds)
            firestore.enableNetwork().await()
            withTimeout(15_000) { pendingWrite.join() }

            val child = Subcategory(
                id = "subcategory-$suffix", householdId = householdId, categoryId = category.id,
                name = " Karma ", authorId = uid, updatedById = uid,
            )
            repository.save(child)
            repository.save(savedCategory.copy(name = "Zwierzęta domowe", archived = true, updatedById = uid))
            val archivedCategory = withTimeout(15_000) {
                repository.observeCategories(householdId).first { observation ->
                    observation.value.orEmpty().any { it.id == category.id && it.archived }
                }.value!!.single { it.id == category.id }
            }
            val savedSubcategory = withTimeout(15_000) {
                repository.observeSubcategories(householdId, category.id).first { observation ->
                    observation.value.orEmpty().any { it.id == child.id }
                }.value!!.single { it.id == child.id }
            }
            assertEquals(child.id, savedSubcategory.id)
            assertEquals(category.id, savedSubcategory.categoryId)
            assertFalse(savedSubcategory.archived)
            assertEquals(category.id, archivedCategory.id)
            assertEquals("Zwierzęta domowe", archivedCategory.name)
            assertEquals("rose", archivedCategory.color)
        } finally {
            runCatching { firestore.terminate().await() }
            // Keep this named app registered until the instrumentation process exits;
            // deletion can race Firebase Auth's background workers in later tests.
        }
    }
}
