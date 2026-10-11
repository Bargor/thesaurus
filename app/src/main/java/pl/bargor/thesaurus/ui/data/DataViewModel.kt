package pl.bargor.thesaurus.ui.data

import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import java.time.Clock
import java.time.LocalDate
import java.util.UUID
import javax.inject.Inject
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.withContext
import pl.bargor.thesaurus.data.firebase.HouseholdRepository
import pl.bargor.thesaurus.data.model.MemberRole
import pl.bargor.thesaurus.data.model.SyncState
import pl.bargor.thesaurus.data.observation.reduceSyncState
import pl.bargor.thesaurus.data.observation.withObservationErrors
import pl.bargor.thesaurus.data.transfer.*
import pl.bargor.thesaurus.ui.backupjson.JsonBackupAction
import pl.bargor.thesaurus.ui.backupjson.JsonBackupController
import pl.bargor.thesaurus.ui.importcsv.CsvImportAction
import pl.bargor.thesaurus.ui.importcsv.CsvImportController
import pl.bargor.thesaurus.ui.importcsv.PrivateCsvImportCheckpointStore

/** Retains picker ownership and leaf operations across rotation. Saved state contains
 * opaque picker/checkpoint tokens and scalar binding only, never financial file bytes. */
@HiltViewModel
class DataViewModel @Inject constructor(
    private val coordinator: JsonBackupCoordinator,
    csvCoordinator: CsvImportCoordinator,
    checkpoints: PrivateCsvImportCheckpointStore,
    private val documents: DocumentGateway,
    private val savedState: SavedStateHandle,
    private val clock: Clock,
    private val households: HouseholdRepository,
) : ViewModel() {
    private val csv = CsvImportController(csvCoordinator, checkpoints)
    private val json = JsonBackupController(coordinator)
    val csvState = csv.state
    val jsonState = json.state
    private val mutableState = MutableStateFlow(DataUiState(pickerRequest = restorePicker()))
    val state = mutableState.asStateFlow()
    private var binding: Pair<String, String>? = null
    private var owner = false
    private var operation: Job? = null
    private var readiness: Job? = null
    private var observation: Job? = null
    private var exportBytes: ByteArray? = null
    private var exportIdentity: CsvImportIdentity? = null
    private var generation = 0
    private var pendingOperations = 0
    private val pendingCsvCommands = mutableSetOf<CsvImportAction>()
    private var bindingReady = false
    private var deferredResult: Pair<String, String?>? = null
    private var actionsJob = SupervisorJob(viewModelScope.coroutineContext[Job])
    private var actions = CoroutineScope(viewModelScope.coroutineContext + actionsJob)

    fun bind(householdId: String, uid: String) {
        val next = householdId to uid
        if (binding == next) return
        val previous = binding ?: savedState.get<String>("dataHousehold")?.let {
            it to savedState.get<String>("dataUid").orEmpty()
        }
        val changed = previous != null && previous != next
        val oldActions = actionsJob
        oldActions.cancel()
        generation++
        val currentGeneration = generation
        actionsJob = SupervisorJob(viewModelScope.coroutineContext[Job])
        actions = CoroutineScope(viewModelScope.coroutineContext + actionsJob)
        pendingOperations = 0
        pendingCsvCommands.clear()
        bindingReady = false
        binding = next
        owner = false
        savedState["dataHousehold"] = householdId
        savedState["dataUid"] = uid
        if (changed) {
            exportBytes = null
            exportIdentity = null
            clearPicker()
            deferredResult = null
            mutableState.value = DataUiState()
        }
        mutableState.value = state.value.copy(busy = true, canExport = false, importEnabled = false, importBlockCode = "DATA_CHECKING")
        operation = actions.launch {
            // Controllers may be finishing an acknowledged transaction/checkpoint in a
            // non-cancellable block. Await all old work before resetting either leaf.
            withContext(NonCancellable) { oldActions.cancelAndJoin() }
            currentCoroutineContext().ensureActive()
            if (generation != currentGeneration) return@launch
            if (changed) {
                savedState.remove<String>("dataCsvToken")
                savedState.remove<String>("dataLeaf")
                csv.reset()
                json.reset()
            } else savedState.get<String>("dataCsvToken")?.takeIf { savedState.get<String>("dataLeaf") == DataFormat.CSV.name }?.let { token ->
                withContext(Dispatchers.IO) { csv.restore(token, householdId, uid) }
                if (generation == currentGeneration) setLeaf(DataFormat.CSV)
            }
            actions.launch {
                csv.state.collect { value ->
                    if (generation == currentGeneration) value.checkpointToken?.let { savedState["dataCsvToken"] = it }
                }
            }
            bindingReady = true
            mutableState.value = state.value.copy(busy = false)
            deferredResult?.let { (id, uri) -> deferredResult = null; onDocumentResult(id, uri) }
        }
        observation = actions.launch {
            households.observeHousehold(householdId).withObservationErrors()
                .combine(households.observeMembers(householdId).withObservationErrors()) { home, members ->
                home to members
            }.collect { (home, members) ->
                val member = members.value?.firstOrNull { it.uid == uid }
                owner = member?.role == MemberRole.OWNER && home.value?.ownerId == uid && home.value.id == householdId
                val sync = reduceSyncState(listOf(home, members))
                val code = when {
                    home.value == null || members.value == null -> "DATA_SYNC_REQUIRED"
                    !owner -> "DATA_OWNER_REQUIRED"
                    sync == SyncState.PENDING -> "DATA_PENDING"
                    sync == SyncState.OFFLINE -> "DATA_OFFLINE"
                    sync != SyncState.SYNCED -> "DATA_SYNC_REQUIRED"
                    else -> null
                }
                mutableState.value = state.value.copy(canExport = member != null && home.value?.id == householdId,
                    importEnabled = code == null, importBlockCode = code)
            }
        }
    }

    fun refreshReadiness() {
        val current = binding ?: return
        readiness?.cancel()
        if (!owner) return
        readiness = actions.launch {
            try {
                withContext(Dispatchers.IO) { coordinator.checkImportReadiness(current.first) }
                if (binding == current) mutableState.value = state.value.copy(importEnabled = true, importBlockCode = null)
            } catch (cancelled: CancellationException) { throw cancelled }
            catch (failure: Exception) {
                if (binding == current) mutableState.value = state.value.copy(importEnabled = false, importBlockCode = safeCode(failure))
            }
        }
    }

    private fun available(): Boolean = bindingReady && binding != null && !state.value.busy && state.value.pickerRequest == null &&
        !csv.state.value.busy && !json.state.value.busy && operation?.isActive != true

    fun requestExport(format: DataFormat) {
        if (!available() || !state.value.canExport || state.value.warning != null) return
        mutableState.value = state.value.copy(warning = format, completedExport = null, errorCode = null)
    }

    fun dismissWarning() { mutableState.value = state.value.copy(warning = null) }

    fun confirmExport() {
        val format = state.value.warning ?: return
        if (!available()) return
        val household = binding?.first ?: return
        mutableState.value = state.value.copy(warning = null)
        launchOperation {
            exportIdentity = coordinator.currentIdentity()
            exportBytes = createExport(household, format)
            publishPicker(format, DataDirection.EXPORT)
        }
    }

    fun requestImport(format: DataFormat) {
        if (!available() || state.value.warning != null) return
        if (!owner) {
            mutableState.value = state.value.copy(errorCode = state.value.importBlockCode ?: "DATA_OWNER_REQUIRED")
            return
        }
        if (!state.value.importEnabled) {
            mutableState.value = state.value.copy(errorCode = state.value.importBlockCode ?: "DATA_SYNC_REQUIRED")
            return
        }
        val household = binding?.first ?: return
        launchOperation {
            // Revalidate live authorization/sync before opening external file selection.
            withContext(Dispatchers.IO) { coordinator.checkImportReadiness(household) }
            mutableState.value = state.value.copy(importEnabled = true, importBlockCode = null)
            publishPicker(format, DataDirection.IMPORT)
        }
    }

    /** Called immediately before ActivityResultLauncher.launch. A retained launched request
     * is still awaiting its result and must not launch a second picker on recreation. */
    fun acknowledgePickerLaunch(id: String): Boolean {
        val request = state.value.pickerRequest ?: return false
        if (request.id != id || request.launched) return false
        setPicker(request.copy(launched = true))
        return true
    }

    fun onPickerFailure(id: String) {
        if (state.value.pickerRequest?.id != id) return
        clearPicker()
        exportBytes = null
        exportIdentity = null
        mutableState.value = state.value.copy(errorCode = "DATA_DOCUMENT_FAILED")
    }

    fun onDocumentResult(id: String, uri: String?) {
        val request = state.value.pickerRequest ?: return
        if (request.id != id || !request.launched) return
        if (!bindingReady) { deferredResult = id to uri; return }
        clearPicker() // Consume before launching work; duplicate results cannot replay an import.
        if (uri == null) {
            exportBytes = null
            exportIdentity = null
            mutableState.value = state.value.copy(errorCode = null)
            return
        }
        val household = binding?.first ?: return
        launchOperation {
            if (request.direction == DataDirection.EXPORT) {
                // Process death loses only the memory artifact. Regenerate from guarded server
                // state; warning consent and pending picker identity were retained as scalars.
                try {
                    if (exportBytes != null && exportIdentity != coordinator.currentIdentity()) throw JsonBackupException("JSON_SESSION_CHANGED")
                    val bytes = exportBytes ?: createExport(household, request.format)
                    try { documents.write(uri, bytes) }
                    catch (cancelled: CancellationException) { throw cancelled }
                    catch (_: Exception) { throw JsonBackupException("DATA_DOCUMENT_WRITE_FAILED") }
                } finally { exportBytes = null; exportIdentity = null }
                mutableState.value = state.value.copy(completedExport = request.format)
            } else {
                withContext(Dispatchers.IO) { coordinator.checkImportReadiness(household) }
                val bytes = documents.read(uri)
                withContext(Dispatchers.IO) {
                    when (request.format) {
                        DataFormat.CSV -> csv.load(household, bytes)
                        DataFormat.JSON -> json.prepare(household, bytes)
                    }
                }
                setLeaf(request.format)
            }
        }
    }

    fun dismissLeaf() {
        if (state.value.busy || csv.state.value.busy || json.state.value.busy) return
        setLeaf(null)
    }

    private fun setLeaf(format: DataFormat?) {
        savedState["dataLeaf"] = format?.name
        mutableState.value = state.value.copy(leaf = format)
    }

    fun onJsonAction(action: JsonBackupAction) {
        if (action == JsonBackupAction.EXPORT) { requestExport(DataFormat.JSON); return }
        if (!available()) return
        launchOperation {
            withContext(Dispatchers.IO) {
                when (action) {
                    JsonBackupAction.CONFIRM -> json.confirm()
                    JsonBackupAction.RETRY -> json.retry()
                    JsonBackupAction.EXPORT -> Unit
                }
            }
        }
    }

    fun onCsvAction(action: CsvImportAction) {
        // Text fields continue emitting while a previous private draft is being saved.
        // Queue every edit and its following validation/save, preserving arrival order.
        if (!bindingReady || state.value.leaf != DataFormat.CSV || state.value.pickerRequest != null) return
        val isEdit = action is CsvImportAction.Edit
        if (!isEdit && !pendingCsvCommands.add(action)) return
        val currentGeneration = generation
        launchOperation {
            try { withContext(Dispatchers.IO) {
                when (action) {
                    is CsvImportAction.Mapping -> csv.setMapping(action.value, action.format)
                    CsvImportAction.Start -> csv.start()
                    CsvImportAction.Resume -> csv.resume()
                    CsvImportAction.Finish -> csv.finishDiscardingRemaining()
                    is CsvImportAction.Edit -> csv.editRow(action.row, action.cells)
                    is CsvImportAction.Validate -> csv.revalidate(action.row)
                    is CsvImportAction.Save -> csv.saveRepair(action.row)
                    is CsvImportAction.Discard -> csv.discard(action.row)
                    is CsvImportAction.Category -> csv.selectCategory(action.row, action.id, action.batch)
                    is CsvImportAction.Subcategory -> csv.selectSubcategory(action.row, action.id, action.batch)
                    is CsvImportAction.NewCategory -> csv.createCategory(action.row, action.name, action.batch)
                    is CsvImportAction.NewSubcategory -> csv.createSubcategory(action.row, action.parent, action.name, action.batch)
                }
            } } finally {
                if (!isEdit && generation == currentGeneration) pendingCsvCommands.remove(action)
            }
        }
    }

    private fun launchOperation(action: suspend () -> Unit) {
        val currentGeneration = generation
        val previous = operation
        pendingOperations++
        mutableState.value = state.value.copy(busy = true, errorCode = null, completedExport = null)
        operation = actions.launch {
            try {
                previous?.join()
                if (coordinator.currentIdentity()?.uid != binding?.second) throw JsonBackupException("JSON_SESSION_CHANGED")
                action()
            }
            catch (cancelled: CancellationException) { throw cancelled }
            catch (failure: Exception) {
                if (generation == currentGeneration) mutableState.value = state.value.copy(errorCode = safeCode(failure))
            }
            finally {
                if (generation == currentGeneration) {
                    pendingOperations--
                    mutableState.value = state.value.copy(busy = pendingOperations > 0)
                }
            }
        }
    }

    private suspend fun createExport(household: String, format: DataFormat): ByteArray = withContext(Dispatchers.IO) {
        when (format) {
            DataFormat.CSV -> coordinator.exportCsv(household, clock.instant()).bytes
            DataFormat.JSON -> coordinator.export(household, clock.instant()).bytes
        }
    }

    private fun publishPicker(format: DataFormat, direction: DataDirection) {
        val name = if (direction == DataDirection.EXPORT) {
            val type = if (format == DataFormat.CSV) "wpisy" else "kopia"
            "thesaurus-$type-${LocalDate.now(clock)}.${format.name.lowercase()}"
        } else null
        setPicker(DataPickerRequest(UUID.randomUUID().toString(), format, direction, name))
    }

    private fun setPicker(request: DataPickerRequest) {
        savedState["dataPickerId"] = request.id
        savedState["dataPickerFormat"] = request.format.name
        savedState["dataPickerDirection"] = request.direction.name
        savedState["dataPickerName"] = request.suggestedFileName
        savedState["dataPickerLaunched"] = request.launched
        mutableState.value = state.value.copy(pickerRequest = request)
    }

    private fun clearPicker() {
        listOf("dataPickerId", "dataPickerFormat", "dataPickerDirection", "dataPickerName", "dataPickerLaunched")
            .forEach { savedState.remove<Any>(it) }
        mutableState.value = state.value.copy(pickerRequest = null)
    }

    private fun restorePicker(): DataPickerRequest? = runCatching {
        val id = savedState.get<String>("dataPickerId") ?: return null
        DataPickerRequest(id, DataFormat.valueOf(savedState.get<String>("dataPickerFormat")!!),
            DataDirection.valueOf(savedState.get<String>("dataPickerDirection")!!),
            savedState.get<String>("dataPickerName"), savedState.get<Boolean>("dataPickerLaunched") ?: false)
    }.getOrNull()

    private fun safeCode(error: Exception): String = when (error) {
        is JsonBackupException -> when (error.code) {
            "JSON_SERVER_SNAPSHOT_REQUIRED", "JSON_AUTHORIZED_SYNC_REQUIRED" -> "DATA_SYNC_REQUIRED"
            else -> error.code
        }
        is BackupContractException -> error.code.name
        is SecurityException -> "DATA_DOCUMENT_PERMISSION"
        else -> "DATA_DOCUMENT_FAILED"
    }
}
