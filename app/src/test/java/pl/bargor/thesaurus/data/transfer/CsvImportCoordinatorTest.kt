package pl.bargor.thesaurus.data.transfer

import java.time.LocalDate
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test
import pl.bargor.thesaurus.data.model.Category
import pl.bargor.thesaurus.data.model.Household
import pl.bargor.thesaurus.data.model.LedgerEntry
import pl.bargor.thesaurus.data.model.Subcategory

class CsvImportCoordinatorTest {
    private val today = LocalDate.of(2026, 10, 10)
    private val mapping = CsvColumnMapping(date = 0, amount = 1, category = 2, title = 3, tags = 4)

    private fun csv(vararg rows: String) = CsvParser.parse(
        ("date;amount;category;title;tags\n" + rows.joinToString("\n")).toByteArray())

    @Test fun importUsesCurrentPrincipalAndNormalizesDuplicatesAgainstExistingAndSameFile() = runBlocking {
        val backend = FakeBackend()
        backend.entries += LedgerEntry("existing", "house", -100, today.minusDays(1), " title ", "food",
            tags = listOf("A", "b"), authorId = "old-author", updatedById = "old-author")
        val coordinator = CsvImportCoordinator(backend) { today }
        val session = coordinator.prepare("house", csv(
            "2026-10-09;-1; Food ;title;\"[\"\"b\"\",\"\"a\"\"]\"",
            "2026-10-09;-2;food; new ;\"[\"\" A \"\",\"\"a\"\"]\"",
            "2026-10-09;-2;FOOD;new;\"[\"\"a\"\"]\"",
        ), mapping)
        val result = coordinator.commit(session)
        assertEquals(1, result.summary.added)
        assertEquals(2, result.summary.duplicates)
        assertEquals(1, backend.writes.size)
        assertEquals("owner", backend.writes.single().authorId)
        assertEquals("owner", backend.writes.single().updatedById)
        assertEquals("new", backend.writes.single().normalizedTitle)
        assertEquals(listOf("a"), backend.writes.single().normalizedTags)
    }

    @Test fun unknownArchivedAndAmbiguousTaxonomyRemainPendingWithoutWrites() = runBlocking {
        val backend = FakeBackend()
        backend.categories += Category("archived", "house", "Old", archived = true, authorId = "owner", updatedById = "owner")
        backend.categories += Category("second", "house", "FOOD", authorId = "owner", updatedById = "owner")
        val coordinator = CsvImportCoordinator(backend) { today }
        val session = coordinator.prepare("house", csv(
            "2026-10-09;-1;Missing;;", "2026-10-09;-2;Old;;", "2026-10-09;-3;food;;"), mapping)
        val result = coordinator.commit(session)
        assertEquals(3, result.summary.invalidRows)
        assertTrue(backend.writes.isEmpty())
        assertEquals(3, backend.categories.size)
    }

    @Test fun partialServerFailureContinuesAndExposesOnlySafeRowCodes() = runBlocking {
        val backend = FakeBackend().apply { failAmount = -200 }
        val coordinator = CsvImportCoordinator(backend) { today }
        val session = coordinator.prepare("house", csv(
            "2026-10-09;-1;food;private one;", "2026-10-09;-2;food;private two;", "2026-10-09;-3;food;private three;"), mapping)
        val result = coordinator.commit(session)
        assertEquals(2, result.summary.added)
        assertEquals(1, result.summary.serverErrors)
        assertEquals(listOf(3), result.summary.rowFailures.map { it.rowNumber })
        assertFalse(result.summary.issues.toString().contains("private"))
        assertFalse(result.summary.issues.toString().contains("SECRET"))
        assertEquals(listOf(-100L, -300L), backend.writes.map { it.amountGrosze })
    }

    @Test fun cancellationPropagatesAndNeverTurnsIntoSourceRowError() = runBlocking {
        val backend = FakeBackend().apply { cancel = true }
        val coordinator = CsvImportCoordinator(backend) { today }
        val session = coordinator.prepare("house", csv("2026-10-09;-1;food;;"), mapping)
        try { coordinator.commit(session); fail("Cancellation must escape") }
        catch (_: CancellationException) { }
        assertTrue(backend.writes.isEmpty())
    }

    @Test fun ownerAndCompleteSyncGatesRejectBeforeAnyWrite() = runBlocking {
        for (backend in listOf(FakeBackend().apply { household = household.copy(ownerId = "other") },
            FakeBackend().apply { synchronized = false })) {
            val coordinator = CsvImportCoordinator(backend) { today }
            try { coordinator.prepare("house", csv("2026-10-09;-1;food;;"), mapping); fail("Gate must reject") }
            catch (error: IllegalStateException) { assertEquals("IMPORT_OWNER_SYNC_REQUIRED", error.message) }
            assertTrue(backend.writes.isEmpty())
        }
    }

    @Test fun changedSessionCannotCommitAndRepairRechecksDuplicates() = runBlocking {
        val backend = FakeBackend()
        val coordinator = CsvImportCoordinator(backend) { today }
        val session = coordinator.prepare("house", csv("2026-10-09;-1;food;one;", "bad;-1;food;one;"), mapping)
        coordinator.commit(session)
        val repaired = coordinator.repair(session, 3, listOf("2026-10-09", "-1", "food", "one", ""))
        assertEquals(1, repaired.summary.duplicates)
        assertEquals(1, repaired.summary.added)
        assertEquals(1, backend.writes.size)
        assertEquals("bad", repaired.originalRows.single { it.rowNumber == 3 }.cells.first())
        assertEquals("2026-10-09", repaired.rows.single { it.source.rowNumber == 3 }.source.cells.first())
        val next = coordinator.prepare("house", csv("2026-10-09;-2;food;;"), mapping)
        backend.identity = backend.identity.copy(sessionKey = "signed-in-again")
        try { coordinator.commit(next); fail("Changed session must reject") }
        catch (error: IllegalStateException) { assertEquals("IMPORT_SESSION_CHANGED", error.message) }
        assertEquals(1, backend.writes.size)
    }

