package pl.bargor.thesaurus.ui.entry

import android.app.DatePickerDialog
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import java.time.LocalDate
import java.time.format.DateTimeFormatter
import java.util.Calendar
import java.util.Locale
import pl.bargor.thesaurus.R
import pl.bargor.thesaurus.data.model.EntryType
import pl.bargor.thesaurus.data.model.SyncState

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
    val selectedCategory = state.categories.firstOrNull { it.category.id == state.categoryId }
    Column(
        modifier = modifier
            .verticalScroll(rememberScrollState())
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        Row(modifier = Modifier.fillMaxWidth()) {
            Text(
                text = stringResource(R.string.entry_add_title),
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
        Text(stringResource(R.string.entry_category), style = MaterialTheme.typography.titleSmall)
        if (state.categories.isEmpty()) {
            Text(stringResource(R.string.entry_no_active_categories))
        } else {
            state.categories.forEach { category ->
                FilterChip(
                    modifier = Modifier.testTag("entry-category-${category.category.id}"),
                    selected = state.categoryId == category.category.id,
                    enabled = editable,
                    onClick = { onCategorySelected(category.category.id) },
                    label = { Text(category.category.name) },
                )
            }
        }
        if (state.error == EntryFormError.CategoryRequired || state.error == EntryFormError.InactiveTaxonomy) {
            Text(stringResource(R.string.entry_category_error), color = MaterialTheme.colorScheme.error)
        }
        selectedCategory?.let { category ->
            if (category.subcategories.isNotEmpty()) {
                Text(stringResource(R.string.entry_subcategory), style = MaterialTheme.typography.titleSmall)
                FilterChip(
                    modifier = Modifier.testTag("entry-subcategory-none"),
                    selected = state.subcategoryId == null,
                    enabled = editable,
                    onClick = { onSubcategorySelected(null) },
                    label = { Text(stringResource(R.string.entry_subcategory_none)) },
                )
                category.subcategories.forEach { subcategory ->
                    FilterChip(
                        modifier = Modifier.testTag("entry-subcategory-${subcategory.id}"),
                        selected = state.subcategoryId == subcategory.id,
                        enabled = editable,
                        onClick = { onSubcategorySelected(subcategory.id) },
                        label = { Text(subcategory.name) },
                    )
                }
            }
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
            if (state.saving) CircularProgressIndicator() else Text(stringResource(R.string.entry_save))
        }
        if (state.saved) Text(stringResource(if (state.queuedOffline) R.string.entry_queued else R.string.entry_saved))
        Spacer(Modifier.height(8.dp))
    }
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
        SyncState.OFFLINE -> R.string.entry_offline
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
