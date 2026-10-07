package pl.bargor.thesaurus.ui.reports

import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import java.time.format.DateTimeFormatter
import java.util.Locale
import pl.bargor.thesaurus.R
import pl.bargor.thesaurus.data.model.ReportTrendValue
import pl.bargor.thesaurus.data.model.ReportTypeFilter
import pl.bargor.thesaurus.data.model.SummaryPeriod

private val reportsLocale = Locale.forLanguageTag("pl-PL")
/** Shows the monthly buckets provided by [ReportsViewModel] for annual reports. */
@Composable
internal fun TrendChart(values: List<ReportTrendValue>, type: ReportTypeFilter) {
    val displayValues = (if (type == ReportTypeFilter.EXPENSE) {
        values.map { it.copy(amountGrosze = it.amountGrosze.abs()) }
    } else values).sortedBy { it.date }
    val formatter = DateTimeFormatter.ofPattern("LLL uuuu", reportsLocale)
    Text(stringResource(R.string.reports_trend_monthly), style = MaterialTheme.typography.titleLarge,
        modifier = Modifier.fillMaxWidth().testTag("reports-trend-title").semantics { heading() })
    if (displayValues.isEmpty()) return
    val points = displayValues.map { ReportAmountChartPoint(it.date, it.date, it.amountGrosze) }
    val description = stringResource(R.string.reports_trend_chart_description,
        stringResource(R.string.reports_trend_monthly), "")
    LabeledReportAmountChart(points, SummaryPeriod(displayValues.first().date, displayValues.last().date),
        openingBalance = null, step = false, chartTag = "reports-trend-chart", description = description,
        dateLabel = { it.format(formatter) })
}
