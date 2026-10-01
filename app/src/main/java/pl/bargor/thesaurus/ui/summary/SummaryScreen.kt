package pl.bargor.thesaurus.ui.summary

import androidx.compose.foundation.layout.Column
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
import androidx.compose.runtime.Composable
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

private val polishLocale = Locale.forLanguageTag("pl-PL")
private val monthFormatter = DateTimeFormatter.ofPattern("LLLL uuuu", polishLocale)

@Composable
fun SummaryScreen(
    state: SummaryUiState,
    onSelectPeriodMode: (SummaryPeriodMode) -> Unit,
    onPreviousPeriod: () -> Unit,
    onNextPeriod: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(modifier = modifier.fillMaxSize().padding(16.dp)) {
        SummaryPeriodHeader(state.month, state.year, state.mode, onSelectPeriodMode, onPreviousPeriod, onNextPeriod)
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
) {
    Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
        TextButton(
            onClick = onPrevious,
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
        TextButton(
            onClick = onNext,
            modifier = Modifier.testTag("$testTagPrefix-next-period").semantics { contentDescription = nextDescription },
        ) { Text("›") }
    }
}
