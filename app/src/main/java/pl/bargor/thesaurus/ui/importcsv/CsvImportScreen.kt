package pl.bargor.thesaurus.ui.importcsv

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.*
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import kotlinx.coroutines.launch
import kotlinx.coroutines.CoroutineScope
import pl.bargor.thesaurus.R
import pl.bargor.thesaurus.data.transfer.*

sealed interface CsvImportAction {
    data class Mapping(val value: CsvColumnMapping, val format: CsvDateFormat) : CsvImportAction
    data object Start : CsvImportAction
    data object Resume : CsvImportAction
    data object Finish : CsvImportAction
    data class Edit(val row: Int, val cells: List<String>) : CsvImportAction
    data class Validate(val row: Int) : CsvImportAction
    data class Save(val row: Int) : CsvImportAction
    data class Discard(val row: Int) : CsvImportAction
    data class Category(val row: Int, val id: String, val batch: Boolean) : CsvImportAction
    data class Subcategory(val row: Int, val id: String?, val batch: Boolean) : CsvImportAction
    data class NewCategory(val row: Int, val name: String, val batch: Boolean) : CsvImportAction
    data class NewSubcategory(val row: Int, val parent: String, val name: String, val batch: Boolean) : CsvImportAction
}

/** Leaf route: the caller owns source-file selection and the retained controller. */
@Composable
fun CsvImportRoute(controller: CsvImportController, actionScope: CoroutineScope, modifier: Modifier = Modifier) {
    val state by controller.state.collectAsStateWithLifecycle()
    CsvImportScreen(state, { action -> actionScope.launch {
        when (action) {
            is CsvImportAction.Mapping -> controller.setMapping(action.value, action.format)
            CsvImportAction.Start -> controller.start()
            CsvImportAction.Resume -> controller.resume()
            CsvImportAction.Finish -> controller.finishDiscardingRemaining()
            is CsvImportAction.Edit -> controller.editRow(action.row, action.cells)
            is CsvImportAction.Validate -> controller.revalidate(action.row)
            is CsvImportAction.Save -> controller.saveRepair(action.row)
            is CsvImportAction.Discard -> controller.discard(action.row)
            is CsvImportAction.Category -> controller.selectCategory(action.row, action.id, action.batch)
            is CsvImportAction.Subcategory -> controller.selectSubcategory(action.row, action.id, action.batch)
            is CsvImportAction.NewCategory -> controller.createCategory(action.row, action.name, action.batch)
            is CsvImportAction.NewSubcategory -> controller.createSubcategory(action.row, action.parent, action.name, action.batch)
        }
    } }, modifier)
}

