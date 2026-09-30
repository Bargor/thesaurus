package pl.bargor.thesaurus.ui.taxonomy

import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.clickable
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.gestures.detectDragGesturesAfterLongPress
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.KeyboardArrowUp
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.Switch
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.unit.dp
import androidx.compose.ui.zIndex
import kotlin.math.abs
import pl.bargor.thesaurus.R
import pl.bargor.thesaurus.data.model.Category
import pl.bargor.thesaurus.data.model.CategoryPalette
import pl.bargor.thesaurus.data.model.EntryType
import pl.bargor.thesaurus.data.model.Subcategory
import pl.bargor.thesaurus.data.model.SyncState
import pl.bargor.thesaurus.data.model.moveActiveCategory
import pl.bargor.thesaurus.ui.accentColor
import pl.bargor.thesaurus.ui.asColor
import pl.bargor.thesaurus.ui.contrastingContent
import pl.bargor.thesaurus.ui.nameRes

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
    var showArchived by remember { mutableStateOf(false) }
    var dialog by remember { mutableStateOf<TaxonomyDialog?>(null) }
    var previewOrderIds by remember { mutableStateOf<List<String>?>(null) }
    val nodesById = state.categories.associateBy { it.category.id }
    val orderedNodes = previewOrderIds?.mapNotNull(nodesById::get) ?: state.categories
    val categories = orderedNodes.filter { showArchived || !it.category.archived }
    val activeIds = orderedNodes.filterNot { it.category.archived }.map { it.category.id }
    val listState = rememberLazyListState()
    var draggedId by remember { mutableStateOf<String?>(null) }
    var targetId by remember { mutableStateOf<String?>(null) }
    var dragDistance by remember { mutableStateOf(0f) }

    fun endDrag(commit: Boolean) {
        val dragged = draggedId
        val preview = previewOrderIds
        val currentIds = state.categories.map { it.category.id }
        if (commit && dragged != null && preview != null && preview != currentIds) {
            onReorderCategories(preview)
        }
        previewOrderIds = null
        draggedId = null
        targetId = null
        dragDistance = 0f
    }

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
                        dragging = draggedId == node.category.id,
                        dragTranslationY = if (draggedId == node.category.id) dragDistance else 0f,
                        dropTarget = targetId == node.category.id && draggedId != node.category.id,
                        canMoveUp = activeIndex > 0,
                        canMoveDown = activeIndex >= 0 && activeIndex < activeIds.lastIndex,
                        onMoveUp = { onMoveCategory(node.category.id, activeIds[activeIndex - 1]) },
                        onMoveDown = { onMoveCategory(node.category.id, activeIds[activeIndex + 1]) },
                        onDragStart = {
                            draggedId = node.category.id
                            targetId = node.category.id
                            previewOrderIds = state.categories.map { it.category.id }
                            dragDistance = 0f
                        },
                        onDrag = { delta ->
                            dragDistance += delta
                            val visibleItems = listState.layoutInfo.visibleItemsInfo
                            val draggedItem = visibleItems.firstOrNull { it.key == draggedId }
                            val center = draggedItem?.let {
                                it.offset + it.size / 2f + dragDistance
                            }
                            val targetItem = center?.let { draggedCenter ->
                                visibleItems
                                .filter { it.key in activeIds }
                                    .minByOrNull { abs(draggedCenter - (it.offset + it.size / 2f)) }
                            }
                            val nearestId = targetItem?.key as? String
                            if (nearestId != null) {
                                targetId = nearestId
                                val dragged = draggedId
                                if (dragged != null && nearestId != dragged) {
                                    val currentIds = previewOrderIds ?: state.categories.map { it.category.id }
                                    val previewNodes = currentIds.mapNotNull(nodesById::get)
                                    previewOrderIds = previewNodes
                                        .map(CategoryWithSubcategories::category)
                                        .moveActiveCategory(dragged, nearestId)
                                        .map(Category::id)
                                    dragDistance += draggedItem.offset - targetItem.offset
                                }
                            }
                        },
                        onDragEnd = { endDrag(commit = true) },
                        onDragCancel = { endDrag(commit = false) },
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

