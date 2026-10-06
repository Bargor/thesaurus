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
import pl.bargor.thesaurus.data.model.SummaryPeriod
import java.math.BigDecimal
import java.math.BigInteger
import java.math.MathContext
import pl.bargor.thesaurus.data.model.PlnMoney
import pl.bargor.thesaurus.data.model.PlnSign
import java.time.LocalDate
import java.time.format.DateTimeFormatter
import java.util.Locale

private val balanceLocale = Locale.forLanguageTag("pl-PL")
private val balanceDateFormatter = DateTimeFormatter.ofPattern("d MMM uuuu", balanceLocale)
private fun BigInteger.balanceCurrency(): String =
    PlnMoney.currency(this, PlnSign.EXPLICIT_POSITIVE)
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
    val value = (if (signum() > 0) "+" else "−") + PlnMoney.chartNumber(amount.movePointLeft(power))
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
        if (trend.entryCount == 0 && trend.startBalanceGrosze.signum() == 0) {
            Text(stringResource(R.string.reports_balance_empty), Modifier.fillMaxWidth().testTag("reports-balance-empty"))
            return@Column
        }
        Text(stringResource(if (trend.granularity == ReportBalanceGranularity.DAILY)
            R.string.reports_balance_daily else R.string.reports_balance_monthly),
            modifier = Modifier.fillMaxWidth(), style = MaterialTheme.typography.bodySmall)
        val points = remember(trend) { trend.buckets.map { ReportAmountChartPoint(it.from, it.to, it.balanceGrosze) } }
        LabeledReportAmountChart(points, trend.period, trend.startBalanceGrosze, step = true,
            chartTag = "reports-balance-chart", description = description, dateLabel = { it.balanceDate() })
        Text(stringResource(R.string.reports_balance_end_value, endValue), Modifier.fillMaxWidth().testTag("reports-balance-total"))
        if (trend.sparse) Text(stringResource(R.string.reports_balance_sparse), Modifier.fillMaxWidth(), style = MaterialTheme.typography.bodySmall)
    }
}

internal data class ReportAmountChartPoint(val from: LocalDate, val to: LocalDate, val amountGrosze: BigInteger)

