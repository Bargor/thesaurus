package pl.bargor.thesaurus.ui.importcsv

import java.time.LocalDate
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test
import pl.bargor.thesaurus.data.model.*
import pl.bargor.thesaurus.data.transfer.*

/** Every write stays in this synthetic backend; these tests never connect to Firebase. */
class CsvImportControllerTest {
    private fun parsed(vararg rows: String, header: String = "date;amount;category;subcategory;title") =
        CsvParser.parse((header + "\n" + rows.joinToString("\n")).toByteArray())
    private fun controller(backend: Backend = Backend()) =
        CsvImportController(CsvImportCoordinator(backend) { LocalDate.of(2026, 10, 10) }, InMemoryCsvImportCheckpointStore())

    @Test fun completeAutomaticMappingWaitsForExplicitStartThenCommitsOnlyValidRows() = runBlocking {
        val backend = Backend()
        val subject = controller(backend)
        subject.loadParsed("house", parsed("2026-10-09;-1;Food;;Lunch", "bad;-2;Food;;Repair me"))
        assertEquals(CsvImportPhase.READY, subject.state.value.phase)
        assertTrue(backend.writes.isEmpty())
        subject.start()
        assertEquals(CsvImportPhase.ATTENTION, subject.state.value.phase)
        assertEquals(1, subject.state.value.report!!.summary.added)
        assertEquals(1, subject.state.value.report!!.summary.invalidRows)
        assertEquals(listOf(-100L), backend.writes.map { it.amountGrosze })
    }

    @Test fun unrecognizedHeadersRequireCompleteManualMappingBeforeWrites() = runBlocking {
        val backend = Backend()
        val subject = controller(backend)
        subject.loadParsed("house", parsed("2026-10-09;-1;Food", header = "A;B;C"))
        assertEquals(CsvImportPhase.MAPPING, subject.state.value.phase)
        subject.setMapping(CsvColumnMapping(date = 0, amount = 1))
        assertEquals(CsvImportPhase.MAPPING, subject.state.value.phase)
        assertTrue(backend.writes.isEmpty())
        subject.setMapping(CsvColumnMapping(date = 0, amount = 1, category = 2))
        assertEquals(CsvImportPhase.READY, subject.state.value.phase)
        subject.start()
        assertEquals(1, backend.writes.size)
    }

    @Test fun draftValidationDoesNotWriteAndDoubleSaveDoesNotDuplicate() = runBlocking {
        val backend = Backend()
        val subject = controller(backend)
        subject.loadParsed("house", parsed("bad;-1;Food;;Original"))
        subject.start()
        subject.editRow(2, listOf("2026-10-09", "-1", "Food", "", "Changed"))
        subject.revalidate(2)
        assertTrue(backend.writes.isEmpty())
        subject.saveRepair(2)
        subject.saveRepair(2)
        assertEquals(1, backend.writes.size)
        assertEquals(1, subject.state.value.report!!.summary.repairedAndAdded)
        assertEquals("bad", subject.state.value.report!!.originalRows.single().cells.first())
    }

    @Test fun ambiguousHeadersDoNotAutoStartAndChosenDateFormatControlsInterpretation() = runBlocking {
        val backend = Backend()
        val subject = controller(backend)
        subject.loadParsed("house", parsed("01/02/2026;-1;Food;Unused", header = "date;amount;category;amount"))
        assertEquals(CsvImportPhase.MAPPING, subject.state.value.phase)
        subject.start()
        assertTrue(backend.writes.isEmpty())
        subject.setMapping(CsvColumnMapping(date = 0, amount = 1, category = 2), CsvDateFormat.DAY_MONTH_YEAR)
        subject.start()
        assertEquals(LocalDate.of(2026, 2, 1), backend.writes.single().date)
    }

    @Test fun optionalAmbiguousHeadersRemainMappingUntilExplicitUnmappedColumnConfirmation() = runBlocking {
        val backend = Backend()
        val subject = controller(backend)
        subject.loadParsed("house", parsed("2026-10-09;-1;Food;One;Two", header = "date;amount;category;title;title"))
        assertTrue(subject.state.value.mapping.isComplete)
        assertEquals(CsvImportPhase.MAPPING, subject.state.value.phase)
        subject.start()
        assertTrue(backend.writes.isEmpty())
        subject.setMapping(subject.state.value.mapping)
        assertEquals(CsvImportPhase.READY, subject.state.value.phase)
        subject.start()
        assertEquals(1, backend.writes.size)
        assertNull(backend.writes.single().title)
    }

