package pl.bargor.thesaurus.data.transfer

import com.google.gson.JsonParser
import java.time.LocalDate
import java.util.Locale
import kotlin.random.Random
import org.junit.Assert.*
import org.junit.Test

class CsvExporterTest {
    private val exportDate = LocalDate.of(2026, 10, 10)
    private val header = listOf("date", "amount", "type", "title", "category", "subcategory", "tags", "author")
    private val names = CsvNameLookup(mapOf("food" to "Żywność"),
        mapOf(("food" to "shop") to "Łódź"), mapOf("author" to "Karol"))
    private fun entry(id: String = "entry", amount: Long = -12345) =
        BackupEntry(id, amount, LocalDate.of(2024, 2, 29), "food", "author", "editor",
            title = "Zakupy", subcategoryId = "shop")
    private fun export(entries: List<BackupEntry>, lookup: CsvNameLookup = names) =
        CsvExporter.export(entries, lookup, exportDate)
    private fun rows(bytes: ByteArray): List<List<String>> {
        assertArrayEquals(byteArrayOf(0xef.toByte(), 0xbb.toByte(), 0xbf.toByte()), bytes.take(3).toByteArray())
        return readCsv(bytes.toString(Charsets.UTF_8).removePrefix("\uFEFF"))
    }
    private fun rejected(code: TransferErrorCode, action: () -> Unit) {
        val error = assertThrows(BackupContractException::class.java, action)
        assertEquals(code, error.code)
        assertNull(error.cause)
        assertEquals("Backup contract error: ${code.name}", error.message)
    }

    @Test fun standardCsvFixtureChecksIndependentReaderIncludingMultilineAndEscapedQuotes() {
        // RFC 4180-style fixture is handwritten, independent of the production writer.
        assertEquals(listOf(listOf("a", "b", "c"), listOf("one,two", "a\"b", "line1\r\nline2"),
            listOf("", "", "")), readCsv("a,b,c\r\n\"one,two\",\"a\"\"b\",\"line1\r\nline2\"\r\n,,\r\n"))
    }

    @Test fun emptyExportIsBomHeaderAndCrlfWithExplicitDatedFilename() {
        val artifact = export(emptyList())
        assertEquals("thesaurus-2026-10-10.csv", artifact.suggestedFileName)
        assertEquals("\uFEFFdate,amount,type,title,category,subcategory,tags,author\r\n", artifact.bytes.toString(Charsets.UTF_8))
        assertEquals(listOf(header), rows(artifact.bytes))
    }

    @Test fun amountsAreExactAcrossFullSignedLongRangeAndFractionBoundaries() {
        val cases = listOf(Long.MIN_VALUE to "-92233720368547758.08", Long.MAX_VALUE to "92233720368547758.07",
            -1L to "-0.01", 1L to "0.01", -100L to "-1.00", 100L to "1.00")
        for ((amount, expected) in cases) {
            val row = rows(export(listOf(entry(amount = amount))).bytes)[1]
            assertEquals(8, row.size)
            assertEquals("2024-02-29", row[0])
            assertEquals(expected, row[1])
            assertEquals(if (amount < 0) "expense" else "income", row[2])
        }
    }

    @Test fun localeChangesNeverChangeBytesOrFilename() {
        val original = Locale.getDefault()
        val entries = listOf(entry(amount = Long.MIN_VALUE), entry("income", Long.MAX_VALUE))
        val expected = export(entries)
        try {
            for (locale in listOf("pl-PL", "en-US", "ar-EG", "tr-TR")) {
                Locale.setDefault(Locale.forLanguageTag(locale))
                val actual = export(entries)
                assertArrayEquals(locale, expected.bytes, actual.bytes)
                assertEquals(expected.suggestedFileName, actual.suggestedFileName)
            }
        } finally { Locale.setDefault(original) }
    }

    @Test fun allTextAndJsonTagsSurviveIndependentCsvReadingWithoutNormalization() {
        val title = "  Zażółć, \"東京\"\r\nالعربية\nКиїв 😀\rkoniec  "
        val tags = listOf(" z ", "a,b", "a\"b", "a\nb", "a\\b", "😀", "東京", "العربية")
        val display = CsvNameLookup(mapOf("food" to "  食品,\"食\"\r\n "),
            mapOf(("food" to "shop") to "Żółć\n店"), mapOf("author" to " Işık,\"İ\" "))
        val row = rows(export(listOf(entry().copy(title = title, tags = tags)), display).bytes)[1]
        assertEquals(8, row.size)
        assertEquals(title, row[3])
        assertEquals(display.categoryNames["food"], row[4])
        assertEquals(display.subcategoryNames["food" to "shop"], row[5])
        assertEquals(tags.sorted(), JsonParser.parseString(row[6]).asJsonArray.map { it.asString })
        assertEquals(display.authorNames["author"], row[7])
        for (literal in listOf("=SUM(A1:A2)", "+123", "-formula", "@name", "\tleading", "")) {
            assertEquals(literal, rows(export(listOf(entry().copy(title = literal))).bytes)[1][3])
        }
    }

