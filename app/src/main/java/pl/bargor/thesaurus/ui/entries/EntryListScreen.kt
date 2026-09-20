package pl.bargor.thesaurus.ui.entries

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.clickable
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.Alignment
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import java.text.NumberFormat
import java.math.BigDecimal
import java.time.format.DateTimeFormatter
import java.util.Locale
import pl.bargor.thesaurus.R
import pl.bargor.thesaurus.data.model.SyncState

private val PolishDateFormatter: DateTimeFormatter =
    DateTimeFormatter.ofPattern("d MMMM uuuu", Locale.forLanguageTag("pl-PL"))

@Composable
fun EntryListScreen(
    state: EntryListUiState,
    onChangeSort: (EntryListSort) -> Unit,
    onLoadNextPage: () -> Unit,
    onRetry: () -> Unit,
    onOpenSettings: () -> Unit,
    onAddEntry: () -> Unit,
    onOpenFamily: () -> Unit = {},
    onEditEntry: (String) -> Unit = {},
    onConfirmDelete: (EntryListItem) -> Unit = {},
    onUndoDelete: () -> Unit = {},
    modifier: Modifier = Modifier,
) {
    var deleteCandidate by remember { mutableStateOf<EntryListItem?>(null) }
    val snackbarHostState = remember { SnackbarHostState() }
    val deletedLabel = stringResource(R.string.entries_deleted)
    val unnamedDeletedItem = stringResource(R.string.entries_deleted_item)
    val undoLabel = stringResource(R.string.entries_undo)
    LaunchedEffect(state.pendingDeletion?.entry?.id) {
        val pending = state.pendingDeletion ?: return@LaunchedEffect
        val result = snackbarHostState.showSnackbar(
            message = "${pending.entry.normalizedTitle ?: unnamedDeletedItem} $deletedLabel",
            actionLabel = undoLabel,
            withDismissAction = true,
            duration = androidx.compose.material3.SnackbarDuration.Short,
        )
        if (result == androidx.compose.material3.SnackbarResult.ActionPerformed) onUndoDelete()
    }
    Box(modifier = modifier.fillMaxSize()) {
        Column(modifier = Modifier.fillMaxSize()) {
            Row(modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 12.dp)) {
                Text(
                    modifier = Modifier.weight(1f).semantics { heading() },
                    text = stringResource(R.string.navigation_entries),
                    style = MaterialTheme.typography.headlineSmall,
                )
                TextButton(onClick = onOpenSettings, modifier = Modifier.testTag("open-taxonomy-settings")) {
                    Text(stringResource(R.string.open_taxonomy_settings))
                }
                TextButton(onClick = onOpenFamily, modifier = Modifier.testTag("open-family")) {
                    Text(stringResource(R.string.open_family))
                }
            }
            SortSelector(state.sort, onChangeSort)
            EntryListSyncState(state.syncState)
            if (state.deletionError) Text(
                modifier = Modifier.padding(horizontal = 16.dp),
                text = stringResource(R.string.entries_delete_error),
                color = MaterialTheme.colorScheme.error,
            )
            when {
                state.isLoading -> CircularProgressIndicator(
                    modifier = Modifier.padding(24.dp).testTag("entries-loading"),
                )
                state.error != null && state.entries.isEmpty() -> ErrorContent(onRetry)
                state.entries.isEmpty() -> EmptyContent(onAddEntry)
                else -> LazyColumn(
                    modifier = Modifier.fillMaxSize().testTag("entries-list"),
                    contentPadding = PaddingValues(16.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    if (state.error != null) item { ErrorContent(onRetry) }
                    items(state.visibleEntries, key = { it.entry.id }) { item ->
                        EntryCard(item, onEditEntry, { deleteCandidate = item })
                    }
                    if (state.hasMore) item {
                        Button(
                            modifier = Modifier.fillMaxWidth().testTag("entries-load-more"),
                            onClick = onLoadNextPage,
                        ) { Text(stringResource(R.string.entries_load_more)) }
                    }
                    item {
                        Button(
                            modifier = Modifier.fillMaxWidth().testTag("add-entry"),
                            onClick = onAddEntry,
                        ) { Text(stringResource(R.string.entry_add)) }
                    }
                }
            }
        }
        SnackbarHost(
            hostState = snackbarHostState,
            modifier = Modifier.align(Alignment.BottomCenter),
        )
    }
    deleteCandidate?.let { item ->
        AlertDialog(
            onDismissRequest = { deleteCandidate = null },
            title = { Text(stringResource(R.string.entries_delete_title)) },
            text = { Text(stringResource(R.string.entries_delete_message)) },
            confirmButton = {
                TextButton(onClick = {
                    deleteCandidate = null
                    onConfirmDelete(item)
                }) { Text(stringResource(R.string.entries_delete_confirm)) }
            },
            dismissButton = {
                TextButton(onClick = { deleteCandidate = null }) { Text(stringResource(R.string.entries_cancel)) }
            },
        )
    }
}

