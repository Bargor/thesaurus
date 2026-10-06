package pl.bargor.thesaurus.ui.taxonomy

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.Switch
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import pl.bargor.thesaurus.R
import pl.bargor.thesaurus.data.model.Category
import pl.bargor.thesaurus.data.model.CategoryPalette
import pl.bargor.thesaurus.data.model.EntryType
import pl.bargor.thesaurus.data.model.Subcategory
import pl.bargor.thesaurus.data.model.SyncState
import pl.bargor.thesaurus.ui.settings.SettingsAction

private sealed interface TaxonomyDialog {
    data object AddCategory : TaxonomyDialog
    data class EditCategory(val category: Category) : TaxonomyDialog
    data class AddSubcategory(val category: Category) : TaxonomyDialog
    data class EditSubcategory(val subcategory: Subcategory) : TaxonomyDialog
}

@Composable
fun TaxonomyScreen(
    state: TaxonomyUiState,
    onMutation: (TaxonomyMutation) -> Unit,
    onMoveCategory: (String, String) -> Unit = { _, _ -> },
    onReorderCategories: (List<String>) -> Unit = {},
    onBack: (() -> Unit)? = null,
    modifier: Modifier = Modifier,
) {
    var showArchived by rememberSaveable { mutableStateOf(false) }
    var dialog by remember { mutableStateOf<TaxonomyDialog?>(null) }
    val dragState = remember { TaxonomyDragState() }
    val nodesById = state.categories.associateBy { it.category.id }
    val orderedNodes = dragState.previewOrderIds?.mapNotNull(nodesById::get) ?: state.categories
    val categories = orderedNodes.filter { showArchived || !it.category.archived }
    val activeIds = orderedNodes.filterNot { it.category.archived }.map { it.category.id }
    val listState = rememberLazyListState()

    Column(modifier = modifier.padding(8.dp)) {
        Row(modifier = Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Text(
                text = stringResource(R.string.taxonomy_title),
                style = MaterialTheme.typography.headlineSmall,
                modifier = Modifier.weight(1f).semantics { heading() },
            )
            onBack?.let { back ->
                TextButton(modifier = Modifier.testTag("taxonomy-back"), onClick = back) {
                    Text(stringResource(R.string.taxonomy_back))
                }
            }
            SettingsAction()
        }
        Row(
            modifier = Modifier.fillMaxWidth().padding(top = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(stringResource(R.string.taxonomy_show_archived), modifier = Modifier.weight(1f))
            Switch(
                modifier = Modifier.testTag("taxonomy-show-archived"),
                checked = showArchived,
                onCheckedChange = { showArchived = it },
            )
        }
        SyncMessage(state)
        Button(
            modifier = Modifier.padding(top = 8.dp).testTag("taxonomy-add-category"),
            onClick = { dialog = TaxonomyDialog.AddCategory },
            enabled = !state.saving,
        ) { Text(stringResource(R.string.taxonomy_add_category)) }

        when {
            state.isLoading -> CircularProgressIndicator(
                modifier = Modifier.padding(top = 24.dp).testTag("taxonomy-loading"),
            )
            categories.isEmpty() -> Text(
                text = stringResource(R.string.taxonomy_empty),
                modifier = Modifier.padding(top = 24.dp),
            )
            else -> LazyColumn(
                state = listState,
                modifier = Modifier.padding(top = 8.dp).testTag("taxonomy-list"),
                verticalArrangement = Arrangement.spacedBy(4.dp),
            ) {
                items(categories, key = { it.category.id }) { node ->
                    val activeIndex = activeIds.indexOf(node.category.id)
                    CategoryCard(
                        modifier = Modifier.animateItem(),
                        node = node,
                        showArchived = showArchived,
                        enabled = !state.saving && !state.reordering,
                        dragging = dragState.draggedId == node.category.id,
                        dragTranslationY = if (dragState.draggedId == node.category.id) dragState.dragDistance else 0f,
                        dropTarget = dragState.targetId == node.category.id && dragState.draggedId != node.category.id,
                        canMoveUp = activeIndex > 0,
                        canMoveDown = activeIndex >= 0 && activeIndex < activeIds.lastIndex,
                        onMoveUp = { onMoveCategory(node.category.id, activeIds[activeIndex - 1]) },
                        onMoveDown = { onMoveCategory(node.category.id, activeIds[activeIndex + 1]) },
                        onDragStart = { dragState.start(node.category.id, state.categories) },
                        onDrag = { delta -> dragState.drag(delta, state.categories, listState) },
                        onDragEnd = { dragState.end(true, state.categories, onReorderCategories) },
                        onDragCancel = { dragState.end(false, state.categories, onReorderCategories) },
                        onEdit = { dialog = TaxonomyDialog.EditCategory(node.category) },
                        onAddSubcategory = { dialog = TaxonomyDialog.AddSubcategory(node.category) },
                        onArchive = { archived ->
                            onMutation(TaxonomyMutation.SetCategoryArchived(node.category, archived))
                        },
                        onEditSubcategory = { dialog = TaxonomyDialog.EditSubcategory(it) },
                        onArchiveSubcategory = { subcategory, archived ->
                            onMutation(TaxonomyMutation.SetSubcategoryArchived(subcategory, archived))
                        },
                    )
                }
            }
        }
    }

    when (val current = dialog) {
        TaxonomyDialog.AddCategory -> TaxonomyEditorDialog(
            title = stringResource(R.string.taxonomy_add_category),
            initialName = "",
            initialType = EntryType.EXPENSE,
            showDirection = true,
            initialColor = CategoryPalette.defaultToken,
            onDismiss = { dialog = null },
            onConfirm = { name, type, color ->
                onMutation(TaxonomyMutation.AddCategory(name, type, color))
                dialog = null
            },
        )
        is TaxonomyDialog.EditCategory -> TaxonomyEditorDialog(
            title = stringResource(R.string.taxonomy_edit_category),
            initialName = current.category.name,
            initialType = current.category.defaultEntryType,
            showDirection = true,
            initialColor = CategoryPalette.forCategory(current.category).token,
            onDismiss = { dialog = null },
            onConfirm = { name, type, color ->
                onMutation(TaxonomyMutation.EditCategory(current.category, name, type, color))
                dialog = null
            },
        )
        is TaxonomyDialog.AddSubcategory -> TaxonomyEditorDialog(
            title = stringResource(R.string.taxonomy_add_subcategory),
            initialName = "",
            initialType = EntryType.EXPENSE,
            showDirection = false,
            onDismiss = { dialog = null },
            onConfirm = { name, _, _ ->
                onMutation(TaxonomyMutation.AddSubcategory(current.category, name))
                dialog = null
            },
        )
        is TaxonomyDialog.EditSubcategory -> TaxonomyEditorDialog(
            title = stringResource(R.string.taxonomy_edit_subcategory),
            initialName = current.subcategory.name,
            initialType = EntryType.EXPENSE,
            showDirection = false,
            onDismiss = { dialog = null },
            onConfirm = { name, _, _ ->
                onMutation(TaxonomyMutation.EditSubcategory(current.subcategory, name))
                dialog = null
            },
        )
        null -> Unit
    }
}

@Composable
private fun SyncMessage(state: TaxonomyUiState) {
    val text = when {
        state.error == TaxonomyError.InvalidName -> stringResource(R.string.taxonomy_name_error)
        state.error == TaxonomyError.InvalidColor -> stringResource(R.string.taxonomy_color_error)
        state.error == TaxonomyError.ArchivedParent -> stringResource(R.string.taxonomy_archived_parent_error)
        state.error == TaxonomyError.SaveFailed -> stringResource(R.string.taxonomy_save_error)
        state.saving || state.reordering || state.syncState == SyncState.PENDING -> stringResource(R.string.taxonomy_sync_pending)
        else -> null
    }
    text?.let { Text(it, modifier = Modifier.padding(top = 8.dp).testTag("taxonomy-status")) }
}
