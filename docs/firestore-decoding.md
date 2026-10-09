# Strict Firestore decoding

## Complete financial snapshots or an explicit error

Firestore rules protect normal client writes, but imported, administrative, legacy, or incorrectly typed records can bypass those assumptions. Every existing document must pass its decoder before a repository publishes its snapshot. Collection observation is atomic at this boundary: one malformed document yields `SyncObservation(state = ERROR, value = null, error = FirestoreDecodeException(...))`, not a successful list of the remaining records. Mapper/model failures are caught inside the snapshot callback; coroutine cancellation and fatal VM errors are not converted into data errors. The listener stays registered after decoding errors and can recover when the stored data is corrected.

Ledger and taxonomy observation read their complete scoped collections. Firestore `orderBy` and field predicates can omit documents missing those fields before a decoder sees them, so validation precedes local sorting and tombstone filtering. All ledger records, including tombstones, are checked; malformed deletion metadata cannot silently remove financial history. Valid ledger results retain the previous repository ordering (date descending, document ID descending), with tombstones omitted unless requested. Taxonomy keeps name ascending, then ID ascending. Local text comparisons preserve Firestore's [UTF-8 byte ordering](https://firebase.google.com/docs/firestore/manage-data/data-types), including emoji. Screens continue to apply their own documented sorting.

This is not database cursor paging: complete validation includes archived/deleted history and increases the records observed compared with an active-only query. The existing local reveal limit bounds rendering, not Firestore downloads. Rules, indexes, serialization, and write protocols are unchanged; this feature neither deploys production rules nor repairs/deletes stored records.

## Field contracts

`FirestoreDocumentDecoder` validates plain maps and is shared by SDK snapshot adapters and current-balance preparation. It also audits user, household, member, category, subcategory, category-order, and invitation records.

- Money must be a Firestore integer (`Long`), never a coerced `Number`, `Double`, decimal string, or boolean. Ledger zero is invalid; both signed `Long` boundaries are valid. Household opening balance may be zero; ledger revision must be nonnegative.
- IDs must be nonblank, bounded strings without path separators. Nested records must agree with their enclosing household/category/user context. A category-order document must also agree with its document ID.
- Dates must be a valid ISO calendar date in the persisted four-digit-year format. Decoding does not capture a new "today": entry-form future-date validation and report eligibility retain their independent contracts.
- Required booleans, enums, strings, and audit timestamp fields cannot silently fall back on wrong types or unknown values. Timestamps preserve nanoseconds. An unresolved server timestamp may be present as `null` only while the document has pending writes; a missing required timestamp remains an error even then. Invitation expiry must be resolved, and acceptance status must agree with its accepted-user metadata.
- Optional title and display name are trimmed, with blank text becoming `null`. Optional IDs and colors reject invalid values; optional fields never silently accept an incompatible type. Tags must contain strings within the stored size/length limits, and retain the existing case-insensitive normalization. Category order cannot contain duplicate IDs.
- Active entries cannot contain deletion metadata. Tombstones require a deleting user and deletion timestamp; the latter may be unresolved for a pending server-timestamp write.

Supported legacy omissions are explicit: absent household opening balance/revision mean zero; absent/null category color retains the normal palette fallback; absent category direction means expense; absent tags mean an empty list. These defaults do not apply to present incompatible values. Missing audit metadata is not treated as a supported legacy omission. Unknown extra fields are not used to compute financial values.

An absent single document is a valid `null` observation (for example, no saved category-order preference). An existing but malformed document is not equivalent to absence.

## Unavailable state and recovery

The malformed source's cached decoded value is discarded. Shared household observation remembers its invalidity through subsequent transport errors or metadata-only emissions; retrying the same household does not resurrect the cache. Only a fresh successful decoded snapshot clears it. A confirmed absent category-order preference also clears that preference's error.

While a subscribed source is invalid, dependent reports, summaries, overview tiles, and entry lists expose their existing error/retry UI without amounts, zero-total cards, charts, or stale rows. Period, filter, and sort interactions remain selected but cannot recompute old data as current. The global balance hides its amount when either ledger or household settings are invalid. Changing account/household clears the old state immediately.

Entry editing retains the user's unsaved text but invalidates the stored edit authority and prevents saving against a malformed base or taxonomy. Corrected data restores authority without replacing the draft. An already queued creation retains its ID across recovery, preventing duplicate retry entries. Ordinary transient transport errors keep their existing cached-data behavior and error/offline labels; strict invalid-data handling is a separate path.

Current-balance preparation uses the same decoders for complete server ledger/settings reads. Malformed records reject preparation before any balance transaction, rather than deriving an opening balance from partial or coerced values.

SDK visibility boundary: validation checks the exact representation delivered by Firestore, not a parallel REST database scan. The Android SDK's [numeric equality](https://github.com/firebase/firebase-android-sdk/blob/main/firebase-firestore/src/main/java/com/google/firebase/firestore/model/Values.kt) can consider an integer and an equal whole-number double equivalent; an already active query may suppress a type-only administrative update. Even [server query reads use snapshot listeners](https://github.com/firebase/firebase-android-sdk/blob/main/firebase-firestore/src/main/java/com/google/firebase/firestore/Query.java). Decoding cannot inspect an update the SDK does not deliver. A genuinely changed value/field or fresh delivered representation exposes the malformed type. Normal application writes remain constrained by Rules, and the decoder never converts a delivered double into integer grosze. Integration fixtures verify exact stored types and use distinct-value transitions where necessary rather than relying on type-only watch events.

## Private diagnostics

`FirestoreDecodeException` exposes document type, schema field, validation reason, and a full SHA-256 fingerprint of the scoped document path. It never includes financial payloads, original exception messages/causes, credentials, or raw account/document identifiers. `fingerprintFirestoreDocumentPath(knownPath)` lets a developer correlate a known candidate document with an error without logging the path or values. Fingerprints are diagnostics only and are not added to Firestore records.

## CI coverage and visual evidence

New JVM tests exercise field/type/boundary contracts, timestamp and deletion invariants, scope checks, private diagnostics, callback atomicity, cancellation/fatal propagation, and retained-state/retry/control regressions. Existing serialization and scenario assertions remain intact.

New emulator tests seed malformed records only through a test-only, fixed emulator REST endpoint in `demo-thesaurus-integration`. The helper verifies fixture ownership, binds the authenticated owner, limits writes to one owned household and UUID entry allowlist, disables redirects/proxies, bounds requests/responses and I/O, and disconnects on cancellation. There is no arbitrary-path, database-clear, production, or DEV write API. Tests cover missing date/deleted fields, incompatible money, offline cache validation, uninterrupted-listener recovery, balance hiding/recovery, and current-balance rejection without household mutations.

A synthetic Compose test captures report states before corruption, while unavailable, and after recovery into `Pictures/ThesaurusTestEvidence/issue96/` on disposable CI emulators. The CI harness retrieves that folder for PR review; it contains no real account data. All builds and tests for this change run in CI, not locally.

Reviewed captures from the passing API 31 run are available in [the visual proof](visual-proofs/issue96/README.md).
