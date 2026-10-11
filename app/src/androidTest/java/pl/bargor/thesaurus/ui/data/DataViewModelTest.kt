package pl.bargor.thesaurus.ui.data

import android.net.Uri
import android.os.Bundle
import android.os.Looper
import android.os.Parcel
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModelStore
import androidx.test.platform.app.InstrumentationRegistry
import java.io.File
import java.time.Clock
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneOffset
import java.util.UUID
import kotlinx.coroutines.delay
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assert.*
import org.junit.Test
import pl.bargor.thesaurus.data.model.*
import pl.bargor.thesaurus.data.firebase.HouseholdRepository
import pl.bargor.thesaurus.data.transfer.*
import pl.bargor.thesaurus.ui.importcsv.PrivateCsvImportCheckpointStore
import pl.bargor.thesaurus.ui.importcsv.CsvImportAction
import pl.bargor.thesaurus.ui.importcsv.CsvImportPhase

/** The only files touched are per-test synthetic files inside a unique cache directory. */
class DataViewModelTest {
    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val context = instrumentation.targetContext
    private val clock = Clock.fixed(Instant.parse("2026-10-11T12:00:00Z"), ZoneOffset.UTC)
    private fun main(action: () -> Unit) = instrumentation.runOnMainSync(action)
    private suspend fun await(condition: () -> Boolean) = withTimeout(15000) { while (!condition()) delay(10) }

    @Test fun bothExportsRequireConsentUseTypeAndDateAndConsumeDuplicateTapsAndResults() = runBlocking {
        fixture { vm, backend, _, directory ->
            for (format in DataFormat.entries) {
                val readsBefore = backend.reads
                main { vm.requestExport(format) }
                assertEquals(format, vm.state.value.warning)
                assertNull(vm.state.value.pickerRequest)
                assertEquals(readsBefore, backend.reads)
                main { vm.confirmExport(); vm.confirmExport() }
                await { vm.state.value.pickerRequest != null && !vm.state.value.busy }
                val picker = vm.state.value.pickerRequest!!
                val type = if (format == DataFormat.CSV) "wpisy" else "kopia"
                assertEquals("thesaurus-$type-2026-10-11.${format.name.lowercase()}", picker.suggestedFileName)
                assertEquals(readsBefore + 1, backend.reads)
                main { assertTrue(vm.acknowledgePickerLaunch(picker.id)); assertFalse(vm.acknowledgePickerLaunch(picker.id)) }
                val destination = File(directory, "export.${format.name.lowercase()}")
                main { vm.onDocumentResult(picker.id, Uri.fromFile(destination).toString()); vm.onDocumentResult(picker.id, Uri.fromFile(destination).toString()) }
                await { vm.state.value.completedExport == format && !vm.state.value.busy }
                assertTrue(destination.length() > 0)
                assertNull(vm.state.value.pickerRequest)
                assertEquals(0, backend.writes)
                assertEquals(readsBefore + 1, backend.reads)
            }
            assertTrue("Server snapshot and serialization must run away from UI thread", backend.readOnMain.none { it })
        }
    }

    @Test fun bothImportFormatsRecheckReadinessAndOfflineOrPendingNeverPublishPicker() = runBlocking {
        fixture { vm, backend, _, _ ->
            for (format in DataFormat.entries) {
                for (offline in listOf(false, true)) {
                    backend.synchronized = false
                    backend.offline = offline
                    main { vm.requestImport(format); vm.requestImport(format) }
                    await { !vm.state.value.busy }
                    assertNull(vm.state.value.pickerRequest)
                    assertNotNull(vm.state.value.errorCode)
                    assertEquals(0, backend.writes)
                }
                backend.synchronized = true
                backend.offline = false
                main { vm.requestImport(format) }
                await { vm.state.value.pickerRequest != null && !vm.state.value.busy }
                val request = vm.state.value.pickerRequest!!
                assertEquals(DataDirection.IMPORT, request.direction)
                main { vm.acknowledgePickerLaunch(request.id); vm.onDocumentResult(request.id, null) }
                assertNull(vm.state.value.pickerRequest)
                assertNull(vm.state.value.errorCode)
            }
        }
    }