    @Test fun explicitTaxonomyCreationAndRepairCommitUseOwnerAndDisjointSummaryBuckets() = runBlocking {
        val backend = FakeBackend()
        val coordinator = CsvImportCoordinator(backend) { today }
        val session = coordinator.prepare("house", csv("2026-10-09;-1;New category;;", "bad;-2;food;;"), mapping)
        assertTrue(backend.writes.isEmpty())
        val created = coordinator.createCategory(session, "New category")
        assertEquals("owner", created.authorId)
        assertEquals(1, coordinator.commit(session).summary.added)
        val repaired = coordinator.repair(session, 3, listOf("2026-10-09", "-2", "food", "", ""))
        assertEquals(1, repaired.summary.added)
        assertEquals(1, repaired.summary.repairedAndAdded)
        assertEquals(0, repaired.summary.invalidRows)
    }

    @Test fun discardKeepsInvalidCountsAndRechecksSessionBeforeMutatingState() = runBlocking {
        val backend = FakeBackend()
        val coordinator = CsvImportCoordinator(backend) { today }
        val session = coordinator.prepare("house", csv("bad;-1;food;;"), mapping)
        val discarded = coordinator.discard(session, 2)
        assertEquals(1, discarded.summary.discarded)
        assertEquals(1, discarded.summary.invalidRows)
        assertEquals(0, discarded.summary.validRows)
        val next = coordinator.prepare("house", csv("bad;-1;food;;"), mapping)
        backend.identity = backend.identity.copy(sessionKey = "changed")
        try { coordinator.discard(next, 2); fail("Changed session must reject discard") }
        catch (error: IllegalStateException) { assertEquals("IMPORT_SESSION_CHANGED", error.message) }
        assertEquals(CsvImportRowStatus.INVALID, next.report.rows.single().status)
    }

    @Test fun UnicodeTagNormalizationFailureDoesNotBlockOtherRows() = runBlocking {
        val backend = FakeBackend()
        val coordinator = CsvImportCoordinator(backend) { today }
        // ROOT lowercasing U+0130 expands to two UTF-16 code units per source character.
        val parsed = csv("2026-10-09;-1;food;;\"[\"\"${"İ".repeat(40)}\"\"]\"", "2026-10-09;-2;food;;")
        val session = coordinator.prepare("house", parsed, mapping)
        val report = coordinator.commit(session)
        assertEquals(1, report.summary.invalidRows)
        assertEquals(1, report.summary.added)
        assertTrue(report.rows.first().source.issues.any { it.code == "IMPORT_INVALID_TAGS" && it.field == "tags" })
        assertEquals(-200L, backend.writes.single().amountGrosze)
    }

    @Test fun subcategoryNamesAreParentScopedAndTombstonesRemainDuplicates() = runBlocking {
        val backend = FakeBackend()
        backend.categories += Category("other", "house", "Other", authorId = "owner", updatedById = "owner")
        backend.subcategories += Subcategory("same-id", "house", "food", "Shared", authorId = "owner", updatedById = "owner")
        backend.subcategories += Subcategory("same-id", "house", "other", "Shared", authorId = "owner", updatedById = "owner")
        backend.entries += LedgerEntry("deleted", "house", -100, today.minusDays(1), categoryId = "food",
            subcategoryId = "same-id", authorId = "owner", updatedById = "owner", deleted = true, deletedById = "owner")
        val parsed = CsvParser.parse("date;amount;category;subcategory\n2026-10-09;-1;Food;Shared\n2026-10-09;-1;Other;Shared".toByteArray())
        val coordinator = CsvImportCoordinator(backend) { today }
        val session = coordinator.prepare("house", parsed, CsvColumnMapping(date = 0, amount = 1, category = 2, subcategory = 3))
        val result = coordinator.commit(session)
        assertEquals(1, result.summary.duplicates)
        assertEquals(1, result.summary.added)
        assertEquals("other", backend.writes.single().categoryId)
        assertEquals("same-id", backend.writes.single().subcategoryId)
        assertTrue(backend.entries.first().deleted)
    }

    private class FakeBackend : CsvImportBackend {
        var identity = CsvImportIdentity("owner", "session")
        var household = Household("house", "House", "owner")
        val entries = mutableListOf<LedgerEntry>()
        val categories = mutableListOf(Category("food", "house", "Food", authorId = "owner", updatedById = "owner"))
        val subcategories = mutableListOf<Subcategory>()
        val writes = mutableListOf<LedgerEntry>()
        var failAmount: Long? = null
        var cancel = false
        var synchronized = true
        override fun currentIdentity() = identity
        override suspend fun loadSnapshot(householdId: String, expectedIdentity: CsvImportIdentity) =
            CsvImportSnapshot(identity, household, entries.toList(), categories.toList(), subcategories.toList(), synchronized)
        override suspend fun commitEntry(snapshot: CsvImportSnapshot, entry: LedgerEntry): CsvImportCommitResult {
            if (cancel) throw CancellationException("SECRET raw source")
            if (entry.amountGrosze == failAmount) error("SECRET raw source")
            writes += entry
            entries += entry
            household = household.copy(ledgerRevision = household.ledgerRevision + 1)
            return CsvImportCommitResult(household.ledgerRevision, true)
        }
        override suspend fun createCategory(snapshot: CsvImportSnapshot, category: Category) { categories += category }
    }
}
