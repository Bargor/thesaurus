package pl.bargor.thesaurus.ui.importcsv

import pl.bargor.thesaurus.data.model.Category
import pl.bargor.thesaurus.data.model.Subcategory
import pl.bargor.thesaurus.data.transfer.*

enum class CsvImportPhase { EMPTY, MAPPING, READY, IMPORTING, ATTENTION, RESTORED, COMPLETE }

data class CsvImportUiState(
    val phase: CsvImportPhase = CsvImportPhase.EMPTY,
    val headers: List<String> = emptyList(),
    val mapping: CsvColumnMapping = CsvColumnMapping(),
    val dateFormat: CsvDateFormat = CsvDateFormat.AUTO,
    val report: CsvImportReport? = null,
    val drafts: Map<Int, List<String>> = emptyMap(),
    val categories: List<Category> = emptyList(),
    val subcategories: List<Subcategory> = emptyList(),
    val processed: Int = 0,
    val total: Int = 0,
    val attention: Int = 0,
    val busy: Boolean = false,
    val errorCode: String? = null,
    val checkpointToken: String? = null,
    val selectedCategories: Map<Int, String> = emptyMap(),
    val selectedSubcategories: Map<Int, String?> = emptyMap(),
)

data class CsvImportCheckpoint(
    val householdId: String,
    val uid: String,
    val parsed: CsvParseResult,
    val mapping: CsvColumnMapping,
    val dateFormat: CsvDateFormat,
    val rows: List<CsvImportRowResult> = emptyList(),
    val drafts: Map<Int, List<String>> = emptyMap(),
    val categorySelections: Map<Int, String> = emptyMap(),
    val subcategorySelections: Map<Int, String?> = emptyMap(),
    val repaired: Set<Int> = emptySet(),
    val started: Boolean = false,
    val fingerprints: Map<Int, String> = emptyMap(),
    val mappingConfirmed: Boolean = false,
)

interface CsvImportCheckpointStore {
    suspend fun create(checkpoint: CsvImportCheckpoint): String
    suspend fun save(token: String, checkpoint: CsvImportCheckpoint)
    suspend fun append(token: String, row: CsvImportRowResult)
    suspend fun read(token: String): CsvImportCheckpoint?
    suspend fun saveDraft(token: String, rowNumber: Int, cells: List<String>) {
        val checkpoint = checkNotNull(read(token))
        save(token, checkpoint.copy(drafts = checkpoint.drafts + (rowNumber to cells)))
    }
}

class InMemoryCsvImportCheckpointStore : CsvImportCheckpointStore {
    private val checkpoints = mutableMapOf<String, CsvImportCheckpoint>()
    private val journals = mutableMapOf<String, MutableMap<Int, CsvImportRowResult>>()
    override suspend fun create(checkpoint: CsvImportCheckpoint): String = java.util.UUID.randomUUID().toString().also { checkpoints[it] = checkpoint }
    override suspend fun save(token: String, checkpoint: CsvImportCheckpoint) { checkpoints[token] = checkpoint; journals.remove(token) }
    override suspend fun append(token: String, row: CsvImportRowResult) {
        checkNotNull(checkpoints[token])
        journals.getOrPut(token) { mutableMapOf() }[row.source.rowNumber] = row
    }
    override suspend fun read(token: String): CsvImportCheckpoint? = checkpoints[token]?.let { checkpoint ->
        checkpoint.copy(rows = checkpoint.rows.map { journals[token]?.get(it.source.rowNumber) ?: it })
    }
    override suspend fun saveDraft(token: String, rowNumber: Int, cells: List<String>) {
        val checkpoint = checkNotNull(checkpoints[token])
        checkpoints[token] = checkpoint.copy(drafts = checkpoint.drafts + (rowNumber to cells.toList()))
    }
}
