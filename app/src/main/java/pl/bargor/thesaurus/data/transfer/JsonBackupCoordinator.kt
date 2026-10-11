package pl.bargor.thesaurus.data.transfer

import java.time.Instant
import java.time.LocalDate
import java.time.ZoneOffset
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import pl.bargor.thesaurus.data.model.normalizeTags

/** The complete file is validated before the first server read or any write. Preparation
 * is read-only; commit rechecks the exact preview snapshot and never expands its plan. */
class JsonBackupCoordinator(
    private val backend: JsonBackupBackend,
    private val today: () -> LocalDate = { LocalDate.now() },
) {
    private val mutex = Mutex()
    fun currentIdentity(): CsvImportIdentity? = backend.currentIdentity()

    /** Same owner, identity and synchronized server guard used immediately before import. */
    suspend fun checkImportReadiness(householdId: String) {
        val identity = backend.currentIdentity() ?: throw JsonBackupException("JSON_AUTH_REQUIRED")
        load(householdId, identity, true)
    }

    suspend fun exportCsv(householdId: String, exportedAt: Instant): CsvExportArtifact {
        val identity = backend.currentIdentity() ?: throw JsonBackupException("JSON_AUTH_REQUIRED")
        val snapshot = load(householdId, identity, false)
        val document = JsonBackupExporter.toDocument(snapshot, exportedAt)
        return CsvExporter.export(document.entries, CsvNameLookup(
            categoryNames = document.categories.associate { it.id to it.name },
            subcategoryNames = document.subcategories.associate { (it.categoryId to it.id) to it.name },
        ), exportedAt.atZone(ZoneOffset.UTC).toLocalDate())
    }

    suspend fun prepare(householdId: String, bytes: ByteArray): JsonMergeSession {
        val document = BackupJsonCodec.decode(bytes)
        val currentDate = today()
        if (document.entries.any { it.date.isAfter(currentDate) }) throw JsonBackupException("JSON_FUTURE_DATE")
        try { document.entries.forEach { normalizeTags(it.tags) } }
        catch (_: IllegalArgumentException) { throw JsonBackupException("JSON_INVALID_NORMALIZED_VALUE") }
        val identity = backend.currentIdentity() ?: throw JsonBackupException("JSON_AUTH_REQUIRED")
        val snapshot = load(householdId, identity, true)
        return JsonMergePlanner.plan(snapshot, document)
    }

    suspend fun export(householdId: String, exportedAt: Instant,
        appVersion: String? = null): JsonBackupExportArtifact {
        val identity = backend.currentIdentity() ?: throw JsonBackupException("JSON_AUTH_REQUIRED")
        val snapshot = load(householdId, identity, false)
        return JsonBackupExporter.export(snapshot, exportedAt, appVersion)
    }

    suspend fun commit(session: JsonMergeSession,
        onItem: suspend (JsonMergeItem) -> Unit = {}): JsonMergeReport = mutex.withLock {
        if (session.committed) return@withLock session.report
        checkIdentity(session.snapshot.identity)
        val fresh = load(session.snapshot.household.id, session.snapshot.identity, true)
        if (!sameSnapshot(fresh, session.snapshot)) throw JsonBackupException("JSON_PREVIEW_STALE")
        session.committed = true
        // Supply all planned taxonomy expectations once, rather than copying a growing
        // taxonomy list after every write. Parents are committed before their consumers;
        // a failed operation stops the loop, so an uncommitted parent is never used.
        session.snapshot = session.snapshot.copy(
            categories = session.snapshot.categories + session.operations.values.mapNotNull {
                (it as? JsonMergeOperation.NewCategory)?.category },
            subcategories = session.snapshot.subcategories + session.operations.values.mapNotNull {
                (it as? JsonMergeOperation.NewSubcategory)?.subcategory })
        // Opening balance is first, then parents, children and entries. Each backend call
        // is a single transaction. On any failure retain confirmed progress and stop.
        for ((index, operation) in session.operations) {
            var stop = false
            try {
                checkIdentity(session.snapshot.identity)
                val result = backend.commit(session.snapshot, operation)
                val status = when (result.outcome) {
                    JsonMergeWriteOutcome.ADDED -> JsonMergeStatus.ADDED
                    JsonMergeWriteOutcome.SKIPPED -> JsonMergeStatus.SKIPPED
                    JsonMergeWriteOutcome.CONFLICT -> JsonMergeStatus.CONFLICT
                }
                val item = session.results[index].copy(status = status,
                    code = if (status == JsonMergeStatus.CONFLICT) "JSON_WRITE_CONFLICT" else null)
                synchronized(session.results) { session.results[index] = item }
                if (result.outcome == JsonMergeWriteOutcome.ADDED) {
                    session.snapshot = when (operation) {
                        is JsonMergeOperation.OpeningBalance -> session.snapshot.copy(household = session.snapshot.household.copy(
                            openingBalanceGrosze = operation.amountGrosze, ledgerRevision = result.revision))
                        is JsonMergeOperation.NewCategory -> session.snapshot
                        is JsonMergeOperation.NewSubcategory -> session.snapshot
                        is JsonMergeOperation.NewEntry -> session.snapshot.copy(household = session.snapshot.household.copy(ledgerRevision = result.revision))
                    }
                } else {
                    session.snapshot = session.snapshot.copy(household = session.snapshot.household.copy(ledgerRevision = result.revision))
                }
                // A changed dependency cannot authorize the already-previewed descendants.
                stop = result.outcome != JsonMergeWriteOutcome.ADDED
            } catch (cancelled: CancellationException) { throw cancelled }
            catch (_: Exception) {
                val item = session.results[index].copy(status = JsonMergeStatus.SERVER_ERROR, code = "JSON_IMPORT_WRITE_FAILED")
                synchronized(session.results) { session.results[index] = item }
                stop = true
            }
            // Delivery errors are not transaction errors: an acknowledged write remains
            // ADDED even if a caller's progress callback fails or cancels its coroutine.
            onItem(session.results[index])
            if (stop) break
        }
        session.report
    }

    private suspend fun load(householdId: String, identity: CsvImportIdentity, owner: Boolean): JsonBackupSnapshot {
        checkIdentity(identity)
        val value = try { backend.loadSnapshot(householdId, identity, owner) }
        catch (cancelled: CancellationException) { throw cancelled }
        catch (_: Exception) { throw JsonBackupException("JSON_SERVER_SNAPSHOT_REQUIRED") }
        checkIdentity(identity)
        if (value.identity != identity || !value.synchronized || value.household.id != householdId ||
            identity.uid !in value.memberIds || (owner && value.household.ownerId != identity.uid) ||
            value.categories.any { it.householdId != householdId } || value.subcategories.any { it.householdId != householdId } ||
            value.entries.any { it.householdId != householdId }) throw JsonBackupException("JSON_AUTHORIZED_SYNC_REQUIRED")
        val categoryIds = value.categories.mapTo(mutableSetOf()) { it.id }
        val subcategoryIds = value.subcategories.mapTo(mutableSetOf()) { it.categoryId to it.id }
        if (value.categories.size > BackupLimits.MAX_CATEGORIES || value.subcategories.size > BackupLimits.MAX_SUBCATEGORIES ||
            value.entries.size > BackupLimits.MAX_ENTRIES || categoryIds.size != value.categories.size ||
            subcategoryIds.size != value.subcategories.size || value.entries.mapTo(mutableSetOf()) { it.id }.size != value.entries.size ||
            value.subcategories.any { it.categoryId !in categoryIds } || value.entries.any {
                it.categoryId !in categoryIds || (it.subcategoryId != null && it.categoryId to it.subcategoryId !in subcategoryIds)
            }) throw JsonBackupException("JSON_SERVER_SNAPSHOT_REQUIRED")
        return value.copy(entries = value.entries.map { it.copy(tags = it.tags.toList()) },
            categories = value.categories.toList(), subcategories = value.subcategories.toList(), memberIds = value.memberIds.toSet())
    }

    private fun checkIdentity(identity: CsvImportIdentity) {
        if (identity.uid.isBlank() || identity.uid != identity.uid.trim() || identity.uid.length > 128 ||
            '/' in identity.uid || identity.sessionKey.isBlank() || identity.sessionKey != identity.sessionKey.trim())
            throw JsonBackupException("JSON_AUTH_REQUIRED")
        if (backend.currentIdentity() != identity) throw JsonBackupException("JSON_SESSION_CHANGED")
    }

    private fun sameSnapshot(a: JsonBackupSnapshot, b: JsonBackupSnapshot): Boolean =
        a.identity == b.identity && a.household == b.household && a.memberIds == b.memberIds &&
            a.categories.associateBy { it.id } == b.categories.associateBy { it.id } &&
            a.subcategories.associateBy { it.categoryId to it.id } == b.subcategories.associateBy { it.categoryId to it.id } &&
            a.entries.associateBy { it.id } == b.entries.associateBy { it.id }
}

