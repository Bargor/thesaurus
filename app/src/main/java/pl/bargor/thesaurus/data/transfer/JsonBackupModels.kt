package pl.bargor.thesaurus.data.transfer

import pl.bargor.thesaurus.data.model.*

/** Complete, server-confirmed household state. Member IDs are authorization references only. */
data class JsonBackupSnapshot(
    val identity: CsvImportIdentity,
    val household: Household,
    val entries: List<LedgerEntry>,
    val categories: List<Category>,
    val subcategories: List<Subcategory>,
    val memberIds: Set<String>,
    val synchronized: Boolean = true,
)

sealed interface JsonMergeOperation {
    data class OpeningBalance(val amountGrosze: Long) : JsonMergeOperation
    data class NewCategory(val category: Category) : JsonMergeOperation
    data class NewSubcategory(val subcategory: Subcategory) : JsonMergeOperation
    data class NewEntry(val entry: LedgerEntry) : JsonMergeOperation
}

enum class JsonMergeWriteOutcome { ADDED, SKIPPED, CONFLICT }
data class JsonMergeWriteResult(val revision: Long, val outcome: JsonMergeWriteOutcome)

/** All writes are bounded one-operation transactions. Fence principal/owner/revision and
 * selected taxonomy documents; never overwrite an occupied ID or queue offline writes.
 * Audit timestamps are server timestamps, regardless of nullable model timestamps. */
interface JsonBackupBackend {
    fun currentIdentity(): CsvImportIdentity?
    suspend fun loadSnapshot(householdId: String, expectedIdentity: CsvImportIdentity,
        requireOwner: Boolean = true): JsonBackupSnapshot
    suspend fun commit(snapshot: JsonBackupSnapshot, operation: JsonMergeOperation): JsonMergeWriteResult
}

enum class JsonMergeItemKind { OPENING_BALANCE, CATEGORY, SUBCATEGORY, ENTRY }
enum class JsonMergeStatus { NEW, SKIPPED, CONFLICT, ADDED, SERVER_ERROR }
data class JsonMergeItem(val kind: JsonMergeItemKind, val sourceId: String?,
    val targetId: String?, val status: JsonMergeStatus, val code: String? = null)
data class JsonMergeReport(val items: List<JsonMergeItem>) {
    val newCount: Int get() = items.count { it.status == JsonMergeStatus.NEW }
    val addedCount: Int get() = items.count { it.status == JsonMergeStatus.ADDED }
    val skippedCount: Int get() = items.count { it.status == JsonMergeStatus.SKIPPED }
    val conflictCount: Int get() = items.count { it.status == JsonMergeStatus.CONFLICT }
    val errorCount: Int get() = items.count { it.status == JsonMergeStatus.SERVER_ERROR }
}

class JsonBackupException(val code: String) : IllegalStateException(code)
data class JsonBackupExportArtifact(val bytes: ByteArray, val suggestedFilename: String)

class JsonMergeSession internal constructor(
    internal var snapshot: JsonBackupSnapshot,
    internal val operations: Map<Int, JsonMergeOperation>,
    internal val results: MutableList<JsonMergeItem>,
) {
    internal var committed = false
    val report: JsonMergeReport get() = synchronized(results) { JsonMergeReport(results.toList()) }
}
