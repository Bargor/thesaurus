package pl.bargor.thesaurus.data.transfer

import java.time.Instant
import java.time.LocalDate

/** File values only: user IDs below are opaque audit references, never account exports. */
enum class TransferEntryType { INCOME, EXPENSE }

data class ExportMetadata(
    val exportedAt: Instant,
    val sourceHouseholdId: String,
    val appVersion: String? = null,
)

/** Single-household ledger snapshot; categories/entries inherit metadata.sourceHouseholdId. */
data class BackupDocument(
    val metadata: ExportMetadata,
    val openingBalanceGrosze: Long,
    val categories: List<BackupCategory> = emptyList(),
    val subcategories: List<BackupSubcategory> = emptyList(),
    val entries: List<BackupEntry> = emptyList(),
    val formatVersion: Int = 1,
)

data class BackupCategory(
    val id: String,
    val name: String,
    val authorId: String,
    val updatedById: String,
    val color: String? = null,
    val archived: Boolean = false,
    val defaultEntryType: TransferEntryType = TransferEntryType.EXPENSE,
    val createdAt: Instant? = null,
    val updatedAt: Instant? = null,
)

data class BackupSubcategory(
    val id: String,
    val categoryId: String,
    val name: String,
    val authorId: String,
    val updatedById: String,
    val archived: Boolean = false,
    val createdAt: Instant? = null,
    val updatedAt: Instant? = null,
)

/**
 * Null audit timestamps preserve pending domain snapshots. A tombstone always has deletedById,
 * while deletedAt can still be null until its timestamp is resolved. Active entries have neither.
 */
data class BackupEntry(
    val id: String,
    val amountGrosze: Long,
    val date: LocalDate,
    val categoryId: String,
    val authorId: String,
    val updatedById: String,
    val title: String? = null,
    val subcategoryId: String? = null,
    val tags: List<String> = emptyList(),
    val createdAt: Instant? = null,
    val updatedAt: Instant? = null,
    val deleted: Boolean = false,
    val deletedAt: Instant? = null,
    val deletedById: String? = null,
)

/** Zero-based indices; partial mappings can be saved before their required columns are chosen. */
data class CsvColumnMapping(
    val date: Int? = null,
    val amount: Int? = null,
    val title: Int? = null,
    val category: Int? = null,
    val subcategory: Int? = null,
    val tags: Int? = null,
    val type: Int? = null,
) {
    init {
        val assigned = listOfNotNull(date, amount, title, category, subcategory, tags, type)
        require(assigned.all { it >= 0 }) { "Column indices must be nonnegative." }
        require(assigned.toSet().size == assigned.size) { "Columns must have distinct indices." }
    }
    val isComplete: Boolean get() = date != null && amount != null && category != null
}

/** Raw source cells stay intact for later row validation, including invalid ledger values. */
data class ImportRow(
    val rowNumber: Int,
    val cells: List<String>,
    val issues: List<ValidationIssue> = emptyList(),
) {
    init {
        require(rowNumber > 0) { "Source row numbers must be positive." }
        require(cells.all { it.length <= BackupLimits.MAX_RAW_FIELD_CHARS }) { "Raw field exceeds the transport limit." }
        require(issues.all { it.rowNumber == null || it.rowNumber == rowNumber }) { "Issue row must match its source row." }
    }
}
enum class ValidationSeverity { ERROR, WARNING }
/** Codes and field names are safe diagnostics; raw file values are deliberately excluded. */
data class ValidationIssue(
    val code: String,
    val field: String? = null,
    val rowNumber: Int? = null,
    val severity: ValidationSeverity = ValidationSeverity.ERROR,
) {
    init {
        require(code.isNotBlank()) { "Issue codes must be nonblank." }
        require(field == null || field.isNotBlank()) { "Issue fields must be nonblank when present." }
        require(rowNumber == null || rowNumber > 0) { "Issue row numbers must be positive." }
    }
}
data class ImportSummary(
    val totalRows: Int,
    val validRows: Int,
    val invalidRows: Int,
    val issues: List<ValidationIssue> = emptyList(),
    val added: Int = 0,
    val repairedAndAdded: Int = 0,
    val duplicates: Int = 0,
    val discarded: Int = 0,
    val serverErrors: Int = 0,
    val pending: Int = 0,
) {
    init {
        val counts = listOf(totalRows, validRows, invalidRows, added, repairedAndAdded,
            duplicates, discarded, serverErrors, pending)
        require(counts.all { it >= 0 }) { "Summary counts must be nonnegative." }
        require(validRows.toLong() + invalidRows <= totalRows) { "Validation counts exceed source rows." }
        // Outcome buckets are disjoint: repairedAndAdded is not also counted in added.
        require(listOf(added, repairedAndAdded, duplicates, discarded, serverErrors, pending)
            .sumOf { it.toLong() } <= totalRows) { "Outcome counts exceed source rows." }
    }
    val rowFailures: List<ValidationIssue>
        get() = issues.filter { it.rowNumber != null && it.severity == ValidationSeverity.ERROR }
}

enum class TransferErrorCode {
    MALFORMED_JSON, MALFORMED_UTF8, UNSUPPORTED_VERSION, INVALID_VALUE, LIMIT_EXCEEDED,
    MALFORMED_CSV, AMBIGUOUS_DELIMITER,
}

/** No parser cause, offending value or path can escape into logs/UI. */
class BackupContractException(val code: TransferErrorCode) :
    IllegalArgumentException("Backup contract error: ${code.name}")

object BackupLimits {
    const val MAX_BYTES = 16 * 1024 * 1024
    const val MAX_ENTRIES = 50_000
    const val MAX_CATEGORIES = 2_000
    const val MAX_SUBCATEGORIES = 10_000
    const val MAX_JSON_DEPTH = 32
    const val MAX_RAW_FIELD_CHARS = 16 * 1024
    const val MAX_CSV_COLUMNS = 128
}
