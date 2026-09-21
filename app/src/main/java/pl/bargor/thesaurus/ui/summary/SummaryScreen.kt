package pl.bargor.thesaurus.ui.summary

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
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

@Composable
fun SummaryScreen(
    state: SummaryUiState,
    onSelectPeriodMode: (SummaryPeriodMode) -> Unit,
    onPreviousPeriod: () -> Unit,
    onNextPeriod: () -> Unit,
    onRetry: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(
        modifier = modifier.fillMaxSize().padding(16.dp),
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
            CircularProgressIndicator(modifier = Modifier.testTag("summary-loading"))
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
    Card(modifier = Modifier.fillMaxWidth()) {
        Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text(stringResource(labelRes), style = MaterialTheme.typography.labelLarge)
            val prefix = if (signed && amount.signum() > 0) "+" else ""
            Text(
                prefix + NumberFormat.getCurrencyInstance(polishLocale).format(BigDecimal(amount, 2)),
                style = MaterialTheme.typography.headlineSmall,
                modifier = Modifier.testTag(tag),
            )
        }
    }
}