@Composable
private fun SortSelector(selected: EntryListSort, onChangeSort: (EntryListSort) -> Unit) {
    Row(
        modifier = Modifier.padding(horizontal = 16.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        FilterChip(
            modifier = Modifier.testTag("entries-sort-date"),
            selected = selected == EntryListSort.ACCOUNTING_DATE,
            onClick = { onChangeSort(EntryListSort.ACCOUNTING_DATE) },
            label = { Text(stringResource(R.string.entries_sort_date)) },
        )
        FilterChip(
            modifier = Modifier.testTag("entries-sort-created"),
            selected = selected == EntryListSort.CREATION_ORDER,
            onClick = { onChangeSort(EntryListSort.CREATION_ORDER) },
            label = { Text(stringResource(R.string.entries_sort_created)) },
        )
    }
}

@Composable
private fun EntryListSyncState(syncState: SyncState) {
    val label = when (syncState) {
        SyncState.PENDING -> R.string.entries_sync_pending
        SyncState.OFFLINE -> R.string.entries_offline
        else -> null
    } ?: return
    Text(modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp), text = stringResource(label))
}

@Composable
private fun EmptyContent(onAddEntry: () -> Unit) {
    Column(modifier = Modifier.padding(24.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Text(stringResource(R.string.empty_entries))
        Button(modifier = Modifier.testTag("add-entry"), onClick = onAddEntry) {
            Text(stringResource(R.string.entry_add))
        }
    }
}

@Composable
private fun ErrorContent(onRetry: () -> Unit) {
    Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text(stringResource(R.string.entries_load_error), color = MaterialTheme.colorScheme.error)
        Button(modifier = Modifier.testTag("entries-retry"), onClick = onRetry) {
            Text(stringResource(R.string.auth_retry))
        }
    }
}

@Composable
private fun EntryCard(
    item: EntryListItem,
    onEditEntry: (String) -> Unit,
    onDeleteEntry: () -> Unit,
) {
    val entry = item.entry
    val amountColor = if (entry.amountGrosze > 0) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.error
    val taxonomy = listOfNotNull(item.categoryName ?: stringResource(R.string.entries_unknown_category), item.subcategoryName)
        .joinToString(" › ")
    val cardModifier = Modifier
        .fillMaxWidth()
        .testTag("entry-${entry.id}")
        .let { base ->
            if (item.canManage) base.clickable { onEditEntry(entry.id) } else base
        }
    Card(
        modifier = cardModifier,
    ) {
        Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Row(modifier = Modifier.fillMaxWidth()) {
                Text(text = taxonomy, modifier = Modifier.weight(1f), fontWeight = FontWeight.SemiBold)
                Text(
                    text = entry.amountGrosze.toPolishCurrency(),
                    color = amountColor,
                    fontWeight = FontWeight.Bold,
                    modifier = Modifier.testTag(if (entry.amountGrosze > 0) "entry-income-${entry.id}" else "entry-expense-${entry.id}"),
                )
            }
            entry.normalizedTitle?.let { Text(it) }
            Text(stringResource(R.string.entries_author_and_date, item.authorName, entry.date.format(PolishDateFormatter)))
            if (entry.normalizedTags.isNotEmpty()) Text(entry.normalizedTags.joinToString(" ") { "#$it" })
            if (item.canManage) {
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    TextButton(
                        modifier = Modifier.testTag("entry-edit-${entry.id}"),
                        onClick = { onEditEntry(entry.id) },
                    ) { Text(stringResource(R.string.entries_edit)) }
                    TextButton(
                        modifier = Modifier.testTag("entry-delete-${entry.id}"),
                        onClick = onDeleteEntry,
                    ) { Text(stringResource(R.string.entries_delete)) }
                }
            }
        }
    }
}

private fun Long.toPolishCurrency(): String = NumberFormat.getCurrencyInstance(Locale.forLanguageTag("pl-PL"))
    .format(BigDecimal.valueOf(this, 2))
