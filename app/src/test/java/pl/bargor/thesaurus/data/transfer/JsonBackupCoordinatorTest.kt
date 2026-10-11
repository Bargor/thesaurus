package pl.bargor.thesaurus.data.transfer

import java.time.Instant
import java.time.LocalDate
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test
import pl.bargor.thesaurus.data.model.*

class JsonBackupCoordinatorTest {
    private val today = LocalDate.of(2026, 10, 10)
    private val now = Instant.parse("2026-10-10T10:00:00Z")
    private val category = BackupCategory("food", "Food", "foreign", "foreign", color = "mint")
    private val row = BackupEntry("source", -100, today.minusDays(1), "food", "foreign", "foreign")
    private fun document(entries: List<BackupEntry> = listOf(row), balance: Long = 0) =
        BackupDocument(ExportMetadata(now, "source-house"), balance, listOf(category), entries = entries)
    private fun bytes(document: BackupDocument = document()) = BackupJsonCodec.encode(document)

    @Test fun invalidWholeFilesFailBeforeAnyWrite() = runBlocking {
        val valid = bytes().toString(Charsets.UTF_8)
        val invalid = listOf(valid.replace("\"formatVersion\":1", "\"formatVersion\":2").toByteArray(),
            valid.replace("\"categoryId\":\"food\"", "\"categoryId\":\"missing\"").toByteArray(),
            byteArrayOf(0xc3.toByte(), 0x28), ByteArray(BackupLimits.MAX_BYTES + 1),
            bytes(document(listOf(row.copy(date = today.plusDays(1))))))
        for (input in invalid) {
            val backend = FakeBackend()
            assertTrue(runCatching { JsonBackupCoordinator(backend) { today }.prepare("house", input) }.isFailure)
            assertTrue(backend.writes.isEmpty())
        }
    }

    @Test fun previewHasNoWritesAndCommitRemapsSourceIdsWithoutTouchingExistingDocuments() = runBlocking {
        val backend = FakeBackend()
        backend.categories += Category("food", "house", "Different", "amber", authorId = "owner", updatedById = "owner")
        backend.entries += LedgerEntry("source", "house", -999, today, categoryId = "food", authorId = "owner", updatedById = "owner")
        backend.household = backend.household.copy(ledgerRevision = 1)
        val originalCategory = backend.categories.single()
        val originalEntry = backend.entries.single()
        val coordinator = JsonBackupCoordinator(backend) { today }
        val session = coordinator.prepare("house", bytes())
        assertTrue(backend.writes.isEmpty())
        coordinator.commit(session)
        assertEquals(originalCategory, backend.categories.first())
        assertEquals(originalEntry, backend.entries.first())
        val added = backend.entries.last()
        assertNotEquals("source", added.id)
        assertNotEquals("food", added.categoryId)
        assertTrue(added.id.startsWith("json_"))
        assertEquals("owner", added.authorId)
        assertEquals("owner", added.updatedById)
    }

    @Test fun currentMemberAuthorSurvivesAndForeignAuditsUseCurrentOwner() = runBlocking {
        val backend = FakeBackend()
        val coordinator = JsonBackupCoordinator(backend) { today }
        coordinator.commit(coordinator.prepare("house", bytes(document(listOf(
            row.copy(id = "member", authorId = "member", amountGrosze = -100),
            row.copy(id = "foreign", amountGrosze = -200, deleted = true, deletedById = "foreign"))))))
        assertEquals("member", backend.entries.single { it.amountGrosze == -100L }.authorId)
        val tombstone = backend.entries.single { it.deleted }
        assertEquals("owner", tombstone.authorId)
        assertEquals("owner", tombstone.updatedById)
        assertEquals("owner", tombstone.deletedById)
        assertEquals("owner", backend.categories.single().authorId)
    }

