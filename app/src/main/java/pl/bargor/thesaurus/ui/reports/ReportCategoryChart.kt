package pl.bargor.thesaurus.ui.reports

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.LocalTextStyle
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import java.math.BigDecimal
import java.math.BigInteger
import java.math.MathContext
import pl.bargor.thesaurus.data.model.PlnMoney
import java.util.Locale
import kotlin.math.ceil
import pl.bargor.thesaurus.R
import pl.bargor.thesaurus.data.model.ReportCategoryValue

private val reportsLocale = Locale.forLanguageTag("pl-PL")
internal data class NamedCategoryValue(
    val name: String,
    val colorToken: String?,
    val value: ReportCategoryValue,
)

private data class ChartCategoryValue(
    val category: NamedCategoryValue,
    val color: Color,
    val share: Float,
)

/** Divide before converting to Float, including for amounts beyond the floating-point range. */
internal fun reportCategoryShare(amount: BigInteger, total: BigInteger): Float =
    if (total.signum() <= 0) 0f else BigDecimal(amount)
        .divide(BigDecimal(total), MathContext.DECIMAL64).toFloat().coerceIn(0f, 1f)

/** Canvas is paired with a visible Polish legend and a semantic description for screen readers. */
@Composable
internal fun CategoryDonutChart(categories: List<NamedCategoryValue>) {
    val presentation = remember(categories) {
        val total = categories.fold(BigInteger.ZERO) { sum, item -> sum + item.value.amountGrosze }
        val colors = ReportChartPalette.assign(categories.associate { it.value.categoryId to it.colorToken })
        categories.map { item ->
            ChartCategoryValue(
                item,
                Color(0xFF000000L or colors.getValue(item.value.categoryId)),
                reportCategoryShare(item.value.amountGrosze, total),
            )
        }
    }
    fun ChartCategoryValue.label() = "${category.name}: ${category.value.amountGrosze.currency()} (${String.format(reportsLocale, "%.0f", share * 100)}%)"
    val legend = presentation.joinToString("; ") { it.label() }
    val description = stringResource(R.string.reports_category_chart_description, legend)
    Text(stringResource(R.string.reports_category_chart), style = MaterialTheme.typography.titleLarge, modifier = Modifier.semantics { heading() })
    Canvas(
        modifier = Modifier.fillMaxWidth().height(190.dp).testTag("reports-category-chart")
            .semantics { contentDescription = description },
    ) {
        // The stroke is centered on the arc. Keep its full width inside the Canvas so it
        // cannot paint into the legend below, including on narrow screens.
        val outerDiameter = (minOf(size.width, size.height) - 16.dp.toPx()).coerceAtLeast(0f)
        if (outerDiameter == 0f) return@Canvas
        val strokeWidth = outerDiameter * .22f
        val arcDiameter = outerDiameter - strokeWidth
        val arcOffset = Offset((size.width - arcDiameter) / 2f, (size.height - arcDiameter) / 2f)
        var start = -90f
        presentation.forEach { item ->
            val sweep = item.share * 360f
            drawArc(
                item.color,
                start,
                sweep,
                false,
                arcOffset,
                Size(arcDiameter, arcDiameter),
                style = Stroke(width = strokeWidth),
            )
            start += sweep
        }
    }
    Column(
        Modifier.fillMaxWidth().testTag("reports-category-legend"),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Text(
            stringResource(R.string.reports_category_legend_heading),
            style = MaterialTheme.typography.titleMedium,
            modifier = Modifier.semantics { heading() },
        )
        CategoryLegendTable(presentation)
    }
}