@Composable
fun CsvImportScreen(state: CsvImportUiState, onAction: (CsvImportAction) -> Unit, modifier: Modifier = Modifier) {
    var editing by rememberSaveable { mutableStateOf<Int?>(null) }
    var confirmFinish by rememberSaveable { mutableStateOf(false) }
    val rows = remember(state.report, state.phase) { if (state.phase in setOf(CsvImportPhase.ATTENTION, CsvImportPhase.IMPORTING, CsvImportPhase.RESTORED)) state.report?.rows.orEmpty().filter { it.status in attentionStatuses } else emptyList() }
    val originalRows = remember(state.report?.originalRows) { state.report?.originalRows.orEmpty().associateBy { it.rowNumber } }
    val manualMapping = remember(state.headers) { state.headers.isNotEmpty() && CsvHeaderDetector.detect(state.headers).requiresManualMapping }
    LazyColumn(modifier.fillMaxSize().testTag("csv_import_screen"), contentPadding = PaddingValues(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        item { Text(stringResource(R.string.csv_import_title), style = MaterialTheme.typography.headlineSmall, modifier = Modifier.semantics { heading() }) }
        if (state.phase == CsvImportPhase.EMPTY) item { Text(stringResource(R.string.csv_import_empty)) }
        if (state.errorCode != null) item { Text(stringResource(operationErrorLabel(state.errorCode)), color = MaterialTheme.colorScheme.error, modifier = Modifier.semantics { liveRegion = LiveRegionMode.Polite }) }
        if (state.phase in setOf(CsvImportPhase.MAPPING, CsvImportPhase.READY)) {
            item { Text(stringResource(R.string.csv_import_source_count, state.total)) }
            item { Text(stringResource(if (state.phase == CsvImportPhase.MAPPING) R.string.csv_import_mapping_help else R.string.csv_import_auto)) }
            if (manualMapping || state.phase == CsvImportPhase.MAPPING) item {
                Column(Modifier.testTag("csv_import_manual_mapping"), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text(stringResource(R.string.csv_import_mapping), style = MaterialTheme.typography.titleMedium)
                    mappedColumns.forEach { field ->
                        val current = state.mapping.index(field)
                        val taken = mappedColumns.filter { it != field }.mapNotNull { state.mapping.index(it) }.toSet()
                        Choice(stringResource(fieldLabel(field)), current?.let { stringResource(R.string.csv_import_column, it + 1, state.headers[it]) } ?: stringResource(R.string.csv_import_none),
                            listOf(null to stringResource(R.string.csv_import_none)) + state.headers.mapIndexedNotNull { index, name -> if (index in taken) null else index to stringResource(R.string.csv_import_column, index + 1, name) },
                            !state.busy, "csv_import_mapping_${field.name.lowercase()}") { onAction(CsvImportAction.Mapping(state.mapping.withIndex(field, it), state.dateFormat)) }
                    }
                }
            }
            item { Choice(stringResource(R.string.csv_import_date_format), stringResource(dateFormatLabel(state.dateFormat)), CsvDateFormat.entries.map { it to stringResource(dateFormatLabel(it)) }, !state.busy, "csv_import_date_format") { onAction(CsvImportAction.Mapping(state.mapping, it)) } }
            item {
                if (state.phase == CsvImportPhase.MAPPING) {
                    Button(onClick = { onAction(CsvImportAction.Mapping(state.mapping, state.dateFormat)) }, enabled = state.mapping.isComplete && !state.busy, modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp).testTag("csv_import_confirm_mapping")) { Text(stringResource(R.string.csv_import_confirm_mapping)) }
                } else {
                    Button(onClick = { onAction(CsvImportAction.Start) }, enabled = state.mapping.isComplete && !state.busy, modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp).testTag("csv_import_start")) { Text(stringResource(R.string.csv_import_start)) }
                }
            }
        }
        if (state.phase == CsvImportPhase.RESTORED) item {
            Column { Text(stringResource(R.string.csv_import_restored)); Button(onClick = { onAction(CsvImportAction.Resume) }, enabled = !state.busy, modifier = Modifier.testTag("csv_import_resume")) { Text(stringResource(R.string.csv_import_resume)) } }
        }
        if (state.busy || state.phase in setOf(CsvImportPhase.IMPORTING, CsvImportPhase.ATTENTION, CsvImportPhase.COMPLETE)) item {
            Column(Modifier.testTag("csv_import_progress").semantics { liveRegion = LiveRegionMode.Polite }, verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(stringResource(R.string.csv_import_progress, state.processed, state.total, state.attention))
                if (state.busy) { LinearProgressIndicator(Modifier.fillMaxWidth()); Text(stringResource(R.string.csv_import_busy)) }
            }
        }
        if (rows.isNotEmpty()) item { Text(stringResource(R.string.csv_import_attention), style = MaterialTheme.typography.titleLarge, modifier = Modifier.semantics { heading() }) }
        items(rows, key = { it.source.rowNumber }) { row ->
            val original = originalRows[row.source.rowNumber] ?: row.source
            ElevatedCard(Modifier.fillMaxWidth().testTag("csv_import_row_${row.source.rowNumber}")) {
                Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text(stringResource(R.string.csv_import_row, row.source.rowNumber), style = MaterialTheme.typography.titleMedium, modifier = Modifier.semantics { heading() })
                    OriginalCells(state.headers, original.cells)
                    row.source.issues.forEach { IssueText(it) }
                    if (row.status == CsvImportRowStatus.SERVER_ERROR) Text(stringResource(R.string.csv_import_error_generic), color = MaterialTheme.colorScheme.error)
                    Button(onClick = { editing = row.source.rowNumber }, enabled = !state.busy, modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp).testTag("csv_import_edit_${row.source.rowNumber}")) { Text(stringResource(R.string.csv_import_edit)) }
                    OutlinedButton(onClick = { onAction(CsvImportAction.Discard(row.source.rowNumber)) }, enabled = !state.busy, modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp).testTag("csv_import_discard_${row.source.rowNumber}")) { Text(stringResource(R.string.csv_import_discard)) }
                }
            }
        }
        if (state.phase == CsvImportPhase.ATTENTION) item {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                if (state.errorCode != null || rows.any { it.status == CsvImportRowStatus.SERVER_ERROR }) OutlinedButton(onClick = { onAction(CsvImportAction.Resume) }, enabled = !state.busy, modifier = Modifier.fillMaxWidth()) { Text(stringResource(R.string.csv_import_retry)) }
                Button(onClick = { confirmFinish = true }, enabled = !state.busy, modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp).testTag("csv_import_finish")) { Text(stringResource(R.string.csv_import_finish)) }
            }
        }
        if (state.phase in setOf(CsvImportPhase.ATTENTION, CsvImportPhase.IMPORTING, CsvImportPhase.RESTORED, CsvImportPhase.COMPLETE)) state.report?.let { report -> item {
            val summary = report.summary
            Column(Modifier.testTag("csv_import_summary"), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(stringResource(R.string.csv_import_summary), style = MaterialTheme.typography.titleLarge, modifier = Modifier.semantics { heading() })
                Text(stringResource(R.string.csv_import_summary_counts, summary.added, summary.repairedAndAdded, summary.duplicates, summary.discarded,
                    report.rows.count { it.status in attentionStatuses }))
            }
        } }
    }
    if (confirmFinish) AlertDialog(onDismissRequest = { if (!state.busy) confirmFinish = false }, title = { Text(stringResource(R.string.csv_import_finish_confirm)) }, text = { Text(stringResource(R.string.csv_import_finish_help)) },
        confirmButton = { TextButton(onClick = { confirmFinish = false; onAction(CsvImportAction.Finish) }, enabled = !state.busy, modifier = Modifier.testTag("csv_import_confirm_finish")) { Text(stringResource(R.string.csv_import_finish)) } },
        dismissButton = { TextButton(onClick = { confirmFinish = false }, enabled = !state.busy) { Text(stringResource(R.string.csv_import_cancel)) } })
    editing?.let { number ->
        val row = state.report?.rows?.firstOrNull { it.source.rowNumber == number && it.status in attentionStatuses }
        if (row == null) LaunchedEffect(number) { editing = null }
        else RowEditor(state, row, onAction) { editing = null }
    }
}