    @Test fun archivedTaxonomyAndActiveAndDeletedEntriesSurviveExportImportExport() = runBlocking {
        val backend = FakeBackend()
        val source = document(listOf(row.copy(title = "Żółć", tags = listOf(" Raw ")),
            row.copy(id = "deleted", amountGrosze = -200, deleted = true, deletedById = "foreign")), -98765)
            .copy(categories = listOf(category.copy(archived = true)),
                subcategories = listOf(BackupSubcategory("shop", "food", "Shop", "foreign", "foreign", archived = true)),
                entries = listOf(row.copy(title = "Żółć", tags = listOf(" Raw "), subcategoryId = "shop"),
                    row.copy(id = "deleted", amountGrosze = -200, deleted = true, deletedById = "foreign")))
        val coordinator = JsonBackupCoordinator(backend) { today }
        coordinator.commit(coordinator.prepare("house", bytes(source)))
        val exported = BackupJsonCodec.decode(coordinator.export("house", now, "test").bytes)
        assertEquals(-98765L, exported.openingBalanceGrosze)
        assertTrue(exported.categories.single().archived)
        assertTrue(exported.subcategories.single().archived)
        assertEquals(2, exported.entries.size)
        assertEquals(1, exported.entries.count { it.deleted })
        assertEquals("Żółć", exported.entries.single { !it.deleted }.title)
        assertEquals(listOf("raw"), exported.entries.single { !it.deleted }.tags)
        assertEquals(exported.categories.single().id, exported.subcategories.single().categoryId)
        assertEquals(exported.subcategories.single().id, exported.entries.single { !it.deleted }.subcategoryId)
    }

    @Test fun openingBalancePrecedesEntriesAndRequiresPristineLedger() = runBlocking {
        val pristine = FakeBackend()
        val coordinator = JsonBackupCoordinator(pristine) { today }
        coordinator.commit(coordinator.prepare("house", bytes(document(balance = -12345))))
        assertTrue(pristine.writes.first() is JsonMergeOperation.OpeningBalance)
        assertEquals(-12345L, pristine.household.openingBalanceGrosze)
        for (backend in listOf(FakeBackend().apply { household = household.copy(openingBalanceGrosze = 5) },
            FakeBackend().apply { household = household.copy(ledgerRevision = 1) },
            FakeBackend().apply {
                categories += Category("old", "house", "Old", "mint", authorId = "owner", updatedById = "owner")
                entries += LedgerEntry("old", "house", -5, today, categoryId = "old", authorId = "owner", updatedById = "owner")
            })) {
            val importing = JsonBackupCoordinator(backend) { today }
            val report = importing.commit(importing.prepare("house", bytes(document(balance = -12345))))
            assertEquals(1, report.items.count { it.kind == JsonMergeItemKind.OPENING_BALANCE && it.status == JsonMergeStatus.CONFLICT })
            assertTrue(backend.writes.none { it is JsonMergeOperation.OpeningBalance })
            assertNotEquals(-12345L, backend.household.openingBalanceGrosze)
        }
    }

    @Test fun repeatedFileAndRepeatedCommitNeverAddAgain() = runBlocking {
        val backend = FakeBackend()
        val coordinator = JsonBackupCoordinator(backend) { today }
        val session = coordinator.prepare("house", bytes())
        coordinator.commit(session)
        val writes = backend.writes.size
        coordinator.commit(session)
        assertEquals(writes, backend.writes.size)
        val report = coordinator.commit(coordinator.prepare("house", bytes()))
        assertEquals(writes, backend.writes.size)
        assertEquals(0, report.addedCount)
        assertEquals(1, backend.entries.size)
    }

    @Test fun occupiedRemappedCategoryIdConflictsWithoutOverwritingOrAddingDescendants() = runBlocking {
        val backend = FakeBackend()
        val coordinator = JsonBackupCoordinator(backend) { today }
        val targetId = coordinator.prepare("house", bytes()).report.items.single { it.kind == JsonMergeItemKind.CATEGORY }.targetId!!
        val occupied = Category(targetId, "house", "Unrelated", "amber", authorId = "owner", updatedById = "owner")
        backend.categories += occupied
        val report = coordinator.commit(coordinator.prepare("house", bytes()))
        assertEquals(2, report.conflictCount)
        assertEquals(listOf(occupied), backend.categories)
        assertTrue(backend.entries.isEmpty())
        assertTrue(backend.writes.isEmpty())
    }

