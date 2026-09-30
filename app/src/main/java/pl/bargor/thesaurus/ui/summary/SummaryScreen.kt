package pl.bargor.thesaurus.ui.summary

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowDownward
import androidx.compose.material.icons.filled.ArrowUpward
import androidx.compose.material.icons.filled.RestartAlt
import androidx.compose.material.icons.filled.SwapHoriz
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.semantics.onClick
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import java.math.BigDecimal
import java.math.BigInteger
import java.text.NumberFormat
import java.time.format.DateTimeFormatter
import java.util.Locale
import pl.bargor.thesaurus.R
import pl.bargor.thesaurus.data.model.SummaryTotals
import pl.bargor.thesaurus.data.model.SyncState

private val polishLocale = Locale.forLanguageTag("pl-PL")
private val monthFormatter = DateTimeFormatter.ofPattern("LLLL uuuu", polishLocale)
private val entryDateFormatter = DateTimeFormatter.ofPattern("d MMMM uuuu", polishLocale)

@Composable
fun SummaryScreen(
    state: SummaryUiState,
    onSelectPeriodMode: (SummaryPeriodMode) -> Unit,
    onPreviousPeriod: () -> Unit,
    onNextPeriod: () -> Unit,
    onRetry: () -> Unit,
    modifier: Modifier = Modifier,
    onSelectCategory: (String?) -> Unit = {},
    onSelectSubcategory: (String?) -> Unit = {},
    onSelectTag: (String?) -> Unit = {},
    onSelectSort: (SummaryEntrySort) -> Unit = {},
    onToggleSortDirection: () -> Unit = {},
    onClearControls: () -> Unit = {},
    onOpenEntry: (String) -> Unit = {},
) {
    val loadingDescription = stringResource(R.string.accessibility_loading)
    Column(
        modifier = modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        SummaryPeriodNavigator(
            label = when (state.mode) {
                SummaryPeriodMode.MONTH -> state.month.format(monthFormatter).replaceFirstChar { it.titlecase(polishLocale) }
                SummaryPeriodMode.YEAR -> state.year.toString()
            },
            previousDescription = stringResource(
                if (state.mode == SummaryPeriodMode.MONTH) R.string.summary_previous_month else R.string.summary_previous_year,
            ),
            nextDescription = stringResource(
                if (state.mode == SummaryPeriodMode.MONTH) R.string.summary_next_month else R.string.summary_next_year,
            ),
            onPrevious = onPreviousPeriod,
            onNext = onNextPeriod,
            scopeDescription = stringResource(if (state.mode == SummaryPeriodMode.MONTH)
                R.string.summary_scope_month else R.string.summary_scope_year),
            toggleDescription = stringResource(if (state.mode == SummaryPeriodMode.MONTH)
                R.string.summary_switch_to_year else R.string.summary_switch_to_month),
            onToggle = { onSelectPeriodMode(if (state.mode == SummaryPeriodMode.MONTH)
                SummaryPeriodMode.YEAR else SummaryPeriodMode.MONTH) },
        )
        if (state.isLoading) {
            CircularProgressIndicator(
                modifier = Modifier.testTag("summary-loading").semantics {
                    contentDescription = loadingDescription
                },
            )
            return@Column
        }
        if (state.hasError) {
            Text(
                text = stringResource(R.string.summary_load_error),
                color = MaterialTheme.colorScheme.error,
                modifier = Modifier.testTag("summary-error"),
            )
            Button(onClick = onRetry, modifier = Modifier.testTag("summary-retry")) {
                Text(stringResource(R.string.auth_retry))
            }
        }
        when (state.syncState) {
            SyncState.PENDING -> Text(
                stringResource(R.string.summary_sync_pending),
                modifier = Modifier.testTag("summary-pending"),
            )
            else -> Unit
        }
        if (!state.hasError || !state.totals.isEmpty) {
            if (state.totals.isEmpty) Text(
                stringResource(
                    if (state.mode == SummaryPeriodMode.MONTH) R.string.empty_summary else R.string.empty_summary_year,
                ),
                modifier = Modifier.testTag("summary-empty"),
            )
            TotalCard(R.string.summary_income, state.totals.incomeGrosze, "summary-income")
            TotalCard(R.string.summary_expense, state.totals.expenseGrosze, "summary-expense")
            TotalCard(R.string.summary_net, state.totals.netGrosze, "summary-net", signed = true)
        }
        if (!state.hasError || !state.totals.isEmpty) {
            SummaryEntries(
                state, onSelectCategory, onSelectSubcategory, onSelectTag,
                onSelectSort, onToggleSortDirection, onClearControls, onOpenEntry,
            )
        }
    }
}