private val attentionStatuses = setOf(CsvImportRowStatus.INVALID, CsvImportRowStatus.PENDING, CsvImportRowStatus.SERVER_ERROR)
private val mappedColumns = listOf(CsvColumn.DATE, CsvColumn.AMOUNT, CsvColumn.TYPE, CsvColumn.TITLE, CsvColumn.CATEGORY, CsvColumn.SUBCATEGORY, CsvColumn.TAGS)

@Composable
private fun OriginalCells(headers: List<String>, cells: List<String>) {
    Text(stringResource(R.string.csv_import_original), style = MaterialTheme.typography.labelLarge)
    cells.forEachIndexed { index, value -> Text(stringResource(R.string.csv_import_cell, headers.getOrNull(index) ?: stringResource(R.string.csv_import_column, index + 1, ""), value.ifEmpty { stringResource(R.string.csv_import_blank) })) }
}

@Composable
private fun RowEditor(state: CsvImportUiState, row: CsvImportRowResult, onAction: (CsvImportAction) -> Unit, close: () -> Unit) {
    val number = row.source.rowNumber
    var batch by rememberSaveable(number) { mutableStateOf(false) }
    var categoryName by rememberSaveable(number) { mutableStateOf("") }
    var subcategoryName by rememberSaveable(number) { mutableStateOf("") }
    // Raw CSV data stays out of saved instance state; the retained controller owns durable drafts.
    var cells by remember(number) { mutableStateOf(state.drafts[number] ?: row.source.cells) }
    val parent = state.selectedCategories[number] ?: row.entry?.categoryId ?: state.categories.singleOrNull { it.name.trim().equals(cells.getOrNull(state.mapping.category ?: -1)?.trim(), ignoreCase = true) }?.id
    Dialog(onDismissRequest = { if (!state.busy) close() }, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        Surface(Modifier.fillMaxSize().padding(12.dp), shape = MaterialTheme.shapes.large) {
            Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp).testTag("csv_import_editor"), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Text(stringResource(R.string.csv_import_row, number), style = MaterialTheme.typography.headlineSmall)
                if (state.busy) {
                    LinearProgressIndicator(Modifier.fillMaxWidth())
                    Text(stringResource(R.string.csv_import_busy), modifier = Modifier.semantics { liveRegion = LiveRegionMode.Polite })
                }
                OriginalCells(state.headers, state.report?.originalRows?.firstOrNull { it.rowNumber == number }?.cells ?: row.source.cells)
                row.source.issues.filter { it.field == null }.forEach { IssueText(it) }
                state.headers.forEachIndexed { index, header ->
                    val field = mappedColumns.firstOrNull { state.mapping.index(it) == index }
                    // Unmapped columns remain visible as source data but cannot accidentally acquire meaning.
                    if (field != null) OutlinedTextField(value = cells.getOrNull(index).orEmpty(), onValueChange = { value ->
                        val updated = cells.toMutableList(); while (updated.size < state.headers.size) updated.add(""); updated[index] = value
                        cells = updated
                        onAction(CsvImportAction.Edit(number, updated))
                    }, enabled = !state.busy, label = { Text(stringResource(fieldLabel(field))) }, isError = row.source.issues.any { it.field.equals(field.name, ignoreCase = true) }, supportingText = {
                        Column { Text(header); row.source.issues.filter { it.field.equals(field.name, ignoreCase = true) }.forEach { IssueText(it) } }
                    }, modifier = Modifier.fillMaxWidth().testTag("csv_import_edit_field_$index"))
                }
                Row { Checkbox(batch, { batch = it }, enabled = !state.busy, modifier = Modifier.testTag("csv_import_batch_mapping")); Text(stringResource(R.string.csv_import_batch), modifier = Modifier.padding(top = 12.dp)) }
                Choice(stringResource(R.string.csv_import_category), state.categories.firstOrNull { it.id == parent }?.name ?: stringResource(R.string.csv_import_existing), state.categories.filter { !it.archived }.map { it.id to it.name }, !state.busy, "csv_import_category") { onAction(CsvImportAction.Category(number, it, batch)) }
                Choice(stringResource(R.string.csv_import_subcategory), (state.selectedSubcategories[number] ?: row.entry?.subcategoryId)?.let { id -> state.subcategories.firstOrNull { it.id == id }?.name } ?: if (state.selectedSubcategories.containsKey(number)) stringResource(R.string.csv_import_no_subcategory) else stringResource(R.string.csv_import_existing), listOf(null to stringResource(R.string.csv_import_no_subcategory)) + state.subcategories.filter { !it.archived && it.categoryId == parent }.map { it.id to it.name }, !state.busy && parent != null, "csv_import_subcategory") { onAction(CsvImportAction.Subcategory(number, it, batch)) }
                OutlinedTextField(categoryName, { categoryName = it }, enabled = !state.busy, label = { Text(stringResource(R.string.csv_import_new_category_name)) }, modifier = Modifier.fillMaxWidth().testTag("csv_import_new_category_name"))
                OutlinedButton(onClick = { onAction(CsvImportAction.NewCategory(number, categoryName, batch)) }, enabled = !state.busy && categoryName.isNotBlank() && categoryName.trim().length <= 60, modifier = Modifier.fillMaxWidth().testTag("csv_import_create_category")) { Text(stringResource(R.string.csv_import_create_category)) }
                OutlinedTextField(subcategoryName, { subcategoryName = it }, enabled = !state.busy && parent != null, label = { Text(stringResource(R.string.csv_import_new_subcategory_name)) }, modifier = Modifier.fillMaxWidth().testTag("csv_import_new_subcategory_name"))
                OutlinedButton(onClick = { parent?.let { onAction(CsvImportAction.NewSubcategory(number, it, subcategoryName, batch)) } }, enabled = !state.busy && parent != null && subcategoryName.isNotBlank() && subcategoryName.trim().length <= 60, modifier = Modifier.fillMaxWidth().testTag("csv_import_create_subcategory")) { Text(stringResource(R.string.csv_import_create_subcategory)) }
                if (state.errorCode != null) Text(stringResource(R.string.csv_import_error_generic), color = MaterialTheme.colorScheme.error)
                Button(onClick = { onAction(CsvImportAction.Validate(number)) }, enabled = !state.busy, modifier = Modifier.fillMaxWidth().testTag("csv_import_revalidate")) { Text(stringResource(R.string.csv_import_revalidate)) }
                Button(onClick = { onAction(CsvImportAction.Save(number)) }, enabled = !state.busy, modifier = Modifier.fillMaxWidth().testTag("csv_import_save_repair")) { Text(stringResource(R.string.csv_import_save)) }
                TextButton(onClick = close, enabled = !state.busy, modifier = Modifier.fillMaxWidth()) { Text(stringResource(R.string.csv_import_close)) }
            }
        }
    }
}

