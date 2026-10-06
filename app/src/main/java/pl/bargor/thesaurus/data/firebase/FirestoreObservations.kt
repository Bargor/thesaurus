package pl.bargor.thesaurus.data.firebase

import com.google.firebase.firestore.DocumentReference
import com.google.firebase.firestore.DocumentSnapshot
import com.google.firebase.firestore.MetadataChanges
import com.google.firebase.firestore.Query
import com.google.firebase.firestore.SnapshotMetadata
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow
import pl.bargor.thesaurus.data.model.SyncObservation
import pl.bargor.thesaurus.data.model.SyncState

fun syncState(hasPendingWrites: Boolean, fromCache: Boolean): SyncState = when {
    hasPendingWrites -> SyncState.PENDING
    fromCache -> SyncState.OFFLINE
    else -> SyncState.SYNCED
}

fun syncState(metadata: SnapshotMetadata): SyncState = syncState(metadata.hasPendingWrites(), metadata.isFromCache)

internal fun <T> Query.observations(
    mapper: (DocumentSnapshot) -> T?,
): Flow<SyncObservation<List<T>>> = callbackFlow {
    val registration = addSnapshotListener(MetadataChanges.INCLUDE) { snapshot, error ->
        when {
            error != null -> trySend(SyncObservation(state = SyncState.ERROR, error = error))
            snapshot != null -> trySend(
                SyncObservation(
                    value = snapshot.documents.mapNotNull(mapper),
                    state = syncState(snapshot.metadata),
                ),
            )
        }
    }
    awaitClose { registration.remove() }
}

internal fun <T> DocumentReference.observations(
    mapper: (DocumentSnapshot) -> T?,
): Flow<SyncObservation<T>> = callbackFlow {
    val registration = addSnapshotListener(MetadataChanges.INCLUDE) { snapshot, error ->
        when {
            error != null -> trySend(SyncObservation(state = SyncState.ERROR, error = error))
            snapshot != null -> trySend(
                SyncObservation(
                    value = mapper(snapshot),
                    state = syncState(snapshot.metadata),
                ),
            )
        }
    }
    awaitClose { registration.remove() }
}