    @Test fun ambiguousTaxonomyConflictsAndParentScopedSubcategoryIdsRemapIndependently() = runBlocking {
        val ambiguous = FakeBackend()
        ambiguous.categories += Category("a", "house", "Food", "mint", authorId = "owner", updatedById = "owner")
        ambiguous.categories += ambiguous.categories.single().copy(id = "b")
        val coordinator = JsonBackupCoordinator(ambiguous) { today }
        val report = coordinator.commit(coordinator.prepare("house", bytes()))
        assertEquals(2, report.conflictCount)
        assertTrue(ambiguous.writes.isEmpty())

        val backend = FakeBackend()
        val source = document().copy(categories = listOf(category, category.copy(id = "other", name = "Other")),
            subcategories = listOf(BackupSubcategory("same", "food", "Shared", "foreign", "foreign"),
                BackupSubcategory("same", "other", "Shared", "foreign", "foreign")),
            entries = listOf(row.copy(subcategoryId = "same"), row.copy(id = "other-entry", categoryId = "other", subcategoryId = "same")))
        val importer = JsonBackupCoordinator(backend) { today }
        importer.commit(importer.prepare("house", bytes(source)))
        assertEquals(2, backend.entries.size)
        assertEquals(2, backend.entries.map { it.categoryId }.distinct().size)
        assertEquals(2, backend.entries.map { it.subcategoryId }.distinct().size)
    }

    @Test fun distinctSourceIdsPreserveIdenticalEntriesAndSameLineageDeletionIsConflict() = runBlocking {
        val backend = FakeBackend()
        val coordinator = JsonBackupCoordinator(backend) { today }
        val report = coordinator.commit(coordinator.prepare("house", bytes(document(listOf(row, row.copy(id = "same"))))))
        assertEquals(2, backend.entries.size)
        assertEquals(2, report.items.count { it.kind == JsonMergeItemKind.ENTRY && it.status == JsonMergeStatus.ADDED })
        val conflicting = coordinator.commit(coordinator.prepare("house", bytes(document(listOf(row.copy(deleted = true, deletedById = "foreign"))))))
        assertEquals(1, conflicting.items.count { it.kind == JsonMergeItemKind.ENTRY && it.status == JsonMergeStatus.CONFLICT })
        assertTrue(backend.entries.none { it.deleted })
    }

    @Test fun identicalFinancialRowsPreserveMultiplicityAuthorsAndTombstoneAcrossRoundtripAndRepeat() = runBlocking {
        val backend = FakeBackend()
        val source = document(listOf(row.copy(id = "owner-row", authorId = "owner"),
            row.copy(id = "member-row", authorId = "member"),
            row.copy(id = "deleted-row", authorId = "member", deleted = true, deletedById = "member")))
        val coordinator = JsonBackupCoordinator(backend) { today }
        val report = coordinator.commit(coordinator.prepare("house", bytes(source)))
        assertEquals(3, report.items.count { it.kind == JsonMergeItemKind.ENTRY && it.status == JsonMergeStatus.ADDED })
        assertEquals(3, backend.entries.size)
        assertEquals(3, backend.entries.map { it.id }.distinct().size)
        assertEquals(2, backend.entries.count { !it.deleted })
        assertEquals(-200L, backend.entries.filterNot { it.deleted }.sumOf { it.amountGrosze })
        assertEquals(setOf("owner", "member"), backend.entries.filterNot { it.deleted }.map { it.authorId }.toSet())
        val exported = BackupJsonCodec.decode(coordinator.export("house", now).bytes)
        assertEquals(3, exported.entries.size)
        assertEquals(1, exported.entries.count { it.deleted })
        val before = backend.entries.toList()
        val repeated = coordinator.commit(coordinator.prepare("house", bytes(source)))
        assertEquals(0, repeated.addedCount)
        assertEquals(3, repeated.items.count { it.kind == JsonMergeItemKind.ENTRY && it.status == JsonMergeStatus.SKIPPED })
        assertEquals(before, backend.entries)
    }

