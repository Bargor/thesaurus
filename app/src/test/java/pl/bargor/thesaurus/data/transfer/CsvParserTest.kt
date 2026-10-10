package pl.bargor.thesaurus.data.transfer

import java.time.LocalDate
import java.util.Locale
import org.junit.Assert.*
import org.junit.Test

class CsvParserTest {
    private val today = LocalDate.of(2026, 10, 10)
    private val standardHeader = "date,amount,type,title,category,subcategory,tags,author"
    private fun parse(text: String, delimiter: Char? = null) = CsvParser.parse(text.toByteArray(Charsets.UTF_8), delimiter)
    private fun validate(text: String, dateFormat: CsvDateFormat = CsvDateFormat.AUTO): CsvValidationResult {
        val parsed = parse(text)
        return CsvRowValidator.validate(parsed, CsvHeaderDetector.detect(parsed.headers).mapping, today, dateFormat)
    }
    private fun rejected(code: TransferErrorCode, action: () -> Unit) {
        val error = assertThrows(BackupContractException::class.java, action)
        assertEquals(code, error.code)
        assertNull(error.cause)
        assertEquals("Backup contract error: ${code.name}", error.message)
    }

    @Test fun literalFixturesDetectThreeDelimitersWithOptionalBomAndQuotedMultilineCells() {
        for (delimiter in listOf(',', ';', '\t')) for (bom in listOf("", "\uFEFF")) {
            val text = bom + listOf("date", "amount", "category", "title").joinToString(delimiter.toString()) +
                "\r\n2024-02-29${delimiter}-1.25${delimiter}Żywność${delimiter}\"a${delimiter}b \"\"東京\"\"\r\n😀\"\r\n" +
                "2024-03-01${delimiter}2${delimiter}Praca${delimiter}koniec"
            val parsed = parse(text)
            assertEquals(delimiter, parsed.delimiter)
            assertEquals(listOf("date", "amount", "category", "title"), parsed.headers)
            assertEquals(listOf(2, 4), parsed.rows.map { it.rowNumber })
            assertEquals("a${delimiter}b \"東京\"\r\n😀", parsed.rows.first().cells[3])
            assertEquals("koniec", parsed.rows.last().cells[3])
        }
    }

    @Test fun lfCrAndCrlfRecordsRetainPhysicalSourceRowsAndRawWhitespace() {
        val parsed = parse("date,amount,category,title\n2024-01-01,1, Work ,\" line1\nline2\"\r2024-01-02,2,Food,  x  \r\n")
        assertEquals(listOf(2, 4), parsed.rows.map { it.rowNumber })
        assertEquals(" Work ", parsed.rows[0].cells[2])
        assertEquals(" line1\nline2", parsed.rows[0].cells[3])
        assertEquals("  x  ", parsed.rows[1].cells[3])
    }

    @Test fun duplicateRecognizedAndMissingRequiredHeadersRequireExplicitManualMapping() {
        val duplicate = CsvHeaderDetector.detect(listOf("date", "amount", "kwota", "category"))
        assertTrue(duplicate.requiresManualMapping)
        assertTrue(CsvColumn.AMOUNT in duplicate.ambiguousColumns)
        assertNull(duplicate.mapping.amount)
        val parsed = parse("when,value,bucket\n2024-01-01,-1.50,Food")
        val missing = CsvHeaderDetector.detect(parsed.headers)
        assertTrue(missing.requiresManualMapping)
        assertEquals(setOf(CsvColumn.DATE, CsvColumn.AMOUNT, CsvColumn.CATEGORY), missing.missingRequired)
        val manual = CsvRowValidator.validate(parsed, CsvColumnMapping(date = 0, amount = 1, category = 2), today)
        assertEquals(-150L, manual.rows.single().entry!!.amountGrosze)
        for (mapping in listOf(CsvColumnMapping(), CsvColumnMapping(date = 0, amount = 1, category = 5))) {
            val pending = CsvRowValidator.validate(parsed, mapping, today)
            assertTrue(pending.requiresManualMapping)
            assertEquals(parsed.rows, pending.rows.map { it.source })
            assertTrue(pending.rows.all { it.entry == null })
            assertEquals(0, pending.summary.validRows)
            assertEquals(0, pending.summary.invalidRows)
        }
    }

