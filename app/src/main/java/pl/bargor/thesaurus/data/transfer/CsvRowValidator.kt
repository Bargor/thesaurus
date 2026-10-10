package pl.bargor.thesaurus.data.transfer

import com.google.gson.Strictness
import com.google.gson.stream.JsonReader
import com.google.gson.stream.JsonToken
import java.io.StringReader
import java.math.BigDecimal
import java.time.DateTimeException
import java.time.LocalDate

enum class CsvDateFormat { AUTO, DAY_MONTH_YEAR, MONTH_DAY_YEAR }

/** Source author labels never become write identities. Household/authorship belongs to the caller. */
data class CsvImportEntry(
    val date: LocalDate,
    val amountGrosze: Long,
    val title: String?,
    val categoryName: String,
    val subcategoryName: String?,
    val tags: List<String>,
) {
    val type: TransferEntryType get() = if (amountGrosze < 0L) TransferEntryType.EXPENSE else TransferEntryType.INCOME
}

data class CsvValidatedRow(val source: ImportRow, val entry: CsvImportEntry?)
data class CsvValidationResult(
    val rows: List<CsvValidatedRow>,
    val summary: ImportSummary,
    val requiresManualMapping: Boolean = false,
)

/** Pure row validation, with an explicit clock and date convention.
 * AUTO accepts ISO, dotted DMY and slash dates only when their DMY/MDY interpretations agree
 * or only one is a real calendar date. Amounts have no grouping/currency and at most two decimals.
 * An unsigned magnitude gets its direction from type (otherwise income); explicit signs must
 * agree with type. Tags are the exporter's JSON string array, or a blank cell for no tags.
 * Original cells and text whitespace are retained; safe issue codes carry no source payload.
 */
object CsvRowValidator {
    private val amountPattern = Regex("[+-]?[0-9]+(?:[.,][0-9]{1,2})?")
    private val isoPattern = Regex("[0-9]{4}-[0-9]{2}-[0-9]{2}")
    private val regionalPattern = Regex("([0-9]{1,2})([./])([0-9]{1,2})\\2([0-9]{4})")
    private val builtInTypes = buildMap {
        listOf("income", "przychód", "przychod", "wpływ", "wplyw", "einnahme", "revenu")
            .forEach { put(it, TransferEntryType.INCOME) }
        listOf("expense", "wydatek", "koszt", "ausgabe", "dépense", "depense")
            .forEach { put(it, TransferEntryType.EXPENSE) }
    }

    fun validate(
        parsed: CsvParseResult,
        mapping: CsvColumnMapping,
        today: LocalDate,
        dateFormat: CsvDateFormat = CsvDateFormat.AUTO,
        typeAliases: Map<String, TransferEntryType> = emptyMap(),
    ): CsvValidationResult {
        val indices = listOfNotNull(mapping.date, mapping.amount, mapping.title, mapping.category,
            mapping.subcategory, mapping.tags, mapping.type)
        if (!mapping.isComplete || indices.any { it >= parsed.headers.size }) {
            return CsvValidationResult(parsed.rows.map { CsvValidatedRow(it, null) },
                ImportSummary(parsed.rows.size, 0, 0), requiresManualMapping = true)
        }
        // Built-ins win over caller aliases, keeping the portable export contract stable.
        val types = typeAliases.mapKeys { CsvHeaderDetector.normalize(it.key) } + builtInTypes
        val rows = parsed.rows.map { source -> validateRow(source, parsed.headers.size, mapping, today, dateFormat, types) }
        val validCount = rows.count { it.entry != null }
        return CsvValidationResult(rows, ImportSummary(rows.size, validCount, rows.size - validCount,
            rows.flatMap { it.source.issues }))
    }