    @Test fun nativeSameHouseholdRestorePrefersExactSourceIdAndNeverResurrectsItsTombstone() = runBlocking {
        val backend = FakeBackend()
        backend.categories += Category("food", "house", "Food", "mint", authorId = "owner", updatedById = "owner")
        val tombstone = LedgerEntry("source", "house", -100, today.minusDays(1), categoryId = "food", authorId = "owner",
            updatedById = "owner", deleted = true, deletedById = "owner")
        backend.entries += tombstone
        backend.entries += tombstone.copy(id = "other-active", deleted = false, deletedById = null)
        val source = document(listOf(row.copy(authorId = "owner"))).copy(metadata = ExportMetadata(now, "house"))
        val coordinator = JsonBackupCoordinator(backend) { today }
        val report = coordinator.commit(coordinator.prepare("house", bytes(source)))
        val item = report.items.single { it.kind == JsonMergeItemKind.ENTRY }
        assertEquals("source", item.targetId)
        assertEquals(JsonMergeStatus.SKIPPED, item.status)
        assertEquals(tombstone, backend.entries.first())
        assertEquals(2, backend.entries.size)
        assertTrue(backend.writes.isEmpty())
    }

    @Test fun editedImportedLineageConflictsOnOldBackupReplayWithoutCreatingReplacement() = runBlocking {
        val backend = FakeBackend()
        val coordinator = JsonBackupCoordinator(backend) { today }
        coordinator.commit(coordinator.prepare("house", bytes()))
        val edited = backend.entries.single().copy(amountGrosze = -777, title = "User edit")
        backend.entries[0] = edited
        backend.household = backend.household.copy(ledgerRevision = backend.household.ledgerRevision + 1)
        val writes = backend.writes.size
        val report = coordinator.commit(coordinator.prepare("house", bytes()))
        assertEquals(1, report.items.count { it.kind == JsonMergeItemKind.ENTRY && it.status == JsonMergeStatus.CONFLICT })
        assertEquals(listOf(edited), backend.entries)
        assertEquals(writes, backend.writes.size)
    }

    @Test fun existingNativeAndImportedMatchesAreConsumedOnceWithKnownLineageReservedFirst() = runBlocking {
        val backend = FakeBackend()
        backend.categories += Category("food", "house", "Food", "mint", authorId = "owner", updatedById = "owner")
        val coordinator = JsonBackupCoordinator(backend) { today }
        coordinator.commit(coordinator.prepare("house", bytes(document(listOf(row.copy(id = "source2"))))))
        val imported = backend.entries.single()
        backend.entries += imported.copy(id = "native-existing")
        backend.household = backend.household.copy(ledgerRevision = backend.household.ledgerRevision + 1)
        val source = document(listOf(row.copy(id = "source1"), row.copy(id = "source2"), row.copy(id = "source3")))
        val session = coordinator.prepare("house", bytes(source))
        val entries = session.report.items.filter { it.kind == JsonMergeItemKind.ENTRY }
        assertEquals(1, entries.count { it.status == JsonMergeStatus.NEW })
        assertEquals(2, entries.count { it.status == JsonMergeStatus.SKIPPED })
        assertEquals(imported.id, entries.single { it.sourceId == "source2" }.targetId)
        coordinator.commit(session)
        assertEquals(3, backend.entries.size)
        assertEquals(3, backend.entries.map { it.id }.distinct().size)
        assertEquals(-300L, backend.entries.sumOf { it.amountGrosze })
    }

    @Test fun activeReplayCannotResurrectExistingTombstone() = runBlocking {
        val backend = FakeBackend()
        val coordinator = JsonBackupCoordinator(backend) { today }
        coordinator.commit(coordinator.prepare("house", bytes(document(listOf(row.copy(deleted = true, deletedById = "foreign"))))))
        val before = backend.entries.single()
        val report = coordinator.commit(coordinator.prepare("house", bytes()))
        assertEquals(before, backend.entries.single())
        assertEquals(1, report.items.count { it.kind == JsonMergeItemKind.ENTRY && it.status == JsonMergeStatus.SKIPPED })
    }

