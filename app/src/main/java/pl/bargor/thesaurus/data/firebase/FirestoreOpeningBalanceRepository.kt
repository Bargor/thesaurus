package pl.bargor.thesaurus.data.firebase

import com.google.firebase.firestore.FieldValue
import com.google.firebase.firestore.FirebaseFirestore
import com.google.firebase.firestore.Source
import kotlinx.coroutines.tasks.await
import pl.bargor.thesaurus.data.model.SyncState
import pl.bargor.thesaurus.data.model.deriveOpeningBalance

internal class FirestoreOpeningBalanceRepository(private val firestore: FirebaseFirestore) : OpeningBalanceRepository {
    private fun household(id: String) = firestore.collection(FirestorePaths.HOUSEHOLDS).document(id)

    override suspend fun saveOpeningBalance(householdId: String, amountGrosze: Long) {
        household(householdId).update(
            mapOf("openingBalanceGrosze" to amountGrosze, "updatedAt" to FieldValue.serverTimestamp()),
        ).await()
    }

    override suspend fun saveCurrentBalance(householdId: String, currentBalanceGrosze: Long) {
        commitCurrentBalance(prepareCurrentBalance(householdId, currentBalanceGrosze))
    }

    internal suspend fun prepareCurrentBalance(
        householdId: String,
        currentBalanceGrosze: Long,
    ): PreparedCurrentBalance {
        val reference = household(householdId)
        val before = reference.get(Source.SERVER).await()
        check(before.exists() && syncState(before.metadata) == SyncState.SYNCED) {
            "Bieżące saldo wymaga zsynchronizowanego gospodarstwa."
        }
        val snapshot = reference.collection(FirestorePaths.ENTRIES).get(Source.SERVER).await()
        check(syncState(snapshot.metadata) == SyncState.SYNCED &&
            snapshot.documents.none { it.metadata.hasPendingWrites() }) {
            "Bieżące saldo wymaga pełnej synchronizacji wpisów."
        }
        val entries = snapshot.documents.map { document ->
            check(document.get("amountGrosze") is Long && document.get("deleted") is Boolean &&
                document.getString("householdId") == householdId) { "Nie można odczytać wszystkich wpisów." }
            checkNotNull(document.toLedgerEntry()) { "Nie można odczytać wszystkich wpisów." }
        }
        val after = reference.get(Source.SERVER).await()
        check(after.exists() && syncState(after.metadata) == SyncState.SYNCED &&
            before.ledgerRevision() == after.ledgerRevision()) {
            "Wpisy zmieniły się podczas obliczania salda. Spróbuj ponownie."
        }
        val rawOpening = after.get("openingBalanceGrosze")
        check(!after.contains("openingBalanceGrosze") || rawOpening is Long) { "Nieprawidłowe saldo początkowe." }
        return PreparedCurrentBalance(
            householdId = householdId,
            expectedRevision = after.ledgerRevision(),
            expectedOpeningBalanceGrosze = rawOpening as Long?,
            derivedOpeningBalanceGrosze = deriveOpeningBalance(currentBalanceGrosze, entries),
        )
    }

    internal suspend fun commitCurrentBalance(prepared: PreparedCurrentBalance) {
        val reference = household(prepared.householdId)
        firestore.runTransaction { transaction ->
            val latest = transaction.get(reference)
            check(latest.exists() && !latest.metadata.hasPendingWrites() &&
                latest.ledgerRevision() == prepared.expectedRevision &&
                latest.get("openingBalanceGrosze") == prepared.expectedOpeningBalanceGrosze) {
                "Saldo lub wpisy zmieniły się podczas zapisu. Spróbuj ponownie."
            }
            transaction.update(reference, mapOf(
                "openingBalanceGrosze" to prepared.derivedOpeningBalanceGrosze,
                "updatedAt" to FieldValue.serverTimestamp(),
            ))
        }.await()
    }
}
