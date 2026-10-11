package pl.bargor.thesaurus.data.transfer

import java.security.MessageDigest
import java.time.LocalDate
import java.util.Locale
import java.util.UUID
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import pl.bargor.thesaurus.data.model.*

data class CsvImportIdentity(val uid: String, val sessionKey: String)
data class CsvImportSnapshot(
    val identity: CsvImportIdentity,
    val household: Household,
    val entries: List<LedgerEntry>,
    val categories: List<Category>,
    val subcategories: List<Subcategory>,
    val synchronized: Boolean = true,
    private val indexedCategories: Map<String, Category> = categories.associateBy { it.id },
    private val indexedSubcategories: Map<Pair<String, String>, Subcategory> = subcategories.associateBy { it.categoryId to it.id },
) {
    fun category(id: String): Category? = indexedCategories[id]
    fun subcategory(categoryId: String, id: String): Subcategory? = indexedSubcategories[categoryId to id]
}
data class CsvImportCommitResult(val revision: Long, val added: Boolean)

/** Implementations must fence revision, principal, owner and active taxonomy atomically.
 * Failed writes must be terminal before returning; never enqueue an offline batch. */
interface CsvImportBackend {
    fun currentIdentity(): CsvImportIdentity?
    suspend fun loadSnapshot(householdId: String, expectedIdentity: CsvImportIdentity): CsvImportSnapshot
    suspend fun commitEntry(snapshot: CsvImportSnapshot, entry: LedgerEntry): CsvImportCommitResult
    suspend fun createCategory(snapshot: CsvImportSnapshot, category: Category) { error("Unsupported taxonomy creation") }
    suspend fun createSubcategory(snapshot: CsvImportSnapshot, subcategory: Subcategory) { error("Unsupported taxonomy creation") }
}

enum class CsvImportRowStatus { INVALID, PENDING, ADDED, REPAIRED_AND_ADDED, DUPLICATE, DISCARDED, SERVER_ERROR }
data class CsvImportRowResult(val source: ImportRow, val status: CsvImportRowStatus, val entry: LedgerEntry? = null)
data class CsvImportDraft(val rowNumber: Int, val cells: List<String>, val categoryId: String? = null,
    val subcategoryId: String? = null, val selectTaxonomy: Boolean = false, val selectSubcategory: Boolean = false)
data class CsvImportReport(
    val rows: List<CsvImportRowResult>,
    val summary: ImportSummary,
    /** Original file values remain available even after a repair changes the working cells. */
    val originalRows: List<ImportRow> = emptyList(),
)

class CsvImportSession internal constructor(
    internal var snapshot: CsvImportSnapshot,
    internal val parsed: CsvParseResult,
    internal val mapping: CsvColumnMapping,
    internal val dateFormat: CsvDateFormat,
) {
    internal val mutex = Mutex()
    internal var categoriesByName = snapshot.categories.groupBy { it.name.trim().lowercase(Locale.ROOT) }
    internal var subcategoriesByName = snapshot.subcategories.groupBy { it.categoryId to it.name.trim().lowercase(Locale.ROOT) }
    internal val repaired = mutableSetOf<Int>()
    internal val selectedCategories = mutableMapOf<Int, String>()
    internal val selectedSubcategories = mutableMapOf<Int, String?>()
    internal val sources = parsed.rows.associateBy { it.rowNumber }.toMutableMap()
    internal val rowIndices = parsed.rows.mapIndexed { index, row -> row.rowNumber to index }.toMap()
    internal val rows = mutableListOf<CsvImportRowResult>()
    internal fun updateRow(index: Int, row: CsvImportRowResult) { synchronized(rows) { rows[index] = row } }
    val report: CsvImportReport get() {
        val copy = synchronized(rows) { rows.map { it.copy(source = it.source.copy(cells = it.source.cells.toList(), issues = it.source.issues.toList()), entry = it.entry?.copy(tags = it.entry.tags.toList())) } }
        fun count(status: CsvImportRowStatus) = copy.count { it.status == status }
        val invalid = copy.count { it.entry == null }
        return CsvImportReport(copy, ImportSummary(copy.size, copy.size - invalid, invalid,
            copy.flatMap { it.source.issues }, count(CsvImportRowStatus.ADDED),
            count(CsvImportRowStatus.REPAIRED_AND_ADDED), count(CsvImportRowStatus.DUPLICATE),
            count(CsvImportRowStatus.DISCARDED), count(CsvImportRowStatus.SERVER_ERROR),
            count(CsvImportRowStatus.PENDING)),
            parsed.rows.map { it.copy(cells = it.cells.toList(), issues = it.issues.toList()) })
    }
}

