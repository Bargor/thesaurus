package pl.bargor.thesaurus.ui.reports

import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material3.Card
import androidx.compose.material3.LocalTextStyle
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.unit.dp
import java.math.BigInteger
import pl.bargor.thesaurus.data.model.PlnMoney
import pl.bargor.thesaurus.data.model.PlnSign
import kotlin.math.ceil
import pl.bargor.thesaurus.R
import pl.bargor.thesaurus.data.model.ReportTotals

/** Preserve the report's existing signs and exact arbitrary-precision PLN formatting. */
private fun reportTotalAmount(value: BigInteger, signed: Boolean = false): String =
    PlnMoney.currency(value, if (signed) PlnSign.EXPLICIT_POSITIVE else PlnSign.MAGNITUDE)

@Composable
internal fun ReportsTotalCards(totals: ReportTotals, modifier: Modifier = Modifier) {
    val labels = listOf(stringResource(R.string.summary_income), stringResource(R.string.summary_expense),
        stringResource(R.string.summary_net))
    val values = remember(totals) { listOf(reportTotalAmount(totals.incomeGrosze),
        reportTotalAmount(totals.expenseGrosze), reportTotalAmount(totals.netGrosze, signed = true)) }
    val tags = listOf("reports-income", "reports-expense", "reports-net")
    // Use the same styles for measuring and rendering, including inherited text settings.
    val amountStyle = LocalTextStyle.current
    val labelStyle = amountStyle.merge(MaterialTheme.typography.labelSmall)
    val measurer = rememberTextMeasurer()
    val density = LocalDensity.current
    val widths = remember(labels, values, amountStyle, labelStyle, measurer, density) {
        fun width(text: String, style: TextStyle): Int {
            val measured = measurer.measure(text, style, softWrap = false, maxLines = 1)
            // Intrinsic width may be fractional; reserve rounding room around the glyphs.
            return ceil(maxOf(measured.size.width.toFloat(), measured.multiParagraph.maxIntrinsicWidth))
                .toLong().coerceAtMost(Int.MAX_VALUE.toLong() - 2).toInt() + 2
        }
        values.map { width(it, amountStyle) } to labels.map { width(it, labelStyle) }
    }
    BoxWithConstraints(modifier.fillMaxWidth().testTag("reports-total-cards")) {
        val availableWidthPx = constraints.maxWidth
        val paddingPx = with(density) { 10.dp.roundToPx() } * 2
        val gapPx = with(density) { 8.dp.roundToPx() }
        val cardWidths = widths.first.indices.map {
            (maxOf(widths.first[it], widths.second[it]).toLong() + paddingPx)
                .coerceAtMost(Int.MAX_VALUE.toLong()).toInt()
        }
        val layout = chooseReportTotalsLayout(availableWidthPx, cardWidths, gapPx)
        if (layout == ReportTotalsLayout.ROW) {
            Row(Modifier.fillMaxWidth().testTag("reports-total-row"),
                horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                tags.indices.forEach { index ->
                    ReportTotalCard(labels[index], values[index], tags[index], amountStyle, labelStyle,
                        naturalAmountWidthPx = widths.first[index], scrollAmount = false,
                        modifier = Modifier.weight(1f))
                }
            }
        } else {
            Column(Modifier.fillMaxWidth().testTag("reports-total-stack"),
                verticalArrangement = Arrangement.spacedBy(8.dp)) {
                tags.indices.forEach { index ->
                    ReportTotalCard(labels[index], values[index], tags[index], amountStyle, labelStyle,
                        naturalAmountWidthPx = widths.first[index],
                        scrollAmount = widths.first[index].toLong() + paddingPx > availableWidthPx.toLong(),
                        modifier = Modifier.fillMaxWidth())
                }
            }
        }
    }
}

@Composable
private fun ReportTotalCard(
    label: String,
    value: String,
    tag: String,
    amountStyle: TextStyle,
    labelStyle: TextStyle,
    naturalAmountWidthPx: Int,
    scrollAmount: Boolean,
    modifier: Modifier,
) {
    val description = stringResource(R.string.reports_total_description, label, value)
    val naturalAmountWidth = with(LocalDensity.current) { naturalAmountWidthPx.toDp() }
    Card(modifier.testTag("$tag-card").semantics(mergeDescendants = true) { contentDescription = description }) {
        Column(Modifier.fillMaxWidth().padding(10.dp), verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Text(label, style = labelStyle)
            if (scrollAmount) {
                // Only amounts wider than a full card need this fallback. Keep the actual
                // text at its normal accessible size rather than shrinking or abbreviating.
                Box(Modifier.fillMaxWidth().testTag("$tag-scroll").horizontalScroll(rememberScrollState())) {
                    Text(value, Modifier.width(naturalAmountWidth).testTag(tag),
                        style = amountStyle, softWrap = false, maxLines = 1)
                }
                Text(stringResource(R.string.reports_total_scroll_hint), style = labelStyle)
            } else Text(value, Modifier.fillMaxWidth().testTag(tag),
                style = amountStyle, softWrap = false, maxLines = 1)
        }
    }
}
