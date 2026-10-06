package pl.bargor.thesaurus.data.firebase

import com.google.firebase.firestore.DocumentSnapshot
import com.google.firebase.firestore.FirebaseFirestore
import com.google.firebase.firestore.MetadataChanges
import com.google.firebase.firestore.SetOptions
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.tasks.await
import pl.bargor.thesaurus.data.model.Household
import pl.bargor.thesaurus.data.model.Member
import pl.bargor.thesaurus.data.model.SyncObservation
import pl.bargor.thesaurus.data.model.SyncState

internal class FirestoreHouseholdRepository(private val firestore: FirebaseFirestore) : HouseholdRepository {
    private fun household(id: String) = firestore.collection(FirestorePaths.HOUSEHOLDS).document(id)

    override fun observeHousehold(
        householdId: String,
    ): Flow<SyncObservation<Household>> = callbackFlow {
        val registration = household(householdId)
            .addSnapshotListener(MetadataChanges.INCLUDE) { value, error ->
                when {
                    error != null -> trySend(
                        SyncObservation(state = SyncState.ERROR, error = error),
                    )
                    value != null -> trySend(
                        SyncObservation(
                            value = value.toHousehold(),
                            state = syncState(value.metadata),
                        ),
                    )
                }
            }
        awaitClose {
            registration.remove()
        }
    }

    override fun observeMembers(householdId: String): Flow<SyncObservation<List<Member>>> =
        household(householdId).collection(FirestorePaths.MEMBERS).observations(DocumentSnapshot::toMember)

    override suspend fun saveHousehold(household: Household) {
        household(household.id).set(household.toDocument(), SetOptions.merge()).await()
    }

    override suspend fun removeMember(householdId: String, memberId: String) {
        household(householdId).collection(FirestorePaths.MEMBERS).document(memberId).delete().await()
    }
}
