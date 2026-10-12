package pl.bargor.thesaurus.data.transfer

import android.content.res.Configuration
import android.os.LocaleList
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import java.text.NumberFormat
import java.time.Instant
import java.time.LocalDate
import java.util.Locale
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith

/** Exercise Android resource locales and the process locale, without changing device settings. */
@RunWith(AndroidJUnit4::class)
class TransferLocaleInstrumentationTest {
    @Test fun androidPolishAndArabicLocalesKeepCsvAndJsonBytesCanonical() {
        val target = InstrumentationRegistry.getInstrumentation().targetContext
        val savedDefault = Locale.getDefault()
        val savedDisplay = Locale.getDefault(Locale.Category.DISPLAY)
        val savedFormat = Locale.getDefault(Locale.Category.FORMAT)
        val savedConfigurationLocales = target.resources.configuration.locales.toLanguageTags()
        val date = LocalDate.of(2024, 2, 29)
        val document = BackupDocument(
            ExportMetadata(Instant.parse("2026-10-10T12:34:56Z"), "synthetic-household", "test"),
            -12345,
            categories = listOf(BackupCategory("food", "Żywność 東京 طعام", "author", "author")),
            entries = listOf(
                BackupEntry("expense", -12345, date, "food", "author", "author",
                    title = "Żółć, 東京 😀", tags = listOf("żółć", "東京")),
                BackupEntry("income", 67890, date.plusDays(1), "food", "author", "author", title = "دخل"),
            ),
        )
        val names = CsvNameLookup(mapOf("food" to document.categories.single().name),
            authorNames = mapOf("author" to "Işık"))
        val csvBytes = mutableListOf<ByteArray>()
        val jsonBytes = mutableListOf<ByteArray>()
        val localizedNumbers = mutableListOf<String>()
        try {
            for (tag in listOf("pl-PL", "ar-EG")) {
                val locale = Locale.forLanguageTag(tag)
                val localized = target.createConfigurationContext(Configuration(target.resources.configuration).apply {
                    setLocales(LocaleList(locale))
                })
                assertEquals(locale, localized.resources.configuration.locales[0])
                Locale.setDefault(locale)
                assertEquals(locale, Locale.getDefault())
                localizedNumbers += NumberFormat.getInstance(localized.resources.configuration.locales[0]).format(1234.5)

                val csv = CsvExporter.export(document.entries, names, LocalDate.of(2026, 10, 10))
                assertEquals("thesaurus-2026-10-10.csv", csv.suggestedFileName)
                val records = CsvParser.parse(csv.bytes)
                assertEquals(listOf("date", "amount", "type", "title", "category", "subcategory", "tags", "author"), records.headers)
                assertEquals(listOf("2024-02-29", "-123.45", "expense"), records.rows[0].cells.take(3))
                assertEquals(listOf("2024-03-01", "678.90", "income"), records.rows[1].cells.take(3))
                assertEquals("Żółć, 東京 😀", records.rows[0].cells[3])
                assertEquals("Żywność 東京 طعام", records.rows[0].cells[4])
                csvBytes += csv.bytes

                val json = BackupJsonCodec.encode(document)
                val decoded = BackupJsonCodec.decode(json)
                assertEquals(document, decoded)
                assertEquals(listOf(-12345L, 67890L), decoded.entries.map { it.amountGrosze })
                assertEquals(listOf(date, date.plusDays(1)), decoded.entries.map { it.date })
                assertEquals(TransferEntryType.EXPENSE, decoded.categories.single().defaultEntryType)
                assertTrue(json.toString(Charsets.UTF_8).contains("\"amountGrosze\":-12345"))
                jsonBytes += json
            }
            assertNotEquals("The two Android locales must exercise different number rendering", localizedNumbers[0], localizedNumbers[1])
            assertArrayEquals(csvBytes[0], csvBytes[1])
            assertArrayEquals(jsonBytes[0], jsonBytes[1])
        } finally {
            Locale.setDefault(savedDefault)
            Locale.setDefault(Locale.Category.DISPLAY, savedDisplay)
            Locale.setDefault(Locale.Category.FORMAT, savedFormat)
        }
        assertEquals(savedDefault, Locale.getDefault())
        assertEquals(savedConfigurationLocales, target.resources.configuration.locales.toLanguageTags())
    }
}
