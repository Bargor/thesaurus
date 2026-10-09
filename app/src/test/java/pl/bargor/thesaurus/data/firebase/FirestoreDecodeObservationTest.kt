package pl.bargor.thesaurus.data.firebase

import org.junit.Assert.*
import org.junit.Test
import kotlinx.coroutines.CancellationException
import pl.bargor.thesaurus.data.model.SyncState

class FirestoreDecodeObservationTest {
    @Test fun mixedSnapshotPublishesNoPartialValueAndLaterValidSnapshotRecovers() {
        val malformed = FirestoreDecodeException(FirestoreDocumentType.LEDGER_ENTRY, "date", FirestoreDecodeReason.MISSING_FIELD)
        val invalid = decodeCollectionObservation(listOf("valid", "invalid"), SyncState.OFFLINE,
            decoder = { if (it == "invalid") throw malformed else it })
        assertEquals(SyncState.ERROR, invalid.state)
        assertNull(invalid.value)
        assertSame(malformed, invalid.error)
        val corrected = decodeCollectionObservation(listOf("valid", "corrected"), SyncState.SYNCED, decoder = { it })
        assertEquals(SyncState.SYNCED, corrected.state)
        assertEquals(listOf("valid", "corrected"), corrected.value)
        assertNull(corrected.error)
    }

    @Test fun mapperOrPostDecodeFailuresAreSanitizedAndNeverEscapeCallbacks() {
        val invalid = decodeObservation<String>(SyncState.PENDING) { throw IllegalArgumentException("private payload") }
        assertEquals(SyncState.ERROR, invalid.state)
        assertNull(invalid.value)
        assertTrue(invalid.error is FirestoreDecodeException)
        assertNull(invalid.error?.cause)
        assertFalse(invalid.error.toString().contains("private payload"))
        val transformFailure = decodeCollectionObservation(listOf("valid"), SyncState.SYNCED, decoder = { it },
            transform = { throw IllegalStateException("private payload") })
        assertEquals(SyncState.ERROR, transformFailure.state)
        assertNull(transformFailure.value)
        val nullRecord = decodeCollectionObservation<String, String>(listOf("missing"), SyncState.SYNCED, decoder = { null })
        assertEquals(SyncState.ERROR, nullRecord.state)
        assertNull(nullRecord.value)
    }

    @Test fun genuinelyAbsentSingleDocumentRemainsAnOrdinaryNullSnapshot() {
        val missing = decodeObservation<String>(SyncState.SYNCED) { null }
        assertEquals(SyncState.SYNCED, missing.state)
        assertNull(missing.value)
        assertNull(missing.error)
    }

    @Test fun cancellationAndFatalErrorsAreNeverConvertedToDecodeFailures() {
        val cancellation = CancellationException("cancelled")
        assertSame(cancellation, assertThrows(CancellationException::class.java) {
            decodeObservation<String>(SyncState.SYNCED) { throw cancellation }
        })
        val fatal = AssertionError("fatal")
        assertSame(fatal, assertThrows(AssertionError::class.java) {
            decodeCollectionObservation<String, String>(listOf("entry"), SyncState.SYNCED, decoder = { throw fatal })
        })
    }
}
