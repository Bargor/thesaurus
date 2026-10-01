package pl.bargor.thesaurus.ui.reports

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.LocalTextStyle
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.OutlinedButton
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowDownward
import androidx.compose.material.icons.filled.ArrowUpward
import androidx.compose.material.icons.filled.RestartAlt
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.unit.dp
import java.math.BigDecimal
import java.math.BigInteger
import java.math.MathContext
import java.text.NumberFormat
import java.time.format.DateTimeFormatter
import java.util.Locale
import kotlin.math.max
import kotlin.math.ceil
import pl.bargor.thesaurus.R
import pl.bargor.thesaurus.data.model.ReportCategoryValue
import pl.bargor.thesaurus.data.model.ReportTrendValue
import pl.bargor.thesaurus.data.model.ReportTypeFilter
import pl.bargor.thesaurus.data.model.SyncState
import pl.bargor.thesaurus.ui.categoryAccentColor
import pl.bargor.thesaurus.ui.categoryContainer

private val reportsLocale = Locale.forLanguageTag("pl-PL")
private val reportsMonthFormatter = DateTimeFormatter.ofPattern("LLLL uuuu", reportsLocale)
private val reportsDateFormatter = DateTimeFormatter.ofPattern("d MMM", reportsLocale)
@Composable
fun ReportsScreen(
    state: ReportsUiState,
    onSelectPeriodMode: (ReportPeriodMode) -> Unit,
    onPreviousPeriod: () -> Unit,
    onNextPeriod: () -> Unit,
    onSelectType: (ReportTypeFilter) -> Unit,
    onCustomFromChange: (String) -> Unit,
    onCustomToChange: (String) -> Unit,
    onApplyCustomPeriod: () -> Unit,
    onOpenEntry: (String) -> Unit,
    onRetry: () -> Unit,
    modifier: Modifier = Modifier,
    onSelectCategory: (String?) -> Unit = {},
    onSelectSubcategory: (String?) -> Unit = {},
    onSelectSort: (ReportEntrySort) -> Unit = {},
    onToggleSortDirection: () -> Unit = {},
    onClearControls: () -> Unit = {},
) {
    Column(
        modifier = modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        ReportPeriodSelector(state.mode, onSelectPeriodMode)
        if (state.mode == ReportPeriodMode.CUSTOM) {
            CustomPeriodFields(state, onCustomFromChange, onCustomToChange, onApplyCustomPeriod)
        } else {
            PeriodNavigator(state, onPreviousPeriod, onNextPeriod)
        }
        TypeSelector(state.typeFilter, onSelectType)
        ReportControls(state, onSelectCategory, onSelectSubcategory, onSelectSort, onToggleSortDirection, onClearControls)
        if (state.isLoading) {
            CircularProgressIndicator(Modifier.testTag("reports-loading"))
            return@Column
        }
        if (state.hasError) {
            Text(
                stringResource(R.string.reports_load_error),
                color = MaterialTheme.colorScheme.error,
                modifier = Modifier.testTag("reports-error"),
            )
            Button(onClick = onRetry, modifier = Modifier.testTag("reports-retry")) {
                Text(stringResource(R.string.auth_retry))
            }
        }
        when (state.syncState) {
            SyncState.PENDING -> Text(stringResource(R.string.reports_sync_pending), Modifier.testTag("reports-pending"))
            else -> Unit
        }
        if (state.hasError && state.aggregation.totals.isEmpty) return@Column
        ReportsTotalCards(state.aggregation.totals)
        if (state.aggregation.totals.isEmpty) {
            Text(stringResource(R.string.empty_reports), Modifier.testTag("reports-empty"))
            return@Column
        }
        val categories = state.aggregation.categories.map { value ->
            NamedCategoryValue(
                state.entries.firstOrNull { it.entry.categoryId == value.categoryId }?.categoryName
                    ?: stringResource(R.string.reports_unknown_category),
                state.entries.firstOrNull { it.entry.categoryId == value.categoryId }?.categoryColor,
                value,
            )
        }
        CategoryDonutChart(categories)
        if (state.mode == ReportPeriodMode.YEAR) {
            TrendChart(state.aggregation.trend, state.typeFilter)
        }
        Text(
            stringResource(R.string.reports_entries_heading),
            style = MaterialTheme.typography.titleLarge,
            modifier = Modifier.semantics { heading() },
        )
        state.entries.forEach { item ->
            ReportEntryCard(item, onOpenEntry)
        }
    }
}

