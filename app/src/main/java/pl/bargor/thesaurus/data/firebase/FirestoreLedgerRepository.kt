package pl.bargor.thesaurus.data.firebase

import com.google.firebase.firestore.DocumentSnapshot
import com.google.firebase.firestore.FieldValue
import com.google.firebase.firestore.FirebaseFirestore
import com.google.firebase.firestore.Query
import com.google.firebase.firestore.SetOptions
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.tasks.await
import pl.bargor.thesaurus.data.model.LedgerEntry
import pl.bargor.thesaurus.data.model.SyncObservation

internal class FirestoreLedgerRepository(private val firestore: FirebaseFirestore) : LedgerRepository {
    private fun household(id: String) = firestore.collection(FirestorePaths.HOUSEHOLDS).document(id)

    override fun observeEntries(
        householdId: String,
        includeDeleted: Boolean,
    ): Flow<SyncObservation<List<LedgerEntry>>> {
        var query: Query = household(householdId)
            .collection(FirestorePaths.ENTRIES)
            .orderBy("date", Query.Direction.DESCENDING)
        if (!includeDeleted) query = query.whereEqualTo("deleted", false)
        return query.observations(DocumentSnapshot::toLedgerEntry)
    }

    override suspend fun save(entry: LedgerEntry) {
        val reference = household(entry.householdId)
        val entryReference = reference
            .collection(FirestorePaths.ENTRIES)
            .document(entry.id)
        firestore.runBatch { batch ->
            batch.set(entryReference, entry.toDocument(), SetOptions.merge())
            batch.update(reference, "ledgerRevision", FieldValue.increment(1L))
        }.await()
    }

    override suspend fun tombstone(householdId: String, entryId: String, actorId: String) {
        val reference = household(householdId)
        val entryReference = reference
            .collection(FirestorePaths.ENTRIES)
            .document(entryId)
        firestore.runBatch { batch ->
            batch.update(
                entryReference,
                mapOf(
                    "deleted" to true,
                    "deletedById" to actorId,
                    "deletedAt" to FieldValue.serverTimestamp(),
                    "updatedById" to actorId,
                    "updatedAt" to FieldValue.serverTimestamp(),
                ),
            )
            batch.update(reference, "ledgerRevision", FieldValue.increment(1L))
        }.await()
    }
}