    @Test fun partialFailureHasSafeCountsAndFreshPreviewRetryOnlyWritesRemainingItems() = runBlocking {
        val backend = FakeBackend().apply { failAmount = -200 }
        val coordinator = JsonBackupCoordinator(backend) { today }
        val session = coordinator.prepare("house", bytes(document((1..3).map { row.copy(id = "e$it", amountGrosze = -100L * it, title = "private $it") })))
        val partial = coordinator.commit(session)
        assertEquals(1, backend.entries.size)
        assertEquals(1, partial.errorCount)
        assertEquals(1, partial.newCount)
        assertFalse(partial.toString().contains("private"))
        assertFalse(partial.toString().contains("SECRET"))
        backend.failAmount = null
        val complete = coordinator.commit(coordinator.prepare("house", bytes(document((1..3).map { row.copy(id = "e$it", amountGrosze = -100L * it, title = "private $it") }))))
        assertEquals(3, backend.entries.size)
        assertEquals(0, complete.errorCount)
        assertEquals(3, backend.writes.count { it is JsonMergeOperation.NewEntry })
    }

    @Test fun principalOwnerMembershipAndOfflineGatesPreventWrites() = runBlocking {
        val backends = listOf(FakeBackend().apply { identity = null },
            FakeBackend().apply { identity = CsvImportIdentity(" ", "session") },
            FakeBackend().apply { identity = CsvImportIdentity("owner", " ") },
            FakeBackend().apply { household = household.copy(ownerId = "member") },
            FakeBackend().apply { memberIds = setOf("member") },
            FakeBackend().apply { synchronized = false })
        for (backend in backends) {
            assertTrue(runCatching { JsonBackupCoordinator(backend) { today }.prepare("house", bytes()) }.isFailure)
            assertTrue(backend.writes.isEmpty())
        }
    }

    @Test fun changedSessionCannotCommitPreparedPreview() = runBlocking {
        val backend = FakeBackend()
        val coordinator = JsonBackupCoordinator(backend) { today }
        val session = coordinator.prepare("house", bytes())
        backend.identity = CsvImportIdentity("owner", "new-session")
        assertTrue(runCatching { coordinator.commit(session) }.isFailure)
        assertTrue(backend.writes.isEmpty())
    }

    @Test fun balanceRevisionTaxonomyLedgerOrMembershipChangesInvalidatePreviewWithoutWrites() = runBlocking {
        val changes: List<(FakeBackend) -> Unit> = listOf(
            { it.household = it.household.copy(openingBalanceGrosze = 1) },
            { it.household = it.household.copy(ledgerRevision = 1) },
            { it.memberIds = it.memberIds + "new-member" },
            { it.memberIds = setOf("owner") },
            { it.categories += Category("new", "house", "New", "mint", authorId = "owner", updatedById = "owner") },
            { it.entries += LedgerEntry("new", "house", -5, today, categoryId = "food", authorId = "owner", updatedById = "owner") },
            { it.household = it.household.copy(ownerId = "member") },
            { it.synchronized = false },
        )
        for (change in changes) {
            val backend = FakeBackend()
            val coordinator = JsonBackupCoordinator(backend) { today }
            val session = coordinator.prepare("house", bytes())
            change(backend)
            assertTrue(runCatching { coordinator.commit(session) }.isFailure)
            assertTrue(backend.writes.isEmpty())
        }
    }

