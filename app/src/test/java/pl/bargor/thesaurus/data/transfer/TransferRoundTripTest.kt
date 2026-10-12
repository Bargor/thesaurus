package pl.bargor.thesaurus.data.transfer

import java.io.File
import java.time.Instant
import java.time.LocalDate
import java.util.Locale
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test
import pl.bargor.thesaurus.data.model.*

/** Exercises the published template through parser, validation, import, and both exporters. */
class TransferRoundTripTest {
    private val today = LocalDate.of(2026, 10, 12)
    private val exportedAt = Instant.parse("2026-10-12T12:00:00Z")

    @Test fun publishedTemplateImportsReexportsAndReimportsWithoutDuplicateWrites() = runBlocking {
        val parsed = CsvParser.parse(template().readBytes())
        val detection = CsvHeaderDetector.detect(parsed.headers)
        assertFalse(detection.requiresManualMapping)
        val validated = CsvRowValidator.validate(parsed, detection.mapping, today)
        assertEquals(3, validated.summary.validRows)
        assertTrue(validated.summary.issues.isEmpty())
        assertEquals(listOf(-1234L, 250000L, -1L), validated.rows.map { it.entry!!.amountGrosze })
        assertEquals("Lunch, \"Żółć\" 🍲", validated.rows.first().entry!!.title)
        assertNull(validated.rows[1].entry!!.title)
        assertTrue(validated.rows[2].entry!!.title!!.contains('\n'))
        assertEquals(listOf("quote\"tag", "line\nbreak"), validated.rows[2].entry!!.tags)

        val backend = Backend()
        val importer = CsvImportCoordinator(backend) { today }
        val session = importer.prepare("house", parsed, detection.mapping)
        assertEquals(0, backend.writes)
        assertEquals(3, importer.commit(session).summary.added)
        assertEquals(3, backend.writes)
        assertTrue(backend.entries.all { it.authorId == "owner" && it.updatedById == "owner" })
        assertEquals(validated.rows[2].entry!!.title, backend.entries[2].title)
        assertEquals(listOf("line\nbreak", "quote\"tag"), backend.entries[2].tags)

        val document = JsonBackupExporter.toDocument(backend.jsonSnapshot(), exportedAt)
        val json = BackupJsonCodec.encode(document)
        assertEquals(canonicalDocument(document), BackupJsonCodec.decode(json))
        assertArrayEquals(json, BackupJsonCodec.encode(BackupJsonCodec.decode(json)))
        val csv = exportCsv(document)
        val canonical = CsvParser.parse(csv)
        assertEquals(listOf("-12.34", "2500.00", "-0.01"), canonical.rows.map { it.cells[1] })
        assertEquals(listOf("expense", "income", "expense"), canonical.rows.map { it.cells[2] })
        assertTrue(canonical.rows.all { it.cells[7] == "unknown-author" })
        val reimport = importer.prepare("house", canonical, CsvHeaderDetector.detect(canonical.headers).mapping)
        assertEquals(3, importer.commit(reimport).summary.duplicates)
        assertEquals(3, backend.writes)
        assertArrayEquals(csv, exportCsv(JsonBackupExporter.toDocument(backend.jsonSnapshot(), exportedAt)))
    }

    @Test fun importedExtremeMoneyAndUnicodeStayExactAcrossLocalesAndBothFormats() = runBlocking {
        val original = Locale.getDefault()
        try {
            var expectedCsv: ByteArray? = null
            var expectedJson: ByteArray? = null
            for (locale in listOf("en-US", "pl-PL", "ar-EG", "tr-TR")) {
                Locale.setDefault(Locale.forLanguageTag(locale))
                val backend = Backend()
                val source = "date;amount;type;title;category;tags\n" +
                    "2026-01-01;-92233720368547758.08;expense;Żółć 🍲;Food;[]\n" +
                    "2026-01-02;92233720368547758.07;income;;Salary;[]\n"
                val parsed = CsvParser.parse(source.toByteArray(Charsets.UTF_8))
                val importer = CsvImportCoordinator(backend) { today }
                assertEquals(2, importer.commit(importer.prepare("house", parsed,
                    CsvHeaderDetector.detect(parsed.headers).mapping)).summary.added)
                assertEquals(listOf(Long.MIN_VALUE, Long.MAX_VALUE), backend.entries.map { it.amountGrosze })
                val document = JsonBackupExporter.toDocument(backend.jsonSnapshot(), exportedAt)
                val csv = exportCsv(document)
                val json = BackupJsonCodec.encode(document)
                val decoded = BackupJsonCodec.decode(json)
                assertEquals(canonicalDocument(document), decoded)
                val checked = CsvParser.parse(csv).let {
                    CsvRowValidator.validate(it, CsvHeaderDetector.detect(it.headers).mapping, today)
                }
                assertEquals(listOf(Long.MIN_VALUE, Long.MAX_VALUE), checked.rows.map { it.entry!!.amountGrosze })
                if (expectedCsv == null) { expectedCsv = csv; expectedJson = json }
                else { assertArrayEquals(expectedCsv, csv); assertArrayEquals(expectedJson, json) }
            }
        } finally { Locale.setDefault(original) }
    }

