# Import/export contracts (format version 1)

Issue [#108](https://github.com/Bargor/thesaurus/issues/108) introduces file-layer contracts. CSV export (#109), parsing/validation (#110), owner-only persistence (#111), mapping/repair UI (#112) and full JSON backup merge (#113) build on those contracts. Android document pickers follow in #114. No import/export actions appear in production Settings yet.

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

### Full JSON backup and merge (issue #113)

`JsonBackupCoordinator.export` requires an authenticated household member and a complete server snapshot. It exports the opening balance, all categories/subcategories (including archived records and color/default direction), and active/deleted entries with their stored audit references/timestamps. Membership is consulted only for authorization and is never included in the file. Export returns complete validated UTF-8 bytes and `thesaurus-YYYY-MM-DD.json` (UTC export date); it does not open a destination file.

`prepare` decodes and validates the complete file before server reads or writes, rejects future dates and invalid domain-normalized values, and verifies the current owner against a complete, synchronized server snapshot. Duplicate destination IDs, broken destination references and oversized collections fail closed; queries use overflow sentinels, never silent truncation. Counts are bounded by the file contract for both existing and uniquely planned new records; a merge that would exceed those collection limits fails before any write. Membership reads have a separate 10,000-member safety cap.

The read-only preview distinguishes new, skipped and conflicting items, separately for the opening balance, categories, subcategories and entries. Explicit confirmation rereads and compares the exact preview snapshot; changed household state, membership, taxonomy or ledger requires a fresh preview rather than silently expanding the approved changes.

| Imported item | Additive merge policy |
| --- | --- |
| Opening balance | Equal values are skipped. A differing value is initialized only when the destination has zero balance, no entries and revision zero; otherwise it is a conflict and the current value stays unchanged. |
| Category | Match trimmed, case-insensitive name, effective color, archive state and default direction. Multiple matches are a conflict. Different content gets a new deterministic ID rather than overwriting the source ID. |
| Subcategory | Match normalized name/archive state under the remapped parent category. Conflicting parents propagate to their children. |
| Entry | Reserve known source identities first, then match date, signed grosze, normalized title/tags and remapped taxonomy one-to-one, preferring matching deletion state and author. Existing tombstones stay deleted. An incoming tombstone conflicting with an active record is reported as a conflict, not applied as deletion. |

SHA-256 IDs use the `json_` namespace: taxonomy IDs derive from content, while entry IDs derive from source household and source entry identity. Source ID collisions do not overwrite current documents. Occupied generated IDs with different content are conflicts. Known identities are reserved before content matching, so one existing entry cannot satisfy two source rows. Distinct equal-valued entries, including an active entry and tombstone with identical financial fields, retain their multiplicity in an empty destination. Reimporting an older backup cannot replace an edited record with the same known identity. Nullable legacy colors resolve using the original category ID so ID remapping cannot change their displayed color.

New-record authors whose UID still belongs to the current household are preserved; other source authors map to the importer. This is UID-based mapping, not e-mail matching. Import deliberately does **not** replay historical editor/deleter identities or timestamps: new records use the importing user and fresh server-created/updated/deleted timestamps. The backup itself retains the original audit values; restore preserves financial content, relationships and archive/deletion state, not the old server audit trail. Existing records are never modified.

Writes are one-operation transactions, with parents before children and the optional opening balance first. This bounds atomic work and [Firestore rule-access budgets](https://firebase.google.com/docs/firestore/security/rules-conditions#access_call_limits). Each transaction reauthorizes the owner, current author membership, household balance/revision fence and selected taxonomy. Ledger creates atomically increment `ledgerRevision`; initializing opening balance does not increment it. Server-only preflight respects disabled networking, and there are no queued offline import writes. Like CSV, an already dispatched Firestore transaction is awaited to its terminal result; it is not a hard network-acknowledgement timeout.

The first failed/conflicting transaction stops the remaining plan, retaining confirmed outcomes and pending items. Retry rereads the original file and server state into a **new preview requiring confirmation**. Content deduplication and deterministic insert-only IDs make retry safe after partial or unacknowledged success. Progress callbacks do not copy the complete report or reload the ledger per record, and callback failures cannot downgrade an acknowledged write.

Firestore rules add narrowly scoped, owner-only `json_<64 hex>` creation for archived taxonomy and historical entries with existing coherent taxonomy and current-family authors. All existing field validators, server audit checks, ledger revision fencing, immutable authorship and tombstone update prohibitions remain. These rules are not deployed to production automatically.

`JsonBackupController` and the Polish leaf UI expose preview, confirmation, scalar progress, conflict reasons, per-kind outcomes, retry and an export-artifact callback for #114. A retained ViewModel scope protects configuration changes; source bytes stay in private memory, not saved-instance-state Bundles. A process restart requires selecting the backup again; import then deduplicates confirmed server data. The DEV-only simulation uses this real flow with a synthetic memory backend, including archived/deleted data and partial failure, without accessing Firebase or the interactive household.

### CSV export (issue #109)

`CsvExporter.export(entries, names, exportDate)` accepts household-scoped `BackupEntry` values and explicit `CsvNameLookup` maps. The caller selects the household and provides readable names; this pure file layer never reads Firestore or a UI repository. Deleted entries are omitted, including when their unused fields are malformed. Active entries are sorted by accounting date ascending, then stable entry ID. Duplicate active IDs are rejected instead of silently dropping an entry.

The returned `CsvExportArtifact` contains complete validated bytes and the locale-independent suggested filename `thesaurus-YYYY-MM-DD.csv`. It does not open or close a destination stream. Date is supplied explicitly; exporting the same snapshot and export date produces identical bytes/name, independent of the device language. There is no document-picker action yet; that belongs to #114.

CSV uses UTF-8 with a single BOM (`EF BB BF`), a comma delimiter and `CRLF` after every record, including the header/final record. Common quoting follows [RFC 4180](https://www.rfc-editor.org/rfc/rfc4180.html): cells with commas, double quotes or line breaks are quoted; embedded double quotes are doubled. Text inside a cell is preserved, including its original newlines. The column order is fixed:

| Column | File representation |
| --- | --- |
| `date` | ISO `YYYY-MM-DD` |
| `amount` | Signed PLN amount, decimal dot, exactly two places; no grouping or currency symbol |
| `type` | `expense` for negative amounts, `income` for positive amounts |
| `title` | Original optional title; empty cell when absent |
| `category` | Readable category name, or `unknown-category` |
| `subcategory` | Name looked up by `(categoryId, subcategoryId)`, or `unknown-subcategory`; empty when unassigned |
| `tags` | Sorted JSON string array in one CSV cell; `[]` when empty |
| `author` | Readable author name supplied by the caller, or `unknown-author` |

Tags use a JSON array rather than a delimiter within the cell: literal commas, semicolons, quotes and line breaks in a tag remain unambiguous and reversible. The CSV importer in #110 must decode this representation. Tag case/whitespace is preserved, not silently normalized. Missing/blank historical names use neutral markers without exposing opaque IDs. Archived taxonomy names are still usable for active historical entries.

The export enforces 50,000 **active** rows and the 16 MiB byte limit (including BOM/escaping), and validates exported field lengths/Unicode before returning a file. Titles are limited to 160 characters, taxonomy names to 60, up to ten tags of 40 characters, and author labels to 254 (allowing e-mail labels when supplied by later integration). Unrelated unused lookup records and deletion/audit fields are not exported or validated. Zero entry amounts and dates outside four-digit years are rejected. Signed `Long` extremes are converted exactly without floating-point arithmetic.

CSV contains financial details and author labels. Treat it as sensitive. Import it into spreadsheet software using **UTF-8, comma delimiter and text column types** when preserving values; avoid automatic formula evaluation. Literal formula-like text is deliberately retained for lossless transfer. CSV quoting alone does not prevent [spreadsheet formula injection](https://community.owasp.org/attacks/CSV_Injection); do not blindly open files containing untrusted text as executable spreadsheet cells. A future UI warning is part of #114.

### CSV parsing and validation (issue #110)

Import has three independent stages: `CsvParser.parse(bytes, delimiter?)`, `CsvHeaderDetector.detect(headers, extraAliases?)`, and `CsvRowValidator.validate(parsed, mapping, today, dateFormat?, typeAliases?)`. These are pure file operations: they do not read accounts, resolve taxonomy IDs, authorize imports, or write to Firestore. The caller must handle `requiresManualMapping` before importing candidates. Unknown categories/subcategories and current-user ownership are decisions for #111; preview/repair UI belongs to #112, and file selection to #114.

The parser requires UTF-8 (optional single leading BOM), supports comma, semicolon and tab delimiters, doubled quotes, quoted delimiters and multiline fields, and accepts CRLF, LF or CR record endings. Separator detection never consults the device locale. An ambiguous separator produces `AMBIGUOUS_DELIMITER`; the caller can retry with an explicit supported delimiter. The first record is the header. Raw cell contents are retained, and `ImportRow.rowNumber` is the **physical starting line**, so a quoted multiline record advances the following row's source number. Empty records remain available as invalid rows rather than being silently dropped. A header-only file is valid and contains zero data rows.

Header recognition trims and lowercases with `Locale.ROOT`. Neutral headers take precedence over custom aliases; duplicate matching columns, or an alias matching multiple logical fields, require manual mapping even for optional fields. Unknown columns stay in the raw row. `author` is recognized for export compatibility but never becomes an identity for writing entries. Callers may extend aliases without changing the parser or the CSV schema.

| Logical field | Built-in headers (neutral first) |
| --- | --- |
| Date | `date`, `data`, `datum` |
| Amount | `amount`, `kwota`, `betrag`, `montant` |
| Type | `type`, `typ`, `art` |
| Title | `title`, `tytuł`, `tytul`, `titel`, `titre` |
| Category | `category`, `kategoria`, `kategorie`, `catégorie`, `categorie` |
| Subcategory | `subcategory`, `podkategoria`, `unterkategorie`, `sous-catégorie`, `sous-categorie` |
| Tags | `tags`, `tagi`, `schlagwörter`, `schlagworter`, `étiquettes`, `etiquettes` |
| Author | `author`, `autor`, `auteur` |

Required columns are date, amount and category. An incomplete mapping or an index outside the header width returns a pending validation result (`requiresManualMapping = true`), with raw rows retained and zero valid/invalid counts. A complete mapping validates each row independently: a wrong column count or invalid field does not prevent later valid rows from being returned. Each `CsvValidatedRow` retains its source and safe `ValidationIssue` codes; `entry` is null when that row has an error. No raw values are interpolated into diagnostic codes or exceptions.

Dates always accept strict ISO `YYYY-MM-DD`. `AUTO` also accepts dotted day-month-year (`D.M.YYYY`) and slash dates (`D/M/YYYY` or `M/D/YYYY`) only when one interpretation is valid, or both produce the same date. For example, `13/02/2024` and `02/13/2024` are February 13, while `01/02/2024` requires repair (`AMBIGUOUS_DATE`). A caller may explicitly select `DAY_MONTH_YEAR` or `MONTH_DAY_YEAR` for regional dates. Invalid calendar dates and dates after the explicitly supplied `today` produce row issues; neither locale nor a hidden clock chooses their meaning.

Amounts are ungrouped PLN with an optional sign and at most two decimal places, using a dot or comma. Decimal-comma cells must be quoted when the CSV delimiter is comma. Currency symbols, grouping separators, exponents, rounding, zero and overflowing grosze are rejected. Conversion is exact across the full signed `Long` range. With no declared type, the sign determines direction (unsigned positive amounts mean income). An unsigned magnitude with a declared type receives that direction; an explicit `+`/`-` sign must agree with the declared type. A conflict produces `AMOUNT_TYPE_CONFLICT`. Neutral `income`/`expense` always work; built-in aliases are `przychód`, `przychod`, `wpływ`, `wplyw`, `einnahme`, `revenu` and `wydatek`, `koszt`, `ausgabe`, `dépense`, `depense`, respectively. Extra aliases cannot replace built-in meanings.

Tags use the exporter's strict JSON string-array cell, or a blank cell for no tags. Delimiter-separated tag lists are not guessed: malformed JSON, non-string items, duplicate literal tags, blank tags, more than ten tags or tags longer than forty characters require row repair. Titles/names/tags retain their literal whitespace and case in this file layer; #111 applies domain normalization before duplicate detection and persistence. Empty optional title/subcategory cells are null; a supplied whitespace-only subcategory requires repair rather than becoming a name. Taxonomy names over sixty characters and titles over 160 characters are row errors and remain in the raw source for repair.

File failures throw sanitized `BackupContractException` codes (`MALFORMED_UTF8`, `MALFORMED_CSV`, `AMBIGUOUS_DELIMITER`, `LIMIT_EXCEEDED`) without a partial result or parser cause. Limits are 16 MiB including BOM, 50,000 data records, 128 cells per record and 16,384 UTF-16 code units per decoded raw cell. These transport limits are enforced during parsing, separately from stricter ledger-field row validation. Malformed quoting fails the whole file even after earlier valid records. UTF-16 and other non-UTF-8 encodings are not guessed.

The neutral CSV exported by #109, including JSON tags and signed extreme amounts, is automatically mapped regardless of UI language or device locale. Source author labels remain raw reference data only; the import coordinator must assign the authenticated importing user.

### CSV persistence coordinator (issue #111)

`CsvImportCoordinator` exposes `prepare`, `commit`, `repair`, `discard`, `createCategory` and `createSubcategory`, backed by the injectable `CsvImportBackend`. The production `FirestoreCsvImportBackend` is wired through Hilt and uses the current Firebase principal. Each session is tied to the original authenticated session and household. Server reads must confirm the user's assigned household, owner membership and household ownership; cached, pending, malformed or revision-changing snapshots abort preparation without a write. The full ledger (including tombstones) is read without pagination, with `ledgerRevision` checked before and after. Every commit/repair/discard/taxonomy operation refreshes and rechecks authorization; a changed sign-in cannot reuse an old session.

Category names are trimmed and matched case-insensitively using `Locale.ROOT`, and subcategory names are scoped by parent category. Missing, archived or ambiguous names remain invalid rows requiring a decision; there is no automatic taxonomy creation. Explicit owner creation can resolve them for a subsequent commit. Name resolution chooses a stable ID at the synchronized snapshot point; a transaction verifies the selected category/subcategory documents are unchanged and active. It does **not** promise a serializable lock on the whole taxonomy-name namespace: another same-name category created afterwards does not redirect an already-resolved ID. Existing rules and taxonomy writers are unchanged.

Duplicate fingerprints cover calendar date, signed grosze, trimmed/null title, category ID, optional subcategory ID and sorted, case-insensitively normalized tags. IDs/audit metadata are excluded. They are compared against the complete server history and successful earlier rows, using indexed lookups. A length-prefixed SHA-256 fingerprint supplies a deterministic `csv_…` document ID; an occupied ID with different content fails rather than overwriting. Tombstones participate in deduplication, so rereading a deleted import never resurrects it. Current importing user IDs always become both author and editor; the CSV author label is ignored. Timestamps come from the server.

Writes use **one-entry transactions** (entry plus household revision update), a bounded chunk size deliberately chosen for row-level failure isolation and Firestore rule-access budgets. Reads precede writes, and each successful new entry advances `ledgerRevision` by exactly one atomically. A stale snapshot fails without writing; the coordinator refreshes after an error so later rows can proceed when safe. There is no ledger-sized query or row-list copy for each normal successful row. Correct rows are saved without waiting for invalid rows to be repaired.

`CsvImportReport` contains row outcomes and disjoint summary buckets (added, repaired-and-added, duplicates, discarded, server errors, pending), safe row-indexed issues, current working cells and separately retained `originalRows`. `repair` revalidates and writes only the chosen row, checking duplicates again; rejected rows may be discarded without changing persisted entries. A failed transaction is reported as `IMPORT_WRITE_FAILED`, never with raw file or backend error messages. If a refreshed full server snapshot cannot be obtained after failure, remaining rows stay pending, while earlier confirmed commits remain saved. Resume or rereading a file deduplicates against confirmed server history before further writes.

There are no offline batches or optimistic import writes. Before each new transaction, a household preflight uses [Source.SERVER](https://firebase.google.com/docs/reference/android/com/google/firebase/firestore/Source) and rejects cached/pending data, an unavailable server or a changed revision. This also respects the SDK's `disableNetwork()` state, which the regression test showed was not sufficient by itself to stop direct transactions. Server reads have a 30-second deadline. Android Firestore does not expose cancellation of an already dispatched transaction: the backend waits non-cancellably for its terminal result, and checks a deadline inside its callback. This is **not** a hard deadline on a network acknowledgement. Caller cancellation may suppress delivery of an already-confirmed result; a subsequent resume observes that entry as a duplicate, with no second write. A fresh session's counters describe that retry, not the old session's historical success counts.

This stage is an application-layer coordinator, not an automatically deployed rules change. Member clients retain their existing ordinary single-entry permissions; the import API itself rejects non-owner principals. Document-picker actions follow in #114.

### CSV mapping and repair UI (issue #112)

`CsvImportController` exposes explicit commands and `CsvImportUiState`. Complete, unambiguous headers skip manual mapping; ambiguous or incomplete headers require confirmation. A source column cannot serve two logical fields. Import starts only after an explicit action, reports progress, and leaves invalid or failed rows in a repair queue with physical source row numbers, original cells and Polish field-specific errors. Editing, revalidation, saving and discarding are separate actions; finishing with unresolved rows requires confirmation before discarding them.

Taxonomy choices resolve to stable IDs, including when multiple names are ambiguous. Categories/subcategories are never created implicitly. Bulk category mapping affects only pending rows with exactly the same original category cell; subcategory mapping additionally requires the same original subcategory cell. Whitespace and case are not silently broadened for this matching. Completed rows are not rewritten. Final counters distinguish added, repaired-and-added, duplicate, discarded and unsaved rows.

The ViewModel keeps only a random checkpoint token, household ID and user ID in saved instance state. Raw financial cells stay in bounded, versioned private files under `noBackupFilesDir`, not Android Bundles or cloud backup. Atomic base checkpoints use generation-scoped, synchronized outcome journals and small atomic draft files. JSON is streamed with strict UTF-8, scalar/depth/file limits. At most four base sessions are retained; old sessions are pruned after fourteen days when another checkpoint is saved. These files contain financial source data, not authentication credentials.

Reconstruction rechecks the current server owner before exposing a session and never automatically writes entries. Confirmed terminal outcomes stay terminal even if entries or taxonomy names were subsequently edited. An unacknowledged write is deduplicated against server history on explicit resume. Configuration changes keep operations in a retained scope rather than cancelling them with the composition. Switching identity/household clears exposed state and cancels the old action scope.

The DEV-only preview activity uses the real screen/controller with a synthetic in-memory backend and separate private checkpoint directory. It cannot mutate the interactive DEV household. Its backend survives rotation through its ViewModel, but is not a persistent Firebase replacement across process death; reconstruction correctness is tested separately against a persistent test backend. The production screen has no file picker yet (#114).

### Summary contracts

`CsvColumnMapping` uses optional zero-based indices while mapping is incomplete; date, amount and category are required for `isComplete`. Type, title, subcategory and tags are optional. Assigned columns must be distinct and nonnegative.

`ImportRow` retains its positive original row number and raw cells, together with field/row `ValidationIssue` values. Issues use safe codes and field names so later UI can provide Polish resource-based messages. Row-associated issues must match their source row.

`ImportSummary` includes validation counts and disjoint outcome buckets: added, repaired-and-added, duplicates, discarded, server errors and pending. Repaired-and-added rows are not also counted in added. Counts are nonnegative and cannot exceed total source rows; error issues expose row-level failures. It contains no persistence or retry logic by itself.

## Verification and delivery sequence

JVM tests cover round trips, Polish text, signed `Long` extremes, ISO/locale independence, canonical ordering, strict malformed input, relationships and limits, plus mapping/row/summary invariants and coordinator failure policies. Android file tests use owned temporary cache files; coordinator integration tests use separate demo Firebase services to verify actual rules, normalized persistence, owner/member gates, revision races, partial network failures, repair and tombstone replay without changing interactive DEV data.

The delivery sequence is #108 → #109/#110/#113; #111 follows #110, #112 follows #111, #114 joins CSV/JSON/UI flows, and #115 adds complete end-to-end verification/documentation. Each feature uses its own PR. Local tests run before PR submission, and the DEV emulator is launched after each feature at the user's request. This first foundation has no new visible controls to manually exercise.