    @Test fun exportPreservesAllServerFieldsAndAllowsCurrentMemberWithoutOwnerAccess() = runBlocking {
        val backend = FakeBackend()
        backend.identity = CsvImportIdentity("member", "session")
        backend.categories += Category("food", "house", "Food", "mint", true, EntryType.INCOME,
            "owner", "member", now, now)
        backend.subcategories += Subcategory("child", "house", "food", "Child", true, "owner", "member", now, now)
        backend.entries += LedgerEntry("entry", "house", Long.MIN_VALUE, today.minusDays(1), " raw ", "food", "child",
            listOf(" Raw "), "member", "owner", now, now, true, now, "owner")
        backend.household = backend.household.copy(openingBalanceGrosze = Long.MAX_VALUE)
        val artifact = JsonBackupCoordinator(backend) { today }.export("house", now, "1.2.3")
        val exported = BackupJsonCodec.decode(artifact.bytes)
        assertEquals(ExportMetadata(now, "house", "1.2.3"), exported.metadata)
        assertEquals(Long.MAX_VALUE, exported.openingBalanceGrosze)
        assertEquals(BackupCategory("food", "Food", "owner", "member", "mint", true, TransferEntryType.INCOME, now, now), exported.categories.single())
        assertEquals(BackupSubcategory("child", "food", "Child", "owner", "member", true, now, now), exported.subcategories.single())
        assertEquals(BackupEntry("entry", Long.MIN_VALUE, today.minusDays(1), "food", "member", "owner", " raw ", "child",
            listOf(" Raw "), now, now, true, now, "owner"), exported.entries.single())
        assertTrue(artifact.suggestedFilename.endsWith(".json"))
        assertFalse(artifact.bytes.toString(Charsets.UTF_8).contains("memberIds"))
    }

    @Test fun cancellationPropagatesWithoutUnsafeDiagnostics() = runBlocking {
        val backend = FakeBackend().apply { cancel = true }
        val coordinator = JsonBackupCoordinator(backend) { today }
        val session = coordinator.prepare("house", bytes())
        try { coordinator.commit(session); fail("Cancellation must propagate") }
        catch (_: CancellationException) { }
        assertTrue(backend.writes.isEmpty())
    }

    @Test fun progressObserverFailureCannotTurnAcknowledgedWriteIntoServerError() = runBlocking {
        val backend = FakeBackend()
        val coordinator = JsonBackupCoordinator(backend) { today }
        backend.categories += Category("food", "house", "Food", "mint", authorId = "owner", updatedById = "owner")
        val source = document(listOf(row, row.copy(id = "second", amountGrosze = -200)))
        val session = coordinator.prepare("house", bytes(source))
        val observerFailure = IllegalStateException("observer failed")
        val error = runCatching { coordinator.commit(session) { throw observerFailure } }.exceptionOrNull()
        assertSame(observerFailure, error)
        assertEquals(1, backend.writes.size)
        assertEquals(1, backend.entries.size)
        assertEquals(1, session.report.addedCount)
        assertEquals(0, session.report.errorCount)
        assertEquals(1, session.report.newCount)
    }

    @Test fun largeImportUsesBoundedSnapshotReadsAndMonotonicProgress() = runBlocking {
        val backend = FakeBackend()
        val coordinator = JsonBackupCoordinator(backend) { today }
        val session = coordinator.prepare("house", bytes(document((1..2000).map { row.copy(id = "e$it", amountGrosze = -it.toLong()) })))
        val progress = mutableListOf<JsonMergeItem>()
        coordinator.commit(session) { progress += it }
        assertEquals(2000, backend.entries.size)
        assertTrue("Whole ledger must not be reread once per row", backend.reads < 10)
        assertEquals(2000, progress.count { it.kind == JsonMergeItemKind.ENTRY && it.status == JsonMergeStatus.ADDED })
        assertEquals(progress.size, progress.map { it.kind to it.targetId }.distinct().size)
        assertTrue(progress.isNotEmpty())
    }