    @Test fun cancellationAndProviderFailurePreserveExistingJsonPreviewAndNeverCommit() = runBlocking {
        fixture { vm, backend, _, directory ->
            val source = File(directory, "synthetic.json")
            source.writeBytes(BackupJsonCodec.encode(BackupDocument(ExportMetadata(clock.instant(), "source"), 0)))
            main { vm.requestImport(DataFormat.JSON) }
            await { vm.state.value.pickerRequest != null && !vm.state.value.busy }
            var request = vm.state.value.pickerRequest!!
            main { vm.acknowledgePickerLaunch(request.id); vm.onDocumentResult(request.id, Uri.fromFile(source).toString()) }
            await { vm.state.value.leaf == DataFormat.JSON && !vm.state.value.busy }
            val preview = vm.jsonState.value
            main { vm.dismissLeaf(); vm.requestImport(DataFormat.JSON) }
            await { vm.state.value.pickerRequest != null && !vm.state.value.busy }
            request = vm.state.value.pickerRequest!!
            main { vm.acknowledgePickerLaunch(request.id); vm.onDocumentResult(request.id, null) }
            assertEquals(preview, vm.jsonState.value)
            main { vm.requestImport(DataFormat.JSON) }
            await { vm.state.value.pickerRequest != null && !vm.state.value.busy }
            request = vm.state.value.pickerRequest!!
            main { vm.acknowledgePickerLaunch(request.id); vm.onDocumentResult(request.id, "content://nonexistent.synthetic.provider/document") }
            await { !vm.state.value.busy }
            assertNotNull(vm.state.value.errorCode)
            assertEquals(preview, vm.jsonState.value)
            assertEquals(0, backend.writes)
        }
    }

    @Test fun bothSelectedImportFilesOpenReadOnlyLeavesAndDuplicateResultsCannotReplay() = runBlocking {
        fixture { vm, backend, _, directory ->
            for (format in DataFormat.entries) {
                val file = File(directory, "import.${format.name.lowercase()}")
                file.writeBytes(when (format) {
                    DataFormat.CSV -> "date,amount,category,title\n2026-10-10,-1,Food,Synthetic\n".toByteArray()
                    DataFormat.JSON -> BackupJsonCodec.encode(BackupDocument(ExportMetadata(clock.instant(), "source"), 0))
                })
                main { vm.requestImport(format) }
                await { vm.state.value.pickerRequest != null && !vm.state.value.busy }
                val request = vm.state.value.pickerRequest!!
                main {
                    vm.acknowledgePickerLaunch(request.id)
                    vm.onDocumentResult(request.id, Uri.fromFile(file).toString())
                    vm.onDocumentResult(request.id, Uri.fromFile(file).toString())
                }
                await { vm.state.value.leaf == format && !vm.state.value.busy }
                assertNull(vm.state.value.pickerRequest)
                assertEquals(0, backend.writes)
                main { vm.dismissLeaf() }
            }
        }
    }

    @Test fun observedOfflineAndPendingStateRejectBothImportsBeforeAnyServerRead() = runBlocking {
        fixture { vm, backend, _, _ ->
            for (sync in listOf(SyncState.OFFLINE, SyncState.PENDING)) {
                backend.home.value = backend.home.value.copy(state = sync)
                val code = if (sync == SyncState.OFFLINE) "DATA_OFFLINE" else "DATA_PENDING"
                await { !vm.state.value.importEnabled && vm.state.value.importBlockCode == code }
                val reads = backend.reads
                for (format in DataFormat.entries) {
                    main { vm.requestImport(format) }
                    assertNull(vm.state.value.pickerRequest)
                    assertFalse(vm.state.value.busy)
                    assertEquals(reads, backend.reads)
                }
            }
            assertEquals(0, backend.writes)
        }
    }

    @Test fun selectedCsvCanMapStartAndRapidEditsSaveOnlyTheLatestFinancialCells() = runBlocking {
        fixture { vm, backend, _, directory ->
            val source = File(directory, "repair.csv")
            source.writeBytes("when,value,group,note\nbad,-1,Food,Original\n".toByteArray())
            main { vm.requestImport(DataFormat.CSV) }
            await { vm.state.value.pickerRequest != null && !vm.state.value.busy }
            val picker = vm.state.value.pickerRequest!!
            main { vm.acknowledgePickerLaunch(picker.id); vm.onDocumentResult(picker.id, Uri.fromFile(source).toString()) }
            await { vm.state.value.leaf == DataFormat.CSV && !vm.state.value.busy }
            assertEquals(CsvImportPhase.MAPPING, vm.csvState.value.phase)
            main { vm.onCsvAction(CsvImportAction.Mapping(CsvColumnMapping(date = 0, amount = 1, category = 2, title = 3), CsvDateFormat.AUTO)) }
            await { vm.csvState.value.phase == CsvImportPhase.READY && !vm.state.value.busy }
            main { vm.onCsvAction(CsvImportAction.Start) }
            await { vm.csvState.value.phase == CsvImportPhase.ATTENTION && !vm.state.value.busy }
            val row = vm.csvState.value.report!!.rows.single().source.rowNumber
            val latest = listOf("2026-10-10", "-7.89", "Food", "LATEST")
            main {
                vm.onCsvAction(CsvImportAction.Edit(row, listOf("2026-10-10", "-2", "Food", "FIRST")))
                vm.onCsvAction(CsvImportAction.Edit(row, listOf("2026-10-10", "-3", "Food", "SECOND")))
                vm.onCsvAction(CsvImportAction.Edit(row, latest))
                vm.onCsvAction(CsvImportAction.Save(row))
            }
            await { backend.csvEntries.isNotEmpty() && !vm.state.value.busy }
            assertEquals(1, backend.csvEntries.size)
            assertEquals(-789L, backend.csvEntries.single().amountGrosze)
            assertEquals("LATEST", backend.csvEntries.single().title)
        }
    }

