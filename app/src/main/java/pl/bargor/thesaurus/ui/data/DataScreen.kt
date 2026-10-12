package pl.bargor.thesaurus.ui.data

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.*
import androidx.compose.ui.unit.dp
import pl.bargor.thesaurus.R
import pl.bargor.thesaurus.ui.settings.SettingsAction

/** Pure rendering surface: document access and all transfer work belong to the retained route. */
@Composable
fun DataScreen(
    state: DataUiState,
    onExport: (DataFormat) -> Unit,
    onImport: (DataFormat) -> Unit,
    onConfirmExport: () -> Unit,
    onDismissWarning: () -> Unit,
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(modifier.fillMaxSize().testTag("data-screen").verticalScroll(rememberScrollState()).padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)) {
        DataHeader(onBack, enabled = !state.busy)
        Text(stringResource(R.string.data_description))
        val idle = !state.busy && state.pickerRequest == null && state.warning == null
        DataButton(R.string.data_export_csv, "data-export-csv", idle && state.canExport) { onExport(DataFormat.CSV) }
        DataButton(R.string.data_export_json, "data-export-json", idle && state.canExport) { onExport(DataFormat.JSON) }
        HorizontalDivider()
        state.importBlockCode?.let { code ->
            Text(stringResource(when (code) {
                "DATA_OWNER_REQUIRED", "DATA_NOT_OWNER", "JSON_OWNER_REQUIRED" -> R.string.data_owner_only
                "DATA_OFFLINE", "JSON_SERVER_SNAPSHOT_REQUIRED" -> R.string.data_offline
                "DATA_PENDING", "DATA_PENDING_WRITES", "JSON_AUTHORIZED_SYNC_REQUIRED" -> R.string.data_pending
                "DATA_CHECKING" -> R.string.data_loading
                else -> R.string.data_readiness_error
            }), modifier = Modifier.testTag("data-import-blocked"))
        }
        if (state.importBlockCode !in setOf("DATA_OWNER_REQUIRED", "DATA_NOT_OWNER", "JSON_OWNER_REQUIRED")) {
            DataButton(R.string.data_import_csv, "data-import-csv", idle && state.importEnabled) { onImport(DataFormat.CSV) }
            DataButton(R.string.data_import_json, "data-import-json", idle && state.importEnabled) { onImport(DataFormat.JSON) }
        }
        if (state.busy) Column(Modifier.testTag("data-progress").semantics { liveRegion = LiveRegionMode.Polite },
            verticalArrangement = Arrangement.spacedBy(8.dp)) {
            LinearProgressIndicator(Modifier.fillMaxWidth())
            Text(stringResource(R.string.data_busy))
        }
        if (state.errorCode != null) {
            val message = stringResource(when (state.errorCode) {
                "DATA_DOCUMENT_WRITE_FAILED" -> R.string.data_export_error
                "DATA_SYNC_REQUIRED", "DATA_SESSION_CHANGED", "JSON_AUTH_REQUIRED",
                "JSON_SESSION_CHANGED", "JSON_AUTHORIZED_SYNC_REQUIRED", "JSON_SERVER_SNAPSHOT_REQUIRED" -> R.string.data_readiness_error
                "DATA_OWNER_REQUIRED", "DATA_NOT_OWNER", "JSON_OWNER_REQUIRED" -> R.string.data_owner_only
                "DATA_OFFLINE" -> R.string.data_offline
                "DATA_PENDING", "DATA_PENDING_WRITES" -> R.string.data_pending
                else -> R.string.data_error
            })
            Text(message, color = MaterialTheme.colorScheme.error,
                modifier = Modifier.testTag("data-error").semantics { error(message); liveRegion = LiveRegionMode.Polite })
        }
        if (state.completedExport != null) Text(stringResource(R.string.data_export_complete),
            modifier = Modifier.testTag("data-export-complete").semantics { liveRegion = LiveRegionMode.Polite })
    }
    if (state.warning != null) AlertDialog(
        modifier = Modifier.testTag("data-privacy-warning"),
        onDismissRequest = onDismissWarning,
        title = { Text(stringResource(R.string.data_privacy_title)) },
        text = { Text(stringResource(R.string.data_privacy_warning)) },
        confirmButton = {
            // Keep multiline actions in separate rows at large font sizes.
            Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                TextButton(onClick = onConfirmExport, modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp).testTag("data-privacy-confirm")) {
                    Text(stringResource(R.string.data_privacy_confirm))
                }
                TextButton(onClick = onDismissWarning, modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp).testTag("data-privacy-cancel")) {
                    Text(stringResource(R.string.data_cancel))
                }
            }
        },
    )
}

@Composable
internal fun DataHeader(onBack: () -> Unit, enabled: Boolean = true) {
    Row(Modifier.fillMaxWidth()) {
        Text(stringResource(R.string.settings_data), style = MaterialTheme.typography.headlineSmall,
            modifier = Modifier.weight(1f).padding(top = 8.dp).semantics { heading() })
        TextButton(onClick = onBack, enabled = enabled,
            modifier = Modifier.heightIn(min = 48.dp).testTag("data-back")) { Text(stringResource(R.string.settings_back)) }
        SettingsAction()
    }
}

@Composable
private fun DataButton(label: Int, tag: String, enabled: Boolean, action: () -> Unit) {
    OutlinedButton(onClick = action, enabled = enabled,
        modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp).testTag(tag)) { Text(stringResource(label)) }
}