/** Pure export transformation: all financial/taxonomy/audit fields, no account payload. */
object JsonBackupExporter {
    fun export(snapshot: JsonBackupSnapshot, exportedAt: Instant,
        appVersion: String? = null): JsonBackupExportArtifact {
        val document = toDocument(snapshot, exportedAt, appVersion)
        return JsonBackupExportArtifact(BackupJsonCodec.encode(document),
            "thesaurus-${exportedAt.atZone(ZoneOffset.UTC).toLocalDate()}.json")
    }

    fun toDocument(snapshot: JsonBackupSnapshot, exportedAt: Instant,
        appVersion: String? = null): BackupDocument = BackupDocument(ExportMetadata(exportedAt, snapshot.household.id, appVersion),
            snapshot.household.openingBalanceGrosze,
            snapshot.categories.map { BackupCategory(it.id, it.name, it.authorId, it.updatedById,
                it.color, it.archived, TransferEntryType.valueOf(it.defaultEntryType.name), it.createdAt, it.updatedAt) },
            snapshot.subcategories.map { BackupSubcategory(it.id, it.categoryId, it.name, it.authorId,
                it.updatedById, it.archived, it.createdAt, it.updatedAt) },
            snapshot.entries.map { BackupEntry(it.id, it.amountGrosze, it.date, it.categoryId, it.authorId,
                it.updatedById, it.title, it.subcategoryId, it.tags, it.createdAt, it.updatedAt,
                it.deleted, it.deletedAt, it.deletedById) })
}
