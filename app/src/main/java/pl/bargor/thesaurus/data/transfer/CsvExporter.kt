package pl.bargor.thesaurus.data.transfer

import com.google.gson.Strictness
import com.google.gson.stream.JsonWriter
import java.io.ByteArrayOutputStream
import java.io.OutputStream
import java.io.OutputStreamWriter
import java.io.StringWriter
import java.math.BigDecimal
import java.time.LocalDate

/** Names from the selected household; subcategory IDs are scoped to their parent category. */
data class CsvNameLookup(
    val categoryNames: Map<String, String> = emptyMap(),
    val subcategoryNames: Map<Pair<String, String>, String> = emptyMap(),
    val authorNames: Map<String, String> = emptyMap(),
)

/** Fully validated file, ready for a caller-owned destination. Export never opens or closes it. */
data class CsvExportArtifact(val bytes: ByteArray, val suggestedFileName: String)

/**
 * Portable, deterministic CSV for entries already scoped to the selected household by the caller.
 * Tombstones are omitted. Historical missing names use neutral markers, never opaque IDs.
 * Tags are a sorted JSON string array in one RFC 4180 cell, preserving punctuation and newlines
 * without ambiguous delimiters. The future CSV importer must decode that array.
 *
 * Text is preserved exactly. RFC quoting does not protect spreadsheet users from formulas;
 * spreadsheet consumers should import text columns as text rather than execute cell contents.
 * No clock, locale, Android, database, account export, or destination stream is involved.
 */
object CsvExporter {
    private const val HEADER = "date,amount,type,title,category,subcategory,tags,author\r\n"
    private const val UNKNOWN_CATEGORY = "unknown-category"
    private const val UNKNOWN_SUBCATEGORY = "unknown-subcategory"
    private const val UNKNOWN_AUTHOR = "unknown-author"

    fun export(
        entries: List<BackupEntry>,
        names: CsvNameLookup,
        exportDate: LocalDate,
    ): CsvExportArtifact {
        date(exportDate)
        val active = ArrayList<BackupEntry>()
        entries.forEach { entry ->
            if (!entry.deleted) {
                limit(active.size < BackupLimits.MAX_ENTRIES)
                active.add(entry)
            }
        }
        val ids = HashSet<String>()
        active.forEach { entry ->
            identifier(entry.id)
            valid(ids.add(entry.id))
            valid(entry.amountGrosze != 0L)
            date(entry.date)
            reference(entry.categoryId)
            reference(entry.authorId)
            entry.subcategoryId?.let(::reference)
            entry.title?.let { text(it, 160, allowBlank = true) }
            limit(entry.tags.size <= 10)
            valid(entry.tags.toSet().size == entry.tags.size)
            entry.tags.forEach { text(it, 40) }
            // Only names actually used in the file need to satisfy the file contract.
            displayName(names.categoryNames[entry.categoryId], UNKNOWN_CATEGORY, 60)
            entry.subcategoryId?.let {
                displayName(names.subcategoryNames[entry.categoryId to it], UNKNOWN_SUBCATEGORY, 60)
            }
            displayName(names.authorNames[entry.authorId], UNKNOWN_AUTHOR, 254)
        }

        val output = ByteArrayOutputStream()
        val bounded = object : OutputStream() {
            private var count = 0
            override fun write(value: Int) {
                limit(count < BackupLimits.MAX_BYTES)
                output.write(value)
                count++
            }
            override fun write(bytes: ByteArray, offset: Int, length: Int) {
                limit(length <= BackupLimits.MAX_BYTES - count)
                output.write(bytes, offset, length)
                count += length
            }
        }
        bounded.write(byteArrayOf(0xEF.toByte(), 0xBB.toByte(), 0xBF.toByte()))
        OutputStreamWriter(bounded, Charsets.UTF_8).use { writer ->
            writer.write(HEADER)
            active.sortedWith(compareBy<BackupEntry> { it.date }.thenBy { it.id }).forEach { entry ->
                val cells = listOf(
                    entry.date.toString(),
                    BigDecimal.valueOf(entry.amountGrosze, 2).toPlainString(),
                    if (entry.amountGrosze < 0L) "expense" else "income",
                    entry.title.orEmpty(),
                    displayName(names.categoryNames[entry.categoryId], UNKNOWN_CATEGORY, 60),
                    entry.subcategoryId?.let {
                        displayName(names.subcategoryNames[entry.categoryId to it], UNKNOWN_SUBCATEGORY, 60)
                    }.orEmpty(),
                    tags(entry.tags),
                    displayName(names.authorNames[entry.authorId], UNKNOWN_AUTHOR, 254),
                )
                cells.forEachIndexed { index, cell ->
                    if (index > 0) writer.write(",")
                    writer.write(csvCell(cell))
                }
                writer.write("\r\n")
            }
        }
        return CsvExportArtifact(output.toByteArray(), "thesaurus-$exportDate.csv")
    }

    private fun tags(values: List<String>): String {
        val output = StringWriter()
        JsonWriter(output).use { writer ->
            writer.setStrictness(Strictness.STRICT)
            writer.beginArray()
            values.sorted().forEach { writer.value(it) }
            writer.endArray()
        }
        return output.toString()
    }

    private fun csvCell(value: String): String =
        if (value.any { it == ',' || it == '"' || it == '\r' || it == '\n' }) {
            "\"${value.replace("\"", "\"\"")}\""
        } else value

    private fun displayName(value: String?, fallback: String, max: Int): String {
        value ?: return fallback
        text(value, max, allowBlank = true)
        return if (value.isBlank()) fallback else value
    }

    private fun date(value: LocalDate) = valid(value.year in 0..9999)

    private fun identifier(value: String) {
        text(value, 128)
        valid(value == value.trim() && '/' !in value)
    }

    private fun reference(value: String) = text(value, 128, allowBlank = true)

    private fun text(value: String, max: Int, allowBlank: Boolean = false) {
        limit(value.length <= max)
        valid(allowBlank || value.isNotBlank())
        validUnicode(value)
    }
}
