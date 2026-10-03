package pl.bargor.thesaurus.ui.reports

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.layout.Layout
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.getTextLayoutResult
import androidx.compose.ui.text.drawText
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.Constraints
import pl.bargor.thesaurus.R
import pl.bargor.thesaurus.data.model.ReportBalanceTrend
import pl.bargor.thesaurus.data.model.ReportBalanceGranularity
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

/** Compact ticks retain PLN units; semantic descriptions always use the exact amount. */
private data class BalanceAxisUnits(val zero: String, val thousand: String, val million: String,
    val billion: String, val trillion: String, val scientific: String)

private fun BigInteger.axisCurrency(compact: Boolean, units: BalanceAxisUnits): String {
    if (!compact) return balanceCurrency()
    if (signum() == 0) return units.zero
    val amount = BigDecimal(abs(), 2)
    val exponent = amount.precision() - amount.scale() - 1
    if (exponent < 3) return balanceCurrency()
    val power = when {
        exponent < 6 -> 3
        exponent < 9 -> 6
        exponent < 12 -> 9
        exponent < 15 -> 12
        else -> exponent
    }
    val template = when (power) {
        3 -> units.thousand
        6 -> units.million
        9 -> units.billion
        12 -> units.trillion
        else -> units.scientific
    }
    val formatter = NumberFormat.getNumberInstance(balanceLocale).apply { maximumFractionDigits = 2 }
    val value = (if (signum() > 0) "+" else "−") + formatter.format(amount.movePointLeft(power))
    return String.format(balanceLocale, template, value, power)
}

