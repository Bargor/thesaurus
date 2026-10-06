package pl.bargor.thesaurus.ui.taxonomy

import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.clickable
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.gestures.detectDragGesturesAfterLongPress
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.KeyboardArrowUp
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
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
import androidx.compose.ui.unit.dp
import androidx.compose.ui.zIndex
import pl.bargor.thesaurus.R
import pl.bargor.thesaurus.data.model.EntryType
import pl.bargor.thesaurus.data.model.Subcategory
import pl.bargor.thesaurus.ui.accentColor

@Composable
internal fun CategoryCard(
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