@Composable
private fun <T> Choice(label: String, value: String, options: List<Pair<T, String>>, enabled: Boolean, tag: String, select: (T) -> Unit) {
    var expanded by remember { mutableStateOf(false) }
    val accessibleLabel = stringResource(R.string.csv_import_cell, label, value)
    Column {
        Text(label, style = MaterialTheme.typography.labelLarge)
        Box {
            OutlinedButton(onClick = { expanded = true }, enabled = enabled, modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp).testTag(tag).semantics { contentDescription = accessibleLabel }) { Text(value) }
            DropdownMenu(expanded = expanded && enabled, onDismissRequest = { expanded = false }, modifier = Modifier.heightIn(max = 360.dp)) {
                options.forEach { (id, title) -> DropdownMenuItem(text = { Text(title) }, onClick = { expanded = false; select(id) }, modifier = Modifier.heightIn(min = 48.dp)) }
            }
        }
    }
}

private fun CsvColumnMapping.index(field: CsvColumn): Int? = when (field) { CsvColumn.DATE -> date; CsvColumn.AMOUNT -> amount; CsvColumn.TYPE -> type; CsvColumn.TITLE -> title; CsvColumn.CATEGORY -> category; CsvColumn.SUBCATEGORY -> subcategory; CsvColumn.TAGS -> tags; CsvColumn.AUTHOR -> null }
private fun CsvColumnMapping.withIndex(field: CsvColumn, index: Int?) = when (field) { CsvColumn.DATE -> copy(date = index); CsvColumn.AMOUNT -> copy(amount = index); CsvColumn.TYPE -> copy(type = index); CsvColumn.TITLE -> copy(title = index); CsvColumn.CATEGORY -> copy(category = index); CsvColumn.SUBCATEGORY -> copy(subcategory = index); CsvColumn.TAGS -> copy(tags = index); CsvColumn.AUTHOR -> this }
private fun fieldLabel(field: CsvColumn): Int = when (field) { CsvColumn.DATE -> R.string.csv_import_date; CsvColumn.AMOUNT -> R.string.csv_import_amount; CsvColumn.TYPE -> R.string.csv_import_type; CsvColumn.TITLE -> R.string.csv_import_title_field; CsvColumn.CATEGORY -> R.string.csv_import_category; CsvColumn.SUBCATEGORY -> R.string.csv_import_subcategory; CsvColumn.TAGS -> R.string.csv_import_tags; CsvColumn.AUTHOR -> R.string.csv_import_author }
private fun dateFormatLabel(format: CsvDateFormat): Int = when (format) { CsvDateFormat.AUTO -> R.string.csv_import_date_auto; CsvDateFormat.DAY_MONTH_YEAR -> R.string.csv_import_date_dmy; CsvDateFormat.MONTH_DAY_YEAR -> R.string.csv_import_date_mdy }