    @Test fun failedExportProviderNeverPublishesCompletionAndCanBeRetried() = runBlocking {
        fixture { vm, backend, _, _ ->
            for (format in DataFormat.entries) {
                main { vm.requestExport(format); vm.confirmExport() }
                await { vm.state.value.pickerRequest != null && !vm.state.value.busy }
                val request = vm.state.value.pickerRequest!!
                main { vm.acknowledgePickerLaunch(request.id); vm.onDocumentResult(request.id, "content://nonexistent.synthetic.provider/no-space") }
                await { !vm.state.value.busy }
                assertNotNull(vm.state.value.errorCode)
                assertNull(vm.state.value.completedExport)
                assertNull(vm.state.value.pickerRequest)
                assertEquals(0, backend.writes)
            }
        }
    }

    @Test fun accountChangeCancelsOldExportAndDropsPickerTokensAndOldResults() = runBlocking {
        fixture { vm, backend, saved, _ ->
            main { vm.requestExport(DataFormat.JSON); vm.confirmExport() }
            await { vm.state.value.pickerRequest != null && !vm.state.value.busy }
            val request = vm.state.value.pickerRequest!!
            main { vm.acknowledgePickerLaunch(request.id) }
            backend.identity = CsvImportIdentity("member", "different-session")
            main { vm.bind("house", "member"); vm.onDocumentResult(request.id, "content://nonexistent.synthetic.provider/old") }
            await { vm.state.value.canExport && vm.state.value.importBlockCode == "DATA_OWNER_REQUIRED" && !vm.state.value.busy }
            assertNull(vm.state.value.pickerRequest)
            assertNull(vm.state.value.completedExport)
            assertNull(vm.state.value.errorCode)
            assertFalse(saved.keys().any { it.startsWith("dataPicker") || it == "dataCsvToken" })
            backend.gate = CompletableDeferred()
            main { vm.requestExport(DataFormat.CSV); vm.confirmExport() }
            await { vm.state.value.busy }
            backend.identity = CsvImportIdentity("owner", "third-session")
            main { vm.bind("house", "owner") }
            backend.gate!!.complete(Unit)
            await { vm.state.value.importEnabled && !vm.state.value.busy }
            assertNull(vm.state.value.pickerRequest)
            assertNull(vm.state.value.completedExport)
            assertEquals(0, backend.writes)
        }
    }

    @Test fun restoredLaunchedPickerIsNotRelaunchedAndSavedStateContainsOnlySmallScalars() = runBlocking {
        fixture { vm, backend, saved, directory ->
            main { vm.requestExport(DataFormat.JSON); vm.confirmExport() }
            await { vm.state.value.pickerRequest != null && !vm.state.value.busy }
            val request = vm.state.value.pickerRequest!!
            main { vm.acknowledgePickerLaunch(request.id) }
            val bundle = Bundle()
            saved.keys().forEach { key ->
                val value = saved.get<Any>(key)
                assertTrue("Financial bytes must never enter saved state: $key", value == null || value is String || value is Boolean)
                when (value) { is String -> bundle.putString(key, value); is Boolean -> bundle.putBoolean(key, value) }
            }
            val parcel = Parcel.obtain()
            try {
                parcel.writeBundle(bundle)
                assertTrue(parcel.dataSize() < 4096)
                assertFalse(String(parcel.marshall(), Charsets.UTF_16LE).contains("PRIVATE-SYNTHETIC-TITLE"))
            } finally { parcel.recycle() }
            val restoredState = SavedStateHandle(saved.keys().associateWith { saved.get<Any>(it) })
            lateinit var restored: DataViewModel
            val store = ViewModelStore()
            main { restored = model(backend, restoredState, directory); store.put("restored", restored); restored.bind("house", "owner") }
            try {
                await { restored.state.value.importEnabled && !restored.state.value.busy }
                assertEquals(request.id, restored.state.value.pickerRequest?.id)
                main { assertFalse(restored.acknowledgePickerLaunch(request.id)); restored.onDocumentResult(request.id, null) }
                assertNull(restored.state.value.pickerRequest)
                assertEquals(0, backend.writes)
            } finally { main { store.clear() } }
        }
    }