@Composable
private fun SummaryEntries(
    state: SummaryUiState,
    onSelectCategory: (String?) -> Unit,
    onSelectSubcategory: (String?) -> Unit,
    onSelectTag: (String?) -> Unit,
    onSelectSort: (SummaryEntrySort) -> Unit,
    onToggleSortDirection: () -> Unit,
    onClearControls: () -> Unit,
    onOpenEntry: (String) -> Unit,
) {
    Text(
        stringResource(R.string.summary_entries_count, state.entries.size),
        style = MaterialTheme.typography.titleLarge,
        modifier = Modifier.testTag("summary-entry-count").semantics { heading() },
    )
    SummaryChoice(
        label = stringResource(R.string.summary_filter_category),
        selected = state.categories.firstOrNull { it.id == state.selectedCategoryId }?.name
            ?: stringResource(R.string.summary_all_categories),
        options = listOf(null to stringResource(R.string.summary_all_categories)) + state.categories.map { it.id to it.name },
        tag = "summary-filter-category",
        inline = true,
        onSelect = onSelectCategory,
    )
    SummaryChoice(
        label = stringResource(R.string.summary_filter_subcategory),
        selected = state.subcategories.firstOrNull { it.id == state.selectedSubcategoryId }?.name
            ?: stringResource(R.string.summary_all_subcategories),
        options = listOf(null to stringResource(R.string.summary_all_subcategories)) +
            state.subcategories.map { it.id to it.name },
        tag = "summary-filter-subcategory",
        inline = true,
        enabled = state.selectedCategoryId != null,
        onSelect = onSelectSubcategory,
    )
    SummaryChoice(
        label = stringResource(R.string.summary_filter_tag),
        selected = state.selectedTag?.let { "#$it" } ?: stringResource(R.string.summary_all_tags),
        options = listOf(null to stringResource(R.string.summary_all_tags)) + state.tags.map { it to "#$it" },
        tag = "summary-filter-tag",
        inline = true,
        onSelect = onSelectTag,
    )
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(4.dp),
        verticalAlignment = Alignment.Bottom,
    ) {
        SummaryChoice(
            label = stringResource(R.string.summary_sort),
            selected = summarySortLabel(state.sort),
            options = SummaryEntrySort.entries.map { it.name to summarySortLabel(it) },
            tag = "summary-sort",
            modifier = Modifier.weight(1f),
            buttonModifier = Modifier.fillMaxWidth(),
            onSelect = { selected -> selected?.let { onSelectSort(SummaryEntrySort.valueOf(it)) } },
        )
        val directionDescription = stringResource(if (state.direction == SummarySortDirection.DESCENDING)
            R.string.summary_sort_descending else R.string.summary_sort_ascending)
        IconButton(
            onClick = onToggleSortDirection,
            modifier = Modifier.size(48.dp).testTag("summary-sort-direction")
                .semantics { contentDescription = directionDescription },
        ) {
            Icon(
                imageVector = if (state.direction == SummarySortDirection.DESCENDING)
                    Icons.Filled.ArrowDownward else Icons.Filled.ArrowUpward,
                contentDescription = null,
            )
        }
        val clearDescription = stringResource(R.string.summary_clear_controls)
        IconButton(
            onClick = onClearControls,
            modifier = Modifier.size(48.dp).testTag("summary-clear-controls")
                .semantics { contentDescription = clearDescription },
        ) {
            Icon(Icons.Filled.RestartAlt, contentDescription = null)
        }
    }
    if (state.hasActiveFilters) SummaryFilteredTotals(state.filteredTotals)
    if (state.entries.isEmpty() && !state.totals.isEmpty) {
        Text(stringResource(R.string.summary_no_matching_entries), modifier = Modifier.testTag("summary-no-matches"))
    }
    state.entries.forEach { item ->
        val entry = item.entry
        Card(
            modifier = Modifier.fillMaxWidth().testTag("summary-entry-${entry.id}")
                .clickable(onClickLabel = stringResource(R.string.entries_edit)) { onOpenEntry(entry.id) },
        ) {
            Column(modifier = Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                    Text(
                        listOfNotNull(item.categoryName, item.subcategoryName).joinToString(" › "),
                        style = MaterialTheme.typography.titleMedium,
                        modifier = Modifier.weight(1f),
                    )
                    Text(
                        NumberFormat.getCurrencyInstance(polishLocale).format(BigDecimal.valueOf(entry.amountGrosze, 2)),
                        fontWeight = FontWeight.Bold,
                    )
                }
                entry.normalizedTitle?.let { Text(it) }
                Text(entry.date.format(entryDateFormatter))
                if (entry.normalizedTags.isNotEmpty()) Text(entry.normalizedTags.joinToString(" ") { "#$it" })
            }
        }
    }
}

@Composable
private fun SummaryFilteredTotals(totals: SummaryTotals) {
    Card(modifier = Modifier.fillMaxWidth().testTag("summary-filtered-result")) {
        Column(modifier = Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text(
                stringResource(R.string.summary_filtered_result),
                style = MaterialTheme.typography.titleMedium,
                modifier = Modifier.testTag("summary-filtered-heading").semantics { heading() },
            )
            FilteredTotalRow(R.string.summary_filtered_income, totals.incomeGrosze, "summary-filtered-income")
            FilteredTotalRow(R.string.summary_expense, totals.expenseGrosze, "summary-filtered-expense")
            FilteredTotalRow(R.string.summary_net, totals.netGrosze, "summary-filtered-net", signed = true)
        }
    }
}

