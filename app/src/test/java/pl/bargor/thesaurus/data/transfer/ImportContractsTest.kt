package pl.bargor.thesaurus.data.transfer

import org.junit.Assert.*
import org.junit.Test

class ImportContractsTest {
    @Test fun mappingPreservesOptionalColumnsAndRequiresUniqueNonnegativeIndices() {
        assertFalse(CsvColumnMapping().isComplete)
        assertFalse(CsvColumnMapping(date = 0, amount = 1).isComplete)
        val mapping = CsvColumnMapping(date = 0, amount = 1, category = 2, type = 3)
        assertTrue(mapping.isComplete)
        assertEquals(3, mapping.type)
        assertNull(mapping.title)
        assertThrows(IllegalArgumentException::class.java) { CsvColumnMapping(date = -1) }
        assertThrows(IllegalArgumentException::class.java) { CsvColumnMapping(date = 1, amount = 1) }
        assertThrows(IllegalArgumentException::class.java) { CsvColumnMapping(tags = 4, type = 4) }
    }

    @Test fun rawRowsKeepMalformedValuesAndOriginalWhitespaceWithoutDomainValidation() {
        val cells = listOf(" not-a-date ", "0,00", "  Zażółć  ", "")
        val issue = ValidationIssue("INVALID_DATE", "date", 7)
        val row = ImportRow(7, cells, listOf(issue))
        assertEquals(cells, row.cells)
        assertEquals(listOf(issue), row.issues)
        assertThrows(IllegalArgumentException::class.java) { ImportRow(0, cells) }
        assertThrows(IllegalArgumentException::class.java) { ImportRow(7, cells, listOf(issue.copy(rowNumber = 8))) }
        assertThrows(IllegalArgumentException::class.java) { ImportRow(1, listOf("x".repeat(BackupLimits.MAX_RAW_FIELD_CHARS + 1))) }
        assertEquals(BackupLimits.MAX_RAW_FIELD_CHARS, ImportRow(1, listOf("x".repeat(BackupLimits.MAX_RAW_FIELD_CHARS))).cells.single().length)
    }

    @Test fun issueAndSummaryContractsExposeRowFailuresAndDisjointOutcomeCounts() {
        val failure = ValidationIssue("INVALID_AMOUNT", "amount", 2)
        val warning = ValidationIssue("REPAIRED_TITLE", "title", 3, ValidationSeverity.WARNING)
        val fileIssue = ValidationIssue("UNSUPPORTED_FORMAT")
        val summary = ImportSummary(6, 4, 2, listOf(failure, warning, fileIssue),
            added = 1, repairedAndAdded = 1, duplicates = 1, discarded = 1, serverErrors = 1, pending = 1)
        assertEquals(listOf(failure), summary.rowFailures)
        assertEquals(1, summary.repairedAndAdded)
        assertThrows(IllegalArgumentException::class.java) { ImportSummary(1, 1, 1) }
        assertThrows(IllegalArgumentException::class.java) { ImportSummary(Int.MAX_VALUE, Int.MAX_VALUE, Int.MAX_VALUE) }
        assertThrows(IllegalArgumentException::class.java) { ImportSummary(1, 0, 0, added = 1, duplicates = 1) }
        assertThrows(IllegalArgumentException::class.java) { ImportSummary(1, 0, 0, pending = -1) }
        assertThrows(IllegalArgumentException::class.java) { ValidationIssue(" ") }
        assertThrows(IllegalArgumentException::class.java) { ValidationIssue("INVALID", rowNumber = 0) }
        assertThrows(IllegalArgumentException::class.java) { ValidationIssue("INVALID", field = "") }
    }
}
