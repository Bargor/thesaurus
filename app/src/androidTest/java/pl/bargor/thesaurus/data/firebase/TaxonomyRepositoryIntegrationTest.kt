package pl.bargor.thesaurus.data.firebase

import androidx.test.ext.junit.runners.AndroidJUnit4
import com.google.firebase.firestore.FieldValue
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.tasks.await
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
import pl.bargor.thesaurus.testfixtures.FirebaseIntegrationFixture

@RunWith(AndroidJUnit4::class)
class TaxonomyRepositoryIntegrationTest {
    @get:Rule
    val localNetworkPermissionRule = LocalNetworkPermissionRule()

    @Test
    fun ownerCanCreateUpdateAndArchiveTaxonomyWithStableIds() = runBlocking {
        val suffix = UUID.randomUUID().toString()
        val fixture = FirebaseIntegrationFixture.open("TaxonomyRepositoryIntegrationTest")
        val auth = fixture.auth
        val firestore = fixture.firestore

        try {
            fixture.scenario("TaxonomyRepositoryIntegrationTest scenario") {
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
                val savedCategory = fixture.operation("TaxonomyRepositoryIntegrationTest wait 1") {
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
                val ordered = fixture.operation("TaxonomyRepositoryIntegrationTest wait 2") {
                    repository.observeOrderedCategories(householdId, uid).first { observation ->
                        observation.state == SyncState.SYNCED && observation.value.orEmpty().size == 2
                    }
                }
                assertEquals(listOf(secondCategory.id, category.id), ordered.value!!.map(Category::id))

                fixture.disableNetwork()
                val offlineOrder = CategoryOrder(householdId, uid, listOf(category.id, secondCategory.id))
                val pendingWrite = launch { repository.saveCategoryOrder(offlineOrder) }
                val pending = fixture.operation("TaxonomyRepositoryIntegrationTest wait 3") {
                    repository.observeCategoryOrder(householdId, uid).first { it.state == SyncState.PENDING }
                }
                assertEquals(offlineOrder.categoryIds, pending.value!!.categoryIds)
                fixture.enableNetwork()
                fixture.operation("TaxonomyRepositoryIntegrationTest wait 4") { pendingWrite.join() }

                val child = Subcategory(
                    id = "subcategory-$suffix", householdId = householdId, categoryId = category.id,
                    name = " Karma ", authorId = uid, updatedById = uid,
                )
                repository.save(child)
                repository.save(savedCategory.copy(name = "Zwierzęta domowe", archived = true, updatedById = uid))
                val archivedCategory = fixture.operation("TaxonomyRepositoryIntegrationTest wait 5") {
                    repository.observeCategories(householdId).first { observation ->
                        observation.value.orEmpty().any { it.id == category.id && it.archived }
                    }.value!!.single { it.id == category.id }
                }
                val savedSubcategory = fixture.operation("TaxonomyRepositoryIntegrationTest wait 6") {
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
            }
        } finally {
            fixture.close()
        }
    }
}