/** The drawing is a step line: each bucket changes the cumulative balance at its end. */
@Composable
fun ReportsBalanceChart(trend: ReportBalanceTrend) {
    val endValue = trend.endBalanceGrosze.balanceCurrency()
    val description = stringResource(R.string.reports_balance_description, trend.startBalanceGrosze.balanceCurrency(), endValue)
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
        val labelStyle = MaterialTheme.typography.labelSmall.copy(color = MaterialTheme.colorScheme.onSurface)
        val axisUnits = BalanceAxisUnits(stringResource(R.string.reports_balance_axis_zero),
            stringResource(R.string.reports_balance_axis_thousand), stringResource(R.string.reports_balance_axis_million),
            stringResource(R.string.reports_balance_axis_billion), stringResource(R.string.reports_balance_axis_trillion),
            stringResource(R.string.reports_balance_axis_scientific))
        val measurer = rememberTextMeasurer()
        val density = LocalDensity.current
        val pointDescriptions = remember(trend) {
            trend.buckets.joinToString("; ") { "${it.from.balanceDate()} – ${it.to.balanceDate()}: ${it.balanceGrosze.balanceCurrency()}" }
        }
        BoxWithConstraints(Modifier.fillMaxWidth()) {
            val widthPx = with(density) { maxWidth.roundToPx() }
            val axisCap = (widthPx * .38f).toInt().coerceAtLeast(1)
            val low = (trend.buckets.minOfOrNull { it.balanceGrosze } ?: trend.startBalanceGrosze)
                .min(trend.startBalanceGrosze).min(BigInteger.ZERO)
            val high = (trend.buckets.maxOfOrNull { it.balanceGrosze } ?: trend.startBalanceGrosze)
                .max(trend.startBalanceGrosze).max(BigInteger.ZERO)
            val tickValues = (listOf(high, BigInteger.ZERO, low) +
                if (high != low && (high.signum() == 0 || low.signum() == 0)) listOf((high + low) / BigInteger.valueOf(2))
                else emptyList()).distinct()
            val compact = tickValues.any { measurer.measure(it.balanceCurrency(), labelStyle).size.width > axisCap }
            val ticks = tickValues.map { it to measurer.measure(it.axisCurrency(compact, axisUnits),
                labelStyle.copy(textAlign = TextAlign.End),
                constraints = Constraints(minWidth = axisCap, maxWidth = axisCap)) }
            val axisWidth = (ticks.maxOfOrNull { it.second.size.width } ?: 0).toFloat()
            val axisDescription = ticks.joinToString("; ") { it.first.balanceCurrency() }
            Column(Modifier.fillMaxWidth().testTag("reports-balance-y-axis")
                .semantics { contentDescription = axisDescription }) {
                Canvas(Modifier.fillMaxWidth().height(180.dp).testTag("reports-balance-chart")
                    .semantics {
                        contentDescription = "$description $pointDescriptions"
                        // Expose the actual measured Canvas labels for accessibility/layout QA.
                        getTextLayoutResult { results -> results.addAll(ticks.map { it.second }); true }
                    }) {
                    val range = high - low
                    val inset = 8.dp.toPx()
                    val left = axisWidth + 8.dp.toPx()
                    val plotWidth = (size.width - left - inset).coerceAtLeast(0f)
                    val plotHeight = (size.height - inset * 2).coerceAtLeast(0f)
                    fun y(value: BigInteger): Float = if (range.signum() == 0) size.height / 2f else
                        inset + plotHeight * (1f - BigDecimal(value - low).divide(BigDecimal(range), MathContext.DECIMAL64).toFloat())
                    val firstDay = trend.period.from.toEpochDay()
                    val dayCount = trend.period.to.toEpochDay() - firstDay + 1
                    fun x(date: LocalDate) = left + plotWidth * ((date.toEpochDay() - firstDay + 1).toDouble() / dayCount).toFloat()
                    val zero = y(BigInteger.ZERO)
                    drawLine(baselineColor, Offset(left, zero), Offset(left + plotWidth, zero), 1.dp.toPx())
                    // Reserve zero first, then omit any measured tick that would collide.
                    val occupied = mutableListOf<Pair<Float, Float>>()
                    ticks.sortedBy { if (it.first.signum() == 0) 0 else 1 }.forEach { (amount, label) ->
                        val top = (y(amount) - label.size.height / 2f)
                            .coerceIn(0f, (size.height - label.size.height).coerceAtLeast(0f))
                        val bottom = top + label.size.height
                        if (occupied.none { top < it.second + 4.dp.toPx() && bottom > it.first - 4.dp.toPx() }) {
                            drawText(label, topLeft = Offset(axisWidth - label.size.width, top))
                            drawLine(baselineColor, Offset(left - 4.dp.toPx(), y(amount)), Offset(left, y(amount)), 1.dp.toPx())
                            occupied += top to bottom
                        }
                    }
                    var previous = Offset(left, y(trend.startBalanceGrosze))
                    trend.buckets.forEach { bucket ->
                        val horizontalEnd = Offset(x(bucket.to), previous.y)
                        val current = Offset(horizontalEnd.x, y(bucket.balanceGrosze))
                        drawLine(lineColor, previous, horizontalEnd, 3.dp.toPx(), StrokeCap.Round)
                        drawLine(lineColor, horizontalEnd, current, 3.dp.toPx(), StrokeCap.Round)
                        previous = current
                    }
                    drawCircle(lineColor, 4.dp.toPx(), Offset(left, y(trend.startBalanceGrosze)))
                    drawCircle(lineColor, 4.dp.toPx(), previous)
                }
                val dateSpan = trend.period.to.toEpochDay() - trend.period.from.toEpochDay()
                val midpoint = trend.period.from.plusDays(dateSpan / 2)
                val dates = listOf(trend.period.from, midpoint, trend.period.to)
                val labels = dates.map { it.balanceDate() }
                val widths = labels.map { measurer.measure(it, labelStyle).size.width + 2 }
                val axisPadding = with(density) { axisWidth.toDp() } + 8.dp
                val plotWidthPx = widthPx - axisWidth - with(density) { 16.dp.toPx() }
                val middleRatio = if (dateSpan == 0L) .5 else (midpoint.toEpochDay() - dates.first().toEpochDay()).toDouble() / dateSpan
                val middleCenter = plotWidthPx * middleRatio
                val gap = with(density) { 8.dp.toPx() }
                val showMiddle = midpoint != dates.first() && midpoint != dates.last() &&
                    widths.first() + gap < middleCenter - widths[1] / 2 &&
                    middleCenter + widths[1] / 2 + gap < plotWidthPx - widths.last()
                Layout(modifier = Modifier.fillMaxWidth().padding(start = axisPadding, end = 8.dp)
                    .testTag("reports-balance-x-axis"), content = {
                    Text(labels.first(), Modifier.fillMaxWidth(), style = labelStyle)
                    if (showMiddle) Text(labels[1], Modifier.fillMaxWidth(), style = labelStyle, textAlign = TextAlign.Center)
                    Text(labels.last(), Modifier.fillMaxWidth(), style = labelStyle, textAlign = TextAlign.End)
                }) { children, constraints ->
                    val width = constraints.maxWidth
                    val placeables = children.mapIndexed { index, child ->
                        val allocated = if (showMiddle) widths[index] else (width / 2).coerceAtLeast(1)
                        child.measure(Constraints(minWidth = allocated, maxWidth = allocated))
                    }
                    layout(width, placeables.maxOf { it.height }) {
                        placeables.first().placeRelative(0, 0)
                        if (showMiddle) placeables[1].placeRelative((width * middleRatio - placeables[1].width / 2).toInt(), 0)
                        placeables.last().placeRelative(width - placeables.last().width, 0)
                    }
                }
            }
        }
        Text(stringResource(R.string.reports_balance_end_value, endValue), Modifier.fillMaxWidth().testTag("reports-balance-total"))
        if (trend.sparse) Text(stringResource(R.string.reports_balance_sparse), Modifier.fillMaxWidth(), style = MaterialTheme.typography.bodySmall)
    }
}
