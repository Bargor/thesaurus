package pl.bargor.thesaurus.data.firebase

import com.google.firebase.firestore.DocumentSnapshot
import com.google.firebase.firestore.FieldValue
import com.google.firebase.firestore.FirebaseFirestore
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.tasks.await
import pl.bargor.thesaurus.data.model.SyncObservation
import pl.bargor.thesaurus.data.model.User

internal class FirestoreUserRepository(private val firestore: FirebaseFirestore) : UserRepository {
    override fun observeUser(userId: String): Flow<SyncObservation<User>> =
        firestore.collection(FirestorePaths.USERS).document(userId).observations(DocumentSnapshot::toUser)

    override suspend fun save(user: User) {
        firestore.collection(FirestorePaths.USERS).document(user.id)
            .update(
                mapOf(
                    "displayName" to user.displayName?.trim()?.takeIf(String::isNotEmpty),
                    "updatedAt" to FieldValue.serverTimestamp(),
                ),
            ).await()
    }
}