@Composable
private fun ReportControls(
    state: ReportsUiState,
    onCategory: (String?) -> Unit,
    onSubcategory: (String?) -> Unit,
    onSort: (ReportEntrySort) -> Unit,
    onDirection: () -> Unit,
    onClear: () -> Unit,
) {
    ReportChoice(
        stringResource(R.string.reports_filter_category),
        state.categories.firstOrNull { it.id == state.selectedCategoryId }?.name
            ?: stringResource(R.string.reports_all_categories),
        listOf(null to stringResource(R.string.reports_all_categories)) + state.categories.map { it.id to it.name },
        "reports-filter-category", onSelect = onCategory,
    )
    ReportChoice(
        stringResource(R.string.reports_filter_subcategory),
        state.subcategories.firstOrNull { it.id == state.selectedSubcategoryId }?.name
            ?: stringResource(R.string.reports_all_subcategories),
        listOf(null to stringResource(R.string.reports_all_subcategories)) + state.subcategories.map { it.id to it.name },
        "reports-filter-subcategory", enabled = state.selectedCategoryId != null, onSelect = onSubcategory,
    )
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(4.dp)) {
        ReportChoice(
            stringResource(R.string.reports_sort), reportSortLabel(state.sort),
            ReportEntrySort.entries.map { it.name to reportSortLabel(it) }, "reports-sort",
            modifier = Modifier.weight(1f),
            onSelect = { name -> name?.let { onSort(ReportEntrySort.valueOf(it)) } },
        )
        val directionDescription = stringResource(if (state.direction == ReportSortDirection.DESCENDING)
            R.string.reports_sort_descending else R.string.reports_sort_ascending)
        IconButton(onClick = onDirection, modifier = Modifier.size(48.dp).testTag("reports-sort-direction")
            .semantics { contentDescription = directionDescription }) {
            Icon(if (state.direction == ReportSortDirection.DESCENDING) Icons.Filled.ArrowDownward
                else Icons.Filled.ArrowUpward, contentDescription = null)
        }
        val clearDescription = stringResource(R.string.reports_clear_controls)
        IconButton(onClick = onClear, modifier = Modifier.size(48.dp).testTag("reports-clear-controls")
            .semantics { contentDescription = clearDescription }) {
            Icon(Icons.Filled.RestartAlt, contentDescription = null)
        }
    }
}

@Composable
private fun reportSortLabel(sort: ReportEntrySort): String = stringResource(when (sort) {
    ReportEntrySort.DATE -> R.string.reports_sort_date
    ReportEntrySort.AMOUNT -> R.string.reports_sort_amount
})

@Composable
private fun ReportChoice(
    label: String,
    selected: String,
    options: List<Pair<String?, String>>,
    tag: String,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    onSelect: (String?) -> Unit,
) {
    var expanded by remember { mutableStateOf(false) }
    BoxWithConstraints(modifier.fillMaxWidth()) {
        // Reserve a usable dropdown beside the label even when the adjacent icons and
        // enlarged fonts leave the sort selector much less space than a category row.
        val labelLimit = (maxWidth - 64.dp - 8.dp).coerceAtLeast(0.dp)
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(label, style = MaterialTheme.typography.labelMedium,
                modifier = Modifier.widthIn(max = labelLimit), maxLines = 2, overflow = TextOverflow.Ellipsis)
            Box(Modifier.weight(1f)) {
                OutlinedButton(onClick = { expanded = true }, enabled = enabled,
                    modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp).testTag(tag).semantics {
                        contentDescription = label
                        stateDescription = selected
                    }) {
                    Text(selected, maxLines = 1, overflow = TextOverflow.Ellipsis)
                }
                DropdownMenu(expanded = expanded && enabled, onDismissRequest = { expanded = false }) {
                    options.forEach { (id, name) ->
                        DropdownMenuItem(text = { Text(name) }, onClick = {
                            expanded = false
                            onSelect(id)
                        }, modifier = Modifier.testTag("$tag-option-${id ?: "all"}"))
                    }
                }
            }
        }
    }
}

