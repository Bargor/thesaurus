package pl.bargor.thesaurus.data.transfer

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import java.io.File
import java.time.LocalDate
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith

/** Local file contract only: no login, repository, household, or backend is involved. */
@RunWith(AndroidJUnit4::class)
class CsvExportFileIntegrationTest {
    private val date = LocalDate.of(2026, 10, 10)

    @Test fun unicodeAndQuotedMultilineCsvBytesSurviveAndroidFileStreams() {
        val entry = BackupEntry("entry", Long.MIN_VALUE, LocalDate.of(2024, 2, 29), "food", "author", "editor",
            title = "Żółć, \"東京\"\r\n😀", tags = listOf("z", "a"))
        val artifact = CsvExporter.export(listOf(entry), CsvNameLookup(mapOf("food" to "Żywność"),
            authorNames = mapOf("author" to "Işık")), date)
        withTemporaryFile { file ->
            file.outputStream().use { it.write(artifact.bytes) }
            val bytes = file.inputStream().use { it.readBytes() }
            assertArrayEquals(artifact.bytes, bytes)
            assertEquals("thesaurus-2026-10-10.csv", artifact.suggestedFileName)
            assertEquals("\uFEFFdate,amount,type,title,category,subcategory,tags,author\r\n" +
                "2024-02-29,-92233720368547758.08,expense,\"Żółć, \"\"東京\"\"\r\n😀\",Żywność,,\"[\"\"a\"\",\"\"z\"\"]\",Işık\r\n",
                bytes.toString(Charsets.UTF_8))
        }
    }

    @Test fun emptyAndDeletedOnlyExportsPersistExactlyOneHeaderRecord() {
        val deleted = BackupEntry("deleted", 0, date, "missing", "missing", "editor", deleted = true)
        val empty = CsvExporter.export(emptyList(), CsvNameLookup(), date)
        val deletedOnly = CsvExporter.export(listOf(deleted), CsvNameLookup(), date)
        assertArrayEquals(empty.bytes, deletedOnly.bytes)
        withTemporaryFile { file ->
            file.outputStream().use { it.write(deletedOnly.bytes) }
            val bytes = file.inputStream().use { it.readBytes() }
            assertArrayEquals(byteArrayOf(0xef.toByte(), 0xbb.toByte(), 0xbf.toByte()), bytes.take(3).toByteArray())
            assertEquals("\uFEFFdate,amount,type,title,category,subcategory,tags,author\r\n", bytes.toString(Charsets.UTF_8))
        }
    }

    private fun withTemporaryFile(action: (File) -> Unit) {
        val cache = InstrumentationRegistry.getInstrumentation().targetContext.cacheDir
        check(cache.isDirectory || cache.mkdirs()) { "Test cache directory could not be created." }
        val file = File.createTempFile("issue109-csv-contract-", ".csv", cache)
        try { action(file) } finally { check(file.delete()) }
    }
}