/** Fingerprints omit IDs and audit data; a length prefix makes arbitrary Unicode unambiguous. */
fun csvImportFingerprint(entry: LedgerEntry): String {
    val fields = listOf(entry.date.toString(), entry.amountGrosze.toString(), entry.normalizedTitle.orEmpty(),
        entry.categoryId, entry.subcategoryId.orEmpty()) + entry.normalizedTags
    val encoded = fields.joinToString("") { "${it.length}:$it" }
    return MessageDigest.getInstance("SHA-256").digest(encoded.toByteArray(Charsets.UTF_8))
        .joinToString("") { "%02x".format(it.toInt() and 255) }
}

class CsvImportCoordinator(private val backend: CsvImportBackend, private val today: () -> LocalDate = { LocalDate.now() }) {
    fun currentIdentity(): CsvImportIdentity? = backend.currentIdentity()
    fun taxonomy(session: CsvImportSession): Pair<List<Category>, List<Subcategory>> = session.snapshot.categories to session.snapshot.subcategories
    suspend fun prepare(householdId: String, parsed: CsvParseResult, mapping: CsvColumnMapping,
        dateFormat: CsvDateFormat = CsvDateFormat.AUTO): CsvImportSession {
        require(parsed.rows.map { it.rowNumber }.distinct().size == parsed.rows.size)
        val identity = checkNotNull(backend.currentIdentity()) { "IMPORT_AUTH_REQUIRED" }
        val snapshot = backend.loadSnapshot(householdId, identity)
        checkSnapshot(snapshot, householdId, identity)
        val session = CsvImportSession(snapshot, parsed.copy(headers = parsed.headers.toList(), rows = parsed.rows.map { it.copy(cells = it.cells.toList(), issues = it.issues.toList()) }), mapping, dateFormat)
        session.rows.addAll(session.parsed.rows.map { validate(session, it) })
        return session
    }

    suspend fun commit(session: CsvImportSession, onRow: suspend (CsvImportRowResult) -> Unit = {}): CsvImportReport = session.mutex.withLock { commitLocked(session, onRow = onRow) }

    suspend fun draft(session: CsvImportSession, rowNumber: Int, cells: List<String>, categoryId: String? = null,
        subcategoryId: String? = null, selectTaxonomy: Boolean = false, selectSubcategory: Boolean = false): CsvImportRowResult =
        draftBatch(session, listOf(CsvImportDraft(rowNumber, cells, categoryId, subcategoryId, selectTaxonomy, selectSubcategory))).single()

    suspend fun draftBatch(session: CsvImportSession, drafts: List<CsvImportDraft>): List<CsvImportRowResult> = session.mutex.withLock {
        require(drafts.map { it.rowNumber }.distinct().size == drafts.size)
        refresh(session)
        drafts.forEach { draft ->
            val index = session.rowIndices.getValue(draft.rowNumber)
            require(session.rows[index].status in attentionStatuses)
            ImportRow(draft.rowNumber, draft.cells)
            if (draft.selectTaxonomy) {
                require(draft.categoryId != null && session.snapshot.category(draft.categoryId)?.archived == false)
                require(draft.subcategoryId == null || session.snapshot.subcategory(draft.categoryId, draft.subcategoryId)?.archived == false)
            }
        }
        drafts.map { draft ->
            val previous = session.sources.getValue(draft.rowNumber)
            fun changed(column: Int?): Boolean = column != null && previous.cells.getOrElse(column) { "" } != draft.cells.getOrElse(column) { "" }
            val categoryChanged = changed(session.mapping.category)
            val parentChanged = draft.selectTaxonomy && session.selectedCategories[draft.rowNumber]?.let { it != draft.categoryId } == true
            if (categoryChanged && !draft.selectTaxonomy) session.selectedCategories.remove(draft.rowNumber)
            if (categoryChanged || parentChanged || changed(session.mapping.subcategory)) session.selectedSubcategories.remove(draft.rowNumber)
            if (draft.selectTaxonomy) {
                session.selectedCategories[draft.rowNumber] = checkNotNull(draft.categoryId)
                if (draft.selectSubcategory) session.selectedSubcategories[draft.rowNumber] = draft.subcategoryId
            }
            session.repaired.add(draft.rowNumber)
            val source = ImportRow(draft.rowNumber, draft.cells.toList())
            session.sources[draft.rowNumber] = source
            validate(session, source).also { session.updateRow(session.rowIndices.getValue(draft.rowNumber), it) }
        }
    }

