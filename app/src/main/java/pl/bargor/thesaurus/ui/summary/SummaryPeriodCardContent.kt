package pl.bargor.thesaurus.ui.summary

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowDownward
import androidx.compose.material.icons.filled.ArrowUpward
import androidx.compose.material3.Card
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.graphics.vector.path
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.onClick
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import java.math.BigDecimal
import java.math.BigInteger
import java.math.RoundingMode
import pl.bargor.thesaurus.data.model.PlnMoney
import java.time.format.DateTimeFormatter
import java.util.Locale
import pl.bargor.thesaurus.R

private val summaryLocale = Locale.forLanguageTag("pl-PL")
private val summaryMonthFormatter = DateTimeFormatter.ofPattern("LLLL uuuu", summaryLocale)
private val summaryBalanceIcon = ImageVector.Builder(
    name = "SummaryBalance", defaultWidth = 24.dp, defaultHeight = 24.dp,
    viewportWidth = 24f, viewportHeight = 24f,
).apply {
    path(fill = SolidColor(Color.Black)) {
        moveTo(5f, 3f)
        lineTo(19f, 3f)
        lineTo(19f, 5f)
        lineTo(8.5f, 5f)
        lineTo(15.5f, 12f)
        lineTo(8.5f, 19f)
        lineTo(19f, 19f)
        lineTo(19f, 21f)
        lineTo(5f, 21f)
        lineTo(5f, 19f)
        lineTo(12f, 12f)
        lineTo(5f, 5f)
        close()
    }
}.build()

internal fun summaryPeriodLabel(card: SummaryPeriodCard): String =
    if (card.key.mode == SummaryPeriodMode.MONTH) card.period.from.format(summaryMonthFormatter)
        .replaceFirstChar { it.titlecase(summaryLocale) } else card.period.from.year.toString()

internal val SummaryPeriodCard.tagKey: String get() = when (key.mode) {
    SummaryPeriodMode.MONTH -> "month-${key.year}-${key.month}"
    SummaryPeriodMode.YEAR -> "year-${key.year}"
}

internal fun summaryCurrency(amount: BigInteger): String =
    PlnMoney.currency(amount)

@Composable
internal fun SummaryPeriodCardContent(card: SummaryPeriodCard, onClick: (() -> Unit)? = null) {
    val label = summaryPeriodLabel(card)
    val income = summaryCurrency(card.totals.incomeGrosze)
    val expense = summaryCurrency(card.totals.expenseGrosze.negate())
    val balance = summaryCurrency(card.totals.netGrosze)
    val category = if (card.highestExpenseCategoryId != null) stringResource(R.string.summary_top_expense,
        card.highestExpenseCategory?.name ?: stringResource(R.string.entries_unknown_category))
        else stringResource(R.string.summary_no_expenses)
    val description = stringResource(R.string.summary_card_description, label, income, expense, balance, category)
    val action = stringResource(R.string.summary_open_period)
    val content: @Composable () -> Unit = {
        Column(Modifier.fillMaxWidth().padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Text(label, style = MaterialTheme.typography.titleLarge,
                modifier = Modifier.semantics { heading() }.testTag("summary-heading-${card.tagKey}"))
            BoxWithConstraints(Modifier.fillMaxWidth()) {
                val largeText = LocalDensity.current.fontScale >= 1.4f
                // Exact totals may be wider than any practical phone column. Stack for those
                // values and large text, so wrapping retains every digit and the currency.
                val stack = largeText || maxOf(income.length, expense.length, balance.length) > 21
                val chartSize = if (maxWidth < 360.dp) 88.dp else 120.dp
                if (stack) Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    SummaryAmounts(card, income, expense, balance, Modifier.fillMaxWidth())
                    SummaryDonut(card, chartSize, Modifier.align(Alignment.CenterHorizontally))
                } else Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    SummaryAmounts(card, income, expense, balance, Modifier.weight(1f))
                    SummaryDonut(card, chartSize)
                }
            }
            Text(category, style = MaterialTheme.typography.bodyMedium,
                modifier = Modifier.testTag("summary-top-category-${card.tagKey}"))
        }
    }
    val modifier = Modifier.fillMaxWidth().testTag("summary-card-${card.tagKey}")
    if (onClick != null) Card(onClick = onClick, modifier = modifier.semantics(mergeDescendants = true) {
        contentDescription = description
        onClick(label = action) { onClick(); true }
    }) { content() }
    else Card(modifier = modifier) { content() }
}

