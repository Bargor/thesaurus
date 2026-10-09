package pl.bargor.thesaurus.data.firebase

import com.google.firebase.firestore.DocumentReference
import com.google.firebase.firestore.DocumentSnapshot
import com.google.firebase.firestore.MetadataChanges
import com.google.firebase.firestore.Query
import com.google.firebase.firestore.SnapshotMetadata
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.CancellationException
import pl.bargor.thesaurus.data.model.SyncObservation
import pl.bargor.thesaurus.data.model.SyncState

fun syncState(hasPendingWrites: Boolean, fromCache: Boolean): SyncState = when {
    hasPendingWrites -> SyncState.PENDING
    fromCache -> SyncState.OFFLINE
    else -> SyncState.SYNCED
}

fun syncState(metadata: SnapshotMetadata): SyncState = syncState(metadata.hasPendingWrites(), metadata.isFromCache)

/** A callback either publishes its complete decoded snapshot or an error with no partial value. */
internal fun <T> decodeObservation(state: SyncState, decoder: () -> T?): SyncObservation<T> = try {
    SyncObservation(value = decoder(), state = state)
} catch (error: CancellationException) {
    throw error
} catch (error: Exception) {
    SyncObservation(state = SyncState.ERROR, error = if (error is FirestoreDecodeException) error else
        FirestoreDecodeException(FirestoreDocumentType.UNKNOWN, "_document", FirestoreDecodeReason.INVALID_VALUE))
}

internal fun <D, T> decodeCollectionObservation(
    documents: Iterable<D>,
    state: SyncState,
    decoder: (D) -> T?,
    transform: (List<T>) -> List<T> = { it },
): SyncObservation<List<T>> = decodeObservation(state) {
    transform(documents.map { document -> decoder(document) ?: throw FirestoreDecodeException(
        FirestoreDocumentType.UNKNOWN, "_document", FirestoreDecodeReason.MISSING_FIELD,
    ) })
}

internal fun <T> Query.observations(
    mapper: (DocumentSnapshot) -> T?,
    transform: (List<T>) -> List<T> = { it },
): Flow<SyncObservation<List<T>>> = callbackFlow {
    val registration = addSnapshotListener(MetadataChanges.INCLUDE) { snapshot, error ->
        when {
            error != null -> trySend(SyncObservation(state = SyncState.ERROR, error = error))
            snapshot != null -> trySend(
                decodeCollectionObservation(snapshot.documents, syncState(snapshot.metadata), mapper, transform),
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
                decodeObservation(syncState(snapshot.metadata)) { mapper(snapshot) },
            )
        }
    }
    awaitClose { registration.remove() }
}