/** Shared exact amount scale and measured axes for cumulative steps and monthly results. */
@Composable
internal fun LabeledReportAmountChart(
    points: List<ReportAmountChartPoint>,
    period: SummaryPeriod,
    openingBalance: BigInteger?,
    step: Boolean,
    chartTag: String,
    description: String,
    dateLabel: (LocalDate) -> String,
) {
    if (points.isEmpty()) return
    val axisTag = chartTag.removeSuffix("-chart")
    val lineColor = MaterialTheme.colorScheme.primary
    val baselineColor = MaterialTheme.colorScheme.onSurfaceVariant
    val labelStyle = MaterialTheme.typography.labelSmall.copy(color = MaterialTheme.colorScheme.onSurface)
    val axisUnits = BalanceAxisUnits(stringResource(R.string.reports_balance_axis_zero),
        stringResource(R.string.reports_balance_axis_thousand), stringResource(R.string.reports_balance_axis_million),
        stringResource(R.string.reports_balance_axis_billion), stringResource(R.string.reports_balance_axis_trillion),
        stringResource(R.string.reports_balance_axis_scientific))
    val measurer = rememberTextMeasurer()
    val density = LocalDensity.current
    val pointDescriptions = points.joinToString("; ") {
        if (it.from == it.to) "${dateLabel(it.to)}: ${it.amountGrosze.balanceCurrency()}"
        else "${dateLabel(it.from)} – ${dateLabel(it.to)}: ${it.amountGrosze.balanceCurrency()}"
    }
    BoxWithConstraints(Modifier.fillMaxWidth()) {
        val widthPx = with(density) { maxWidth.roundToPx() }
        val axisCap = (widthPx * .38f).toInt().coerceAtLeast(1)
        val low = points.minOf { it.amountGrosze }.min(openingBalance ?: BigInteger.ZERO).min(BigInteger.ZERO)
        val high = points.maxOf { it.amountGrosze }.max(openingBalance ?: BigInteger.ZERO).max(BigInteger.ZERO)
        val tickValues = (listOf(high, BigInteger.ZERO, low) +
            if (high != low && (high.signum() == 0 || low.signum() == 0)) listOf((high + low) / BigInteger.valueOf(2))
            else emptyList()).distinct()
        val compact = tickValues.any { measurer.measure(it.balanceCurrency(), labelStyle).size.width > axisCap }
        val ticks = tickValues.map { it to measurer.measure(it.axisCurrency(compact, axisUnits),
            labelStyle.copy(textAlign = TextAlign.End),
            constraints = Constraints(minWidth = axisCap, maxWidth = axisCap)) }
        val axisWidth = (ticks.maxOfOrNull { it.second.size.width } ?: 0).toFloat()
        val axisDescription = ticks.joinToString("; ") { it.first.balanceCurrency() }
        Column(Modifier.fillMaxWidth().testTag("$axisTag-y-axis")
            .semantics { contentDescription = axisDescription }) {
            Canvas(Modifier.fillMaxWidth().height(180.dp).testTag(chartTag)
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
                val firstDay = period.from.toEpochDay()
                val span = period.to.toEpochDay() - firstDay
                fun x(date: LocalDate): Float = when {
                    step -> left + plotWidth * ((date.toEpochDay() - firstDay + 1).toDouble() / (span + 1)).toFloat()
                    span == 0L -> left + plotWidth / 2f
                    else -> left + plotWidth * ((date.toEpochDay() - firstDay).toDouble() / span).toFloat()
                }
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
                var previous: Offset? = openingBalance?.let { Offset(left, y(it)) }
                previous?.let { drawCircle(lineColor, 4.dp.toPx(), it) }
                points.forEach { point ->
                    val current = Offset(x(point.to), y(point.amountGrosze))
                    previous?.let { before ->
                        if (step) {
                            val horizontalEnd = Offset(current.x, before.y)
                            drawLine(lineColor, before, horizontalEnd, 3.dp.toPx(), StrokeCap.Round)
                            drawLine(lineColor, horizontalEnd, current, 3.dp.toPx(), StrokeCap.Round)
                        } else drawLine(lineColor, before, current, 3.dp.toPx(), StrokeCap.Round)
                    }
                    if (!step) drawCircle(lineColor, 4.dp.toPx(), current)
                    previous = current
                }
                if (step) previous?.let { drawCircle(lineColor, 4.dp.toPx(), it) }
            }
            val dateSpan = period.to.toEpochDay() - period.from.toEpochDay()
            // For monthly results choose an actual month point, so the middle label never
            // suggests a daily result or sits at an unrelated equal-spacing column.
            val midpoint = if (step) period.from.plusDays(dateSpan / 2) else points[points.size / 2].to
            val dates = listOf(period.from, midpoint, period.to)
            val labels = dates.map(dateLabel)
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
                .testTag("$axisTag-x-axis"), content = {
                Text(labels.first(), Modifier.fillMaxWidth(), style = labelStyle,
                    textAlign = if (dateSpan == 0L) TextAlign.Center else TextAlign.Start)
                if (showMiddle) Text(labels[1], Modifier.fillMaxWidth(), style = labelStyle, textAlign = TextAlign.Center)
                if (dateSpan != 0L) Text(labels.last(), Modifier.fillMaxWidth(), style = labelStyle, textAlign = TextAlign.End)
            }) { children, constraints ->
                val width = constraints.maxWidth
                val placeables = children.mapIndexed { index, child ->
                    val allocated = if (dateSpan == 0L) width else if (showMiddle) widths[index] else (width / 2).coerceAtLeast(1)
                    child.measure(Constraints(minWidth = allocated, maxWidth = allocated))
                }
                layout(width, placeables.maxOf { it.height }) {
                    placeables.first().placeRelative(0, 0)
                    if (showMiddle) placeables[1].placeRelative((width * middleRatio - placeables[1].width / 2).toInt(), 0)
                    if (dateSpan != 0L) placeables.last().placeRelative(width - placeables.last().width, 0)
                }
            }
        }
    }
}
