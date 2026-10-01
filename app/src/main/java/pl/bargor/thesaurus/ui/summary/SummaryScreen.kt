package pl.bargor.thesaurus.ui.summary

import androidx.compose.foundation.layout.Column
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.SwapHoriz
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.onClick
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.unit.dp
import java.time.Year
import java.time.YearMonth
import java.time.format.DateTimeFormatter
import java.util.Locale
import pl.bargor.thesaurus.R
import pl.bargor.thesaurus.data.model.SyncState
import pl.bargor.thesaurus.ui.entries.EntryCard
import pl.bargor.thesaurus.ui.entries.EntryListRow
import pl.bargor.thesaurus.ui.entries.PolishDateFormatter
import pl.bargor.thesaurus.ui.entries.entryListRows

private val polishLocale = Locale.forLanguageTag("pl-PL")
private val monthFormatter = DateTimeFormatter.ofPattern("LLLL uuuu", polishLocale)

@Composable
fun SummaryScreen(
    state: SummaryUiState,
    onSelectPeriodMode: (SummaryPeriodMode) -> Unit,
    onPreviousPeriod: () -> Unit,
    onNextPeriod: () -> Unit,
    modifier: Modifier = Modifier,
    onOpenPeriod: (SummaryPeriodKey) -> Unit = {},
    onClosePeriod: () -> Unit = {},
    onOpenEntry: (String) -> Unit = {},
    onRetry: () -> Unit = {},
) {
    // Both list states stay in composition while details are open. Their built-in Saver
    // also restores the position after an edit route or recreation.
    val overviewListState = rememberLazyListState()
    val detailListState = rememberLazyListState()
    val detail = state.detailCard
    var detailScrollKey by rememberSaveable { mutableStateOf<String?>(null) }
    LaunchedEffect(detail?.key) {
        val activeKey = detail?.tagKey ?: return@LaunchedEffect
        if (activeKey != detailScrollKey) {
            detailScrollKey = activeKey
            detailListState.scrollToItem(0)
        }
    }
    val detailRows = remember(state.detailEntries) { entryListRows(state.detailEntries) }
    val loadingLabel = stringResource(R.string.accessibility_loading)
    BackHandler(enabled = detail != null, onBack = onClosePeriod)
    Column(modifier = modifier.fillMaxSize().padding(horizontal = 16.dp)) {
        if (detail == null) {
            SummaryPeriodNavigator(
                label = if (state.mode == SummaryPeriodMode.MONTH) state.year.toString()
                    else stringResource(R.string.summary_all_years),
                previousDescription = stringResource(R.string.summary_previous_year),
                nextDescription = stringResource(R.string.summary_next_year),
                onPrevious = onPreviousPeriod,
                onNext = onNextPeriod,
                scopeDescription = stringResource(if (state.mode == SummaryPeriodMode.MONTH)
                    R.string.summary_scope_month else R.string.summary_scope_year),
                toggleDescription = stringResource(if (state.mode == SummaryPeriodMode.MONTH)
                    R.string.summary_switch_to_year else R.string.summary_switch_to_month),
                onToggle = { onSelectPeriodMode(if (state.mode == SummaryPeriodMode.MONTH)
                    SummaryPeriodMode.YEAR else SummaryPeriodMode.MONTH) },
                nextEnabled = state.year.value < state.currentYear,
                showNavigation = state.mode == SummaryPeriodMode.MONTH,
            )
        } else Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
            TextButton(onClick = onClosePeriod, modifier = Modifier.heightIn(min = 48.dp)
                .testTag("summary-detail-back")) { Text(stringResource(R.string.summary_back_overview)) }
        }
        LazyColumn(
            modifier = Modifier.weight(1f).fillMaxWidth()
                .testTag(if (detail == null) "summary-list" else "summary-detail-list"),
            state = if (detail == null) overviewListState else detailListState,
            contentPadding = PaddingValues(bottom = 16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            if (state.syncState == SyncState.PENDING) item(key = "pending") {
                Text(stringResource(R.string.summary_sync_pending), style = MaterialTheme.typography.bodyMedium)
            }
            if (state.isLoading) item(key = "loading") {
                CircularProgressIndicator(Modifier.padding(24.dp).testTag("summary-loading")
                    .semantics { contentDescription = loadingLabel })
            }
            if (state.hasError) item(key = "error") {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text(stringResource(R.string.summary_load_error), color = MaterialTheme.colorScheme.error)
                    Button(onClick = onRetry, modifier = Modifier.testTag("summary-retry")) {
                        Text(stringResource(R.string.auth_retry))
                    }
                }
            }
            if (detail == null) {
                if (!state.isLoading && !state.hasError && state.cards.isEmpty()) item(key = "empty") {
                    Text(stringResource(R.string.summary_no_entries), modifier = Modifier.testTag("summary-empty"))
                }
                items(state.cards, key = { it.tagKey }, contentType = { "summary-card" }) { card ->
                    SummaryPeriodCardContent(card, onClick = { onOpenPeriod(card.key) })
                }
            } else {
                item(key = "detail-period") {
                    Column(Modifier.testTag("summary-detail-period")) { SummaryPeriodCardContent(detail) }
                }
                if (!state.isLoading && !state.hasError && detailRows.isEmpty()) item(key = "detail-empty") {
                    Text(stringResource(R.string.summary_no_entries), modifier = Modifier.testTag("summary-detail-empty"))
                }
                items(detailRows, key = { it.key }, contentType = {
                    if (it is EntryListRow.DateHeading) "date" else "entry"
                }) { row ->
                    when (row) {
                        is EntryListRow.DateHeading -> Text(row.date.format(PolishDateFormatter),
                            style = MaterialTheme.typography.labelLarge,
                            modifier = Modifier.testTag(row.key).semantics { heading() })
                        is EntryListRow.Entry -> Column {
                            EntryCard(row.item, onOpenEntry)
                            Text(stringResource(R.string.browse_entry_author, row.item.authorName),
                                style = MaterialTheme.typography.labelSmall,
                                modifier = Modifier.padding(start = 14.dp, top = 4.dp))
                        }
                    }
                }
            }
        }
    }
}

