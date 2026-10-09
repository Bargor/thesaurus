package pl.bargor.thesaurus.data.firebase

import com.google.firebase.firestore.FirebaseFirestore
import com.google.firebase.firestore.SetOptions
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.tasks.await
import pl.bargor.thesaurus.data.model.Category
import pl.bargor.thesaurus.data.model.CategoryOrder
import pl.bargor.thesaurus.data.model.Subcategory
import pl.bargor.thesaurus.data.model.SyncObservation

internal class FirestoreTaxonomyRepository(private val firestore: FirebaseFirestore) : TaxonomyRepository {
    private fun household(id: String) = firestore.collection(FirestorePaths.HOUSEHOLDS).document(id)

    override fun observeCategories(householdId: String): Flow<SyncObservation<List<Category>>> =
        household(householdId)
            .collection(FirestorePaths.CATEGORIES)
            .observations(mapper = { it.toCategory(expectedHouseholdId = householdId) },
                transform = ::orderedCategories)

    override fun observeSubcategories(
        householdId: String,
        categoryId: String,
    ): Flow<SyncObservation<List<Subcategory>>> = household(householdId)
        .collection(FirestorePaths.CATEGORIES)
        .document(categoryId)
        .collection(FirestorePaths.SUBCATEGORIES)
        .observations(mapper = { it.toSubcategory(expectedHouseholdId = householdId, expectedCategoryId = categoryId) },
            transform = ::orderedSubcategories)

    override suspend fun save(category: Category) {
        household(category.householdId)
            .collection(FirestorePaths.CATEGORIES)
            .document(category.id)
            .set(category.toDocument(), SetOptions.merge())
            .await()
    }

    override suspend fun save(subcategory: Subcategory) {
        household(subcategory.householdId)
            .collection(FirestorePaths.CATEGORIES)
            .document(subcategory.categoryId)
            .collection(FirestorePaths.SUBCATEGORIES)
            .document(subcategory.id)
            .set(subcategory.toDocument(), SetOptions.merge())
            .await()
    }

    override fun observeCategoryOrder(
        householdId: String,
        userId: String,
    ): Flow<SyncObservation<CategoryOrder>> = firestore
        .collection(FirestorePaths.USERS)
        .document(userId)
        .collection(FirestorePaths.CATEGORY_ORDERS)
        .document(householdId)
        .observations { it.toCategoryOrder(expectedHouseholdId = householdId, expectedUserId = userId) }

    override suspend fun saveCategoryOrder(order: CategoryOrder) {
        firestore.collection(FirestorePaths.USERS)
            .document(order.userId)
            .collection(FirestorePaths.CATEGORY_ORDERS)
            .document(order.householdId)
            .set(order.toDocument())
            .await()
    }
}