    @Test fun headerAliasesCanBeExtendedWithoutChangingParserAndUnknownColumnsStayRaw() {
        val parsed = parse("  DATE  ;Kwota;Kategoria;vendor-specific\n2024-01-01;1;Food;ignored")
        assertTrue(CsvHeaderDetector.detect(parsed.headers).mapping.isComplete)
        val custom = CsvHeaderDetector.detect(listOf("jour", "valeur", "groupe"), mapOf(
            CsvColumn.DATE to setOf("jour"), CsvColumn.AMOUNT to setOf("valeur"), CsvColumn.CATEGORY to setOf("groupe")))
        assertEquals(CsvColumnMapping(date = 0, amount = 1, category = 2), custom.mapping)
        assertFalse(custom.requiresManualMapping)
        assertEquals("ignored", parsed.rows.single().cells.last())
    }

    @Test fun multilingualHeadersAndTypesAreLocaleIndependentAndExtensible() {
        val fixtures = listOf("Data;Kwota;Typ;Kategoria\n2024-01-01;1;wydatek;Food",
            "Datum;Betrag;Art;Kategorie\n2024-01-01;1;Ausgabe;Food",
            "Date;Montant;Type;Catégorie\n2024-01-01;1;Dépense;Food")
        val original = Locale.getDefault()
        try {
            Locale.setDefault(Locale.forLanguageTag("tr-TR"))
            fixtures.forEach { assertEquals(-100L, validate(it).rows.single().entry!!.amountGrosze) }
            val parsed = parse("date,amount,type,category\n2024-01-01,1,debit,Food\n2024-01-01,1,income,Food")
            val result = CsvRowValidator.validate(parsed, CsvHeaderDetector.detect(parsed.headers).mapping, today,
                typeAliases = mapOf("debit" to TransferEntryType.EXPENSE, "income" to TransferEntryType.EXPENSE))
            assertEquals(listOf(-100L, 100L), result.rows.map { it.entry!!.amountGrosze })
            val neutral = CsvHeaderDetector.detect(listOf("date", "amount", "category"), mapOf(CsvColumn.TITLE to setOf("date")))
            assertEquals(0, neutral.mapping.date)
            assertNull(neutral.mapping.title)
            assertFalse(neutral.requiresManualMapping)
            val collision = CsvHeaderDetector.detect(listOf("day", "amount", "category"), mapOf(
                CsvColumn.DATE to setOf("day"), CsvColumn.TITLE to setOf("day")))
            assertTrue(collision.requiresManualMapping)
            assertEquals(setOf(CsvColumn.DATE, CsvColumn.TITLE), collision.ambiguousColumns)
        } finally { Locale.setDefault(original) }
    }

    @Test fun ambiguousDialectRequiresExplicitChoiceAndEmptyInputFailsCleanly() {
        val fixture = "one,two;three\na,b;c"
        rejected(TransferErrorCode.AMBIGUOUS_DELIMITER) { parse(fixture) }
        assertEquals(listOf("one", "two;three"), parse(fixture, ',').headers)
        assertEquals(listOf("one,two", "three"), parse(fixture, ';').headers)
        for (empty in listOf("", "\uFEFF")) rejected(TransferErrorCode.MALFORMED_CSV) { parse(empty) }
        val headerOnly = validate("date,amount,category\n")
        assertEquals(0, headerOnly.summary.totalRows)
        assertTrue(headerOnly.rows.isEmpty())
    }