@Composable
private fun SummaryAmounts(card: SummaryPeriodCard, income: String, expense: String, balance: String, modifier: Modifier) {
    val dark = MaterialTheme.colorScheme.surface.luminance() < 0.5f
    val green = if (dark) Color(0xFF8FDBA1) else Color(0xFF146C2E)
    val red = if (dark) Color(0xFFFFB4AB) else Color(0xFFB3261E)
    Column(modifier, verticalArrangement = Arrangement.spacedBy(8.dp)) {
        SummaryAmount(Icons.Default.ArrowUpward, stringResource(R.string.summary_income), income, green,
            "summary-income-${card.tagKey}")
        SummaryAmount(Icons.Default.ArrowDownward, stringResource(R.string.summary_expense), expense, red,
            "summary-expense-${card.tagKey}")
        SummaryAmount(summaryBalanceIcon, stringResource(R.string.summary_net), balance, when {
            card.totals.netGrosze.signum() > 0 -> green
            card.totals.netGrosze.signum() < 0 -> red
            else -> MaterialTheme.colorScheme.onSurface
        }, "summary-balance-${card.tagKey}")
    }
}

@Composable
private fun SummaryAmount(symbol: ImageVector, label: String, value: String, color: Color, tag: String) {
    val description = stringResource(R.string.summary_metric_description, label, value)
    Row(
        modifier = Modifier.fillMaxWidth().testTag("$tag-row").semantics(mergeDescendants = true) {
            contentDescription = description
        },
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Icon(symbol, contentDescription = null, tint = color,
            modifier = Modifier.size(20.dp).testTag("$tag-symbol"))
        Text(value, color = color, fontWeight = FontWeight.Bold,
            style = MaterialTheme.typography.bodyLarge, modifier = Modifier.weight(1f).testTag(tag))
    }
}

@Composable
private fun SummaryDonut(card: SummaryPeriodCard, chartSize: Dp, modifier: Modifier = Modifier) {
    val income = card.totals.incomeGrosze
    val expense = card.totals.expenseGrosze
    val total = income + expense
    val incomeRatio = remember(income, expense) {
        if (total.signum() == 0) BigDecimal.ZERO else
            BigDecimal(income).divide(BigDecimal(total), 12, RoundingMode.HALF_UP)
    }
    val incomePercent = incomeRatio.multiply(BigDecimal(100)).setScale(1, RoundingMode.HALF_UP)
        .toPlainString().replace('.', ',')
    val expensePercent = (BigDecimal(100) - incomeRatio.multiply(BigDecimal(100)))
        .setScale(1, RoundingMode.HALF_UP).toPlainString().replace('.', ',')
    val dark = MaterialTheme.colorScheme.surface.luminance() < 0.5f
    val green = if (dark) Color(0xFF8FDBA1) else Color(0xFF146C2E)
    val red = if (dark) Color(0xFFFFB4AB) else Color(0xFFB3261E)
    val neutral = MaterialTheme.colorScheme.outlineVariant
    val noEntries = stringResource(R.string.summary_no_entries)
    val description = if (total.signum() == 0) noEntries else stringResource(
        R.string.summary_chart_description, summaryCurrency(income), summaryCurrency(expense.negate()))
    Column(modifier, horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Canvas(Modifier.size(chartSize).testTag("summary-chart-${card.tagKey}")
            .semantics { contentDescription = description }) {
            val stroke = Stroke(width = 14.dp.toPx())
            val inset = stroke.width / 2
            val chartBounds = Size(size.width - stroke.width, size.height - stroke.width)
            drawArc(neutral, -90f, 360f, false, Offset(inset, inset), chartBounds, style = stroke)
            if (total.signum() > 0) {
                val angle = incomeRatio.multiply(BigDecimal(360)).toFloat().coerceIn(0f, 360f)
                if (income.signum() > 0) drawArc(green, -90f, angle, false, Offset(inset, inset), chartBounds, style = stroke)
                if (expense.signum() > 0) drawArc(red, -90f + angle, 360f - angle, false, Offset(inset, inset), chartBounds, style = stroke)
            }
        }
        if (total.signum() == 0) Text(noEntries, style = MaterialTheme.typography.labelSmall)
        else {
            SummaryChartLegend(green, stringResource(R.string.summary_chart_income, incomePercent))
            SummaryChartLegend(red, stringResource(R.string.summary_chart_expense, expensePercent))
        }
    }
}

@Composable
private fun SummaryChartLegend(color: Color, label: String) {
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(4.dp)) {
        Box(Modifier.size(8.dp).background(color))
        Text(label, style = MaterialTheme.typography.labelSmall)
    }
}
