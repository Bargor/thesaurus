package pl.bargor.thesaurus.data.firebase

import com.google.firebase.firestore.FieldValue
import com.google.firebase.firestore.FirebaseFirestore
import kotlinx.coroutines.tasks.await
import pl.bargor.thesaurus.data.model.MemberRole
import pl.bargor.thesaurus.data.onboarding.StarterTaxonomy
import java.util.Locale

internal class FirestoreOnboardingRepository(private val firestore: FirebaseFirestore) : OnboardingRepository {
    override suspend fun householdIdFor(uid: String): String? =
        firestore.collection(FirestorePaths.USERS).document(uid).get().await().toUser()?.householdId

    override suspend fun createFirstHousehold(
        identity: OnboardingIdentity,
        householdName: String,
    ): FirstHouseholdResult {
        val normalizedName = householdName.trim()
        require(normalizedName.isNotEmpty() && normalizedName.length <= 80) {
            "Nazwa gospodarstwa musi mieć od 1 do 80 znaków."
        }
        val users = firestore.collection(FirestorePaths.USERS)
        val user = users.document(identity.uid)
        // Generate once outside the transaction: retries always target the same candidate household.
        val household = firestore.collection(FirestorePaths.HOUSEHOLDS).document()
        val member = household.collection(FirestorePaths.MEMBERS).document(identity.uid)
        return firestore.runTransaction { transaction ->
            val existing = transaction.get(user).toUser()
            if (existing != null) {
                FirstHouseholdResult.Existing(existing.householdId)
            } else {
                val normalizedEmail = identity.email.trim().lowercase(Locale.ROOT)
                transaction.set(
                    user,
                    mapOf(
                        "email" to normalizedEmail,
                        "displayName" to identity.displayName?.trim()?.takeIf(String::isNotEmpty),
                        "householdId" to household.id,
                        "createdAt" to FieldValue.serverTimestamp(),
                        "updatedAt" to FieldValue.serverTimestamp(),
                    ),
                )
                transaction.set(
                    household,
                    mapOf(
                        "name" to normalizedName,
                        "ownerId" to identity.uid,
                        "createdAt" to FieldValue.serverTimestamp(),
                        "updatedAt" to FieldValue.serverTimestamp(),
                    ),
                )
                transaction.set(
                    member,
                    mapOf(
                        "email" to normalizedEmail,
                        "displayName" to identity.displayName?.trim()?.takeIf(String::isNotEmpty),
                        "role" to MemberRole.OWNER.name,
                        "invitationId" to null,
                        "joinedAt" to FieldValue.serverTimestamp(),
                    ),
                )
                StarterTaxonomy.categories.forEach { category ->
                    val categoryReference = household.collection(FirestorePaths.CATEGORIES).document(category.id)
                    transaction.set(
                        categoryReference,
                        mapOf(
                            "householdId" to household.id,
                            "name" to category.name,
                            "color" to category.color,
                            "archived" to false,
                            "defaultEntryType" to category.defaultEntryType.name,
                            "authorId" to identity.uid,
                            "updatedById" to identity.uid,
                            "createdAt" to FieldValue.serverTimestamp(),
                            "updatedAt" to FieldValue.serverTimestamp(),
                        ),
                    )
                    category.subcategories.forEach { subcategory ->
                        transaction.set(
                            categoryReference.collection(FirestorePaths.SUBCATEGORIES).document(subcategory.id),
                            mapOf(
                                "householdId" to household.id,
                                "categoryId" to category.id,
                                "name" to subcategory.name,
                                "archived" to false,
                                "authorId" to identity.uid,
                                "updatedById" to identity.uid,
                                "createdAt" to FieldValue.serverTimestamp(),
                                "updatedAt" to FieldValue.serverTimestamp(),
                            ),
                        )
                    }
                }
                FirstHouseholdResult.Created(household.id)
            }
        }.await()
    }
}