    @Test fun dateRecognitionIsStrictExplicitAndRejectsFutureAgainstSuppliedToday() {
        val dates = listOf("2024-02-29", "29.02.2024", "13/02/2024", "02/13/2024", "01/02/2024", "2023-02-29", "2026-10-11", "2026-10-10")
        val result = validate("date,amount,category\n" + dates.joinToString("\n") { "$it,1,Food" })
        assertEquals(List(2) { LocalDate.of(2024, 2, 29) }, result.rows.take(2).map { it.entry!!.date })
        assertEquals(LocalDate.of(2024, 2, 13), result.rows[2].entry!!.date)
        assertEquals(LocalDate.of(2024, 2, 13), result.rows[3].entry!!.date)
        for ((index, code) in listOf(4 to "AMBIGUOUS_DATE", 5 to "INVALID_DATE", 6 to "FUTURE_DATE")) {
            assertNull(result.rows[index].entry)
            assertTrue(result.rows[index].source.issues.any { it.code == code && it.field == "date" })
        }
        assertEquals(today, result.rows[7].entry!!.date)
        for ((format, date) in listOf(CsvDateFormat.DAY_MONTH_YEAR to LocalDate.of(2024, 2, 1), CsvDateFormat.MONTH_DAY_YEAR to LocalDate.of(2024, 1, 2))) {
            assertEquals(date, validate("date,amount,category\n01/02/2024,1,Food", format).rows.single().entry!!.date)
        }
    }

    @Test fun literalSignedMoneyIsExactAndMagnitudeWithTypeChoosesDirection() {
        val fixtures = listOf("-92233720368547758.08,expense" to Long.MIN_VALUE,
            "92233720368547758.07,income" to Long.MAX_VALUE, "0.01,expense" to -1L,
            "+0.01,income" to 1L, "\"12,34\",expense" to -1234L, "12,income" to 1200L)
        val result = validate("date,amount,type,category\n" + fixtures.joinToString("\n") { "2024-01-01,${it.first},Food" })
        assertEquals(fixtures.map { it.second }, result.rows.map { it.entry!!.amountGrosze })
        assertEquals(TransferEntryType.EXPENSE, result.rows[2].entry!!.type)
    }

    @Test fun malformedMoneyConflictingTypesAndZeroRemainIndependentRowErrors() {
        val bad = listOf("0,income" to "ZERO_AMOUNT", "-1,income" to "AMOUNT_TYPE_CONFLICT", "+1,expense" to "AMOUNT_TYPE_CONFLICT",
            "1,unknown" to "INVALID_TYPE", "1.001,income" to "INVALID_AMOUNT", "1e2,income" to "INVALID_AMOUNT",
            "92233720368547758.08,income" to "INVALID_AMOUNT", "\"1,234.56\",income" to "INVALID_AMOUNT")
        val result = validate("date,amount,type,category\n" + bad.joinToString("\n") { "2024-01-01,${it.first},Food" } + "\n2024-01-01,-1,expense,Food")
        bad.forEachIndexed { index, fixture ->
            assertNull(result.rows[index].entry)
            assertTrue(fixture.second, result.rows[index].source.issues.any { it.code == fixture.second })
        }
        assertEquals(-100L, result.rows.last().entry!!.amountGrosze)
        assertEquals(bad.size, result.summary.invalidRows)
        assertEquals(1, result.summary.validRows)
    }

    @Test fun independentErrorsPreserveSourceCellsAndCollectMultipleFieldsWithoutDiscardingGoodRows() {
        val parsed = parse("date,amount,category,title\ninvalid,no-money,,\"private\ntext\"\n2024-01-01,2,Food,ok\n2024-01-01,1\n")
        val result = CsvRowValidator.validate(parsed, CsvHeaderDetector.detect(parsed.headers).mapping, today)
        assertEquals(3, result.summary.totalRows)
        assertEquals(1, result.summary.validRows)
        assertEquals(2, result.summary.invalidRows)
        assertEquals(parsed.rows[0].cells, result.rows[0].source.cells)
        assertEquals(2, result.rows[0].source.rowNumber)
        assertTrue(result.rows[0].source.issues.map { it.field }.containsAll(listOf("date", "amount", "category")))
        assertTrue(result.rows[0].source.issues.all { it.rowNumber == 2 })
        assertEquals(4, result.rows[1].source.rowNumber)
        assertTrue(result.rows[2].source.issues.any { it.code == "COLUMN_COUNT_MISMATCH" })
        assertTrue(result.summary.issues.none { it.code.contains("private") || it.code.contains("no-money") })
    }

