package pl.bargor.thesaurus.ui.importcsv

import android.content.Context
import android.util.AtomicFile
import com.google.gson.GsonBuilder
import com.google.gson.Strictness
import com.google.gson.stream.JsonReader
import com.google.gson.stream.JsonToken
import dagger.hilt.android.qualifiers.ApplicationContext
import java.io.File
import java.util.UUID
import java.time.Clock
import java.nio.ByteBuffer
import java.nio.charset.CodingErrorAction
import java.io.InputStream
import java.io.OutputStream
import java.io.InputStreamReader
import java.io.OutputStreamWriter
import java.io.FilterInputStream
import java.io.FilterOutputStream
import java.io.FilterReader
import java.io.Reader
import java.util.concurrent.ConcurrentHashMap
import javax.inject.Inject
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import pl.bargor.thesaurus.data.transfer.*

/** Generation-scoped private journal: a crash after replacing the base cannot replay old edits. */
class PrivateCsvImportCheckpointStore(private val directory: File, private val clock: Clock = Clock.systemUTC()) : CsvImportCheckpointStore {
    @Inject constructor(@ApplicationContext context: Context, clock: Clock) : this(File(context.noBackupFilesDir, "csv-import-checkpoints"), clock)
    constructor(context: Context) : this(File(context.noBackupFilesDir, "csv-import-checkpoints"))
    private val gson = GsonBuilder().setStrictness(Strictness.STRICT).create()
    private val generations = ConcurrentHashMap<String, String>()
    private val maximumBytes = 192L * 1024 * 1024
    private data class StoredRow(val number: Int, val cells: List<String>?, val issues: List<ValidationIssue>,
        val status: CsvImportRowStatus, val fingerprint: String?)
    private data class Stored(val generation: String, val householdId: String, val uid: String, val parsed: CsvParseResult,
        val mapping: CsvColumnMapping, val dateFormat: CsvDateFormat, val rows: List<StoredRow>,
        val drafts: Map<Int, List<String>>, val categorySelections: Map<Int, String>,
        val subcategorySelections: Map<Int, String?>, val repaired: Set<Int>, val started: Boolean, val mappingConfirmed: Boolean,
        val formatVersion: Int = 1)
    private fun validateToken(token: String) { require(runCatching { UUID.fromString(token).toString() == token }.getOrDefault(false)) }
    private fun file(token: String, suffix: String): File { validateToken(token); return File(directory, "$token.$suffix") }
    private fun journal(token: String, generation: String): File { validateToken(generation); return file(token, "$generation.journal") }
    override suspend fun create(checkpoint: CsvImportCheckpoint): String {
        val token = UUID.randomUUID().toString()
        save(token, checkpoint)
        return token
    }
    override suspend fun save(token: String, checkpoint: CsvImportCheckpoint) = withContext(Dispatchers.IO) {
        directory.mkdirs()
        val generation = UUID.randomUUID().toString()
        val originals = checkpoint.parsed.rows.associateBy { it.rowNumber }
        val value = checkpoint.run { Stored(generation, householdId, uid, parsed, mapping, dateFormat,
            rows.map { row -> StoredRow(row.source.rowNumber, row.source.cells.takeUnless { it == originals[row.source.rowNumber]?.cells },
                row.source.issues, row.status, row.entry?.let(::csvImportFingerprint) ?: fingerprints[row.source.rowNumber]) },
            drafts, categorySelections, subcategorySelections, repaired, started, mappingConfirmed) }
        validate(value)
        writeAtomic(file(token, "json"), value, maximumBytes)
        generations[token] = generation
        directory.listFiles().orEmpty().filter { it.name.startsWith("$token.") &&
            (it.name.endsWith(".journal") || it.name.endsWith(".draft") || it.name.endsWith(".draft.bak") || it.name.endsWith(".draft.new")) &&
            !it.name.startsWith("$token.$generation.") }.forEach { it.delete() }
        prune(token)
        Unit
    }
    override suspend fun append(token: String, row: CsvImportRowResult) = withContext(Dispatchers.IO) {
        val generation = checkNotNull(generations[token])
        val journal = journal(token, generation)
        val bytes = (gson.toJson(StoredRow(row.source.rowNumber, null, row.source.issues, row.status,
            row.entry?.let(::csvImportFingerprint))) + "\n").toByteArray(Charsets.UTF_8)
        require(journal.length() + bytes.size <= maximumBytes)
        java.io.FileOutputStream(journal, true).use { it.write(bytes); it.fd.sync() }
    }
    override suspend fun saveDraft(token: String, rowNumber: Int, cells: List<String>) = withContext(Dispatchers.IO) {
        require(rowNumber > 0); validateCells(cells)
        val generation = checkNotNull(generations[token])
        writeAtomic(file(token, "$generation.$rowNumber.draft"), cells,
            8L * BackupLimits.MAX_RAW_FIELD_CHARS * BackupLimits.MAX_CSV_COLUMNS)
    }
    override suspend fun read(token: String): CsvImportCheckpoint? = withContext(Dispatchers.IO) {
        val base = file(token, "json")
        if (!base.exists() && !File(base.path + ".bak").exists()) return@withContext null
        require(base.length() <= maximumBytes)
        val stored = AtomicFile(base).openRead().use { stream ->
            readJson(stream, Stored::class.java, maximumBytes)
        }
        validate(stored)
        val originals = stored.parsed.rows.associateBy { it.rowNumber }
        val rows = stored.rows.associateBy { it.number }.toMutableMap()
        val journal = journal(token, stored.generation)
        if (journal.exists()) {
            require(journal.length() <= maximumBytes)
            journal.inputStream().buffered().use { stream -> readJournal(stream) { bytes ->
                val row = gson.fromJson(decode(bytes), StoredRow::class.java)
                validateRow(row, originals.keys)
                require(row.status in terminalStatuses || row.status == CsvImportRowStatus.SERVER_ERROR)
                val previous = checkNotNull(rows[row.number])
                rows[row.number] = row.copy(cells = previous.cells)
            } }
        }
        val drafts = stored.drafts.toMutableMap()
        val draftFiles = directory.listFiles().orEmpty().filter { it.name.startsWith("$token.${stored.generation}.") &&
            (it.name.endsWith(".draft") || it.name.endsWith(".draft.bak")) }.map { File(it.path.removeSuffix(".bak")) }.distinct()
        require(draftFiles.size <= BackupLimits.MAX_ENTRIES)
        draftFiles.forEach { draftFile ->
            val number = draftFile.name.removePrefix("$token.${stored.generation}.").removeSuffix(".draft").toInt()
            require(number in originals)
            val cells = AtomicFile(draftFile).openRead().use { readJson(it, Array<String>::class.java,
                8L * BackupLimits.MAX_RAW_FIELD_CHARS * BackupLimits.MAX_CSV_COLUMNS) }.toList()
            validateCells(cells)
            drafts[number] = cells
        }
        require(drafts.values.sumOf { it.sumOf(String::length).toLong() } <= BackupLimits.MAX_BYTES)
        generations[token] = stored.generation
        CsvImportCheckpoint(stored.householdId, stored.uid, stored.parsed, stored.mapping, stored.dateFormat,
            rows.values.map { CsvImportRowResult(ImportRow(it.number, it.cells ?: originals.getValue(it.number).cells, it.issues), it.status) },
            drafts, stored.categorySelections, stored.subcategorySelections, stored.repaired, stored.started,
            rows.values.mapNotNull { row -> row.fingerprint?.let { row.number to it } }.toMap(), stored.mappingConfirmed)
    }
    private val terminalStatuses = setOf(CsvImportRowStatus.ADDED, CsvImportRowStatus.REPAIRED_AND_ADDED, CsvImportRowStatus.DUPLICATE, CsvImportRowStatus.DISCARDED)
    private fun decode(bytes: ByteArray): String = Charsets.UTF_8.newDecoder().onMalformedInput(CodingErrorAction.REPORT)
        .onUnmappableCharacter(CodingErrorAction.REPORT).decode(ByteBuffer.wrap(bytes)).toString()
    private fun writeAtomic(target: File, value: Any, limit: Long) {
        val atomic = AtomicFile(target)
        val stream = atomic.startWrite()
        try {
            val encoder = Charsets.UTF_8.newEncoder().onMalformedInput(CodingErrorAction.REPORT).onUnmappableCharacter(CodingErrorAction.REPORT)
            val writer = OutputStreamWriter(BoundedOutput(stream, limit), encoder)
            gson.toJson(value, writer)
            writer.flush()
            atomic.finishWrite(stream)
        } catch (error: Throwable) { atomic.failWrite(stream); throw error }
    }
    private fun <T> readJson(stream: InputStream, type: Class<T>, limit: Long): T {
        val decoder = Charsets.UTF_8.newDecoder().onMalformedInput(CodingErrorAction.REPORT).onUnmappableCharacter(CodingErrorAction.REPORT)
        val reader = JsonReader(ScalarBoundedReader(InputStreamReader(BoundedInput(stream, limit), decoder)))
        reader.strictness = Strictness.STRICT
        reader.setNestingLimit(32)
        val value: T = gson.fromJson(reader, type)
        require(reader.peek() == JsonToken.END_DOCUMENT)
        return value
    }
    private fun readJournal(stream: InputStream, consume: (ByteArray) -> Unit) {
        val record = java.io.ByteArrayOutputStream()
        var total = 0L
        while (true) {
            val byte = stream.read()
            if (byte < 0) break // Ignore only the incomplete final record, including a split UTF-8 scalar.
            require(++total <= maximumBytes)
            if (byte == '\n'.code) {
                consume(record.toByteArray())
                record.reset()
            } else {
                require(record.size() < 64 * 1024)
                record.write(byte)
            }
        }
    }
    private class BoundedInput(stream: InputStream, private val limit: Long) : FilterInputStream(stream) {
        private var count = 0L
        override fun read(): Int = super.read().also { if (it >= 0) require(++count <= limit) }
        override fun read(buffer: ByteArray, offset: Int, length: Int): Int = `in`.read(buffer, offset, length).also {
            if (it > 0) { count += it; require(count <= limit) }
        }
    }
    private class BoundedOutput(stream: OutputStream, private val limit: Long) : FilterOutputStream(stream) {
        private var count = 0L
        override fun write(value: Int) { require(++count <= limit); out.write(value) }
        override fun write(buffer: ByteArray, offset: Int, length: Int) {
            require(count + length <= limit); count += length; out.write(buffer, offset, length)
        }
    }
    /** Bound each JSON string before Gson materializes it; escaped Unicode counts as one UTF-16 unit. */
    private class ScalarBoundedReader(reader: Reader) : FilterReader(reader) {
        private var quoted = false
        private var escaped = false
        private var unicodeRemaining = 0
        private var length = 0
        private fun inspect(value: Int) {
            if (value < 0) return
            val char = value.toChar()
            if (!quoted) { if (char == '"') { quoted = true; length = 0 }; return }
            if (unicodeRemaining > 0) { unicodeRemaining--; return }
            if (escaped) { escaped = false; if (char == 'u') unicodeRemaining = 4; require(++length <= BackupLimits.MAX_RAW_FIELD_CHARS); return }
            if (char == '\\') { escaped = true; return }
            if (char == '"') { quoted = false; return }
            require(++length <= BackupLimits.MAX_RAW_FIELD_CHARS)
        }
        override fun read(): Int = super.read().also(::inspect)
        override fun read(buffer: CharArray, offset: Int, length: Int): Int = super.read(buffer, offset, length).also { count ->
            if (count > 0) for (index in offset until offset + count) inspect(buffer[index].code)
        }
    }
    private fun validate(value: Stored) {
        require(value.formatVersion == 1)
        validateToken(value.generation)
        require(value.uid.isNotBlank() && value.uid.length <= 256 && value.householdId.isNotBlank() && value.householdId.length <= 256)
        require(value.dateFormat in CsvDateFormat.entries && value.parsed.delimiter in listOf(',', ';', '\t'))
        require(value.parsed.headers.size <= BackupLimits.MAX_CSV_COLUMNS)
        validateCells(value.parsed.headers)
        require(value.parsed.rows.size <= BackupLimits.MAX_ENTRIES)
        val numbers = value.parsed.rows.map { it.rowNumber }.toSet()
        require(numbers.size == value.parsed.rows.size)
        value.parsed.rows.forEach { row -> require(row.rowNumber > 0); validateCells(row.cells); validateIssues(row.issues, row.rowNumber) }
        val mapping = value.mapping
        val indices = listOfNotNull(mapping.date, mapping.amount, mapping.title, mapping.category, mapping.subcategory, mapping.tags, mapping.type)
        require(indices.distinct().size == indices.size && indices.all { it in value.parsed.headers.indices })
        require(!value.started || value.mappingConfirmed && mapping.isComplete)
        require(value.rows.size <= numbers.size && value.rows.map { it.number }.distinct().size == value.rows.size)
        value.rows.forEach { validateRow(it, numbers) }
        require(value.drafts.keys.all { it in numbers } && value.repaired.all { it in numbers })
        value.drafts.values.forEach(::validateCells)
        require(value.categorySelections.keys.all { it in numbers } && value.subcategorySelections.keys.all { it in numbers })
        require(value.categorySelections.values.all { it.isNotBlank() && it.length <= 256 })
        require(value.subcategorySelections.values.all { it == null || it.isNotBlank() && it.length <= 256 })
        require(value.parsed.rows.sumOf { it.cells.sumOf(String::length).toLong() } <= BackupLimits.MAX_BYTES)
        require(value.drafts.values.sumOf { it.sumOf(String::length).toLong() } <= BackupLimits.MAX_BYTES)
    }
    private fun validateCells(cells: List<String>) { require(cells.size <= BackupLimits.MAX_CSV_COLUMNS && cells.all { it.length <= BackupLimits.MAX_RAW_FIELD_CHARS }) }
    private fun validateIssues(issues: List<ValidationIssue>, number: Int) {
        require(issues.size <= 32)
        issues.forEach { require(it.code.isNotBlank() && it.code.length <= 128 && (it.field == null || it.field.isNotBlank() && it.field.length <= 64) &&
            (it.rowNumber == null || it.rowNumber == number) && it.severity in ValidationSeverity.entries) }
    }
    private fun validateRow(row: StoredRow, numbers: Set<Int>) {
        require(row.number in numbers && row.status in CsvImportRowStatus.entries)
        row.cells?.let(::validateCells); validateIssues(row.issues, row.number)
        require(row.fingerprint == null || row.fingerprint.matches(Regex("[0-9a-f]{64}")))
    }
    private fun prune(currentToken: String) {
        val bases = directory.listFiles().orEmpty().filter { it.name.endsWith(".json") }.sortedByDescending { it.lastModified() }
        val cutoff = clock.millis() - 14L * 24 * 60 * 60 * 1000
        bases.filterIndexed { index, base -> base.name != "$currentToken.json" && (index >= 4 || base.lastModified() < cutoff) }.forEach { base ->
            val oldToken = base.name.removeSuffix(".json")
            if (runCatching { validateToken(oldToken) }.isSuccess) {
                directory.listFiles().orEmpty().filter { it.name.startsWith("$oldToken.") }.forEach { it.delete() }
                generations.remove(oldToken)
            }
        }
    }
}