    @Test fun discardOneThenFinishPreservesDisjointSummaryAndNeverWritesInvalidRows() = runBlocking {
        val backend = Backend()
        val subject = controller(backend)
        subject.loadParsed("house", parsed("bad;-1;Food;;First", "bad;-2;Food;;Second", "2026-10-09;-3;Food;;Valid"))
        subject.start()
        subject.discard(2)
        subject.finishDiscardingRemaining()
        val summary = subject.state.value.report!!.summary
        assertEquals(CsvImportPhase.COMPLETE, subject.state.value.phase)
        assertEquals(1, summary.added)
        assertEquals(2, summary.discarded)
        assertEquals(0, summary.pending)
        assertEquals(1, backend.writes.size)
    }

    @Test fun bulkCategoryReplacementUsesExactOriginalValueEvenAfterDraftChanges() = runBlocking {
        val subject = controller()
        subject.loadParsed("house", parsed("2026-10-09;-1;Unknown;;One", "2026-10-09;-2;Unknown;;Two", "2026-10-09;-3; unknown ;;Three"))
        subject.start()
        subject.editRow(3, listOf("2026-10-09", "-2", "Edited", "", "Two"))
        subject.selectCategory(2, "food", true)
        val rows = subject.state.value.report!!.rows.associateBy { it.source.rowNumber }
        assertEquals("food", rows.getValue(2).entry!!.categoryId)
        assertEquals("food", rows.getValue(3).entry!!.categoryId)
        assertNull(rows.getValue(4).entry)
        assertEquals(" unknown ", rows.getValue(4).source.cells[2])
    }

    @Test fun bulkSubcategoryReplacementStaysWithinOriginalCategoryPair() = runBlocking {
        val backend = Backend().apply {
            categories += Category("home", "house", "Home", authorId = "owner", updatedById = "owner")
            subcategories += Subcategory("shop", "house", "food", "Shop", authorId = "owner", updatedById = "owner")
        }
        val subject = controller(backend)
        subject.loadParsed("house", parsed("2026-10-09;-1;Food;Unknown;One", "2026-10-09;-2;Food;Unknown;Two", "2026-10-09;-3;Home;Unknown;Three"))
        subject.start()
        subject.selectSubcategory(2, "shop", true)
        val rows = subject.state.value.report!!.rows.associateBy { it.source.rowNumber }
        assertEquals("shop", rows.getValue(2).entry!!.subcategoryId)
        assertEquals("shop", rows.getValue(3).entry!!.subcategoryId)
        assertNull(rows.getValue(4).entry)
        assertEquals("Unknown", rows.getValue(4).source.cells[3])
    }

    @Test fun explicitTaxonomyCreationUsesCurrentOwnerAndCanRepairWithoutImplicitEntryWrites() = runBlocking {
        val backend = Backend()
        val subject = controller(backend)
        subject.loadParsed("house", parsed("2026-10-09;-1;New;New child;Taxonomy"))
        subject.start()
        subject.createCategory(2, "New")
        val category = backend.categories.single { it.name == "New" }
        assertEquals("owner", category.authorId)
        subject.createSubcategory(2, category.id, "New child")
        assertEquals("owner", backend.subcategories.single().authorId)
        assertTrue(backend.writes.isEmpty())
        subject.revalidate(2)
        subject.saveRepair(2)
        assertEquals(category.id, backend.writes.single().categoryId)
        assertEquals(backend.subcategories.single().id, backend.writes.single().subcategoryId)
    }

    @Test fun changedSessionBlocksRepairAndSanitizesServerFailure() = runBlocking {
        val backend = Backend()
        val subject = controller(backend)
        subject.loadParsed("house", parsed("bad;-1;Food;;Private title"))
        subject.start()
        subject.editRow(2, listOf("2026-10-09", "-1", "Food", "", "Private title"))
        backend.identity = CsvImportIdentity("owner", "changed")
        subject.saveRepair(2)
        assertTrue(backend.writes.isEmpty())
        assertFalse(subject.state.value.toString().contains("SECRET"))
    }

    @Test fun ownerGateAndMalformedFileNeverExposeRawCausesOrWrite() = runBlocking {
        val backend = Backend().apply { household = household.copy(ownerId = "other") }
        val subject = controller(backend)
        subject.loadParsed("house", parsed("2026-10-09;-1;Food;;Private"))
        assertNull(subject.state.value.report)
        assertNotNull(subject.state.value.errorCode)
        assertTrue(backend.writes.isEmpty())
        subject.load("house", byteArrayOf(0xc3.toByte(), 0x28))
        assertEquals("MALFORMED_UTF8", subject.state.value.errorCode)
        assertTrue(backend.writes.isEmpty())
    }