/** Period controls accept a display label, so a later summary can reuse them for other periods. */
@Composable
internal fun SummaryPeriodHeader(
    month: YearMonth,
    year: Year,
    mode: SummaryPeriodMode,
    onSelectPeriodMode: (SummaryPeriodMode) -> Unit,
    onPrevious: () -> Unit,
    onNext: () -> Unit,
    testTagPrefix: String = "summary",
) {
    SummaryPeriodNavigator(
        label = when (mode) {
            SummaryPeriodMode.MONTH -> month.format(monthFormatter).replaceFirstChar { it.titlecase(polishLocale) }
            SummaryPeriodMode.YEAR -> year.toString()
        },
        previousDescription = stringResource(if (mode == SummaryPeriodMode.MONTH)
            R.string.summary_previous_month else R.string.summary_previous_year),
        nextDescription = stringResource(if (mode == SummaryPeriodMode.MONTH)
            R.string.summary_next_month else R.string.summary_next_year),
        onPrevious = onPrevious,
        onNext = onNext,
        scopeDescription = stringResource(if (mode == SummaryPeriodMode.MONTH)
            R.string.summary_scope_month else R.string.summary_scope_year),
        toggleDescription = stringResource(if (mode == SummaryPeriodMode.MONTH)
            R.string.summary_switch_to_year else R.string.summary_switch_to_month),
        onToggle = { onSelectPeriodMode(if (mode == SummaryPeriodMode.MONTH)
            SummaryPeriodMode.YEAR else SummaryPeriodMode.MONTH) },
        testTagPrefix = testTagPrefix,
    )
}

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
    testTagPrefix: String = "summary",
    previousEnabled: Boolean = true,
    nextEnabled: Boolean = true,
    showNavigation: Boolean = true,
) {
    Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
        if (showNavigation) TextButton(
            onClick = onPrevious,
            enabled = previousEnabled,
            modifier = Modifier.testTag("$testTagPrefix-previous-period").semantics { contentDescription = previousDescription },
        ) { Text("‹") }
        TextButton(
            onClick = onToggle,
            modifier = Modifier.weight(1f).heightIn(min = 48.dp).testTag("$testTagPrefix-period").semantics {
                heading()
                stateDescription = scopeDescription
                onClick(label = toggleDescription, action = null)
            },
        ) {
            Text(label, style = MaterialTheme.typography.titleLarge, modifier = Modifier.weight(1f))
            Icon(Icons.Default.SwapHoriz, contentDescription = null, modifier = Modifier.size(18.dp))
        }
        if (showNavigation) TextButton(
            onClick = onNext,
            enabled = nextEnabled,
            modifier = Modifier.testTag("$testTagPrefix-next-period").semantics { contentDescription = nextDescription },
        ) { Text("›") }
    }
}