@Composable
private fun FilteredTotalRow(labelRes: Int, amount: BigInteger, tag: String, signed: Boolean = false) {
    val label = stringResource(labelRes)
    val formattedAmount = formatSummaryAmount(amount, signed)
    val description = stringResource(R.string.summary_total_description, label, formattedAmount)
    Row(
        modifier = Modifier.fillMaxWidth().testTag(tag).semantics(mergeDescendants = true) {
            contentDescription = description
        },
        horizontalArrangement = Arrangement.SpaceBetween,
    ) {
        Text(label, style = MaterialTheme.typography.bodyMedium)
        Text(formattedAmount, style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.SemiBold)
    }
}

@Composable
private fun summarySortLabel(sort: SummaryEntrySort): String = stringResource(when (sort) {
    SummaryEntrySort.DATE -> R.string.summary_sort_date
    SummaryEntrySort.AMOUNT -> R.string.summary_sort_amount
    SummaryEntrySort.CATEGORY -> R.string.summary_sort_category
    SummaryEntrySort.SUBCATEGORY -> R.string.summary_sort_subcategory
})

@Composable
private fun SummaryChoice(
    label: String,
    selected: String,
    options: List<Pair<String?, String>>,
    tag: String,
    modifier: Modifier = Modifier,
    buttonModifier: Modifier = Modifier,
    inline: Boolean = false,
    enabled: Boolean = true,
    onSelect: (String?) -> Unit,
) {
    if (inline) {
        Row(
            modifier = modifier.fillMaxWidth().testTag("$tag-row"),
            horizontalArrangement = Arrangement.spacedBy(12.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(label, style = MaterialTheme.typography.labelLarge, modifier = Modifier.testTag("$tag-label"))
            SummaryChoicePicker(label, selected, options, tag, enabled, onSelect, Modifier.weight(1f))
        }
    } else {
        Column(modifier = modifier) {
            Text(label, style = MaterialTheme.typography.labelLarge)
            SummaryChoicePicker(label, selected, options, tag, enabled, onSelect, buttonModifier)
        }
    }
}

@Composable
private fun SummaryChoicePicker(
    label: String,
    selected: String,
    options: List<Pair<String?, String>>,
    tag: String,
    enabled: Boolean,
    onSelect: (String?) -> Unit,
    modifier: Modifier,
) {
    var expanded by remember { mutableStateOf(false) }
    Box(modifier = modifier) {
        OutlinedButton(
            onClick = { expanded = true }, enabled = enabled,
            modifier = Modifier.fillMaxWidth().testTag(tag).semantics { contentDescription = "$label: $selected" },
        ) { Text(selected, maxLines = 1, overflow = TextOverflow.Ellipsis) }
        DropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
            options.forEach { (value, name) ->
                DropdownMenuItem(
                    text = { Text(name) },
                    onClick = { expanded = false; onSelect(value) },
                    modifier = Modifier.testTag("$tag-${value ?: "all"}"),
                )
            }
        }
    }
}

/** Period controls accept a display label, so a later summary can reuse them for other periods. */
@Composable
internal fun SummaryPeriodNavigator(
    label: String,
    previousDescription: String,
    nextDescription: String,
    onPrevious: () -> Unit,
    onNext: () -> Unit,
    scopeDescription: String,
    toggleDescription: String,
    onToggle: () -> Unit,
) {
    Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
        TextButton(
            onClick = onPrevious,
            modifier = Modifier.testTag("summary-previous-period").semantics { contentDescription = previousDescription },
        ) { Text("‹") }
        TextButton(
            onClick = onToggle,
            modifier = Modifier.weight(1f).heightIn(min = 48.dp).testTag("summary-period").semantics {
                heading()
                stateDescription = scopeDescription
                onClick(label = toggleDescription, action = null)
            },
        ) {
            Text(label, style = MaterialTheme.typography.titleLarge, modifier = Modifier.weight(1f))
            Icon(Icons.Default.SwapHoriz, contentDescription = null, modifier = Modifier.size(18.dp))
        }
        TextButton(
            onClick = onNext,
            modifier = Modifier.testTag("summary-next-period").semantics { contentDescription = nextDescription },
        ) { Text("›") }
    }
}

@Composable
private fun TotalCard(labelRes: Int, amount: BigInteger, tag: String, signed: Boolean = false) {
    val label = stringResource(labelRes)
    val formattedAmount = formatSummaryAmount(amount, signed)
    val description = stringResource(R.string.summary_total_description, label, formattedAmount)
    Card(modifier = Modifier.fillMaxWidth()) {
        Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text(label, style = MaterialTheme.typography.labelLarge)
            Text(
                formattedAmount,
                style = MaterialTheme.typography.headlineSmall,
                modifier = Modifier.testTag(tag).semantics {
                    contentDescription = description
                },
            )
        }
    }
}

private fun formatSummaryAmount(amount: BigInteger, signed: Boolean): String {
    val prefix = if (signed && amount.signum() > 0) "+" else ""
    return prefix + NumberFormat.getCurrencyInstance(polishLocale).format(BigDecimal(amount, 2))
}