    @Test fun activeRowsResolveArchivedTaxonomyAndSubcategoryIdsWithinParent() {
        val archived = BackupCategory("food", "Archived food", "author", "editor", archived = true)
        val archivedSubcategory = BackupSubcategory("shop", "food", "Archived shop", "author", "editor", archived = true)
        val lookup = CsvNameLookup(mapOf(archived.id to archived.name, "other" to "Other"),
            mapOf((archivedSubcategory.categoryId to archivedSubcategory.id) to archivedSubcategory.name,
                ("other" to "shop") to "Other shop"), names.authorNames)
        val decoded = rows(export(listOf(entry("a"), entry("b").copy(categoryId = "other"),
            entry("deleted").copy(deleted = true, amountGrosze = 0, title = "\uD800")), lookup).bytes)
        assertEquals(3, decoded.size)
        assertEquals("Archived food", decoded[1][4])
        assertEquals("Archived shop", decoded[1][5])
        assertEquals("Other shop", decoded[2][5])
    }

    @Test fun unknownAndBlankNamesUseNeutralFallbacksAndAbsentOptionalFieldsStayEmpty() {
        val unknown = rows(export(listOf(entry().copy(categoryId = "missing", authorId = "missing")), CsvNameLookup()).bytes)[1]
        assertEquals(listOf("unknown-category", "unknown-subcategory", "[]", "unknown-author"), unknown.drop(4))
        val blank = rows(export(listOf(entry().copy(title = null, subcategoryId = null)),
            CsvNameLookup(mapOf("food" to " \t"), authorNames = mapOf("author" to "\n"))).bytes)[1]
        assertEquals("", blank[3])
        assertEquals("unknown-category", blank[4])
        assertEquals("", blank[5])
        assertEquals("unknown-author", blank[7])
        val blankSub = rows(export(listOf(entry()), names.copy(subcategoryNames = mapOf(("food" to "shop") to " "))).bytes)[1]
        assertEquals("unknown-subcategory", blankSub[5])
    }

    @Test fun orderingIsDateThenIdAndExportNeverMutatesInputsOrTags() {
        val tags = mutableListOf("z", "a")
        val entries = mutableListOf(entry("z").copy(tags = tags), entry("a"),
            entry("later").copy(date = LocalDate.of(2025, 1, 1)), entry("earlier").copy(date = LocalDate.of(2023, 1, 1)))
        val before = entries.map { it.copy(tags = it.tags.toList()) }
        val expected = export(entries)
        val shuffled = export(entries.shuffled(Random(109)).map { it.copy(tags = it.tags.reversed()) })
        assertArrayEquals(expected.bytes, shuffled.bytes)
        assertEquals(before, entries)
        assertEquals(listOf("z", "a"), tags)
        assertEquals(listOf("2023-01-01", "2024-02-29", "2024-02-29", "2025-01-01"), rows(expected.bytes).drop(1).map { it[0] })
        assertEquals("[]", rows(expected.bytes)[2][6]) // id a precedes z on the same date.
    }

    @Test fun fourDigitDatesIncludeYearZeroButRejectOutOfRangeYearsAndZeroAmount() {
        for (year in listOf(0, 1, 9999)) {
            val date = LocalDate.of(year, 1, 2)
            val artifact = CsvExporter.export(listOf(entry().copy(date = date)), names, date)
            val iso = year.toString().padStart(4, '0') + "-01-02"
            assertEquals(iso, rows(artifact.bytes)[1][0])
            assertEquals("thesaurus-$iso.csv", artifact.suggestedFileName)
        }
        rejected(TransferErrorCode.INVALID_VALUE) { export(listOf(entry(amount = 0))) }
        for (year in listOf(-1, 10000)) {
            rejected(TransferErrorCode.INVALID_VALUE) { export(listOf(entry().copy(date = LocalDate.of(year, 1, 1)))) }
            rejected(TransferErrorCode.INVALID_VALUE) { CsvExporter.export(emptyList(), names, LocalDate.of(year, 1, 1)) }
        }
    }

    @Test fun invalidUnicodeAndDuplicateIdsOrTagsReturnSanitizedFailures() {
        for (invalid in listOf(entry().copy(title = "private\uD800"), entry().copy(tags = listOf("a", "a")),
            entry().copy(tags = listOf(" ")), entry().copy(id = ""))) {
            rejected(TransferErrorCode.INVALID_VALUE) { export(listOf(invalid)) }
        }
        rejected(TransferErrorCode.INVALID_VALUE) { export(listOf(entry(), entry())) }
        rejected(TransferErrorCode.INVALID_VALUE) { export(listOf(entry()), names.copy(authorNames = mapOf("author" to "\uDC00"))) }
    }