/** One shared table aligns amounts beside percentages at the right edge. */
@Composable
private fun CategoryLegendTable(presentation: List<ChartCategoryValue>) {
    val textStyle = LocalTextStyle.current.merge(MaterialTheme.typography.bodyMedium)
    val textMeasurer = rememberTextMeasurer()
    val density = LocalDensity.current
    val amounts = remember(presentation) { presentation.map { it.category.value.amountGrosze.currency() } }
    val percentages = remember(presentation) { presentation.map { String.format(reportsLocale, "%.0f%%", it.share * 100) } }
    val widths = remember(presentation, amounts, percentages, textStyle, textMeasurer, density) {
        fun width(text: String): Int {
            val result = textMeasurer.measure(
                text = text, style = textStyle, softWrap = false, maxLines = 1,
            )
            // Text's fractional intrinsic width can exceed its integer measured size. Reserve
            // a pixel on each side so rounding a shared column cannot clip the rendered text.
            return ceil(maxOf(result.size.width.toFloat(), result.multiParagraph.maxIntrinsicWidth)).toInt() + 2
        }
        Triple(
            presentation.maxOfOrNull { width(it.category.name) } ?: 0,
            amounts.maxOfOrNull { width(it) } ?: 0,
            percentages.maxOfOrNull { width(it) } ?: 0,
        )
    }
    BoxWithConstraints(Modifier.fillMaxWidth()) {
        // Measure rendered text, including font/display scaling. Shared numeric columns stay
        // beside each other at the right edge, with the remaining space given to category names.
        // Only overflow expands the entire table into one shared scroller.
        val tableWidth = with(density) {
            maxOf(
                maxWidth.roundToPx(),
                widths.first + 24.dp.roundToPx() + widths.second + widths.third + 2 * 12.dp.roundToPx(),
            ).toDp()
        }
        val amountWidth = with(density) { widths.second.toDp() }
        val nameWidth = with(density) { widths.first.toDp() }
        val percentageWidth = with(density) { widths.third.toDp() }
        val nameColumnWidth = tableWidth - amountWidth - 12.dp - percentageWidth
        Column(
            Modifier.fillMaxWidth().horizontalScroll(rememberScrollState())
                .testTag("reports-category-legend-table"),
        ) {
            Column(
                Modifier.width(tableWidth).testTag("reports-category-legend-content"),
                verticalArrangement = Arrangement.spacedBy(4.dp),
            ) {
                presentation.forEachIndexed { index, item ->
                    val id = item.category.value.categoryId
                    val label = "${item.category.name}: ${amounts[index]} (${percentages[index]})"
                    val rowDescription = stringResource(R.string.reports_category_segment_description, label)
                    Row(
                        modifier = Modifier.fillMaxWidth().padding(vertical = 2.dp)
                            .testTag("reports-category-row-$id")
                            .semantics(mergeDescendants = true) { contentDescription = rowDescription },
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Row(
                            Modifier.width(nameColumnWidth),
                            horizontalArrangement = Arrangement.spacedBy(8.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Box(
                                Modifier.size(16.dp).background(item.color)
                                    .border(1.dp, MaterialTheme.colorScheme.onSurface)
                                    .testTag("reports-category-swatch-$id"),
                            )
                            Text(
                                item.category.name, style = textStyle, maxLines = 1, softWrap = false,
                                modifier = Modifier.width(nameWidth).testTag("reports-category-name-$id"),
                            )
                        }
                        Box(Modifier.width(amountWidth), contentAlignment = Alignment.CenterEnd) {
                            Text(
                                amounts[index], style = textStyle, maxLines = 1, softWrap = false,
                                textAlign = TextAlign.End,
                                modifier = Modifier.width(amountWidth).testTag("reports-category-amount-$id"),
                            )
                        }
                        Spacer(Modifier.width(12.dp))
                        Box(Modifier.width(percentageWidth), contentAlignment = Alignment.CenterEnd) {
                            Text(
                                percentages[index], style = textStyle, maxLines = 1, softWrap = false,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                textAlign = TextAlign.End,
                                modifier = Modifier.width(percentageWidth).testTag("reports-category-percentage-$id"),
                            )
                        }
                    }
                }
            }
        }
    }
}


private fun BigInteger.currency(): String = PlnMoney.currency(this)
