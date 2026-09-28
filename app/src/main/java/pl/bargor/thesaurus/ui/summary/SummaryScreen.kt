package pl.bargor.thesaurus.ui.summary

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
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
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import java.math.BigDecimal
import java.math.BigInteger
import java.text.NumberFormat
import java.time.format.DateTimeFormatter
import java.util.Locale
import pl.bargor.thesaurus.R
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
        Text(
            text = stringResource(R.string.navigation_summary),
            style = MaterialTheme.typography.headlineSmall,
            modifier = Modifier.semantics { heading() },
        )
        SummaryPeriodModeSelector(state.mode, onSelectPeriodMode)
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
            SyncState.OFFLINE -> Text(
                stringResource(R.string.summary_offline),
                modifier = Modifier.testTag("summary-offline"),
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
        onSelect = onSelectCategory,
    )
    SummaryChoice(
        label = stringResource(R.string.summary_filter_subcategory),
        selected = state.subcategories.firstOrNull { it.id == state.selectedSubcategoryId }?.name
            ?: stringResource(R.string.summary_all_subcategories),
        options = listOf(null to stringResource(R.string.summary_all_subcategories)) +
            state.subcategories.map { it.id to it.name },
        tag = "summary-filter-subcategory",
        enabled = state.selectedCategoryId != null,
        onSelect = onSelectSubcategory,
    )
    SummaryChoice(
        label = stringResource(R.string.summary_filter_tag),
        selected = state.selectedTag?.let { "#$it" } ?: stringResource(R.string.summary_all_tags),
        options = listOf(null to stringResource(R.string.summary_all_tags)) + state.tags.map { it to "#$it" },
        tag = "summary-filter-tag",
        onSelect = onSelectTag,
    )
    SummaryChoice(
        label = stringResource(R.string.summary_sort),
        selected = summarySortLabel(state.sort),
        options = SummaryEntrySort.entries.map { it.name to summarySortLabel(it) },
        tag = "summary-sort",
        onSelect = { selected -> selected?.let { onSelectSort(SummaryEntrySort.valueOf(it)) } },
    )
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        OutlinedButton(
            modifier = Modifier.testTag("summary-sort-direction"),
            onClick = onToggleSortDirection,
        ) {
            Text(stringResource(if (state.direction == SummarySortDirection.DESCENDING)
                R.string.summary_sort_descending else R.string.summary_sort_ascending))
        }
        TextButton(onClick = onClearControls, modifier = Modifier.testTag("summary-clear-controls")) {
            Text(stringResource(R.string.summary_clear_controls))
        }
    }
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
private fun summarySortLabel(sort: SummaryEntrySort): String = stringResource(when (sort) {
    SummaryEntrySort.DATE -> R.string.summary_sort_date
    SummaryEntrySort.AMOUNT -> R.string.summary_sort_amount
    SummaryEntrySort.CATEGORY -> R.string.summary_sort_category
    SummaryEntrySort.SUBCATEGORY -> R.string.summary_sort_subcategory
    SummaryEntrySort.TAGS -> R.string.summary_sort_tags
})

@Composable
private fun SummaryChoice(
    label: String,
    selected: String,
    options: List<Pair<String?, String>>,
    tag: String,
    enabled: Boolean = true,
    onSelect: (String?) -> Unit,
) {
    var expanded by remember { mutableStateOf(false) }
    Column {
        Text(label, style = MaterialTheme.typography.labelLarge)
        OutlinedButton(
            onClick = { expanded = true }, enabled = enabled,
            modifier = Modifier.testTag(tag).semantics { contentDescription = "$label: $selected" },
        ) { Text(selected) }
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

@Composable
private fun SummaryPeriodModeSelector(
    selectedMode: SummaryPeriodMode,
    onSelect: (SummaryPeriodMode) -> Unit,
) {
    SingleChoiceSegmentedButtonRow {
        SegmentedButton(
            selected = selectedMode == SummaryPeriodMode.MONTH,
            onClick = { onSelect(SummaryPeriodMode.MONTH) },
            shape = SegmentedButtonDefaults.itemShape(index = 0, count = 2),
            label = { Text(stringResource(R.string.summary_mode_month)) },
            modifier = Modifier.testTag("summary-mode-month"),
        )
        SegmentedButton(
            selected = selectedMode == SummaryPeriodMode.YEAR,
            onClick = { onSelect(SummaryPeriodMode.YEAR) },
            shape = SegmentedButtonDefaults.itemShape(index = 1, count = 2),
            label = { Text(stringResource(R.string.summary_mode_year)) },
            modifier = Modifier.testTag("summary-mode-year"),
        )
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
) {
    Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
        TextButton(
            onClick = onPrevious,
            modifier = Modifier.testTag("summary-previous-period").semantics { contentDescription = previousDescription },
        ) { Text("‹") }
        Text(
            text = label,
            style = MaterialTheme.typography.titleLarge,
            modifier = Modifier.weight(1f).testTag("summary-period"),
        )
        TextButton(
            onClick = onNext,
            modifier = Modifier.testTag("summary-next-period").semantics { contentDescription = nextDescription },
        ) { Text("›") }
    }
}

@Composable
private fun TotalCard(labelRes: Int, amount: BigInteger, tag: String, signed: Boolean = false) {
    val label = stringResource(labelRes)
    val prefix = if (signed && amount.signum() > 0) "+" else ""
    val formattedAmount = prefix + NumberFormat.getCurrencyInstance(polishLocale).format(BigDecimal(amount, 2))
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