    @Test fun memberExportsAreAvailableAndBothImportsAreBlocked() = runBlocking {
        fixture { vm, backend, _, _ ->
            backend.identity = CsvImportIdentity("member", "session")
            main { vm.bind("house", "member") }
            await { vm.state.value.canExport && vm.state.value.importBlockCode == "DATA_OWNER_REQUIRED" && !vm.state.value.busy }
            assertTrue(vm.state.value.canExport)
            assertFalse(vm.state.value.importEnabled)
            for (format in DataFormat.entries) {
                main { vm.requestImport(format) }
                assertNull(vm.state.value.pickerRequest)
                assertEquals("DATA_OWNER_REQUIRED", vm.state.value.errorCode)
                main { vm.requestExport(format) }
                assertEquals(format, vm.state.value.warning)
                main { vm.dismissWarning() }
            }
        }
    }

    private suspend fun fixture(test: suspend (DataViewModel, Backend, SavedStateHandle, File) -> Unit) {
        val directory = File(context.cacheDir, "issue114-${UUID.randomUUID()}").apply { mkdirs() }
        val backend = Backend()
        val saved = SavedStateHandle()
        lateinit var vm: DataViewModel
        val store = ViewModelStore()
        main { vm = model(backend, saved, directory); store.put("test", vm); vm.bind("house", "owner") }
        try { await { vm.state.value.importEnabled && !vm.state.value.busy }; test(vm, backend, saved, directory) }
        finally { main { store.clear() }; directory.deleteRecursively() }
    }

    private fun model(backend: Backend, state: SavedStateHandle, directory: File) = DataViewModel(
        JsonBackupCoordinator(backend) { LocalDate.now(clock) }, CsvImportCoordinator(backend) { LocalDate.now(clock) },
        PrivateCsvImportCheckpointStore(directory), DocumentGateway(context), state, clock, backend)

    private class Backend : JsonBackupBackend, CsvImportBackend, HouseholdRepository {
        @Volatile var identity = CsvImportIdentity("owner", "session")
        @Volatile var synchronized = true
        @Volatile var offline = false
        @Volatile var reads = 0
        @Volatile var writes = 0
        var gate: CompletableDeferred<Unit>? = null
        val csvEntries = java.util.Collections.synchronizedList(mutableListOf<LedgerEntry>())
        val readOnMain = java.util.Collections.synchronizedList(mutableListOf<Boolean>())
        val home = MutableStateFlow(SyncObservation(Household("house", "Synthetic", "owner"), SyncState.SYNCED))
        val members = MutableStateFlow(SyncObservation(listOf(
            Member("owner", "owner@example.invalid", "Owner", MemberRole.OWNER),
            Member("member", "member@example.invalid", "Member", MemberRole.MEMBER)), SyncState.SYNCED))
        override fun observeHousehold(householdId: String) = home
        override fun observeMembers(householdId: String) = members
        override suspend fun saveHousehold(household: Household) = error("No writes expected")
        override suspend fun removeMember(householdId: String, memberId: String) = error("No writes expected")
        override fun currentIdentity() = identity
        override suspend fun loadSnapshot(householdId: String, expectedIdentity: CsvImportIdentity, requireOwner: Boolean): JsonBackupSnapshot {
            reads++
            readOnMain += (Looper.myLooper() == Looper.getMainLooper())
            gate?.await()
            if (offline) error("offline")
            return JsonBackupSnapshot(identity, Household("house", "Synthetic", "owner"),
                listOf(LedgerEntry("entry", "house", -100, LocalDate.of(2026, 10, 10), categoryId = "food", authorId = "owner",
                    updatedById = "owner", title = "PRIVATE-SYNTHETIC-TITLE")),
                listOf(Category("food", "house", "Food", "mint", authorId = "owner", updatedById = "owner")),
                emptyList(), setOf("owner", "member"), synchronized)
        }
        override suspend fun loadSnapshot(householdId: String, expectedIdentity: CsvImportIdentity): CsvImportSnapshot =
            CsvImportSnapshot(identity, Household("house", "Synthetic", "owner", ledgerRevision = csvEntries.size.toLong()), csvEntries.toList(),
                listOf(Category("food", "house", "Food", "mint", authorId = "owner", updatedById = "owner")), emptyList())
        override suspend fun commit(snapshot: JsonBackupSnapshot, operation: JsonMergeOperation): JsonMergeWriteResult { writes++; error("No commits expected") }
        override suspend fun commitEntry(snapshot: CsvImportSnapshot, entry: LedgerEntry): CsvImportCommitResult {
            csvEntries += entry
            writes++
            return CsvImportCommitResult(csvEntries.size.toLong(), true)
        }
    }
}