@Composable
private fun ReportPeriodSelector(selected: ReportPeriodMode, onSelect: (ReportPeriodMode) -> Unit) {
    SingleChoiceSegmentedButtonRow(modifier = Modifier.fillMaxWidth().testTag("reports-period-selector")) {
        ReportPeriodMode.entries.forEachIndexed { index, mode ->
            SegmentedButton(
                selected = selected == mode,
                onClick = { onSelect(mode) },
                label = { Text(stringResource(mode.labelRes())) },
                shape = SegmentedButtonDefaults.itemShape(index, ReportPeriodMode.entries.size),
                modifier = Modifier.testTag("reports-mode-${mode.name.lowercase()}"),
            )
        }
    }
}

private fun ReportPeriodMode.labelRes() = when (this) {
    ReportPeriodMode.MONTH -> R.string.reports_mode_month
    ReportPeriodMode.YEAR -> R.string.reports_mode_year
    ReportPeriodMode.CUSTOM -> R.string.reports_mode_custom
}

@Composable
private fun PeriodNavigator(state: ReportsUiState, previous: () -> Unit, next: () -> Unit) {
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
            modifier = Modifier.testTag("reports-next-period").semantics {
                contentDescription = nextDescription
            },
        ) { Text("›") }
    }
}

@Composable
private fun CustomPeriodFields(
    state: ReportsUiState,
    onFrom: (String) -> Unit,
    onTo: (String) -> Unit,
    onApply: () -> Unit,
) {
    OutlinedTextField(
        value = state.customFromInput,
        onValueChange = onFrom,
        label = { Text(stringResource(R.string.reports_from)) },
        isError = state.customDateError,
        supportingText = { Text(stringResource(if (state.customDateError) R.string.reports_date_error else R.string.reports_date_hint)) },
        singleLine = true,
        modifier = Modifier.fillMaxWidth().testTag("reports-custom-from"),
    )
    OutlinedTextField(
        value = state.customToInput,
        onValueChange = onTo,
        label = { Text(stringResource(R.string.reports_to)) },
        isError = state.customDateError,
        // The group exposes one error message. Repeating it for both inputs makes TalkBack announce
        // the same Polish validation error twice before the user can correct the range.
        supportingText = null,
        singleLine = true,
        modifier = Modifier.fillMaxWidth().testTag("reports-custom-to"),
    )
    Button(onClick = onApply, modifier = Modifier.testTag("reports-apply-custom")) {
        Text(stringResource(R.string.reports_apply_period))
    }
}

@Composable
private fun TypeSelector(selected: ReportTypeFilter, onSelect: (ReportTypeFilter) -> Unit) {
    SingleChoiceSegmentedButtonRow(modifier = Modifier.fillMaxWidth()) {
        ReportTypeFilter.entries.forEachIndexed { index, type ->
            SegmentedButton(
                selected = selected == type,
                onClick = { onSelect(type) },
                label = { Text(stringResource(type.labelRes())) },
                shape = SegmentedButtonDefaults.itemShape(index, ReportTypeFilter.entries.size),
                modifier = Modifier.testTag("reports-type-${type.name.lowercase()}"),
            )
        }
    }
}

private fun ReportTypeFilter.labelRes() = when (this) {
    ReportTypeFilter.ALL -> R.string.reports_type_all
    ReportTypeFilter.INCOME -> R.string.reports_type_income
    ReportTypeFilter.EXPENSE -> R.string.reports_type_expense
}