    @Test fun mergedUniqueEntryLimitRejectsWholePlanBeforeProposedBalanceOrTaxonomyWrites() = runBlocking {
        val backend = FakeBackend()
        backend.categories += Category("food", "house", "Food", "mint", authorId = "owner", updatedById = "owner")
        backend.entries += List(BackupLimits.MAX_ENTRIES) { index ->
            LedgerEntry("existing-$index", "house", -999, today, categoryId = "food", authorId = "owner", updatedById = "owner")
        }
        val failure = runCatching { JsonBackupCoordinator(backend) { today }.prepare("house", bytes(document(balance = 12345))) }.exceptionOrNull()
        assertTrue(failure is BackupContractException)
        assertEquals(TransferErrorCode.LIMIT_EXCEEDED, (failure as BackupContractException).code)
        assertTrue(backend.writes.isEmpty())
        assertEquals(0L, backend.household.openingBalanceGrosze)
        assertEquals(BackupLimits.MAX_ENTRIES, backend.entries.size)
    }

    @Test fun duplicateServerIdsAndOrphanRelationsRejectSnapshotBeforeAnyWrite() = runBlocking {
        val backends = listOf(
            FakeBackend().apply {
                val category = Category("food", "house", "Food", "mint", authorId = "owner", updatedById = "owner")
                categories += listOf(category, category)
            },
            FakeBackend().apply {
                categories += Category("food", "house", "Food", "mint", authorId = "owner", updatedById = "owner")
                val entry = LedgerEntry("duplicate", "house", -1, today, categoryId = "food", authorId = "owner", updatedById = "owner")
                entries += listOf(entry, entry)
            },
            FakeBackend().apply { subcategories += Subcategory("orphan", "house", "missing", "Child", authorId = "owner", updatedById = "owner") },
            FakeBackend().apply { entries += LedgerEntry("orphan", "house", -1, today, categoryId = "missing", authorId = "owner", updatedById = "owner") },
            FakeBackend().apply {
                categories += Category("food", "house", "Food", "mint", authorId = "owner", updatedById = "owner")
                entries += LedgerEntry("orphan-child", "house", -1, today, categoryId = "food", subcategoryId = "missing", authorId = "owner", updatedById = "owner")
            },
        )
        for (backend in backends) {
            val failure = runCatching { JsonBackupCoordinator(backend) { today }.prepare("house", bytes()) }.exceptionOrNull()
            assertTrue(failure is JsonBackupException)
            assertEquals("JSON_SERVER_SNAPSHOT_REQUIRED", (failure as JsonBackupException).code)
            assertTrue(backend.writes.isEmpty())
        }
    }

    private class FakeBackend : JsonBackupBackend {
        var identity: CsvImportIdentity? = CsvImportIdentity("owner", "session")
        var household = Household("house", "House", "owner")
        var memberIds = setOf("owner", "member")
        var synchronized = true
        val categories = mutableListOf<Category>()
        val subcategories = mutableListOf<Subcategory>()
        val entries = mutableListOf<LedgerEntry>()
        val writes = mutableListOf<JsonMergeOperation>()
        var reads = 0
        var failAmount: Long? = null
        var cancel = false
        override fun currentIdentity() = identity
        override suspend fun loadSnapshot(householdId: String, expectedIdentity: CsvImportIdentity, requireOwner: Boolean): JsonBackupSnapshot {
            reads++
            return JsonBackupSnapshot(identity ?: expectedIdentity, household, entries.toList(), categories.toList(), subcategories.toList(), memberIds, synchronized)
        }
        override suspend fun commit(snapshot: JsonBackupSnapshot, operation: JsonMergeOperation): JsonMergeWriteResult {
            if (cancel) throw CancellationException("SECRET private file")
            if (operation is JsonMergeOperation.NewEntry && operation.entry.amountGrosze == failAmount) error("SECRET private file")
            writes += operation
            when (operation) {
                is JsonMergeOperation.OpeningBalance -> household = household.copy(openingBalanceGrosze = operation.amountGrosze)
                is JsonMergeOperation.NewCategory -> categories += operation.category
                is JsonMergeOperation.NewSubcategory -> subcategories += operation.subcategory
                is JsonMergeOperation.NewEntry -> { entries += operation.entry; household = household.copy(ledgerRevision = household.ledgerRevision + 1) }
            }
            return JsonMergeWriteResult(household.ledgerRevision, JsonMergeWriteOutcome.ADDED)
        }
    }
}
