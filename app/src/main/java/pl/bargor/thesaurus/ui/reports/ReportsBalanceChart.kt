package pl.bargor.thesaurus.ui.reports

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import pl.bargor.thesaurus.R
import pl.bargor.thesaurus.data.model.ReportBalanceTrend
import pl.bargor.thesaurus.data.model.ReportBalanceGranularity
import pl.bargor.thesaurus.data.model.ReportBalanceBucket
import java.math.BigDecimal
import java.math.BigInteger
import java.math.MathContext
import java.text.NumberFormat
import java.time.LocalDate
import java.time.format.DateTimeFormatter
import java.util.Locale

private val balanceLocale = Locale.forLanguageTag("pl-PL")
private val balanceDateFormatter = DateTimeFormatter.ofPattern("d MMM uuuu", balanceLocale)
private fun BigInteger.balanceCurrency(): String =
    (if (signum() > 0) "+" else "") + NumberFormat.getCurrencyInstance(balanceLocale).format(BigDecimal(this, 2))
private fun LocalDate.balanceDate() = format(balanceDateFormatter)
private fun ReportBalanceBucket.selectionKey() = "$from/$to/$changeGrosze/$balanceGrosze"

/** The drawing is a step line: each bucket changes the cumulative balance at its end. */
@Composable
fun ReportsBalanceChart(trend: ReportBalanceTrend) {
    var showDetails by rememberSaveable(trend) { mutableStateOf(false) }
    // Zero is a selectable initial point; bucket i occupies selection i + 1.
    var selectedKey by rememberSaveable(trend) { mutableStateOf<String?>(null) }
    // Validate restored selection against dates AND exact values, including after process death
    // when the backend may return a changed report before the saved dialog is restored.
    val selectedIndex = selectedKey?.let { key -> trend.buckets.indexOfFirst { it.selectionKey() == key } + 1 } ?: 0
    fun select(index: Int) { selectedKey = trend.buckets.getOrNull(index - 1)?.selectionKey() }
    val endValue = trend.endBalanceGrosze.balanceCurrency()
    val description = stringResource(R.string.reports_balance_description, endValue)
    Column(Modifier.fillMaxWidth().testTag("reports-balance-section"), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        // Allocate the parent's available width, rather than a rounded intrinsic text width.
        // This also lets exact amounts and headings wrap with large system font scales.
        Text(stringResource(R.string.reports_balance_title), style = MaterialTheme.typography.titleLarge,
            modifier = Modifier.fillMaxWidth().semantics { heading() })
        if (trend.entryCount == 0) {
            Text(stringResource(R.string.reports_balance_empty), Modifier.fillMaxWidth().testTag("reports-balance-empty"))
            return@Column
        }
        Text(stringResource(if (trend.granularity == ReportBalanceGranularity.DAILY)
            R.string.reports_balance_daily else R.string.reports_balance_monthly),
            modifier = Modifier.fillMaxWidth(), style = MaterialTheme.typography.bodySmall)
        val lineColor = MaterialTheme.colorScheme.primary
        val baselineColor = MaterialTheme.colorScheme.onSurfaceVariant
        Canvas(Modifier.fillMaxWidth().height(180.dp).testTag("reports-balance-chart")
            .semantics { contentDescription = description }) {
            val low = trend.buckets.minOfOrNull { it.balanceGrosze }?.min(BigInteger.ZERO) ?: BigInteger.ZERO
            val high = trend.buckets.maxOfOrNull { it.balanceGrosze }?.max(BigInteger.ZERO) ?: BigInteger.ZERO
            val range = high - low
            val inset = 8.dp.toPx()
            val plotWidth = (size.width - inset * 2).coerceAtLeast(0f)
            val plotHeight = (size.height - inset * 2).coerceAtLeast(0f)
            fun y(value: BigInteger): Float = if (range.signum() == 0) size.height / 2f else
                inset + plotHeight * (1f - BigDecimal(value - low).divide(BigDecimal(range), MathContext.DECIMAL64).toFloat())
            val firstDay = trend.period.from.toEpochDay()
            val dayCount = trend.period.to.toEpochDay() - firstDay + 1
            fun x(date: LocalDate) = inset + plotWidth * ((date.toEpochDay() - firstDay + 1).toDouble() / dayCount).toFloat()
            val zero = y(BigInteger.ZERO)
            drawLine(baselineColor, Offset(inset, zero), Offset(inset + plotWidth, zero), 1.dp.toPx())
            var previous = Offset(inset, zero)
            trend.buckets.forEach { bucket ->
                val horizontalEnd = Offset(x(bucket.to), previous.y)
                val current = Offset(horizontalEnd.x, y(bucket.balanceGrosze))
                drawLine(lineColor, previous, horizontalEnd, 3.dp.toPx(), StrokeCap.Round)
                drawLine(lineColor, horizontalEnd, current, 3.dp.toPx(), StrokeCap.Round)
                previous = current
            }
            drawCircle(lineColor, 4.dp.toPx(), Offset(inset, zero))
            drawCircle(lineColor, 4.dp.toPx(), previous)
        }
        Text(stringResource(R.string.reports_balance_zero), Modifier.fillMaxWidth(), style = MaterialTheme.typography.bodySmall)
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
            Text(trend.period.from.balanceDate(), Modifier.weight(1f), style = MaterialTheme.typography.bodySmall)
            Text(trend.period.to.balanceDate(), Modifier.weight(1f), style = MaterialTheme.typography.bodySmall)
        }
        Text(stringResource(R.string.reports_balance_end_value, endValue), Modifier.fillMaxWidth().testTag("reports-balance-total"))
        if (trend.sparse) Text(stringResource(R.string.reports_balance_sparse), Modifier.fillMaxWidth(), style = MaterialTheme.typography.bodySmall)
        OutlinedButton(onClick = { showDetails = true }, modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp)
            .testTag("reports-balance-details")) { Text(stringResource(R.string.reports_balance_details), Modifier.fillMaxWidth(), textAlign = TextAlign.Center) }
    }
    if (showDetails) Dialog(onDismissRequest = { showDetails = false }) {
        Surface(shape = MaterialTheme.shapes.large) {
            Column(Modifier.fillMaxWidth().verticalScroll(rememberScrollState()).padding(20.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Text(stringResource(R.string.reports_balance_details), style = MaterialTheme.typography.titleLarge,
                    modifier = Modifier.fillMaxWidth().semantics { heading() })
                val bucket = trend.buckets.getOrNull(selectedIndex - 1)
                val dateLabel = if (bucket == null) stringResource(R.string.reports_balance_initial, trend.period.from.balanceDate())
                    else if (bucket.from == bucket.to) bucket.from.balanceDate()
                    else "${bucket.from.balanceDate()} – ${bucket.to.balanceDate()}"
                val balance = bucket?.balanceGrosze ?: BigInteger.ZERO
                val sign = stringResource(when (balance.signum()) {
                    1 -> R.string.reports_balance_positive
                    -1 -> R.string.reports_balance_negative
                    else -> R.string.reports_balance_neutral
                })
                Column(Modifier.fillMaxWidth().testTag("reports-balance-bucket"), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text(dateLabel, Modifier.fillMaxWidth())
                    Text(stringResource(R.string.reports_balance_change, (bucket?.changeGrosze ?: BigInteger.ZERO).balanceCurrency()), Modifier.fillMaxWidth())
                    Text(stringResource(R.string.reports_balance_value, balance.balanceCurrency(), sign), Modifier.fillMaxWidth())
                }
                // Vertical controls keep complete labels usable on narrow screens and large fonts.
                TextButton(onClick = { select(selectedIndex - 1) }, enabled = selectedIndex > 0,
                    modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp).testTag("reports-balance-previous")) {
                    Text(stringResource(R.string.reports_balance_previous), Modifier.fillMaxWidth(), textAlign = TextAlign.Center)
                }
                TextButton(onClick = { select(selectedIndex + 1) }, enabled = selectedIndex < trend.buckets.size,
                    modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp).testTag("reports-balance-next")) {
                    Text(stringResource(R.string.reports_balance_next), Modifier.fillMaxWidth(), textAlign = TextAlign.Center)
                }
                TextButton(onClick = { select(0) }, modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp)
                    .testTag("reports-balance-start")) { Text(stringResource(R.string.reports_balance_start), Modifier.fillMaxWidth(), textAlign = TextAlign.Center) }
                TextButton(onClick = { select(trend.buckets.size) }, modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp)
                    .testTag("reports-balance-end")) { Text(stringResource(R.string.reports_balance_end), Modifier.fillMaxWidth(), textAlign = TextAlign.Center) }
                OutlinedButton(onClick = { showDetails = false }, modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp)
                    .testTag("reports-balance-close")) { Text(stringResource(R.string.reports_balance_close), Modifier.fillMaxWidth(), textAlign = TextAlign.Center) }
            }
        }
    }
}