    @Test fun editingRawCategoryClearsPreviouslySelectedCategoryOverride() = runBlocking {
        val backend = Backend().apply { categories += Category("home", "house", "Home", authorId = "owner", updatedById = "owner") }
        val subject = controller(backend)
        subject.loadParsed("house", parsed("bad;-1;Unknown;;Original"))
        subject.start()
        subject.selectCategory(2, "food")
        subject.editRow(2, listOf("2026-10-09", "-1", "Home", "", "Original"))
        subject.revalidate(2)
        assertEquals("home", subject.state.value.report!!.rows.single().entry!!.categoryId)
        subject.saveRepair(2)
        assertEquals("home", backend.writes.single().categoryId)
    }

    @Test fun editingRawSubcategoryClearsPreviouslySelectedSubcategoryOverride() = runBlocking {
        val backend = Backend().apply {
            subcategories += Subcategory("shop", "house", "food", "Shop", authorId = "owner", updatedById = "owner")
            subcategories += Subcategory("market", "house", "food", "Market", authorId = "owner", updatedById = "owner")
        }
        val subject = controller(backend)
        subject.loadParsed("house", parsed("2026-10-09;-1;Food;Unknown;Original"))
        subject.start()
        subject.selectSubcategory(2, "shop")
        subject.editRow(2, listOf("2026-10-09", "-1", "Food", "Market", "Original"))
        subject.revalidate(2)
        assertEquals("market", subject.state.value.report!!.rows.single().entry!!.subcategoryId)
        subject.saveRepair(2)
        assertEquals("market", backend.writes.single().subcategoryId)
    }

    @Test fun changingParentClearsSelectedChildAndResolvesUneditedRawNameWithinNewParent() = runBlocking {
        val backend = Backend().apply {
            categories += Category("home", "house", "Home", authorId = "owner", updatedById = "owner")
            subcategories += Subcategory("food-shop", "house", "food", "Shop", authorId = "owner", updatedById = "owner")
            subcategories += Subcategory("home-shop", "house", "home", "Shop", authorId = "owner", updatedById = "owner")
        }
        val subject = controller(backend)
        subject.loadParsed("house", parsed("2026-10-09;-1;Unknown;Shop;Original"))
        subject.start()
        subject.selectCategory(2, "food")
        subject.selectSubcategory(2, "food-shop")
        subject.editRow(2, listOf("2026-10-09", "-1", "Home", "Shop", "Original"))
        subject.revalidate(2)
        val row = subject.state.value.report!!.rows.single()
        assertEquals("Shop", row.source.cells[3])
        assertEquals("home", row.entry!!.categoryId)
        assertEquals("home-shop", row.entry.subcategoryId)
        subject.saveRepair(2)
        assertEquals("home-shop", backend.writes.single().subcategoryId)
    }

    @Test fun failedNewCheckpointCreationPreservesCompletedSessionAndCannotStartNewWrites() = runBlocking {
        val backend = Backend()
        val delegate = InMemoryCsvImportCheckpointStore()
        var failCreate = false
        val store = object : CsvImportCheckpointStore by delegate {
            override suspend fun create(checkpoint: CsvImportCheckpoint): String {
                if (failCreate) error("SECRET file checkpoint path")
                return delegate.create(checkpoint)
            }
        }
        val subject = CsvImportController(CsvImportCoordinator(backend) { LocalDate.of(2026, 10, 10) }, store)
        subject.loadParsed("house", parsed("2026-10-09;-1;Food;;Completed A"))
        subject.start()
        val report = subject.state.value.report
        val token = subject.state.value.checkpointToken
        failCreate = true
        subject.loadParsed("house", parsed("2026-10-09;-2;Food;;New B"))
        assertEquals(CsvImportPhase.COMPLETE, subject.state.value.phase)
        assertSame(report, subject.state.value.report)
        assertEquals(token, subject.state.value.checkpointToken)
        assertNotNull(subject.state.value.errorCode)
        assertFalse(subject.state.value.errorCode.orEmpty().contains("SECRET"))
        subject.start()
        subject.resume()
        assertEquals(listOf(-100L), backend.writes.map { it.amountGrosze })
    }

