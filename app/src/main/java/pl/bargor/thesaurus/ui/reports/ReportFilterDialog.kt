package pl.bargor.thesaurus.ui.reports

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Checkbox
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.semantics.Role
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowDownward
import androidx.compose.material.icons.filled.ArrowUpward
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.unit.dp
import pl.bargor.thesaurus.R
import pl.bargor.thesaurus.data.model.ReportTypeFilter

@Composable
private fun ReportSortControls(
    draft: ReportFilterDraft,
    onSort: (ReportEntrySort) -> Unit,
    onDirection: () -> Unit,
) {
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(4.dp)) {
        ReportChoice(
            stringResource(R.string.reports_sort), reportSortLabel(draft.sort),
            ReportEntrySort.entries.map { it.name to reportSortLabel(it) }, "reports-sort",
            modifier = Modifier.weight(1f),
            onSelect = { name -> name?.let { onSort(ReportEntrySort.valueOf(it)) } },
        )
        val directionDescription = stringResource(if (draft.direction == ReportSortDirection.DESCENDING)
            R.string.reports_sort_descending else R.string.reports_sort_ascending)
        val directionState = stringResource(if (draft.direction == ReportSortDirection.DESCENDING)
            R.string.reports_sort_direction_descending else R.string.reports_sort_direction_ascending)
        IconButton(onClick = onDirection, modifier = Modifier.size(48.dp).testTag("reports-sort-direction")
            .semantics { contentDescription = directionDescription; stateDescription = directionState }) {
            Icon(if (draft.direction == ReportSortDirection.DESCENDING) Icons.Filled.ArrowDownward
                else Icons.Filled.ArrowUpward, contentDescription = null)
        }
    }
}

@Composable
private fun reportSortLabel(sort: ReportEntrySort): String = stringResource(when (sort) {
    ReportEntrySort.DATE -> R.string.reports_sort_date
    ReportEntrySort.AMOUNT -> R.string.reports_sort_amount
})

@Composable
private fun ReportChoice(
    label: String,
    selected: String,
    options: List<Pair<String?, String>>,
    tag: String,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    onSelect: (String?) -> Unit,
) {
    var expanded by remember { mutableStateOf(false) }
    BoxWithConstraints(modifier.fillMaxWidth()) {
        // Reserve a usable dropdown beside the label even when the adjacent icons and
        // enlarged fonts leave the sort selector much less space than a category row.
        val labelLimit = (maxWidth - 64.dp - 8.dp).coerceAtLeast(0.dp)
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(label, style = MaterialTheme.typography.labelMedium,
                modifier = Modifier.widthIn(max = labelLimit), maxLines = 2, overflow = TextOverflow.Ellipsis)
            Box(Modifier.weight(1f)) {
                OutlinedButton(onClick = { expanded = true }, enabled = enabled,
                    modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp).testTag(tag).semantics {
                        contentDescription = label
                        stateDescription = selected
                    }) {
                    Text(selected, maxLines = 1, overflow = TextOverflow.Ellipsis)
                }
                DropdownMenu(expanded = expanded && enabled, onDismissRequest = { expanded = false }) {
                    options.forEach { (id, name) ->
                        DropdownMenuItem(text = { Text(name) }, onClick = {
                            expanded = false
                            onSelect(id)
                        }, modifier = Modifier.testTag("$tag-option-${id ?: "all"}"))
                    }
                }
            }
        }
    }
}