    /** Reauthorize the current owner; confirmed local outcomes remain terminal even if history was edited. */
    suspend fun restoreRows(session: CsvImportSession, rows: List<CsvImportRowResult>, repaired: Set<Int>,
        savedFingerprints: Map<Int, String> = emptyMap(), categorySelections: Map<Int, String> = emptyMap(),
        subcategorySelections: Map<Int, String?> = emptyMap()) = session.mutex.withLock {
        refresh(session)
        val fingerprints = session.snapshot.entries.mapTo(mutableSetOf(), ::csvImportFingerprint)
        val entriesByFingerprint = session.snapshot.entries.associateBy(::csvImportFingerprint)
        val entriesById = session.snapshot.entries.associateBy { it.id }
        val saved = rows.associateBy { it.source.rowNumber }
        session.repaired.addAll(repaired)
        categorySelections.forEach { (number, id) ->
            if (number in session.rowIndices && session.snapshot.category(id)?.archived == false) {
                session.selectedCategories[number] = id
                if (subcategorySelections.containsKey(number)) {
                    val subId = subcategorySelections[number]
                    if (subId == null || session.snapshot.subcategory(id, subId)?.archived == false) session.selectedSubcategories[number] = subId
                }
            }
        }
        session.rows.indices.forEach { index ->
            val row = saved[session.rows[index].source.rowNumber] ?: return@forEach
            session.sources[row.source.rowNumber] = row.source
            val checked = validate(session, row.source)
            val savedFingerprint = savedFingerprints[row.source.rowNumber] ?: row.entry?.let(::csvImportFingerprint)
            val savedEntry = entriesByFingerprint[savedFingerprint] ?: savedFingerprint?.let { entriesById["csv_$it"] }
            val terminal = row.status in setOf(CsvImportRowStatus.DISCARDED, CsvImportRowStatus.ADDED, CsvImportRowStatus.REPAIRED_AND_ADDED, CsvImportRowStatus.DUPLICATE)
            val result = if (terminal) checked.copy(status = row.status, entry = savedEntry ?: checked.entry)
                else if (row.status == CsvImportRowStatus.SERVER_ERROR && checked.entry != null) checked.copy(status = row.status,
                    source = checked.source.copy(issues = checked.source.issues + row.source.issues.filter { it.code == "IMPORT_WRITE_FAILED" })) else checked
            session.updateRow(index, if (terminal && row.status != CsvImportRowStatus.DISCARDED && savedEntry == null &&
                checked.entry?.let { csvImportFingerprint(it) in fingerprints } != true)
                result.copy(source = result.source.copy(issues = result.source.issues + ValidationIssue("IMPORT_RESTORE_HISTORY_CHANGED",
                    rowNumber = row.source.rowNumber, severity = ValidationSeverity.WARNING))) else result)
        }
    }

    suspend fun repair(session: CsvImportSession, rowNumber: Int, cells: List<String>,
        onRow: suspend (CsvImportRowResult) -> Unit = {}): CsvImportReport = session.mutex.withLock {
        val index = session.rows.indexOfFirst { it.source.rowNumber == rowNumber }
        require(index >= 0)
        require(session.rows[index].status in setOf(CsvImportRowStatus.INVALID, CsvImportRowStatus.SERVER_ERROR, CsvImportRowStatus.PENDING))
        refresh(session)
        session.repaired.add(rowNumber)
        val source = ImportRow(rowNumber, cells.toList())
        session.sources[rowNumber] = source
        session.updateRow(index, validate(session, source))
        commitLocked(session, setOf(rowNumber), onRow)
    }