    @Test fun resumeValidatesLatestUnvalidatedDraftInsteadOfCommittingRestoredOriginal() = runBlocking {
        for (editedAmount in listOf("-2", "invalid")) {
            val backend = Backend()
            val store = InMemoryCsvImportCheckpointStore()
            val source = parsed("2026-10-09;-1;Food;;Restored")
            val token = store.create(CsvImportCheckpoint("house", "owner", source,
                CsvHeaderDetector.detect(source.headers).mapping, CsvDateFormat.AUTO,
                rows = source.rows.map { CsvImportRowResult(it, CsvImportRowStatus.PENDING) },
                started = true, mappingConfirmed = true))
            val subject = CsvImportController(CsvImportCoordinator(backend) { LocalDate.of(2026, 10, 10) }, store)
            subject.restore(token, "house", "owner")
            assertEquals(CsvImportPhase.RESTORED, subject.state.value.phase)
            subject.editRow(2, listOf("2026-10-09", editedAmount, "Food", "", "Restored"))
            subject.resume()
            assertEquals(editedAmount, subject.state.value.report!!.rows.single().source.cells[1])
            if (editedAmount == "-2") {
                assertEquals(-200L, backend.writes.single().amountGrosze)
                assertEquals(CsvImportPhase.COMPLETE, subject.state.value.phase)
            } else {
                assertTrue(backend.writes.isEmpty())
                assertEquals(CsvImportRowStatus.INVALID, subject.state.value.report!!.rows.single().status)
                assertEquals(1, subject.state.value.attention)
            }
        }
    }

    @Test fun draftAcknowledgementWaitsForDurableSaveAndSurvivesFreshControllerRestore() = runBlocking {
        val backend = Backend()
        val delegate = InMemoryCsvImportCheckpointStore()
        val enteredSave = CompletableDeferred<Unit>()
        val releaseSave = CompletableDeferred<Unit>()
        val store = object : CsvImportCheckpointStore by delegate {
            override suspend fun saveDraft(token: String, rowNumber: Int, cells: List<String>) {
                enteredSave.complete(Unit)
                releaseSave.await()
                delegate.saveDraft(token, rowNumber, cells)
            }
        }
        val subject = CsvImportController(CsvImportCoordinator(backend) { LocalDate.of(2026, 10, 10) }, store)
        subject.loadParsed("house", parsed("bad;-1;Food;;Original"))
        subject.start()
        val token = checkNotNull(subject.state.value.checkpointToken)
        val cells = listOf("2026-10-09", "-1", "Food", "", "Durable draft")
        val edit = launch { subject.editRow(2, cells) }
        try {
            enteredSave.await()
            assertNull("A suspended store has not acknowledged the draft", subject.state.value.drafts[2])
            assertNull(delegate.read(token)!!.drafts[2])
        } finally { releaseSave.complete(Unit) }
        edit.join()
        assertEquals(cells, subject.state.value.drafts[2])
        val restored = CsvImportController(CsvImportCoordinator(backend) { LocalDate.of(2026, 10, 10) }, delegate)
        restored.restore(token, "house", "owner")
        assertEquals(cells, restored.state.value.drafts[2])
        assertTrue(backend.writes.isEmpty())
    }

    @Test fun cancelledDraftSaveDoesNotAcknowledgeAnUnpersistedEdit() = runBlocking {
        val delegate = InMemoryCsvImportCheckpointStore()
        val enteredSave = CompletableDeferred<Unit>()
        val blockedSave = CompletableDeferred<Unit>()
        val store = object : CsvImportCheckpointStore by delegate {
            override suspend fun saveDraft(token: String, rowNumber: Int, cells: List<String>) {
                enteredSave.complete(Unit)
                blockedSave.await()
                delegate.saveDraft(token, rowNumber, cells)
            }
        }
        val subject = CsvImportController(CsvImportCoordinator(Backend()) { LocalDate.of(2026, 10, 10) }, store)
        subject.loadParsed("house", parsed("bad;-1;Food;;Original"))
        subject.start()
        val token = checkNotNull(subject.state.value.checkpointToken)
        val edit = launch { subject.editRow(2, listOf("2026-10-09", "-2", "Food", "", "Cancelled")) }
        enteredSave.await()
        edit.cancel()
        edit.join()
        assertNull(subject.state.value.drafts[2])
        val restored = CsvImportController(CsvImportCoordinator(Backend()) { LocalDate.of(2026, 10, 10) }, delegate)
        restored.restore(token, "house", "owner")
        assertNull(restored.state.value.drafts[2])
    }

