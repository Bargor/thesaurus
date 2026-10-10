# Import/export contracts (format version 1)

Issue [#108](https://github.com/Bargor/thesaurus/issues/108) introduces file-layer contracts only. CSV parsing/export, Firestore writes, import preview/repair and Android document pickers are delivered by #109–#115. No import/export actions appear in Settings yet.

## JSON scope

`BackupDocument` contains explicit `formatVersion: 1`, export metadata (`exportedAt`, opaque `sourceHouseholdId`, optional `appVersion`), signed `openingBalanceGrosze`, categories, subcategories and entries. Metadata is supplied by the caller rather than regenerated during encoding, making repeated encoding deterministic.

Categories preserve names, optional color tokens, archive state, default direction and audit references. Subcategory identifiers are scoped by parent category. Entries preserve signed non-zero integer grosze, ISO calendar dates, optional title/subcategory, tags, author/editor references, timestamps and deletion state. Entry direction is not duplicated: signs retain the existing expense/income meaning. Opening balance may be zero.

The file has no users, e-mail addresses, passwords, tokens, authentication configuration, invitations or membership lists. Author/editor/deleter IDs are opaque references, not account exports. Files still contain private financial information and must be treated as sensitive; the document-picker feature will add the user-facing warning.

The DTOs are independent of domain document models, Firebase maps and `FieldValue`. They preserve nullable audit timestamps, including pending tombstone timestamps, as allowed by existing in-memory models. A tombstone must identify its deleter; an active entry cannot carry deletion metadata. This does not authorize replaying pending/audit values directly into Firestore: later import coordinators must apply current ownership, synchronization and server-write rules.

## Encoding and validation

The codec uses UTF-8, exact JSON integer tokens for `Long` money, ISO dates and UTC ISO instants. Collection order is canonical by category ID, `(categoryId, subcategoryId)` and entry ID; tags are sorted without silently truncating or normalizing raw values. Optional nulls are emitted explicitly. Duplicate tags are rejected rather than discarded. Blank optional titles can be preserved at the file boundary; later import policy applies the app's trimmed/null title semantics.

[Gson's strict streaming APIs](https://github.com/google/gson/blob/gson-parent-2.14.0/gson/src/main/java/com/google/gson/stream/JsonReader.java) are pinned at 2.14.0. No reflective DTO construction or floating-point conversion is used. Malformed UTF-8/JSON, duplicate keys, unexpected fields/container shapes, quoted/fractional/exponent/overflowing money, invalid dates/times, unsupported versions and broken references return typed controlled errors. Parser causes and offending file values are not attached to those errors. Future-version headers are rejected before their following payload is interpreted.

Unknown fields and wrong container shapes are rejected before their contents are materialized. Array limits are checked during reading, and bytes/depth are bounded. Field limits follow existing domain/rule constraints: identifiers 128 characters, taxonomy names 60, titles 160, up to 10 tags of 40 characters each. IDs cannot contain `/`. Calendar dates use four-digit years; rejecting dates after today's date belongs to the import validation stage, not a locale-dependent codec.

## Resource limits

| Boundary | Limit |
| --- | --- |
| JSON/CSV transport file | 16 MiB |
| Ledger entries / source rows | 50,000 |
| Categories | 2,000 |
| Subcategories | 10,000 |
| JSON nesting | 32 |
| Raw CSV cell transport length | 16,384 characters |

The raw-cell limit is distinct from valid ledger-field limits: a 161-character title must remain available to row validation/repair instead of disappearing. CSV parser enforcement is implemented in #110. Category order's existing 200-ID preference limit is not treated as a taxonomy limit.

## Shared CSV/import state

`CsvColumnMapping` uses optional zero-based indices while mapping is incomplete; date, amount and category are required for `isComplete`. Type, title, subcategory and tags are optional. Assigned columns must be distinct and nonnegative.

`ImportRow` retains its positive original row number and raw cells, together with field/row `ValidationIssue` values. Issues use safe codes and field names so later UI can provide Polish resource-based messages. Row-associated issues must match their source row.

`ImportSummary` includes validation counts and disjoint outcome buckets: added, repaired-and-added, duplicates, discarded, server errors and pending. Repaired-and-added rows are not also counted in added. Counts are nonnegative and cannot exceed total source rows; error issues expose row-level failures. It contains no persistence or retry logic by itself.

## Verification and delivery sequence

JVM tests cover round trips, Polish text, signed `Long` extremes, ISO/locale independence, canonical ordering, strict malformed input, relationships and limits, plus mapping/row/summary invariants. Android integration tests exercise the same codec through owned temporary cache files without Firebase writes or changes to interactive DEV data.

The delivery sequence is #108 → #109/#110/#113; #111 follows #110, #112 follows #111, #114 joins CSV/JSON/UI flows, and #115 adds complete end-to-end verification/documentation. Each feature uses its own PR. Local tests run before PR submission, and the DEV emulator is launched after each feature at the user's request. This first foundation has no new visible controls to manually exercise.
