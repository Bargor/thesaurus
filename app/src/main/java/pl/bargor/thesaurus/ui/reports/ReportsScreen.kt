package pl.bargor.thesaurus.ui.reports

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material.icons.filled.FilterList
import androidx.compose.runtime.DisposableEffect
import androidx.compose.material.icons.Icons
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.unit.dp
import java.time.format.DateTimeFormatter
import java.util.Locale
import pl.bargor.thesaurus.R
import pl.bargor.thesaurus.data.model.ReportTypeFilter
import pl.bargor.thesaurus.data.model.SyncState
import pl.bargor.thesaurus.ui.settings.SettingsAction

private val reportsLocale = Locale.forLanguageTag("pl-PL")
private val reportsMonthFormatter = DateTimeFormatter.ofPattern("LLLL uuuu", reportsLocale)
@Composable
fun ReportsScreen(
    state: ReportsUiState,
    onSelectPeriodMode: (ReportPeriodMode) -> Unit,
    onPreviousPeriod: () -> Unit,
    onNextPeriod: () -> Unit,
    onSelectType: (ReportTypeFilter) -> Unit,
    onCustomFromChange: (String) -> Unit,
    onCustomToChange: (String) -> Unit,
    onApplyCustomPeriod: () -> Unit,
    onOpenEntry: (String) -> Unit,
    onRetry: () -> Unit,
    modifier: Modifier = Modifier,
    onSelectCategory: (String?) -> Unit = {},
    onSelectSubcategory: (String?) -> Unit = {},
    onSelectSort: (ReportEntrySort) -> Unit = {},
    onToggleSortDirection: () -> Unit = {},
    onOpenFilters: () -> Unit = {},
    onDismissFilters: () -> Unit = {},
    onApplyFilters: () -> Unit = {},
    onResetFilters: () -> Unit = {},
    onSelectMembers: (Set<String>?) -> Unit = {},
    onOpenSettings: (() -> Unit)? = null,
) {
    // Dialog drafts belong to this screen instance and are discarded when it leaves composition.
    DisposableEffect(Unit) { onDispose { onDismissFilters() } }
    state.filterDraft?.let { draft ->
        ReportFilterDialog(state, draft, onSelectPeriodMode, onPreviousPeriod, onNextPeriod,
            onSelectType, onCustomFromChange, onCustomToChange, onSelectCategory,
            onSelectSubcategory, onSelectMembers, onSelectSort, onToggleSortDirection,
            onDismissFilters, onApplyFilters, onResetFilters)
    }
    Column(
        modifier = modifier.fillMaxSize().padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Text(stringResource(R.string.navigation_reports), style = MaterialTheme.typography.titleLarge,
                modifier = Modifier.weight(1f).semantics { heading() })
            val description = stringResource(R.string.reports_open_filters)
            val active = stringResource(if (state.hasActiveFilters) R.string.reports_filters_active else R.string.reports_filters_default)
            IconButton(onClick = onOpenFilters, modifier = Modifier.size(48.dp).testTag("reports-open-filters")
                .semantics { contentDescription = description; stateDescription = active }) {
                Box {
                    Icon(Icons.Filled.FilterList, contentDescription = null)
                    if (state.hasActiveFilters) Box(Modifier.align(Alignment.TopEnd).size(8.dp)
                        .background(MaterialTheme.colorScheme.primary, androidx.compose.foundation.shape.CircleShape)
                        .testTag("reports-filters-active"))
                }
            }
            SettingsAction(onOpenSettings)
        }
        Column(Modifier.weight(1f).verticalScroll(rememberScrollState()),
            verticalArrangement = Arrangement.spacedBy(12.dp)) content@ {
            Text(when (state.mode) {
                ReportPeriodMode.MONTH -> state.month.format(reportsMonthFormatter)
                ReportPeriodMode.YEAR -> state.year.toString()
                ReportPeriodMode.CUSTOM -> "${state.customFromInput} – ${state.customToInput}"
            }, modifier = Modifier.testTag("reports-period"))
            if (state.isLoading) {
                CircularProgressIndicator(Modifier.testTag("reports-loading"))
                return@content
            }
            if (state.hasError) {
                Text(
                    stringResource(R.string.reports_load_error),
                    color = MaterialTheme.colorScheme.error,
                    modifier = Modifier.testTag("reports-error"),
                )
                Button(onClick = onRetry, modifier = Modifier.testTag("reports-retry")) {
                    Text(stringResource(R.string.auth_retry))
                }
            }
            when (state.syncState) {
                SyncState.PENDING -> Text(stringResource(R.string.reports_sync_pending), Modifier.testTag("reports-pending"))
                else -> Unit
            }
            if (state.hasError && state.aggregation.totals.isEmpty) return@content
            ReportsTotalCards(state.aggregation.totals)
            if (state.aggregation.totals.isEmpty) {
                Text(stringResource(R.string.empty_reports), Modifier.testTag("reports-empty"))
                state.balanceTrend?.let { ReportsBalanceChart(it) }
                return@content
            }
            val categories = state.aggregation.categories.map { value ->
                NamedCategoryValue(
                    state.entries.firstOrNull { it.entry.categoryId == value.categoryId }?.categoryName
                        ?: stringResource(R.string.reports_unknown_category),
                    state.entries.firstOrNull { it.entry.categoryId == value.categoryId }?.categoryColor,
                    value,
                )
            }
            CategoryDonutChart(categories)
            state.balanceTrend?.let { ReportsBalanceChart(it) }
            if (state.mode == ReportPeriodMode.YEAR) {
                TrendChart(state.aggregation.trend, state.typeFilter)
            }
            Text(
                stringResource(R.string.reports_entries_heading),
                style = MaterialTheme.typography.titleLarge,
                modifier = Modifier.semantics { heading() },
            )
            state.entries.forEach { item ->
                ReportEntryCard(item, onOpenEntry)
            }
        }
    }
}
