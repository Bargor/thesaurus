package pl.bargor.thesaurus.ui.entry

import android.app.DatePickerDialog
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExposedDropdownMenuBox
import androidx.compose.material3.ExposedDropdownMenuDefaults
import androidx.compose.material3.ExposedDropdownMenuAnchorType
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import java.time.LocalDate
import java.time.format.DateTimeFormatter
import java.util.Calendar
import java.util.Locale
import pl.bargor.thesaurus.R
import pl.bargor.thesaurus.data.model.EntryType
import pl.bargor.thesaurus.data.model.SyncState
import pl.bargor.thesaurus.ui.accentColor

private val PolishDateFormatter: DateTimeFormatter =
    DateTimeFormatter.ofPattern("d MMMM uuuu", Locale.forLanguageTag("pl-PL"))

@Composable
fun EntryFormScreen(
    state: EntryFormUiState,
    onAmountChange: (String) -> Unit,
    onTitleChange: (String) -> Unit,
    onTagsChange: (String) -> Unit,
    onDateChange: (LocalDate) -> Unit,
    onTypeChange: (EntryType) -> Unit,
    onCategorySelected: (String) -> Unit,
    onSubcategorySelected: (String?) -> Unit,
    onSave: () -> Unit,
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val context = LocalContext.current
    val today = LocalDate.now()
    val editable = !state.saving && !state.saved
    Column(
        modifier = modifier
            .verticalScroll(rememberScrollState())
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        Row(modifier = Modifier.fillMaxWidth()) {
            Text(
                text = stringResource(if (state.editingEntryId == null) R.string.entry_add_title else R.string.entry_edit_title),
                style = MaterialTheme.typography.headlineSmall,
                modifier = Modifier.weight(1f).semantics { heading() },
            )
            TextButton(modifier = Modifier.testTag("entry-back"), onClick = onBack) {
                Text(stringResource(R.string.entry_back))
            }
        }
        EntrySyncState(state)
        if (state.isLoading) {
            CircularProgressIndicator(modifier = Modifier.testTag("entry-loading"))
            return@Column
        }
        OutlinedTextField(
            modifier = Modifier.fillMaxWidth().testTag("entry-amount"),
            value = state.amount,
            onValueChange = onAmountChange,
            enabled = editable,
            singleLine = true,
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
            label = { Text(stringResource(R.string.entry_amount)) },
            supportingText = { Text(stringResource(R.string.entry_amount_hint)) },
            isError = state.error == EntryFormError.InvalidAmount,
        )
        DirectionSelector(state.type, onTypeChange, editable)
        OutlinedTextField(
            modifier = Modifier.fillMaxWidth().testTag("entry-date"),
            value = state.date.format(PolishDateFormatter),
            onValueChange = {},
            enabled = editable,
            readOnly = true,
            singleLine = true,
            label = { Text(stringResource(R.string.entry_date)) },
            isError = state.error == EntryFormError.FutureDate,
            trailingIcon = {
                TextButton(
                    modifier = Modifier.testTag("entry-date-picker"),
                    enabled = editable,
                    onClick = {
                        DatePickerDialog(
                            context,
                            { _, year, month, day -> onDateChange(LocalDate.of(year, month + 1, day)) },
                            state.date.year,
                            state.date.monthValue - 1,
                            state.date.dayOfMonth,
                        ).apply {
                            // The platform picker returns calendar components. It never converts the chosen
                            // day through an instant, and maxDate prevents future values before validation.
                            datePicker.maxDate = Calendar.getInstance().apply {
                                clear()
                                set(today.year, today.monthValue - 1, today.dayOfMonth, 23, 59, 59)
                            }.timeInMillis
                        }.show()
                    },
                ) { Text(stringResource(R.string.entry_date_change)) }
            },
        )
        CategoryPicker(state, editable, onCategorySelected)
        SubcategoryPicker(state, editable, onSubcategorySelected)
        if (state.error == EntryFormError.CategoryRequired || state.error == EntryFormError.InactiveTaxonomy) {
            Text(stringResource(R.string.entry_category_error), color = MaterialTheme.colorScheme.error)
        }
        OutlinedTextField(
            modifier = Modifier.fillMaxWidth().testTag("entry-title"),
            value = state.title,
            onValueChange = onTitleChange,
            enabled = editable,
            label = { Text(stringResource(R.string.entry_optional_title)) },
            isError = state.error == EntryFormError.InvalidTitle,
        )
        OutlinedTextField(
            modifier = Modifier.fillMaxWidth().testTag("entry-tags"),
            value = state.tags,
            onValueChange = onTagsChange,
            enabled = editable,
            label = { Text(stringResource(R.string.entry_tags)) },
            supportingText = { Text(stringResource(R.string.entry_tags_hint)) },
            isError = state.error == EntryFormError.InvalidTags,
        )
        EntryError(state.error)
        Button(
            modifier = Modifier.fillMaxWidth().testTag("entry-save"),
            onClick = onSave,
            enabled = editable,
        ) {
            if (state.saving) CircularProgressIndicator() else Text(
                stringResource(if (state.editingEntryId == null) R.string.entry_save else R.string.entry_save_changes),
            )
        }
        if (state.saved && state.editingEntryId != null) {
            Text(stringResource(if (state.queuedOffline) R.string.entry_queued else R.string.entry_saved))
        }
        Spacer(Modifier.height(8.dp))
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun SubcategoryPicker(state: EntryFormUiState, editable: Boolean, onSelected: (String?) -> Unit) {
    val category = state.categories.firstOrNull { it.category.id == state.categoryId } ?: return
    // A category change discards its popup; only selected IDs belong to the restored draft.
    var expanded by remember(state.categoryId, editable) { mutableStateOf(false) }
    val focusManager = LocalFocusManager.current
    val subcategories = category.subcategories.filter { !it.archived || it.id == state.subcategoryId }
    val selectedSubcategory = subcategories.firstOrNull { it.id == state.subcategoryId }
    // Keep historical selections clearable even when there are no active replacements.
    val enabled = editable && subcategories.isNotEmpty()
    val menuExpanded = expanded && enabled
    val expansionState = stringResource(if (menuExpanded) R.string.entry_subcategory_expanded else R.string.entry_subcategory_collapsed)
    ExposedDropdownMenuBox(
        expanded = menuExpanded,
        onExpandedChange = {
            if (enabled) {
                focusManager.clearFocus()
                expanded = it
            }
        },
        modifier = Modifier.fillMaxWidth(),
    ) {
        OutlinedTextField(
            value = selectedSubcategory?.name ?: stringResource(R.string.entry_subcategory_none),
            onValueChange = {},
            readOnly = true,
            enabled = enabled,
            label = { Text(stringResource(R.string.entry_subcategory_optional)) },
            leadingIcon = { CategorySwatch(category.category.accentColor()) },
            trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(expanded = menuExpanded) },
            supportingText = if (selectedSubcategory?.archived == true || subcategories.none { !it.archived }) {
                {
                    Column {
                        if (selectedSubcategory?.archived == true) Text(stringResource(R.string.entry_subcategory_archived))
                        if (subcategories.none { !it.archived }) Text(stringResource(R.string.taxonomy_no_active_subcategories))
                    }
                }
            } else null,
            modifier = Modifier.fillMaxWidth()
                .menuAnchor(ExposedDropdownMenuAnchorType.PrimaryNotEditable, enabled = enabled)
                .testTag("entry-subcategory-picker")
                .semantics { stateDescription = expansionState },
        )
        ExposedDropdownMenu(
            expanded = menuExpanded,
            onDismissRequest = { expanded = false },
            modifier = Modifier.heightIn(max = 320.dp).testTag("entry-subcategory-menu"),
        ) {
            DropdownMenuItem(
                text = { Text(stringResource(R.string.entry_subcategory_none)) },
                trailingIcon = if (state.subcategoryId == null) {
                    { Icon(Icons.Default.Check, contentDescription = stringResource(R.string.entry_subcategory_selected_marker)) }
                } else null,
                enabled = editable,
                onClick = {
                    expanded = false
                    onSelected(null)
                },
                modifier = Modifier.testTag("entry-subcategory-none").semantics { selected = state.subcategoryId == null },
            )
            subcategories.forEach { subcategory ->
                val isSelected = state.subcategoryId == subcategory.id
                DropdownMenuItem(
                    text = {
                        Column {
                            Text(subcategory.name)
                            if (subcategory.archived) Text(stringResource(R.string.entry_subcategory_archived), style = MaterialTheme.typography.bodySmall)
                        }
                    },
                    trailingIcon = if (isSelected) {
                        { Icon(Icons.Default.Check, contentDescription = stringResource(R.string.entry_subcategory_selected_marker)) }
                    } else null,
                    enabled = editable && !subcategory.archived,
                    onClick = {
                        expanded = false
                        onSelected(subcategory.id)
                    },
                    modifier = Modifier.testTag("entry-subcategory-${subcategory.id}").semantics { selected = isSelected },
                )
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun CategoryPicker(state: EntryFormUiState, editable: Boolean, onSelected: (String) -> Unit) {
    // Only the draft survives recreation; a popup is transient interaction state.
    var expanded by remember { mutableStateOf(false) }
    val focusManager = LocalFocusManager.current
    val categories = state.categories.filter { !it.category.archived || it.category.id == state.categoryId }
    val selectedCategory = categories.firstOrNull { it.category.id == state.categoryId }?.category
    val enabled = editable && categories.isNotEmpty()
    val menuExpanded = expanded && enabled
    val expansionState = stringResource(if (menuExpanded) R.string.entry_category_expanded else R.string.entry_category_collapsed)
    ExposedDropdownMenuBox(
        expanded = menuExpanded,
        onExpandedChange = {
            if (enabled) {
                focusManager.clearFocus()
                expanded = it
            }
        },
        modifier = Modifier.fillMaxWidth(),
    ) {
        OutlinedTextField(
            value = selectedCategory?.name ?: stringResource(R.string.entry_category_choose),
            onValueChange = {},
            readOnly = true,
            enabled = enabled,
            label = { Text(stringResource(R.string.entry_category)) },
            leadingIcon = selectedCategory?.let { category -> { CategorySwatch(category.accentColor()) } },
            trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(expanded = menuExpanded) },
            isError = state.error == EntryFormError.CategoryRequired || state.error == EntryFormError.InactiveTaxonomy,
            modifier = Modifier.fillMaxWidth()
                .menuAnchor(ExposedDropdownMenuAnchorType.PrimaryNotEditable, enabled = enabled)
                .testTag("entry-category-picker")
                .semantics { stateDescription = expansionState },
        )
        ExposedDropdownMenu(
            expanded = menuExpanded,
            onDismissRequest = { expanded = false },
            modifier = Modifier.heightIn(max = 320.dp).testTag("entry-category-menu"),
        ) {
            categories.forEach { item ->
                val category = item.category
                val isSelected = state.categoryId == category.id
                DropdownMenuItem(
                    text = {
                        Column {
                            Text(category.name)
                            if (category.archived) Text(stringResource(R.string.entry_category_archived), style = MaterialTheme.typography.bodySmall)
                        }
                    },
                    leadingIcon = { CategorySwatch(category.accentColor()) },
                    trailingIcon = if (isSelected) {
                        { Icon(Icons.Default.Check, contentDescription = stringResource(R.string.entry_category_selected_marker)) }
                    } else null,
                    enabled = editable && !category.archived,
                    onClick = {
                        expanded = false
                        onSelected(category.id)
                    },
                    modifier = Modifier.testTag("entry-category-${category.id}").semantics { selected = isSelected },
                )
            }
        }
    }
    if (categories.none { !it.category.archived }) Text(stringResource(R.string.entry_no_active_categories))
}

@Composable
private fun CategorySwatch(color: androidx.compose.ui.graphics.Color) {
    Box(Modifier.size(16.dp).background(color, MaterialTheme.shapes.extraSmall)
        .border(1.dp, MaterialTheme.colorScheme.onSurface, MaterialTheme.shapes.extraSmall))
}

@Composable
private fun DirectionSelector(selected: EntryType, onSelected: (EntryType) -> Unit, enabled: Boolean) {
    Column {
        Text(stringResource(R.string.entry_direction), style = MaterialTheme.typography.titleSmall)
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            FilterChip(
                modifier = Modifier.testTag("entry-type-expense"),
                selected = selected == EntryType.EXPENSE,
                enabled = enabled,
                onClick = { onSelected(EntryType.EXPENSE) },
                label = { Text(stringResource(R.string.entry_expense)) },
            )
            FilterChip(
                modifier = Modifier.testTag("entry-type-income"),
                selected = selected == EntryType.INCOME,
                enabled = enabled,
                onClick = { onSelected(EntryType.INCOME) },
                label = { Text(stringResource(R.string.entry_income)) },
            )
        }
    }
}

@Composable
private fun EntrySyncState(state: EntryFormUiState) {
    val message = when (state.syncState) {
        SyncState.PENDING -> R.string.entry_sync_pending
        else -> null
    }
    message?.let { Text(stringResource(it)) }
}

@Composable
private fun EntryError(error: EntryFormError?) {
    val message = when (error) {
        EntryFormError.InvalidAmount -> R.string.entry_amount_error
        EntryFormError.FutureDate -> R.string.entry_future_date_error
        EntryFormError.InvalidTitle -> R.string.entry_title_error
        EntryFormError.InvalidTags -> R.string.entry_tags_error
        EntryFormError.SaveFailed -> R.string.entry_save_error
        else -> null
    }
    message?.let { Text(stringResource(it), color = MaterialTheme.colorScheme.error) }
}