private data class NamedCategoryValue(
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
private fun CategoryDonutChart(categories: List<NamedCategoryValue>) {
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

/** Shows the monthly buckets provided by [ReportsViewModel] for annual reports. */
@Composable
private fun TrendChart(values: List<ReportTrendValue>, type: ReportTypeFilter) {
    val displayValues = if (type == ReportTypeFilter.EXPENSE) {
        values.map { it.copy(amountGrosze = it.amountGrosze.abs()) }
    } else values
    val formatter = DateTimeFormatter.ofPattern("LLL", reportsLocale)
    val summary = displayValues.joinToString("; ") { "${it.date.format(formatter)}: ${it.amountGrosze.signedCurrency()}" }
    val description = stringResource(R.string.reports_trend_chart_description, stringResource(R.string.reports_trend_monthly), summary)
    Text(stringResource(R.string.reports_trend_monthly), style = MaterialTheme.typography.titleLarge, modifier = Modifier.semantics { heading() })
    Canvas(
        modifier = Modifier.fillMaxWidth().height(160.dp).testTag("reports-trend-chart")
            .semantics { contentDescription = description },
    ) {
        if (displayValues.isEmpty()) return@Canvas
        val amounts = displayValues.map { it.amountGrosze.toFloat() }
        var low = amounts.minOrNull() ?: 0f
        var high = amounts.maxOrNull() ?: 0f
        if (low == high) { low -= 1f; high += 1f }
        val xStep = size.width / max(1, displayValues.lastIndex)
        fun y(amount: Float) = size.height - ((amount - low) / (high - low) * size.height)
        val zero = y(0f)
        drawLine(Color.Gray.copy(alpha = .4f), Offset(0f, zero), Offset(size.width, zero), strokeWidth = 2f)
        displayValues.zipWithNext().forEachIndexed { index, (first, second) ->
            drawLine(
                color = Color(0xFF1565C0),
                start = Offset(index * xStep, y(first.amountGrosze.toFloat())),
                end = Offset((index + 1) * xStep, y(second.amountGrosze.toFloat())),
                strokeWidth = 5f,
                cap = StrokeCap.Round,
            )
        }
        displayValues.forEachIndexed { index, value -> drawCircle(Color(0xFF1565C0), 5f, Offset(index * xStep, y(value.amountGrosze.toFloat()))) }
    }
    Text(summary, style = MaterialTheme.typography.bodySmall, modifier = Modifier.testTag("reports-trend-summary"))
}

@Composable
private fun ReportEntryCard(item: ReportEntryItem, onOpen: (String) -> Unit) {
    val entry = item.entry
    val label = listOfNotNull(item.categoryName, item.subcategoryName, entry.normalizedTitle, entry.amountGrosze.signedCurrency()).joinToString(", ")
    val openDescription = stringResource(R.string.reports_open_entry, label)
    val accent = categoryAccentColor(entry.categoryId, item.categoryColor)
    val surface = MaterialTheme.colorScheme.surface
    val dark = surface.luminance() < 0.5f
    Card(
        modifier = Modifier.fillMaxWidth().clickable { onOpen(entry.id) }
            .testTag("report-entry-${entry.id}").semantics { contentDescription = openDescription },
        colors = CardDefaults.cardColors(
            containerColor = accent.categoryContainer(surface, selected = false, dark = dark),
        ),
        border = BorderStroke(1.dp, accent),
    ) {
        Row(Modifier.fillMaxWidth().height(IntrinsicSize.Min)) {
            androidx.compose.foundation.layout.Box(
                Modifier.width(6.dp).fillMaxHeight().background(accent)
                    .testTag("report-entry-category-color-${entry.id}"),
            )
            Column(Modifier.padding(12.dp).weight(1f)) {
                Text(item.categoryName, style = MaterialTheme.typography.labelLarge)
                item.subcategoryName?.let { Text(it, style = MaterialTheme.typography.labelMedium) }
                entry.normalizedTitle?.let { Text(it) }
                Text("${entry.date.format(reportsDateFormatter)} · ${entry.amountGrosze.signedCurrency()}")
            }
        }
    }
}

private fun BigInteger.currency(): String = NumberFormat.getCurrencyInstance(reportsLocale).format(BigDecimal(this, 2))
private fun BigInteger.signedCurrency(): String = (if (signum() > 0) "+" else "") + currency()
private fun Long.signedCurrency(): String = (if (this > 0) "+" else "") + BigInteger.valueOf(this).currency()