    suspend fun discard(session: CsvImportSession, rowNumber: Int): CsvImportReport = session.mutex.withLock {
        refresh(session)
        val index = session.rows.indexOfFirst { it.source.rowNumber == rowNumber }
        require(index >= 0)
        val row = session.rows[index]
        require(row.status in setOf(CsvImportRowStatus.INVALID, CsvImportRowStatus.SERVER_ERROR, CsvImportRowStatus.PENDING))
        session.updateRow(index, row.copy(status = CsvImportRowStatus.DISCARDED))
        session.report
    }

    suspend fun discardRemaining(session: CsvImportSession): CsvImportReport = session.mutex.withLock {
        refresh(session)
        session.rows.indices.forEach { index ->
            val row = session.rows[index]
            if (row.status in attentionStatuses) session.updateRow(index, row.copy(status = CsvImportRowStatus.DISCARDED))
        }
        session.report
    }

    suspend fun createCategory(session: CsvImportSession, name: String): Category = session.mutex.withLock {
        refresh(session)
        require(session.snapshot.categories.none { key(it.name) == key(name) }) { "IMPORT_CATEGORY_EXISTS" }
        val uid = session.snapshot.identity.uid
        val category = Category(UUID.randomUUID().toString(), session.snapshot.household.id, name.trim(), authorId = uid, updatedById = uid)
        backend.createCategory(session.snapshot, category)
        refresh(session)
        category
    }

    suspend fun createSubcategory(session: CsvImportSession, categoryId: String, name: String): Subcategory = session.mutex.withLock {
        refresh(session)
        require(session.snapshot.categories.any { it.id == categoryId && !it.archived })
        require(session.snapshot.subcategories.none { it.categoryId == categoryId && key(it.name) == key(name) })
        val uid = session.snapshot.identity.uid
        val subcategory = Subcategory(UUID.randomUUID().toString(), session.snapshot.household.id, categoryId, name.trim(), authorId = uid, updatedById = uid)
        backend.createSubcategory(session.snapshot, subcategory)
        refresh(session)
        subcategory
    }

    private suspend fun commitLocked(session: CsvImportSession, only: Set<Int>? = null, onRow: suspend (CsvImportRowResult) -> Unit = {}): CsvImportReport {
        refresh(session)
        val fingerprints = session.snapshot.entries.mapTo(mutableSetOf(), ::csvImportFingerprint)
        for (index in session.rows.indices) {
            var row = session.rows[index]
            if (only != null && row.source.rowNumber !in only) continue
            if (row.status !in setOf(CsvImportRowStatus.PENDING, CsvImportRowStatus.SERVER_ERROR, CsvImportRowStatus.INVALID)) continue
            row = validate(session, session.sources.getValue(row.source.rowNumber))
            val entry = row.entry
            if (entry != null) {
                val fingerprint = csvImportFingerprint(entry)
                if (fingerprint in fingerprints) row = row.copy(status = CsvImportRowStatus.DUPLICATE)
                else try {
                    checkIdentity(session)
                    val result = backend.commitEntry(session.snapshot, entry)
                    session.snapshot = session.snapshot.copy(household = session.snapshot.household.copy(ledgerRevision = result.revision))
                    fingerprints.add(fingerprint)
                    row = row.copy(status = if (!result.added) CsvImportRowStatus.DUPLICATE
                        else if (row.source.rowNumber in session.repaired) CsvImportRowStatus.REPAIRED_AND_ADDED else CsvImportRowStatus.ADDED)
                } catch (cancelled: CancellationException) { throw cancelled }
                catch (_: Exception) {
                    row = row.copy(status = CsvImportRowStatus.SERVER_ERROR,
                        source = row.source.copy(issues = row.source.issues + ValidationIssue("IMPORT_WRITE_FAILED", rowNumber = row.source.rowNumber)))
                    // Refresh only after failure: normal import reads the full ledger once, not for each row.
                    try { refresh(session); fingerprints.clear(); session.snapshot.entries.mapTo(fingerprints, ::csvImportFingerprint) }
                    catch (cancelled: CancellationException) { throw cancelled }
                    catch (_: Exception) {
                        session.updateRow(index, row)
                        onRow(row)
                        break
                    }
                }
            }
            session.updateRow(index, row)
            onRow(row)
        }
        return session.report
    }

