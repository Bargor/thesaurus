package pl.bargor.thesaurus.data.transfer

import java.nio.ByteBuffer
import java.nio.charset.CodingErrorAction

/** The first record is the header. Row numbers are physical start lines, including quoted newlines. */
data class CsvParseResult(val headers: List<String>, val rows: List<ImportRow>, val delimiter: Char)

/** Bounded UTF-8 CSV reader. File failures never expose source values or partial records. */
object CsvParser {
    private val delimiters = listOf(',', ';', '\t')

    fun parse(bytes: ByteArray, delimiter: Char? = null): CsvParseResult {
        limit(bytes.size <= BackupLimits.MAX_BYTES)
        val content = try {
            Charsets.UTF_8.newDecoder().onMalformedInput(CodingErrorAction.REPORT)
                .onUnmappableCharacter(CodingErrorAction.REPORT).decode(ByteBuffer.wrap(bytes)).toString()
        } catch (_: java.nio.charset.CharacterCodingException) { fail(TransferErrorCode.MALFORMED_UTF8) }
        val text = content.removePrefix("\uFEFF")
        if (delimiter != null) {
            valid(delimiter in delimiters)
            return read(text, delimiter)
        }
        return read(text, detectDelimiter(text))
    }

    // Sniff only the header, before choosing a single strict parser. Failed dialects never
    // become candidates for fallback: doing so could hide malformed CSV or transport limits.
    private fun detectDelimiter(text: String): Char {
        val counts = delimiters.associateWith { 0 }.toMutableMap()
        var quoted = false
        var end = text.length
        var index = 0
        while (index < text.length) {
            val char = text[index++]
            if (char == '"') {
                if (quoted && index < text.length && text[index] == '"') index++ else quoted = !quoted
            } else if (!quoted) {
                if (char == '\r' || char == '\n') { end = index - 1; break }
                if (char in delimiters) counts[char] = counts.getValue(char) + 1
            }
        }
        val present = delimiters.filter { counts.getValue(it) > 0 }
        if (present.isEmpty()) return ','
        if (present.size == 1) return present.single()
        val header = text.substring(0, end)
        fun recognized(delimiter: Char): Int {
            var count = 0
            var inside = false
            var position = 0
            val cell = StringBuilder()
            fun finish() { if (CsvHeaderDetector.matches(cell.toString()).isNotEmpty()) count++; cell.setLength(0) }
            while (position < header.length) {
                val char = header[position++]
                if (char == '"') {
                    if (inside && position < header.length && header[position] == '"') {
                        position++; if (cell.length < BackupLimits.MAX_RAW_FIELD_CHARS) cell.append('"')
                    } else inside = !inside
                } else if (!inside && char == delimiter) finish()
                else if (cell.length < BackupLimits.MAX_RAW_FIELD_CHARS) cell.append(char)
            }
            finish()
            return count
        }
        val scores = present.associateWith(::recognized)
        val maximum = scores.values.max()
        val winners = present.filter { scores.getValue(it) == maximum }
        if (winners.size != 1) fail(TransferErrorCode.AMBIGUOUS_DELIMITER)
        return winners.single()
    }

    private fun read(text: String, delimiter: Char): CsvParseResult {
        val records = ArrayList<ImportRow>()
        val cells = ArrayList<String>()
        val cell = StringBuilder()
        var line = 1
        var startLine = 1
        var index = 0
        var quoted = false
        var closedQuote = false
        var recordStarted = false
        fun append(char: Char) {
            limit(cell.length < BackupLimits.MAX_RAW_FIELD_CHARS)
            cell.append(char)
        }
        fun endCell() {
            limit(cells.size < BackupLimits.MAX_CSV_COLUMNS)
            cells.add(cell.toString()); cell.setLength(0); closedQuote = false
        }
        fun endRecord() {
            endCell()
            limit(records.size <= BackupLimits.MAX_ENTRIES) // Header is not a ledger row.
            records.add(ImportRow(startLine, cells.toList()))
            cells.clear(); recordStarted = false; startLine = line
        }
        while (index < text.length) {
            val char = text[index++]
            if (quoted) {
                if (char == '"') {
                    if (index < text.length && text[index] == '"') { index++; append('"') }
                    else { quoted = false; closedQuote = true }
                } else {
                    append(char)
                    if (char == '\r') {
                        if (index < text.length && text[index] == '\n') { append(text[index++]) }
                        line++
                    } else if (char == '\n') line++
                }
                continue
            }
            when {
                char == delimiter -> { endCell(); recordStarted = true }
                char == '\r' || char == '\n' -> {
                    if (char == '\r' && index < text.length && text[index] == '\n') index++
                    line++; endRecord()
                }
                char == '"' -> {
                    if (cell.isNotEmpty() || closedQuote) fail(TransferErrorCode.MALFORMED_CSV)
                    quoted = true; recordStarted = true
                }
                else -> {
                    if (closedQuote) fail(TransferErrorCode.MALFORMED_CSV)
                    append(char); recordStarted = true
                }
            }
        }
        if (quoted) fail(TransferErrorCode.MALFORMED_CSV)
        if (recordStarted || cells.isNotEmpty() || cell.isNotEmpty() || closedQuote) endRecord()
        if (records.isEmpty()) fail(TransferErrorCode.MALFORMED_CSV)
        return CsvParseResult(records.first().cells, records.drop(1), delimiter)
    }
}
