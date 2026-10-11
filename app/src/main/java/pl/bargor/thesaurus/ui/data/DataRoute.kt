package pl.bargor.thesaurus.ui.data

import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import pl.bargor.thesaurus.ui.backupjson.JsonBackupScreen
import pl.bargor.thesaurus.ui.importcsv.CsvImportScreen

@Composable
fun DataRoute(householdId: String, actorId: String, onBack: () -> Unit,
    modifier: Modifier = Modifier, viewModel: DataViewModel = hiltViewModel()) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val csvState by viewModel.csvState.collectAsStateWithLifecycle()
    val jsonState by viewModel.jsonState.collectAsStateWithLifecycle()
    LaunchedEffect(householdId, actorId) { viewModel.bind(householdId, actorId) }
    fun documentResult(format: DataFormat, direction: DataDirection, uri: android.net.Uri?) {
        viewModel.state.value.pickerRequest?.takeIf { it.format == format && it.direction == direction }
            ?.let { viewModel.onDocumentResult(it.id, uri?.toString()) }
    }
    // ActivityResultRegistry retains the launch across rotation. The retained request id identifies
    // its callback; no URI bytes or import contents are placed in saved instance state.
    val csvExport = rememberLauncherForActivityResult(DataDocumentContracts.createDocument(DataFormat.CSV)) { uri ->
        documentResult(DataFormat.CSV, DataDirection.EXPORT, uri)
    }
    val jsonExport = rememberLauncherForActivityResult(DataDocumentContracts.createDocument(DataFormat.JSON)) { uri ->
        documentResult(DataFormat.JSON, DataDirection.EXPORT, uri)
    }
    val csvImport = rememberLauncherForActivityResult(DataDocumentContracts.openDocument()) { uri ->
        documentResult(DataFormat.CSV, DataDirection.IMPORT, uri)
    }
    val jsonImport = rememberLauncherForActivityResult(DataDocumentContracts.openDocument()) { uri ->
        documentResult(DataFormat.JSON, DataDirection.IMPORT, uri)
    }
    LaunchedEffect(state.pickerRequest) {
        val request = state.pickerRequest ?: return@LaunchedEffect
        if (request.launched || !viewModel.acknowledgePickerLaunch(request.id)) return@LaunchedEffect
        try {
            when (request.direction) {
                DataDirection.EXPORT -> when (request.format) {
                    DataFormat.CSV -> csvExport.launch(requireNotNull(request.suggestedFileName))
                    DataFormat.JSON -> jsonExport.launch(requireNotNull(request.suggestedFileName))
                }
                DataDirection.IMPORT -> when (request.format) {
                    DataFormat.CSV -> csvImport.launch(DataDocumentContracts.importMimeTypes(DataFormat.CSV))
                    DataFormat.JSON -> jsonImport.launch(DataDocumentContracts.importMimeTypes(DataFormat.JSON))
                }
            }
        } catch (_: Exception) { viewModel.onPickerFailure(request.id) }
    }
    val leafBusy = state.busy || when (state.leaf) {
        DataFormat.CSV -> csvState.busy
        DataFormat.JSON -> jsonState.busy
        null -> false
    }
    val back = { if (!leafBusy) { if (state.leaf != null) viewModel.dismissLeaf() else onBack() } }
    BackHandler(enabled = state.leaf != null || state.busy) { back() }
    when (state.leaf) {
        null -> DataScreen(state, viewModel::requestExport, viewModel::requestImport,
            viewModel::confirmExport, viewModel::dismissWarning, back, modifier)
        else -> Column(modifier.fillMaxSize()) {
            Column(Modifier.padding(horizontal = 16.dp)) { DataHeader(back, enabled = !leafBusy) }
            when (state.leaf) {
                DataFormat.CSV -> CsvImportScreen(csvState, viewModel::onCsvAction, Modifier.weight(1f))
                DataFormat.JSON -> JsonBackupScreen(jsonState, viewModel::onJsonAction, Modifier.weight(1f), showExport = false)
                null -> Unit
            }
        }
    }
}