    private fun validate(session: CsvImportSession, source: ImportRow): CsvImportRowResult {
        val checked = CsvRowValidator.validate(session.parsed.copy(rows = listOf(source)), session.mapping, today(), session.dateFormat)
        val parsedRow = checked.rows.single()
        val value = parsedRow.entry ?: return CsvImportRowResult(parsedRow.source.copy(issues = parsedRow.source.issues +
            if (checked.requiresManualMapping) listOf(ValidationIssue("IMPORT_MAPPING_REQUIRED", rowNumber = source.rowNumber)) else emptyList()), CsvImportRowStatus.INVALID)
        fun failure(code: String, field: String) = CsvImportRowResult(parsedRow.source.copy(issues = parsedRow.source.issues +
            ValidationIssue(code, field, source.rowNumber)), CsvImportRowStatus.INVALID)
        val categories = session.selectedCategories[source.rowNumber]?.let { listOfNotNull(session.snapshot.category(it)) }
            ?: session.categoriesByName[key(value.categoryName)].orEmpty()
        if (categories.size != 1) return failure(if (categories.isEmpty()) "IMPORT_UNKNOWN_CATEGORY" else "IMPORT_AMBIGUOUS_CATEGORY", "category")
        val category = categories.single()
        if (category.archived) return failure("IMPORT_ARCHIVED_CATEGORY", "category")
        val sub = if (session.selectedSubcategories.containsKey(source.rowNumber)) session.selectedSubcategories[source.rowNumber]?.let {
            listOfNotNull(session.snapshot.subcategory(category.id, it))
        } else value.subcategoryName?.let { name ->
            session.subcategoriesByName[category.id to key(name)].orEmpty()
        }
        if (sub != null && sub.size != 1) return failure(if (sub.isEmpty()) "IMPORT_UNKNOWN_SUBCATEGORY" else "IMPORT_AMBIGUOUS_SUBCATEGORY", "subcategory")
        if (sub?.single()?.archived == true) return failure("IMPORT_ARCHIVED_SUBCATEGORY", "subcategory")
        val uid = session.snapshot.identity.uid
        val tags = try { normalizeTags(value.tags) }
        catch (_: IllegalArgumentException) { return failure("IMPORT_INVALID_TAGS", "tags") }
        val entry = LedgerEntry("temporary", session.snapshot.household.id, value.amountGrosze, value.date,
            value.title?.trim()?.takeIf { it.isNotEmpty() }, category.id, sub?.single()?.id, tags, uid, uid)
        return CsvImportRowResult(parsedRow.source, CsvImportRowStatus.PENDING, entry.copy(id = "csv_${csvImportFingerprint(entry)}"))
    }

    private fun key(value: String) = value.trim().lowercase(Locale.ROOT)
    private val attentionStatuses = setOf(CsvImportRowStatus.INVALID, CsvImportRowStatus.SERVER_ERROR, CsvImportRowStatus.PENDING)
    private fun checkIdentity(session: CsvImportSession) { check(backend.currentIdentity() == session.snapshot.identity) { "IMPORT_SESSION_CHANGED" } }
    private suspend fun refresh(session: CsvImportSession) {
        checkIdentity(session)
        val snapshot = backend.loadSnapshot(session.snapshot.household.id, session.snapshot.identity)
        checkSnapshot(snapshot, session.snapshot.household.id, session.snapshot.identity)
        session.snapshot = snapshot
        session.categoriesByName = snapshot.categories.groupBy { key(it.name) }
        session.subcategoriesByName = snapshot.subcategories.groupBy { it.categoryId to key(it.name) }
    }
    private fun checkSnapshot(snapshot: CsvImportSnapshot, householdId: String, identity: CsvImportIdentity) {
        check(snapshot.identity == identity && backend.currentIdentity() == identity && snapshot.synchronized &&
            snapshot.household.id == householdId && snapshot.household.ownerId == identity.uid &&
            snapshot.entries.all { it.householdId == householdId } && snapshot.categories.all { it.householdId == householdId } &&
            snapshot.subcategories.all { it.householdId == householdId }) { "IMPORT_OWNER_SYNC_REQUIRED" }
    }
}
