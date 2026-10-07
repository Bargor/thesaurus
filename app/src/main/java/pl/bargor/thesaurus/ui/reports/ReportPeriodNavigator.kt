package pl.bargor.thesaurus.ui.reports

import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import java.time.format.DateTimeFormatter
import java.util.Locale
import pl.bargor.thesaurus.R

private val reportsLocale = Locale.forLanguageTag("pl-PL")
private val reportsMonthFormatter = DateTimeFormatter.ofPattern("LLLL uuuu", reportsLocale)
@Composable
internal fun PeriodNavigator(state: ReportsUiState, previous: () -> Unit, next: () -> Unit) {
    val label = when (state.mode) {
        ReportPeriodMode.MONTH -> state.month.format(reportsMonthFormatter).replaceFirstChar { it.titlecase(reportsLocale) }
        ReportPeriodMode.YEAR -> state.year.toString()
        ReportPeriodMode.CUSTOM -> ""
    }
    val previousDescription = stringResource(if (state.mode == ReportPeriodMode.MONTH) R.string.reports_previous_month else R.string.reports_previous_year)
    val nextDescription = stringResource(if (state.mode == ReportPeriodMode.MONTH) R.string.reports_next_month else R.string.reports_next_year)
    Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
        TextButton(
            onClick = previous,
            modifier = Modifier.testTag("reports-previous-period").semantics {
                contentDescription = previousDescription
            },
        ) { Text("‹") }
        Text(label, style = MaterialTheme.typography.titleLarge, modifier = Modifier.weight(1f).testTag("reports-period"))
        TextButton(
            onClick = next,
            enabled = when (state.mode) {
                ReportPeriodMode.MONTH -> state.month < java.time.YearMonth.from(state.today)
                ReportPeriodMode.YEAR -> state.year < java.time.Year.from(state.today)
                ReportPeriodMode.CUSTOM -> false
            },
            modifier = Modifier.testTag("reports-next-period").semantics {
                contentDescription = nextDescription
            },
        ) { Text("›") }
    }
}