@Composable
private fun CategoryCard(
    node: CategoryWithSubcategories,
    showArchived: Boolean,
    enabled: Boolean,
    dragging: Boolean,
    dragTranslationY: Float,
    dropTarget: Boolean,
    canMoveUp: Boolean,
    canMoveDown: Boolean,
    onMoveUp: () -> Unit,
    onMoveDown: () -> Unit,
    onDragStart: () -> Unit,
    onDrag: (Float) -> Unit,
    onDragEnd: () -> Unit,
    onDragCancel: () -> Unit,
    onEdit: () -> Unit,
    onAddSubcategory: () -> Unit,
    onArchive: (Boolean) -> Unit,
    onEditSubcategory: (Subcategory) -> Unit,
    onArchiveSubcategory: (Subcategory, Boolean) -> Unit,
    modifier: Modifier = Modifier,
) {
    val category = node.category
    val subcategories = node.subcategories.filter { showArchived || !it.archived }
    var expanded by rememberSaveable(category.id) { mutableStateOf(false) }
    val expandDescription = stringResource(
        if (expanded) R.string.taxonomy_collapse_category else R.string.taxonomy_expand_category,
        category.name,
    )
    val expansionState = stringResource(
        if (expanded) R.string.taxonomy_expanded else R.string.taxonomy_collapsed,
    )
    val emptyMessage = stringResource(
        if (node.subcategories.isEmpty() || showArchived) R.string.taxonomy_no_subcategories
        else R.string.taxonomy_no_active_subcategories,
    )
    val editDescription = stringResource(R.string.taxonomy_edit_category_named, category.name)
    val addDescription = stringResource(R.string.taxonomy_add_subcategory_named, category.name)
    val archiveDescription = stringResource(
        if (category.archived) R.string.taxonomy_restore_category_named
        else R.string.taxonomy_archive_category_named,
        category.name,
    )
    val dragDescription = stringResource(R.string.taxonomy_drag_category, category.name)
    val draggingDescription = stringResource(R.string.taxonomy_dragging)
    val moveUpDescription = stringResource(R.string.taxonomy_move_up_named, category.name)
    val moveDownDescription = stringResource(R.string.taxonomy_move_down_named, category.name)
    val elevation by animateDpAsState(if (dragging) 12.dp else 1.dp, label = "category elevation")
    val scale by animateFloatAsState(if (dragging) 1.02f else 1f, label = "category scale")
    Card(
        modifier = modifier
            .fillMaxWidth()
            .testTag("taxonomy-category-${category.id}")
            .semantics {
                if (!category.archived) contentDescription = dragDescription
                if (dragging) stateDescription = draggingDescription
            }
            .pointerInput(category.id, enabled, category.archived) {
                if (enabled && !category.archived) detectDragGesturesAfterLongPress(
                    onDragStart = { onDragStart() },
                    onDragEnd = onDragEnd,
                    onDragCancel = onDragCancel,
                    onDrag = { change, amount ->
                        change.consume()
                        onDrag(amount.y)
                    },
                )
            }
            .graphicsLayer {
                scaleX = scale
                scaleY = scale
                translationY = dragTranslationY
            }
            .zIndex(if (dragging) 1f else 0f),
        elevation = CardDefaults.cardElevation(defaultElevation = elevation),
        border = when {
            dragging -> BorderStroke(3.dp, MaterialTheme.colorScheme.primary)
            dropTarget -> BorderStroke(2.dp, MaterialTheme.colorScheme.primary.copy(alpha = 0.45f))
            else -> null
        },
    ) {
        Column(modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp)) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(min = 48.dp)
                    .testTag("taxonomy-category-header-${category.id}")
                    .clickable { expanded = !expanded }
                    .semantics {
                        contentDescription = expandDescription
                        stateDescription = expansionState
                    },
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        Surface(shape = CircleShape, color = category.accentColor(), modifier = Modifier.size(16.dp)) {}
                        Text(category.name, style = MaterialTheme.typography.titleMedium, modifier = Modifier.weight(1f))
                    }
                    if (category.archived) {
                        Text(stringResource(R.string.taxonomy_archived), style = MaterialTheme.typography.labelMedium)
                    }
                }
                Icon(
                    imageVector = if (expanded) Icons.Filled.KeyboardArrowDown
                    else Icons.AutoMirrored.Filled.KeyboardArrowRight,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.primary,
                )
            }
            if (expanded) {
                FlowRow(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                    TextButton(
                        modifier = Modifier.heightIn(min = 48.dp).testTag("taxonomy-edit-category-${category.id}")
                            .semantics { contentDescription = editDescription },
                        onClick = onEdit,
                        enabled = enabled,
                    ) { Text(stringResource(R.string.taxonomy_edit)) }
                    TextButton(
                        modifier = Modifier.heightIn(min = 48.dp).testTag("taxonomy-add-subcategory-${category.id}")
                            .semantics { contentDescription = addDescription },
                        onClick = onAddSubcategory,
                        enabled = enabled && !category.archived,
                    ) {
                        Text(stringResource(R.string.taxonomy_add_subcategory))
                    }
                    TextButton(
                        modifier = Modifier.heightIn(min = 48.dp).testTag("taxonomy-archive-category-${category.id}")
                            .semantics { contentDescription = archiveDescription },
                        onClick = { onArchive(!category.archived) },
                        enabled = enabled,
                    ) {
                        Text(stringResource(if (category.archived) R.string.taxonomy_restore else R.string.taxonomy_archive))
                    }
                }
                Text(
                    text = stringResource(R.string.taxonomy_default_type),
                    style = MaterialTheme.typography.labelMedium,
                    modifier = Modifier.padding(top = 8.dp),
                )
                Text(
                    text = stringResource(
                        if (category.defaultEntryType == EntryType.INCOME) R.string.taxonomy_income
                        else R.string.taxonomy_expense,
                    ),
                    style = MaterialTheme.typography.bodyMedium,
                    modifier = Modifier.testTag("taxonomy-default-type-${category.id}"),
                )
                if (!category.archived) {
                    Text(
                        stringResource(R.string.taxonomy_category_order),
                        style = MaterialTheme.typography.labelMedium,
                        modifier = Modifier.padding(top = 8.dp).semantics { heading() },
                    )
                    FlowRow(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                        TextButton(
                            modifier = Modifier.heightIn(min = 48.dp).testTag("taxonomy-move-up-${category.id}")
                                .semantics { contentDescription = moveUpDescription },
                            enabled = enabled && canMoveUp,
                            onClick = onMoveUp,
                        ) {
                            Icon(Icons.Default.KeyboardArrowUp, contentDescription = null)
                            Text(stringResource(R.string.taxonomy_move_up))
                        }
                        TextButton(
                            modifier = Modifier.heightIn(min = 48.dp).testTag("taxonomy-move-down-${category.id}")
                                .semantics { contentDescription = moveDownDescription },
                            enabled = enabled && canMoveDown,
                            onClick = onMoveDown,
                        ) {
                            Icon(Icons.Default.KeyboardArrowDown, contentDescription = null)
                            Text(stringResource(R.string.taxonomy_move_down))
                        }
                    }
                }
                if (subcategories.isEmpty()) {
                    Text(emptyMessage, style = MaterialTheme.typography.labelMedium)
                } else {
                    subcategories.forEach { subcategory ->
                        SubcategoryRow(
                            subcategory = subcategory,
                            enabled = enabled,
                            onEdit = { onEditSubcategory(subcategory) },
                            onArchive = { onArchiveSubcategory(subcategory, !subcategory.archived) },
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun SubcategoryRow(
    subcategory: Subcategory,
    enabled: Boolean,
    onEdit: () -> Unit,
    onArchive: () -> Unit,
) {
    val editDescription = stringResource(R.string.taxonomy_edit_subcategory_named, subcategory.name)
    val archiveDescription = stringResource(
        if (subcategory.archived) R.string.taxonomy_restore_subcategory_named
        else R.string.taxonomy_archive_subcategory_named,
        subcategory.name,
    )
    val actions: @Composable () -> Unit = {
        TextButton(
            modifier = Modifier.heightIn(min = 48.dp).testTag("taxonomy-edit-subcategory-${subcategory.id}")
                .semantics { contentDescription = editDescription },
            onClick = onEdit,
            enabled = enabled,
        ) { Text(stringResource(R.string.taxonomy_edit)) }
        TextButton(
            modifier = Modifier.heightIn(min = 48.dp).testTag("taxonomy-archive-subcategory-${subcategory.id}")
                .semantics { contentDescription = archiveDescription },
            onClick = onArchive,
            enabled = enabled,
        ) {
            Text(stringResource(if (subcategory.archived) R.string.taxonomy_restore else R.string.taxonomy_archive))
        }
    }
    BoxWithConstraints(
        modifier = Modifier.fillMaxWidth().padding(start = 12.dp, top = 6.dp),
    ) {
        if (maxWidth < 320.dp || LocalDensity.current.fontScale > 1.3f) {
            Column {
                Text(subcategory.name, modifier = Modifier.testTag("taxonomy-subcategory-${subcategory.id}"))
                FlowRow(horizontalArrangement = Arrangement.spacedBy(4.dp)) { actions() }
            }
        } else {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(subcategory.name, modifier = Modifier.weight(1f).testTag("taxonomy-subcategory-${subcategory.id}"))
                actions()
            }
        }
    }
}

@Composable
private fun TaxonomyEditorDialog(
    title: String,
    initialName: String,
    initialType: EntryType,
    showDirection: Boolean,
    initialColor: String = CategoryPalette.defaultToken,
    onDismiss: () -> Unit,
    onConfirm: (String, EntryType, String) -> Unit,
) {
    var name by remember(title, initialName) { mutableStateOf(initialName) }
    var type by remember(title, initialType) { mutableStateOf(initialType) }
    var color by remember(title, initialColor) { mutableStateOf(initialColor) }
    val valid = TaxonomyValidation.nameOrNull(name) != null
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title) },
        text = {
            Column(modifier = Modifier.heightIn(max = 420.dp).verticalScroll(rememberScrollState())) {
                OutlinedTextField(
                    modifier = Modifier.fillMaxWidth().testTag("taxonomy-name"),
                    value = name,
                    onValueChange = { name = it },
                    isError = name.isNotBlank() && !valid,
                    label = { Text(stringResource(R.string.taxonomy_name)) },
                    supportingText = if (!valid) {
                        { Text(stringResource(R.string.taxonomy_name_error)) }
                    } else null,
                    singleLine = true,
                )
                if (showDirection) {
                    Text(stringResource(R.string.taxonomy_default_type), modifier = Modifier.padding(top = 12.dp))
                    Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        FilterChip(
                            selected = type == EntryType.EXPENSE,
                            onClick = { type = EntryType.EXPENSE },
                            label = { Text(stringResource(R.string.taxonomy_expense)) },
                        )
                        FilterChip(
                            selected = type == EntryType.INCOME,
                            onClick = { type = EntryType.INCOME },
                            label = { Text(stringResource(R.string.taxonomy_income)) },
                        )
                    }
                    Text(stringResource(R.string.taxonomy_color), modifier = Modifier.padding(top = 12.dp))
                    CategoryColorSelector(color, onSelected = { color = it })
                }
            }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.taxonomy_cancel)) } },
        confirmButton = {
            Button(
                modifier = Modifier.testTag("taxonomy-save"),
                enabled = valid,
                onClick = { onConfirm(name, type, color) },
            ) { Text(stringResource(R.string.taxonomy_save)) }
        },
    )
}