@Composable
private fun IssueText(issue: ValidationIssue) {
    val message = when (issue.code.removePrefix("IMPORT_")) {
        "REQUIRED_VALUE" -> R.string.csv_import_error_required
        "INVALID_DATE" -> R.string.csv_import_error_date
        "FUTURE_DATE" -> R.string.csv_import_error_future
        "AMBIGUOUS_DATE" -> R.string.csv_import_error_ambiguous_date
        "INVALID_AMOUNT" -> R.string.csv_import_error_amount
        "ZERO_AMOUNT" -> R.string.csv_import_error_zero
        "INVALID_TYPE" -> R.string.csv_import_error_type
        "AMOUNT_TYPE_CONFLICT" -> R.string.csv_import_error_conflict
        "INVALID_TAGS" -> R.string.csv_import_error_tags
        "TOO_MANY_TAGS" -> R.string.csv_import_error_tags_many
        "DUPLICATE_TAGS" -> R.string.csv_import_error_tags_duplicate
        "FIELD_TOO_LONG" -> when(issue.field) { "title" -> R.string.csv_import_error_title_long; "category", "subcategory" -> R.string.csv_import_error_name_long; "tags" -> R.string.csv_import_error_tag_long; else -> R.string.csv_import_error_long }
        "COLUMN_COUNT_MISMATCH" -> R.string.csv_import_error_width
        "UNKNOWN_CATEGORY", "AMBIGUOUS_CATEGORY", "ARCHIVED_CATEGORY" -> R.string.csv_import_error_category
        "UNKNOWN_SUBCATEGORY", "AMBIGUOUS_SUBCATEGORY", "ARCHIVED_SUBCATEGORY" -> R.string.csv_import_error_subcategory
        else -> R.string.csv_import_error_generic
    }
    val field = CsvColumn.entries.firstOrNull { it.name.equals(issue.field, ignoreCase = true) }
    val errorMessage = if (field == null) stringResource(message) else stringResource(R.string.csv_import_error_field, stringResource(fieldLabel(field)), stringResource(message))
    Text(errorMessage, color = MaterialTheme.colorScheme.error, modifier = Modifier.testTag("csv_import_issue_${issue.rowNumber}_${issue.field}_${issue.code}").semantics { error(errorMessage) })
}

private fun operationErrorLabel(code: String): Int = when(code) {
    "MALFORMED_UTF8" -> R.string.csv_import_error_encoding
    "MALFORMED_CSV" -> R.string.csv_import_error_csv
    "AMBIGUOUS_DELIMITER" -> R.string.csv_import_error_delimiter
    "LIMIT_EXCEEDED" -> R.string.csv_import_error_limit
    else -> R.string.csv_import_error_generic
}
