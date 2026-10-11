package pl.bargor.thesaurus.ui.importcsv

import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject
import kotlinx.coroutines.launch
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import pl.bargor.thesaurus.data.transfer.*

@HiltViewModel
class CsvImportViewModel @Inject constructor(
    coordinator: CsvImportCoordinator,
    checkpointStore: PrivateCsvImportCheckpointStore,
    private val savedState: SavedStateHandle,
) : ViewModel() {
    private val controller = CsvImportController(coordinator, checkpointStore)
    private val mutableState = MutableStateFlow(CsvImportUiState())
    val state = mutableState.asStateFlow()
    private var actionJob = SupervisorJob(viewModelScope.coroutineContext[Job])
    private var actionScope = CoroutineScope(viewModelScope.coroutineContext + actionJob)
    private var observation: Job? = null
    private var bound: Pair<String, String>? = null
    init {
        observe()
    }
    private fun observe() {
        observation = viewModelScope.launch { controller.state.collect { value ->
            mutableState.value = value
            value.checkpointToken?.let { savedState["csvImportToken"] = it }
        } }
    }
    fun bind(householdId: String, uid: String) {
        if (bound == householdId to uid) return
        bound = householdId to uid
        val token = savedState.get<String>("csvImportToken")
        val previousHousehold = savedState.get<String>("csvImportHousehold")
        val previousUid = savedState.get<String>("csvImportUid")
        savedState["csvImportHousehold"] = householdId
        savedState["csvImportUid"] = uid
        if (previousHousehold != null && (previousHousehold != householdId || previousUid != uid)) {
            observation?.cancel()
            actionJob.cancel()
            actionJob = SupervisorJob(viewModelScope.coroutineContext[Job])
            actionScope = CoroutineScope(viewModelScope.coroutineContext + actionJob)
            savedState.remove<String>("csvImportToken")
            mutableState.value = CsvImportUiState()
            actionScope.launch { controller.reset(); observe() }
        }
        if (token != null && previousHousehold == householdId && previousUid == uid) {
            actionScope.launch { controller.restore(token, householdId, uid) }
        }
    }
    fun load(bytes: ByteArray) { val household = bound?.first ?: return; actionScope.launch { controller.load(household, bytes) } }
    fun setMapping(mapping: CsvColumnMapping, dateFormat: CsvDateFormat = CsvDateFormat.AUTO) { actionScope.launch { controller.setMapping(mapping, dateFormat) } }
    fun start() { actionScope.launch { controller.start() } }
    fun resume() { actionScope.launch { controller.resume() } }
    fun editRow(rowNumber: Int, cells: List<String>) { actionScope.launch { controller.editRow(rowNumber, cells) } }
    fun revalidate(rowNumber: Int) { actionScope.launch { controller.revalidate(rowNumber) } }
    fun saveRepair(rowNumber: Int) { actionScope.launch { controller.saveRepair(rowNumber) } }
    fun discard(rowNumber: Int) { actionScope.launch { controller.discard(rowNumber) } }
    fun finishDiscardingRemaining() { actionScope.launch { controller.finishDiscardingRemaining() } }
    fun selectCategory(rowNumber: Int, categoryId: String, applyToMatchingOriginal: Boolean = false) {
        actionScope.launch { controller.selectCategory(rowNumber, categoryId, applyToMatchingOriginal) }
    }
    fun selectSubcategory(rowNumber: Int, subcategoryId: String?, applyToMatchingOriginal: Boolean = false) {
        actionScope.launch { controller.selectSubcategory(rowNumber, subcategoryId, applyToMatchingOriginal) }
    }
    fun createCategory(rowNumber: Int, name: String, applyToMatchingOriginal: Boolean = false) {
        actionScope.launch { controller.createCategory(rowNumber, name, applyToMatchingOriginal) }
    }
    fun createSubcategory(rowNumber: Int, categoryId: String, name: String, applyToMatchingOriginal: Boolean = false) {
        actionScope.launch { controller.createSubcategory(rowNumber, categoryId, name, applyToMatchingOriginal) }
    }
}
