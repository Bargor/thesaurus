package pl.bargor.thesaurus.ui.backupjson

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.*
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch
import pl.bargor.thesaurus.R
import pl.bargor.thesaurus.data.transfer.*

enum class JsonBackupAction { CONFIRM, RETRY, EXPORT }

/** Caller owns file selection, household binding and the retained ViewModel action scope. */
@Composable
fun JsonBackupRoute(controller: JsonBackupController, actionScope: CoroutineScope, householdId: String,
    modifier: Modifier = Modifier, onExportArtifact: (JsonBackupExportArtifact) -> Unit = {}) {
    val state by controller.state.collectAsStateWithLifecycle()
    JsonBackupScreen(state, { action -> actionScope.launch {
        when (action) {
            JsonBackupAction.CONFIRM -> controller.confirm()
            JsonBackupAction.RETRY -> controller.retry()
            JsonBackupAction.EXPORT -> controller.export(householdId)?.let(onExportArtifact)
        }
    } }, modifier)
}

@Composable
fun JsonBackupScreen(state: JsonBackupUiState, onAction: (JsonBackupAction) -> Unit, modifier: Modifier = Modifier) {
    val report = state.report
    val conflicts = remember(report) { report?.items.orEmpty().filter { it.status == JsonMergeStatus.CONFLICT } }
    val counts = remember(report) { JsonMergeItemKind.entries.associateWith { kind ->
        val rows = report?.items.orEmpty().filter { it.kind == kind }
        JsonMergeStatus.entries.associateWith { status -> rows.count { it.status == status } }
    } }
    val previewTotals = remember(report) { Triple(report?.newCount ?: 0, report?.skippedCount ?: 0, report?.conflictCount ?: 0) }
    LazyColumn(modifier.fillMaxSize().testTag("json_backup_screen"), contentPadding = PaddingValues(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)) {
        item { Text(stringResource(R.string.json_backup_title), style = MaterialTheme.typography.headlineSmall, modifier = Modifier.semantics { heading() }) }
        if (state.phase == JsonBackupPhase.EMPTY) item { Text(stringResource(R.string.json_backup_empty)) }
        state.errorCode?.let { code -> item {
            val message = stringResource(jsonBackupErrorLabel(code))
            Text(message, color = MaterialTheme.colorScheme.error,
                modifier = Modifier.testTag("json_backup_error").semantics { error(message); liveRegion = LiveRegionMode.Polite })
        } }
        if (state.busy) item {
            Column(Modifier.testTag("json_backup_progress").semantics { liveRegion = LiveRegionMode.Polite }, verticalArrangement = Arrangement.spacedBy(8.dp)) {
                LinearProgressIndicator(Modifier.fillMaxWidth())
                Text(stringResource(if (state.phase == JsonBackupPhase.IMPORTING) R.string.json_backup_progress else R.string.json_backup_busy,
                    state.processed, state.total))
            }
        }
        if (report != null && state.phase in setOf(JsonBackupPhase.PREVIEW, JsonBackupPhase.IMPORTING)) {
            item {
                Column(Modifier.testTag("json_backup_preview"), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text(stringResource(R.string.json_backup_preview), style = MaterialTheme.typography.titleLarge, modifier = Modifier.semantics { heading() })
                    Text(stringResource(R.string.json_backup_counts, previewTotals.first, previewTotals.second, previewTotals.third))
                    Text(stringResource(R.string.json_backup_preview_help))
                    JsonMergeItemKind.entries.forEach { kind ->
                        val rows = counts.getValue(kind)
                        Text(stringResource(R.string.json_backup_kind_preview, stringResource(kindLabel(kind)),
                            rows.getValue(JsonMergeStatus.NEW), rows.getValue(JsonMergeStatus.SKIPPED), rows.getValue(JsonMergeStatus.CONFLICT)))
                    }
                }
            }
            item {
                if (state.total == 0) Text(stringResource(if (previewTotals.third == 0) R.string.json_backup_all_skipped else R.string.json_backup_no_new))
                else Button(onClick = { onAction(JsonBackupAction.CONFIRM) }, enabled = !state.busy,
                    modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp).testTag("json_backup_confirm")) { Text(stringResource(R.string.json_backup_confirm)) }
            }
        }
        if (report != null && state.phase == JsonBackupPhase.COMPLETE) item {
            Column(Modifier.testTag("json_backup_summary").semantics { liveRegion = LiveRegionMode.Polite }, verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(stringResource(R.string.json_backup_summary), style = MaterialTheme.typography.titleLarge, modifier = Modifier.semantics { heading() })
                JsonMergeItemKind.entries.forEach { kind ->
                    val rows = counts.getValue(kind)
                    Text(stringResource(R.string.json_backup_kind_counts, stringResource(kindLabel(kind)),
                        rows.getValue(JsonMergeStatus.ADDED), rows.getValue(JsonMergeStatus.SKIPPED),
                        rows.getValue(JsonMergeStatus.CONFLICT), rows.getValue(JsonMergeStatus.SERVER_ERROR), rows.getValue(JsonMergeStatus.NEW)))
                }
            }
        }
        items(conflicts) { conflict ->
            val reason = stringResource(jsonBackupErrorLabel(conflict.code ?: "JSON_WRITE_CONFLICT"))
            val kind = stringResource(kindLabel(conflict.kind))
            val message = if (conflict.sourceId.isNullOrEmpty()) stringResource(R.string.json_backup_conflict_without_id, kind, reason)
                else stringResource(R.string.json_backup_conflict_item, kind, conflict.sourceId, reason)
            Text(message,
                color = MaterialTheme.colorScheme.error, modifier = Modifier.testTag("json_backup_conflict_reason").semantics { error(reason) })
        }
        if (state.canRetry && state.phase != JsonBackupPhase.IMPORTING) item {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(stringResource(R.string.json_backup_retry_help))
                OutlinedButton(onClick = { onAction(JsonBackupAction.RETRY) }, enabled = !state.busy,
                    modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp).testTag("json_backup_retry")) { Text(stringResource(R.string.json_backup_retry)) }
            }
        }
        item { OutlinedButton(onClick = { onAction(JsonBackupAction.EXPORT) }, enabled = !state.busy,
            modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp).testTag("json_backup_export")) { Text(stringResource(R.string.json_backup_export)) } }
    }
}