    @Test fun literalJsonTagsAndOversizeLedgerValuesRemainAvailableForRepair() {
        val result = validate(standardHeader + "\n" + """2024-01-01,-1,expense,"  東京  ",Food,,"["" a "",""b,c"",""😀""]",Person""")
        assertEquals(listOf(" a ", "b,c", "😀"), result.rows.single().entry!!.tags)
        assertEquals("  東京  ", result.rows.single().entry!!.title)
        val title = "x".repeat(161)
        val oversized = validate("date,amount,category,title\n2024-01-01,1,Food,$title")
        assertNull(oversized.rows.single().entry)
        assertEquals(title, oversized.rows.single().source.cells[3])
        assertTrue(oversized.rows.single().source.issues.any { it.code == "FIELD_TOO_LONG" && it.field == "title" })
    }

    @Test fun malformedTagArraysAndDomainBoundsHaveSafeRowIssues() {
        val badTags = listOf("[1]" to "INVALID_TAGS", "[\"\"]" to "INVALID_TAGS",
            "[\"a\",\"a\"]" to "DUPLICATE_TAGS", "[\"a\"] trailing" to "INVALID_TAGS",
            "[\"\\uD800\"]" to "INVALID_TAGS", "[\"${"x".repeat(41)}\"]" to "FIELD_TOO_LONG",
            (0..10).joinToString(",", "[", "]") { "\"tag$it\"" } to "TOO_MANY_TAGS")
        for ((tags, code) in badTags) {
            val csvTags = "\"" + tags.replace("\"", "\"\"") + "\""
            val result = validate("date,amount,category,tags\n2024-01-01,1,Food,$csvTags")
            assertNull(result.rows.single().entry)
            assertEquals(tags, result.rows.single().source.cells.last())
            assertTrue(code, result.rows.single().source.issues.any { it.code == code && it.field == "tags" })
        }
        for (field in listOf("category", "subcategory")) {
            val headers = if (field == "category") "date,amount,category" else "date,amount,category,subcategory"
            val values = if (field == "category") "2024-01-01,1," else "2024-01-01,1,Food,"
            val result = validate(headers + "\n" + values + "x".repeat(61))
            assertNull(result.rows.single().entry)
            assertTrue(result.rows.single().source.issues.any { it.code == "FIELD_TOO_LONG" && it.field == field })
        }
    }

    @Test fun malformedUtf8AndSyntaxFailWholeFileEvenAfterValidRows() {
        val valid = "date,amount,category\n2024-01-01,1,Food\n"
        rejected(TransferErrorCode.MALFORMED_UTF8) { CsvParser.parse(valid.toByteArray() + byteArrayOf(0xc3.toByte(), 0x28)) }
        for (suffix in listOf("2024-01-01,1,\"unclosed", "2024-01-01,1,un\"quoted", "2024-01-01,1,\"closed\"tail")) {
            rejected(TransferErrorCode.MALFORMED_CSV) { parse(valid + suffix) }
        }
    }