    private fun validateRow(
        source: ImportRow, width: Int, mapping: CsvColumnMapping, today: LocalDate,
        dateFormat: CsvDateFormat, types: Map<String, TransferEntryType>,
    ): CsvValidatedRow {
        val issues = ArrayList<ValidationIssue>()
        fun issue(code: String, field: String? = null) { issues.add(ValidationIssue(code, field, source.rowNumber)) }
        fun cell(index: Int?): String = index?.let { source.cells.getOrNull(it) }.orEmpty()
        fun text(index: Int?, field: String, maximum: Int, required: Boolean = false): String? {
            val value = cell(index)
            if (required && value.isBlank()) issue("REQUIRED_VALUE", field)
            if (value.length > maximum) issue("FIELD_TOO_LONG", field)
            return value.takeUnless { it.isEmpty() }
        }
        if (source.cells.size != width) issue("COLUMN_COUNT_MISMATCH")
        val rawDate = cell(mapping.date).trim()
        val date = if (rawDate.isEmpty()) { issue("REQUIRED_VALUE", "date"); null }
        else parseDate(rawDate, dateFormat) { issue(it, "date") }
        if (date != null && date > today) issue("FUTURE_DATE", "date")
        val typeText = CsvHeaderDetector.normalize(cell(mapping.type))
        val declaredType = types[typeText]
        if (typeText.isNotEmpty() && declaredType == null) issue("INVALID_TYPE", "type")
        val rawAmount = cell(mapping.amount).trim()
        var amount: Long? = null
        if (rawAmount.isEmpty()) issue("REQUIRED_VALUE", "amount")
        else if (!amountPattern.matches(rawAmount)) issue("INVALID_AMOUNT", "amount")
        else {
            var decimal = BigDecimal(rawAmount.replace(',', '.'))
            val signed = rawAmount.first() == '+' || rawAmount.first() == '-'
            if (declaredType != null) {
                val expected = if (declaredType == TransferEntryType.EXPENSE) -1 else 1
                if (signed && decimal.signum() != 0 && decimal.signum() != expected) issue("AMOUNT_TYPE_CONFLICT", "amount")
                if (!signed && expected < 0) decimal = decimal.negate()
            }
            amount = try { decimal.movePointRight(2).longValueExact() }
            catch (_: ArithmeticException) { issue("INVALID_AMOUNT", "amount"); null }
            if (amount == 0L) issue("ZERO_AMOUNT", "amount")
        }
        val title = text(mapping.title, "title", 160)
        val category = text(mapping.category, "category", 60, required = true)
        val subcategory = text(mapping.subcategory, "subcategory", 60)
        if (subcategory != null && subcategory.isBlank()) issue("REQUIRED_VALUE", "subcategory")
        val tags = parseTags(cell(mapping.tags)) { issue(it, "tags") }
        val checkedSource = source.copy(issues = source.issues + issues)
        val entry = if (checkedSource.issues.any { it.severity == ValidationSeverity.ERROR } || date == null || amount == null || category == null) null
        else CsvImportEntry(date, amount, title, category, subcategory, tags)
        return CsvValidatedRow(checkedSource, entry)
    }

    private fun parseDate(value: String, format: CsvDateFormat, issue: (String) -> Unit): LocalDate? {
        if (isoPattern.matches(value)) {
            return try { LocalDate.parse(value) } catch (_: DateTimeException) { issue("INVALID_DATE"); null }
        }
        val match = regionalPattern.matchEntire(value)
        if (match == null) { issue("INVALID_DATE"); return null }
        val a = match.groupValues[1].toInt(); val b = match.groupValues[3].toInt(); val year = match.groupValues[4].toInt()
        fun calendar(month: Int, day: Int): LocalDate? = try { LocalDate.of(year, month, day) } catch (_: DateTimeException) { null }
        val dmy = calendar(b, a); val mdy = calendar(a, b)
        val result = when (format) {
            CsvDateFormat.DAY_MONTH_YEAR -> dmy
            CsvDateFormat.MONTH_DAY_YEAR -> mdy
            CsvDateFormat.AUTO -> if (match.groupValues[2] == ".") dmy else {
                if (dmy != null && mdy != null && dmy != mdy) { issue("AMBIGUOUS_DATE"); return null }
                dmy ?: mdy
            }
        }
        if (result == null) issue("INVALID_DATE")
        return result
    }

    private fun parseTags(value: String, issue: (String) -> Unit): List<String> {
        if (value.isBlank()) return emptyList()
        val tags = ArrayList<String>()
        try {
            JsonReader(StringReader(value)).use { reader ->
                reader.setStrictness(Strictness.STRICT)
                if (reader.peek() != JsonToken.BEGIN_ARRAY) { issue("INVALID_TAGS"); return emptyList() }
                reader.beginArray()
                while (reader.hasNext()) {
                    if (tags.size >= 10) { issue("TOO_MANY_TAGS"); return emptyList() }
                    if (reader.peek() != JsonToken.STRING) { issue("INVALID_TAGS"); return emptyList() }
                    val tag = reader.nextString()
                    if (tag.isBlank()) issue("INVALID_TAGS")
                    if (tag.length > 40) issue("FIELD_TOO_LONG")
                    try { validUnicode(tag) } catch (_: BackupContractException) { issue("INVALID_TAGS") }
                    tags.add(tag)
                }
                reader.endArray()
                if (reader.peek() != JsonToken.END_DOCUMENT) issue("INVALID_TAGS")
            }
        } catch (_: java.io.IOException) { issue("INVALID_TAGS") }
        catch (_: IllegalStateException) { issue("INVALID_TAGS") }
        if (tags.toSet().size != tags.size) issue("DUPLICATE_TAGS")
        return tags
    }
}