private fun kindLabel(kind: JsonMergeItemKind): Int = when (kind) {
    JsonMergeItemKind.OPENING_BALANCE -> R.string.json_backup_balance
    JsonMergeItemKind.CATEGORY -> R.string.json_backup_categories
    JsonMergeItemKind.SUBCATEGORY -> R.string.json_backup_subcategories
    JsonMergeItemKind.ENTRY -> R.string.json_backup_entries
}

private fun jsonBackupErrorLabel(code: String): Int = when (code) {
    "JSON_OPENING_BALANCE_CONFLICT" -> R.string.json_backup_error_balance
    "JSON_DELETION_CONFLICT" -> R.string.json_backup_error_deleted
    "JSON_ENTRY_CONFLICT" -> R.string.json_backup_error_entry
    "JSON_AMBIGUOUS_CATEGORY", "JSON_AMBIGUOUS_SUBCATEGORY" -> R.string.json_backup_error_ambiguous
    "JSON_PARENT_CONFLICT", "JSON_TAXONOMY_CONFLICT" -> R.string.json_backup_error_parent
    "JSON_ID_CONFLICT", "JSON_WRITE_CONFLICT" -> R.string.json_backup_error_id
    "JSON_PREVIEW_STALE" -> R.string.json_backup_error_stale
    "JSON_AUTH_REQUIRED", "JSON_SERVER_SNAPSHOT_REQUIRED", "JSON_AUTHORIZED_SYNC_REQUIRED", "JSON_SESSION_CHANGED" -> R.string.json_backup_error_access
    "MALFORMED_JSON", "MALFORMED_UTF8", "UNSUPPORTED_VERSION", "INVALID_VALUE", "LIMIT_EXCEEDED", "JSON_INVALID_NORMALIZED_VALUE" -> R.string.json_backup_error_file
    "JSON_FUTURE_DATE" -> R.string.json_backup_error_future
    "JSON_IMPORT_WRITE_FAILED" -> R.string.json_backup_error_write
    else -> R.string.json_backup_error_generic
}