    @Test fun byteRowRawCellAndColumnLimitsAreEnforcedBeforeReturningPartialFiles() {
        rejected(TransferErrorCode.LIMIT_EXCEEDED) { CsvParser.parse(ByteArray(BackupLimits.MAX_BYTES + 1) { 'x'.code.toByte() }) }
        val prefix = "date,amount,category,title\n2024-01-01,1,Food,"
        assertEquals(BackupLimits.MAX_RAW_FIELD_CHARS, parse(prefix + "x".repeat(BackupLimits.MAX_RAW_FIELD_CHARS)).rows.single().cells.last().length)
        rejected(TransferErrorCode.LIMIT_EXCEEDED) { parse(prefix + "x".repeat(BackupLimits.MAX_RAW_FIELD_CHARS + 1)) }
        val row = "2024-01-01,1,Food\n"
        assertEquals(BackupLimits.MAX_ENTRIES, parse("date,amount,category\n" + row.repeat(BackupLimits.MAX_ENTRIES)).rows.size)
        rejected(TransferErrorCode.LIMIT_EXCEEDED) { parse("date,amount,category\n" + row.repeat(BackupLimits.MAX_ENTRIES + 1)) }
        assertEquals(128, parse(List(128) { "h$it" }.joinToString(",") + "\n" + List(128) { "v" }.joinToString(",")).headers.size)
        rejected(TransferErrorCode.LIMIT_EXCEEDED) { parse(List(129) { "h$it" }.joinToString(","), ',') }
        val wideHeader = (listOf("date", "amount", "category") + List(126) { "h$it" }).joinToString(",")
        rejected(TransferErrorCode.LIMIT_EXCEEDED) { parse(wideHeader + "\n" + List(129) { "v" }.joinToString(",")) }
    }

    @Test fun exactByteBoundaryIncludesBomHeadersRecordsAndUtf8Encoding() {
        val header = "\uFEFFdate,amount,category,title\n"
        val rowPrefix = "2024-01-01,1,Food,"
        val count = 1_100
        var remaining = BackupLimits.MAX_BYTES - header.toByteArray(Charsets.UTF_8).size -
            count * (rowPrefix.length + 1)
        val fixture = buildString(BackupLimits.MAX_BYTES) {
            append(header)
            repeat(count) {
                val length = minOf(remaining, BackupLimits.MAX_RAW_FIELD_CHARS)
                append(rowPrefix); append("x".repeat(length)); append('\n')
                remaining -= length
            }
        }.toByteArray(Charsets.UTF_8)
        assertEquals(0, remaining)
        assertEquals(BackupLimits.MAX_BYTES, fixture.size)
        assertEquals(count, CsvParser.parse(fixture).rows.size)
        rejected(TransferErrorCode.LIMIT_EXCEEDED) { CsvParser.parse(fixture + byteArrayOf('\n'.code.toByte())) }
        val unicode = parse("date,amount,category,title\n2024-01-01,1,Food," + "😀".repeat(8_192))
        assertEquals(BackupLimits.MAX_RAW_FIELD_CHARS, unicode.rows.single().cells.last().length)
    }

    @Test fun exporterRoundTripPreservesUnicodeWhitespaceJsonTagsAndExactMoneyAcrossLocales() {
        val original = Locale.getDefault()
        val entry = BackupEntry("e", Long.MIN_VALUE, LocalDate.of(2024, 2, 29), "c", "a", "u",
            title = " Żółć, \"東京\"\r\n😀 ", subcategoryId = "s", tags = listOf(" a ", "b,c", "a\"b", "a\nb", "😀"))
        val names = CsvNameLookup(mapOf("c" to " Food "), mapOf(("c" to "s") to " 食 "), mapOf("a" to "Person"))
        try {
            for (locale in listOf("pl-PL", "en-US", "ar-EG", "tr-TR")) {
                Locale.setDefault(Locale.forLanguageTag(locale))
                val parsed = CsvParser.parse(CsvExporter.export(listOf(entry), names, today).bytes)
                val result = CsvRowValidator.validate(parsed, CsvHeaderDetector.detect(parsed.headers).mapping, today)
                assertEquals(0, result.summary.invalidRows)
                val imported = result.rows.single().entry!!
                assertEquals(entry.amountGrosze, imported.amountGrosze)
                assertEquals(entry.date, imported.date)
                assertEquals(entry.title, imported.title)
                assertEquals(" Food ", imported.categoryName)
                assertEquals(" 食 ", imported.subcategoryName)
                assertEquals(entry.tags.sorted(), imported.tags)
            }
        } finally { Locale.setDefault(original) }
    }
}