    @Test fun fieldBoundsRejectRatherThanTruncateAndAcceptMaximumValues() {
        val maximal = entry().copy(title = "t".repeat(160), tags = List(10) { "tag$it" + "x".repeat(36) })
        val lookup = CsvNameLookup(mapOf("food" to "c".repeat(60)), mapOf(("food" to "shop") to "s".repeat(60)),
            mapOf("author" to "a".repeat(254)))
        assertEquals(maximal.title, rows(export(listOf(maximal), lookup).bytes)[1][3])
        for (invalid in listOf(entry().copy(title = "x".repeat(161)), entry().copy(tags = listOf("x".repeat(41))),
            entry().copy(tags = List(11) { "tag$it" }))) {
            rejected(TransferErrorCode.LIMIT_EXCEEDED) { export(listOf(invalid)) }
        }
        rejected(TransferErrorCode.LIMIT_EXCEEDED) { export(listOf(entry()), names.copy(categoryNames = mapOf("food" to "x".repeat(61)))) }
        rejected(TransferErrorCode.LIMIT_EXCEEDED) { export(listOf(entry()), names.copy(authorNames = mapOf("author" to "x".repeat(255)))) }
    }

    @Test fun tenThousandAndFiftyThousandRowsAreCompleteAndShuffleDeterministic() {
        for (count in listOf(10_000, BackupLimits.MAX_ENTRIES)) {
            val entries = List(count) { entry("e" + it.toString().padStart(5, '0')).copy(title = null, subcategoryId = null) }
            val expected = export(entries)
            assertArrayEquals(expected.bytes, export(entries.shuffled(Random(count))).bytes)
            val decoded = rows(expected.bytes)
            assertEquals(count + 1, decoded.size)
            assertTrue(decoded.all { it.size == 8 })
        }
        rejected(TransferErrorCode.LIMIT_EXCEEDED) { export(List(BackupLimits.MAX_ENTRIES + 1) { entry("e$it") }) }
        assertEquals(listOf(header), rows(export(List(BackupLimits.MAX_ENTRIES + 1) { entry("e$it").copy(deleted = true) }).bytes))
    }

    @Test fun sixteenMebibyteByteLimitUsesEncodedUtf8SizeAndIncludesCsvEscaping() {
        // All fields individually fit. UTF-8 and doubled CSV quotes push the complete file over 16 MiB.
        val wide = entry().copy(title = "😀".repeat(80), tags = List(10) { "\"".repeat(38) + it })
        rejected(TransferErrorCode.LIMIT_EXCEEDED) { export(List(20_000) { wide.copy(id = "e$it") }) }
    }

    @Test fun exactlySixteenMebibytesSucceedsAndOneAdditionalByteFails() {
        val lookup = names.copy(authorNames = mapOf("author" to "a".repeat(254)))
        val minimal = entry().copy(title = null)
        val headerSize = export(emptyList(), lookup).bytes.size
        val rowSize = export(listOf(minimal), lookup).bytes.size - headerSize
        var remaining = BackupLimits.MAX_BYTES - headerSize - BackupLimits.MAX_ENTRIES * rowSize
        assertTrue(remaining > 0 && remaining < BackupLimits.MAX_ENTRIES * 160)
        val entries = List(BackupLimits.MAX_ENTRIES) { index ->
            val length = minOf(remaining, 160)
            remaining -= length
            minimal.copy(id = "e$index", title = "x".repeat(length))
        }
        assertEquals(0, remaining)
        assertEquals(BackupLimits.MAX_BYTES, export(entries, lookup).bytes.size)
        val extraByte = entries.toMutableList()
        extraByte[extraByte.lastIndex] = extraByte.last().copy(title = "x")
        rejected(TransferErrorCode.LIMIT_EXCEEDED) { export(extraByte, lookup) }
    }

    /** Test-only RFC reader: understands records, quoted multiline cells, and doubled quotes. */
    private fun readCsv(text: String): List<List<String>> {
        val records = mutableListOf<List<String>>()
        val record = mutableListOf<String>()
        val field = StringBuilder()
        var quoted = false
        var closedQuote = false
        var index = 0
        while (index < text.length) {
            val char = text[index]
            if (quoted) {
                if (char == '"') {
                    if (index + 1 < text.length && text[index + 1] == '"') { field.append('"'); index++ }
                    else { quoted = false; closedQuote = true }
                } else field.append(char)
            } else when (char) {
                '"' -> { check(field.isEmpty() && !closedQuote); quoted = true }
                ',' -> { record += field.toString(); field.setLength(0); closedQuote = false }
                '\r' -> {
                    check(index + 1 < text.length && text[index + 1] == '\n')
                    index++
                    record += field.toString()
                    records += record.toList()
                    record.clear(); field.setLength(0); closedQuote = false
                }
                '\n' -> error("Bare record LF")
                else -> { check(!closedQuote); field.append(char) }
            }
            index++
        }
        check(!quoted && field.isEmpty() && record.isEmpty()) { "Unterminated CSV record" }
        return records
    }
}