    @Test fun malformedFilesInvalidRowsAndUnauthorizedSnapshotsCannotWrite() = runBlocking {
        val backend = Backend()
        for (bytes in listOf(byteArrayOf(0xC3.toByte(), 0x28),
            "date,amount,category\n2026-01-01,1,\"Food".toByteArray(Charsets.UTF_8))) {
            assertTrue(runCatching { CsvParser.parse(bytes) }.isFailure)
        }
        val importer = CsvImportCoordinator(backend) { today }
        val parsed = CsvParser.parse(("date,amount,type,category,tags\n" +
            "2099-01-01,1,income,Food,[]\n" +
            "2026-01-01,+1,expense,Food,[]\n" +
            "2026-01-01,1,income,Food,not-json\n" +
            "2026-01-01,1,income,Archived,[]\n").toByteArray(Charsets.UTF_8))
        val mapping = CsvHeaderDetector.detect(parsed.headers).mapping
        val report = importer.commit(importer.prepare("house", parsed, mapping))
        assertEquals(4, report.summary.invalidRows)
        assertTrue(report.summary.issues.map { it.code }.containsAll(listOf(
            "FUTURE_DATE", "AMOUNT_TYPE_CONFLICT", "INVALID_TAGS", "IMPORT_ARCHIVED_CATEGORY")))
        assertEquals(0, backend.writes)
        backend.identity = CsvImportIdentity("member", "session")
        assertTrue(runCatching { importer.prepare("house", parsed, mapping) }.isFailure)
        backend.identity = CsvImportIdentity("owner", "session")
        backend.synchronized = false
        assertTrue(runCatching { importer.prepare("house", parsed, mapping) }.isFailure)
        assertEquals(0, backend.writes)
    }

    private fun template(): File = listOf(File("docs/templates/thesaurus-entries.csv"),
        File("../docs/templates/thesaurus-entries.csv")).firstOrNull { it.isFile }
        ?: error("Published CSV template must be present in the checkout")

    private fun exportCsv(document: BackupDocument) = CsvExporter.export(document.entries,
        CsvNameLookup(document.categories.associate { it.id to it.name },
            document.subcategories.associate { (it.categoryId to it.id) to it.name }), today).bytes

    private fun canonicalDocument(document: BackupDocument) = document.copy(
        categories = document.categories.sortedBy { it.id },
        subcategories = document.subcategories.sortedWith(compareBy({ it.categoryId }, { it.id })),
        entries = document.entries.sortedBy { it.id }.map { it.copy(tags = it.tags.sorted()) })

    private class Backend : CsvImportBackend {
        var identity = CsvImportIdentity("owner", "session")
        var synchronized = true
        var household = Household("house", "Synthetic household", "owner")
        val entries = mutableListOf<LedgerEntry>()
        var writes = 0
        val categories = listOf(
            Category("food", "house", "Food", authorId = "owner", updatedById = "owner"),
            Category("salary", "house", "Salary", defaultEntryType = EntryType.INCOME,
                authorId = "owner", updatedById = "owner"),
            Category("archived", "house", "Archived", archived = true,
                authorId = "owner", updatedById = "owner"))
        val subcategories = listOf(Subcategory("groceries", "house", "food", "Groceries",
            authorId = "owner", updatedById = "owner"))
        override fun currentIdentity() = identity
        override suspend fun loadSnapshot(householdId: String, expectedIdentity: CsvImportIdentity) =
            CsvImportSnapshot(identity, household, entries.toList(), categories, subcategories, synchronized)
        override suspend fun commitEntry(snapshot: CsvImportSnapshot, entry: LedgerEntry): CsvImportCommitResult {
            check(snapshot.identity == identity && household.ownerId == identity.uid && synchronized)
            check(snapshot.household.ledgerRevision == household.ledgerRevision)
            writes++
            entries += entry
            household = household.copy(ledgerRevision = household.ledgerRevision + 1)
            return CsvImportCommitResult(household.ledgerRevision, true)
        }
        fun jsonSnapshot() = JsonBackupSnapshot(identity, household, entries.toList(), categories,
            subcategories, setOf("owner", "member"), synchronized)
    }
}