@Composable
internal fun ReportFilterDialog(
    state: ReportsUiState,
    draft: ReportFilterDraft,
    onMode: (ReportPeriodMode) -> Unit,
    previous: () -> Unit,
    next: () -> Unit,
    onType: (ReportTypeFilter) -> Unit,
    onFrom: (String) -> Unit,
    onTo: (String) -> Unit,
    onCategory: (String?) -> Unit,
    onSubcategory: (String?) -> Unit,
    onMembers: (Set<String>?) -> Unit,
    onSort: (ReportEntrySort) -> Unit,
    onDirection: () -> Unit,
    dismiss: () -> Unit,
    apply: () -> Unit,
    reset: () -> Unit,
) {
    val presentation = state.copy(mode = draft.mode, month = draft.month, year = draft.year,
        typeFilter = draft.typeFilter, customFromInput = draft.customFromInput,
        customToInput = draft.customToInput, customDateError = draft.customDateError,
        selectedCategoryId = draft.selectedCategoryId, selectedSubcategoryId = draft.selectedSubcategoryId)
    val subcategories = state.allSubcategories.filter { it.categoryId == draft.selectedCategoryId }.sortedBy { it.name.lowercase() }
    val categoryOptions = state.categories.map { it.id to it.name }.toMutableList()
    listOfNotNull(state.selectedCategoryId, draft.selectedCategoryId).distinct()
        .filter { id -> categoryOptions.none { it.first == id } }.forEach { id ->
        categoryOptions.add(id to stringResource(R.string.reports_missing_category, id))
    }
    val subcategoryOptions = subcategories.map { it.id to it.name }.toMutableList()
    listOfNotNull(draft.selectedSubcategoryId, state.selectedSubcategoryId.takeIf {
        draft.selectedCategoryId != null && draft.selectedCategoryId == state.selectedCategoryId
    }).distinct().filter { id -> subcategoryOptions.none { it.first == id } }.forEach { id ->
        subcategoryOptions.add(id to stringResource(R.string.reports_missing_subcategory, id))
    }
    Dialog(onDismissRequest = dismiss, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        BoxWithConstraints(Modifier.fillMaxSize().pointerInput(dismiss) {
            detectTapGestures { dismiss() }
        }.padding(16.dp), contentAlignment = Alignment.Center) {
            Surface(shape = MaterialTheme.shapes.extraLarge, tonalElevation = 6.dp,
                modifier = Modifier.widthIn(max = 560.dp).fillMaxWidth().heightIn(max = maxHeight)
                    .testTag("reports-filter-dialog").pointerInput(Unit) { detectTapGestures { } }) {
                Column(Modifier.padding(20.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    Text(stringResource(R.string.reports_filter_title), style = MaterialTheme.typography.headlineSmall,
                        modifier = Modifier.semantics { heading() })
                    Column(Modifier.weight(1f, fill = false).verticalScroll(rememberScrollState())
                        .testTag("reports-filter-content"), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                        ReportChoice(stringResource(R.string.reports_filter_period), stringResource(draft.mode.labelRes()),
                            ReportPeriodMode.entries.map { it.name to stringResource(it.labelRes()) }, "reports-period-selector",
                            onSelect = { it?.let { name -> onMode(ReportPeriodMode.valueOf(name)) } })
                        if (draft.mode == ReportPeriodMode.CUSTOM) {
                            CustomPeriodFields(presentation, onFrom, onTo)
                        } else PeriodNavigator(presentation, previous, next)
                        ReportChoice(stringResource(R.string.reports_filter_type), stringResource(draft.typeFilter.labelRes()),
                            ReportTypeFilter.entries.map { it.name to stringResource(it.labelRes()) }, "reports-type-selector",
                            onSelect = { it?.let { name -> onType(ReportTypeFilter.valueOf(name)) } })
                        ReportSortControls(draft, onSort, onDirection)
                        ReportChoice(stringResource(R.string.reports_filter_category),
                            categoryOptions.firstOrNull { it.first == draft.selectedCategoryId }?.second ?: stringResource(R.string.reports_all_categories),
                            listOf(null to stringResource(R.string.reports_all_categories)) + categoryOptions,
                            "reports-filter-category", onSelect = onCategory)
                        ReportChoice(stringResource(R.string.reports_filter_subcategory),
                            subcategoryOptions.firstOrNull { it.first == draft.selectedSubcategoryId }?.second ?: stringResource(R.string.reports_all_subcategories),
                            listOf(null to stringResource(R.string.reports_all_subcategories)) + subcategoryOptions,
                            "reports-filter-subcategory", enabled = draft.selectedCategoryId != null, onSelect = onSubcategory)
                        MemberSectionHeader { onMembers(emptySet()) }
                        MemberFilterRow(stringResource(R.string.reports_members_all), draft.selectedMemberIds == null,
                            "reports-members-all") { selected -> onMembers(if (selected) null else emptySet()) }
                        state.members.forEach { member ->
                            val label = if (member.former) stringResource(R.string.reports_member_former, member.name) else member.name
                            MemberFilterRow(label, draft.selectedMemberIds == null || member.id in draft.selectedMemberIds,
                                "reports-member-${member.id}") { selected ->
                                val ids = draft.selectedMemberIds ?: state.members.mapTo(mutableSetOf()) { it.id }
                                onMembers(if (selected) ids + member.id else ids - member.id)
                            }
                        }
                    }
                    val resetDescription = stringResource(R.string.reports_reset_filters_description)
                    TextButton(onClick = reset, modifier = Modifier.testTag("reports-reset-filters")
                        .semantics { contentDescription = resetDescription }) {
                        Text(stringResource(R.string.reports_reset_filters))
                    }
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        OutlinedButton(onClick = dismiss, modifier = Modifier.weight(1f).testTag("reports-cancel-filters")) {
                            Text(stringResource(R.string.reports_cancel_filters))
                        }
                        Button(onClick = apply, modifier = Modifier.weight(1f).testTag("reports-apply-filters")) {
                            Text(stringResource(R.string.reports_apply_filters))
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun MemberSectionHeader(clear: () -> Unit) {
    val fontScale = LocalDensity.current.fontScale
    BoxWithConstraints(Modifier.fillMaxWidth()) {
        if (maxWidth < 360.dp || fontScale > 1.3f) {
            Column(Modifier.fillMaxWidth()) {
                Text(stringResource(R.string.reports_filter_members), style = MaterialTheme.typography.titleMedium,
                    modifier = Modifier.semantics { heading() })
                TextButton(onClick = clear, modifier = Modifier.align(Alignment.End).heightIn(min = 48.dp)
                    .testTag("reports-members-clear")) { Text(stringResource(R.string.reports_members_clear)) }
            }
        } else {
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(stringResource(R.string.reports_filter_members), style = MaterialTheme.typography.titleMedium,
                    modifier = Modifier.weight(1f).semantics { heading() })
                TextButton(onClick = clear, modifier = Modifier.heightIn(min = 48.dp)
                    .testTag("reports-members-clear")) { Text(stringResource(R.string.reports_members_clear)) }
            }
        }
    }
}

@Composable
private fun MemberFilterRow(label: String, selected: Boolean, tag: String, change: (Boolean) -> Unit) {
    Row(Modifier.fillMaxWidth().heightIn(min = 48.dp).testTag(tag)
        .toggleable(value = selected, role = Role.Checkbox, onValueChange = change),
        verticalAlignment = Alignment.CenterVertically) {
        Checkbox(checked = selected, onCheckedChange = null)
        Text(label, modifier = Modifier.weight(1f).padding(start = 8.dp))
    }
}

private fun ReportPeriodMode.labelRes() = when (this) {
    ReportPeriodMode.MONTH -> R.string.reports_mode_month
    ReportPeriodMode.YEAR -> R.string.reports_mode_year
    ReportPeriodMode.CUSTOM -> R.string.reports_mode_custom
}

@Composable
internal fun CustomPeriodFields(
    state: ReportsUiState,
    onFrom: (String) -> Unit,
    onTo: (String) -> Unit,
) {
    OutlinedTextField(
        value = state.customFromInput,
        onValueChange = onFrom,
        label = { Text(stringResource(R.string.reports_from)) },
        isError = state.customDateError,
        supportingText = { Text(stringResource(if (state.customDateError) R.string.reports_date_error else R.string.reports_date_hint)) },
        singleLine = true,
        modifier = Modifier.fillMaxWidth().testTag("reports-custom-from"),
    )
    OutlinedTextField(
        value = state.customToInput,
        onValueChange = onTo,
        label = { Text(stringResource(R.string.reports_to)) },
        isError = state.customDateError,
        // The group exposes one error message. Repeating it for both inputs makes TalkBack announce
        // the same Polish validation error twice before the user can correct the range.
        supportingText = null,
        singleLine = true,
        modifier = Modifier.fillMaxWidth().testTag("reports-custom-to"),
    )
}

private fun ReportTypeFilter.labelRes() = when (this) {
    ReportTypeFilter.ALL -> R.string.reports_type_all
    ReportTypeFilter.INCOME -> R.string.reports_type_income
    ReportTypeFilter.EXPENSE -> R.string.reports_type_expense
}