@Composable
private fun CategoryColorSelector(selectedToken: String, onSelected: (String) -> Unit) {
    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        CategoryPalette.swatches.chunked(5).forEach { row ->
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                row.forEach { swatch ->
                    val selected = selectedToken == swatch.token
                    val swatchColor = swatch.asColor()
                    val description = stringResource(
                        R.string.taxonomy_color_option,
                        stringResource(swatch.nameRes()),
                    )
                    val selection = stringResource(
                        if (selected) R.string.taxonomy_color_selected else R.string.taxonomy_color_unselected,
                    )
                    Surface(
                        onClick = { onSelected(swatch.token) },
                        modifier = Modifier.size(48.dp)
                            .testTag("taxonomy-color-${swatch.token}")
                            .semantics {
                                contentDescription = description
                                stateDescription = selection
                                this.selected = selected
                            },
                        shape = CircleShape,
                        color = swatchColor,
                        border = BorderStroke(if (selected) 3.dp else 1.dp, MaterialTheme.colorScheme.onSurface),
                    ) {
                        if (selected) {
                            Box(contentAlignment = Alignment.Center) {
                                Icon(
                                    imageVector = Icons.Default.Check,
                                    contentDescription = null,
                                    tint = swatchColor.contrastingContent(),
                                )
                            }
                        }
                    }
                }
            }
        }
    }
}
