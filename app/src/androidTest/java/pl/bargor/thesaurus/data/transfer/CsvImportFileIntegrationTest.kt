package pl.bargor.thesaurus.data.transfer

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import java.io.File
import java.time.LocalDate
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith

/** Real Android file streams, with handwritten fixtures independent of the production exporter. */
@RunWith(AndroidJUnit4::class)
class CsvImportFileIntegrationTest {
    private val today = LocalDate.of(2026, 10, 10)

    @Test fun bomSemicolonUnicodeAndQuotedMultilineCellsSurviveFileParsingAndValidation() {
        val fixture = "\uFEFFData;Kwota;Typ;Tytuł;Kategoria;Tagi\r\n" +
            "2024-02-29;92233720368547758,07;przychód;\"Żółć; \"\"東京\"\"\r\n😀\";Praca;\"[\"\" a \"\",\"\"b,c\"\"]\"\r\n" +
            "2024-03-01;12,34;wydatek;zakupy;Żywność;[]\r\n"
        withTemporaryFile { file ->
            file.outputStream().use { it.write(fixture.toByteArray(Charsets.UTF_8)) }
            val parsed = file.inputStream().use { CsvParser.parse(it.readBytes()) }
            assertEquals(';', parsed.delimiter)
            assertEquals(listOf(2, 4), parsed.rows.map { it.rowNumber })
            val result = CsvRowValidator.validate(parsed, CsvHeaderDetector.detect(parsed.headers).mapping, today)
            assertEquals(2, result.summary.validRows)
            assertEquals(0, result.summary.invalidRows)
            assertEquals(Long.MAX_VALUE, result.rows.first().entry!!.amountGrosze)
            assertEquals("Żółć; \"東京\"\r\n😀", result.rows.first().entry!!.title)
            assertEquals(listOf(" a ", "b,c"), result.rows.first().entry!!.tags)
            assertEquals(-1234L, result.rows.last().entry!!.amountGrosze)
        }
    }

    @Test fun invalidRowsKeepRawCellsWhileGoodRowsRemainReadyForPreview() {
        val fixture = "date\tamount\tcategory\ttitle\n" +
            "bad-date\tnot-money\t\t\"private\nline\"\n" +
            "2024-02-29\t-1.25\t Food \t  title  \n"
        withTemporaryFile { file ->
            file.outputStream().use { it.write(fixture.toByteArray(Charsets.UTF_8)) }
            val parsed = file.inputStream().use { CsvParser.parse(it.readBytes()) }
            val result = CsvRowValidator.validate(parsed, CsvHeaderDetector.detect(parsed.headers).mapping, today)
            assertEquals(1, result.summary.validRows)
            assertEquals(1, result.summary.invalidRows)
            assertEquals(parsed.rows.first().cells, result.rows.first().source.cells)
            assertNull(result.rows.first().entry)
            assertEquals(4, result.rows.last().source.rowNumber)
            assertEquals(-125L, result.rows.last().entry!!.amountGrosze)
            assertEquals(" Food ", result.rows.last().entry!!.categoryName)
            assertEquals("  title  ", result.rows.last().entry!!.title)
        }
    }

    @Test fun malformedEncodingOrTrailingCsvSyntaxFailsWholeAndroidFile() {
        val prefix = "date,amount,category\n2024-01-01,1,Food\n".toByteArray(Charsets.UTF_8)
        for ((bytes, expected) in listOf(
            (prefix + byteArrayOf(0xc3.toByte(), 0x28)) to TransferErrorCode.MALFORMED_UTF8,
            (prefix + "2024-01-01,1,\"unfinished".toByteArray(Charsets.UTF_8)) to TransferErrorCode.MALFORMED_CSV,
        )) {
            withTemporaryFile { file ->
                file.outputStream().use { it.write(bytes) }
                val error = assertThrows(BackupContractException::class.java) {
                    file.inputStream().use { CsvParser.parse(it.readBytes()) }
                }
                assertEquals(expected, error.code)
                assertNull(error.cause)
                assertEquals("Backup contract error: ${expected.name}", error.message)
            }
        }
    }

    @Test fun exporterJsonTagsAndSignedLongExtremeRoundTripThroughAndroidFile() {
        val entry = BackupEntry("e", Long.MIN_VALUE, LocalDate.of(2024, 2, 29), "c", "a", "u",
            title = " 東京,\"Żółć\"\r\n😀 ", tags = listOf(" a ", "b,c", "a\"b", "a\nb", "😀"))
        val artifact = CsvExporter.export(listOf(entry), CsvNameLookup(mapOf("c" to " Food "), authorNames = mapOf("a" to "Person")), today)
        withTemporaryFile { file ->
            file.outputStream().use { it.write(artifact.bytes) }
            val parsed = file.inputStream().use { CsvParser.parse(it.readBytes()) }
            val result = CsvRowValidator.validate(parsed, CsvHeaderDetector.detect(parsed.headers).mapping, today)
            assertEquals(1, result.summary.validRows)
            val imported = result.rows.single().entry!!
            assertEquals(entry.amountGrosze, imported.amountGrosze)
            assertEquals(entry.title, imported.title)
            assertEquals(entry.tags.sorted(), imported.tags)
            assertEquals(" Food ", imported.categoryName)
        }
    }

    private fun withTemporaryFile(action: (File) -> Unit) {
        val cache = InstrumentationRegistry.getInstrumentation().targetContext.cacheDir
        check(cache.isDirectory || cache.mkdirs())
        val file = File.createTempFile("issue110-csv-contract-", ".csv", cache)
        try { action(file) } finally { check(file.delete()) }
    }
}