    @Test fun failedLatestDraftBlocksOlderCellsUntilSuccessfulRetry() = runBlocking {
        val backend = Backend()
        val delegate = InMemoryCsvImportCheckpointStore()
        var failDraft = false
        val store = object : CsvImportCheckpointStore by delegate {
            override suspend fun saveDraft(token: String, rowNumber: Int, cells: List<String>) {
                if (failDraft) error("SECRET checkpoint path")
                delegate.saveDraft(token, rowNumber, cells)
            }
        }
        val subject = CsvImportController(CsvImportCoordinator(backend) { LocalDate.of(2026, 10, 10) }, store)
        subject.loadParsed("house", parsed("bad;-1;Food;;Original"))
        subject.start()
        val acknowledged = listOf("2026-10-09", "-1", "Food", "", "Older")
        subject.editRow(2, acknowledged)
        val latest = listOf("2026-10-09", "-2", "Food", "", "Latest")
        failDraft = true
        subject.editRow(2, latest)
        assertEquals(acknowledged, subject.state.value.drafts[2])
        assertEquals("IMPORT_OPERATION_FAILED", subject.state.value.errorCode)
        subject.revalidate(2)
        assertEquals("bad", subject.state.value.report!!.rows.single().source.cells[0])
        subject.selectCategory(2, "food")
        assertEquals("bad", subject.state.value.report!!.rows.single().source.cells[0])
        subject.saveRepair(2)
        subject.resume()
        assertTrue("An unsaved edit must never cause an older draft to be imported", backend.writes.isEmpty())
        assertEquals("IMPORT_OPERATION_FAILED", subject.state.value.errorCode)
        failDraft = false
        subject.editRow(2, latest)
        assertNull(subject.state.value.errorCode)
        subject.resume()
        assertEquals(-200L, backend.writes.single().amountGrosze)
        assertEquals("Latest", backend.writes.single().title)
    }

    @Test fun largeImportJournalsOneRowPerCallbackWithoutRewritingWholeCheckpointOrReloadingLedger() = runBlocking {
        val backend = Backend()
        val store = CountingStore()
        val subject = CsvImportController(CsvImportCoordinator(backend) { LocalDate.of(2026, 10, 10) }, store)
        subject.loadParsed("house", parsed(*(1..500).map { "2026-10-09;-$it;Food;;Row $it" }.toTypedArray()))
        val initialReport = subject.state.value.report
        backend.onCommit = { assertSame("Progress publishes counts rather than copying the entire row report", initialReport, subject.state.value.report) }
        subject.start()
        assertEquals(500, backend.writes.size)
        assertEquals(500, store.appends)
        assertTrue("Full checkpoints must stay constant rather than one rewrite per imported row", store.saves <= 4)
        assertTrue("Progress must not reload full ledger for each row", backend.snapshotReads <= 3)
        assertEquals(500, subject.state.value.processed)
        assertEquals(0, subject.state.value.attention)
    }

    private class CountingStore : CsvImportCheckpointStore {
        private val delegate = InMemoryCsvImportCheckpointStore()
        var saves = 0
        var appends = 0
        override suspend fun create(checkpoint: CsvImportCheckpoint) = delegate.create(checkpoint)
        override suspend fun save(token: String, checkpoint: CsvImportCheckpoint) { saves++; delegate.save(token, checkpoint) }
        override suspend fun append(token: String, row: CsvImportRowResult) { appends++; delegate.append(token, row) }
        override suspend fun read(token: String) = delegate.read(token)
    }

    private class Backend : CsvImportBackend {
        var identity = CsvImportIdentity("owner", "session")
        var household = Household("house", "Test house", "owner")
        var snapshotReads = 0
        var onCommit: () -> Unit = {}
        val categories = mutableListOf(Category("food", "house", "Food", authorId = "owner", updatedById = "owner"))
        val subcategories = mutableListOf<Subcategory>()
        val writes = mutableListOf<LedgerEntry>()
        override fun currentIdentity() = identity
        override suspend fun loadSnapshot(householdId: String, expectedIdentity: CsvImportIdentity): CsvImportSnapshot {
            snapshotReads++
            return CsvImportSnapshot(identity, household, writes.toList(), categories.toList(), subcategories.toList())
        }
        override suspend fun commitEntry(snapshot: CsvImportSnapshot, entry: LedgerEntry): CsvImportCommitResult {
            onCommit()
            writes += entry
            household = household.copy(ledgerRevision = household.ledgerRevision + 1)
            return CsvImportCommitResult(household.ledgerRevision, true)
        }
        override suspend fun createCategory(snapshot: CsvImportSnapshot, category: Category) { categories += category }
        override suspend fun createSubcategory(snapshot: CsvImportSnapshot, subcategory: Subcategory) { subcategories += subcategory }
    }
}
