package pl.bargor.thesaurus.ui.reports

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import java.math.BigDecimal
import java.math.BigInteger
import java.text.NumberFormat
import java.time.format.DateTimeFormatter
import java.util.Locale
import kotlin.math.max
import pl.bargor.thesaurus.R
import pl.bargor.thesaurus.data.model.ReportCategoryValue
import pl.bargor.thesaurus.data.model.ReportTrendValue
import pl.bargor.thesaurus.data.model.ReportTypeFilter
import pl.bargor.thesaurus.data.model.SyncState

private val reportsLocale = Locale.forLanguageTag("pl-PL")
private val reportsMonthFormatter = DateTimeFormatter.ofPattern("LLLL uuuu", reportsLocale)
private val reportsDateFormatter = DateTimeFormatter.ofPattern("d MMM", reportsLocale)
private val donutColors = listOf(Color(0xFF1565C0), Color(0xFF00897B), Color(0xFFFF8F00), Color(0xFF8E24AA), Color(0xFFC62828))

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
) {
    Column(
        modifier = modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Text(
            text = stringResource(R.string.navigation_reports),
            style = MaterialTheme.typography.headlineSmall,
            modifier = Modifier.semantics { heading() },
        )
        ReportPeriodSelector(state.mode, onSelectPeriodMode)
        if (state.mode == ReportPeriodMode.CUSTOM) {
            CustomPeriodFields(state, onCustomFromChange, onCustomToChange, onApplyCustomPeriod)
        } else {
            PeriodNavigator(state, onPreviousPeriod, onNextPeriod)
        }
        TypeSelector(state.typeFilter, onSelectType)
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
            SyncState.OFFLINE -> Text(stringResource(R.string.reports_offline), Modifier.testTag("reports-offline"))
            else -> Unit
        }
        if (state.hasError && state.aggregation.totals.isEmpty) return@Column
        ReportsTotalCards(state)
        if (state.aggregation.totals.isEmpty) {
            Text(stringResource(R.string.empty_reports), Modifier.testTag("reports-empty"))
            return@Column
        }
        val categories = state.aggregation.categories.map { value ->
            NamedCategoryValue(
                state.entries.firstOrNull { it.entry.categoryId == value.categoryId }?.categoryName
                    ?: stringResource(R.string.reports_unknown_category),
                value,
            )
        }
        CategoryDonutChart(categories)
        TrendChart(state.aggregation.trend, state.mode == ReportPeriodMode.YEAR, state.typeFilter)
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
private fun ReportPeriodSelector(selected: ReportPeriodMode, onSelect: (ReportPeriodMode) -> Unit) {
    SingleChoiceSegmentedButtonRow(modifier = Modifier.fillMaxWidth()) {
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

@Composable
private fun ReportsTotalCards(state: ReportsUiState) {
    val totals = state.aggregation.totals
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxWidth()) {
        ReportTotalCard(R.string.summary_income, totals.incomeGrosze, "reports-income", Modifier.weight(1f))
        ReportTotalCard(R.string.summary_expense, totals.expenseGrosze, "reports-expense", Modifier.weight(1f))
        ReportTotalCard(R.string.summary_net, totals.netGrosze, "reports-net", Modifier.weight(1f), signed = true)
    }
}

@Composable
private fun ReportTotalCard(label: Int, value: BigInteger, tag: String, modifier: Modifier, signed: Boolean = false) {
    Card(modifier = modifier) {
        Column(Modifier.padding(10.dp)) {
            Text(stringResource(label), style = MaterialTheme.typography.labelSmall)
            Text((if (signed && value.signum() > 0) "+" else "") + value.currency(), Modifier.testTag(tag))
        }
    }
}

private data class NamedCategoryValue(val name: String, val value: ReportCategoryValue)

/** Canvas is paired with a visible Polish legend and a semantic description for screen readers. */
@Composable
private fun CategoryDonutChart(categories: List<NamedCategoryValue>) {
    val total = categories.fold(BigInteger.ZERO) { sum, item -> sum + item.value.amountGrosze }
    val legend = categories.joinToString("; ") { item ->
        val percent = item.value.amountGrosze.toDouble() * 100 / total.toDouble()
        "${item.name}: ${item.value.amountGrosze.currency()} (${String.format(reportsLocale, "%.0f", percent)}%)"
    }
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
        categories.forEachIndexed { index, item ->
            val sweep = item.value.amountGrosze.toFloat() / total.toFloat() * 360f
            drawArc(donutColors[index % donutColors.size], start, sweep, false, arcOffset, Size(arcDiameter, arcDiameter), style = Stroke(width = strokeWidth))
            start += sweep
        }
    }
    Text(legend, style = MaterialTheme.typography.bodySmall, modifier = Modifier.fillMaxWidth().testTag("reports-category-legend"))
}

/** Shows daily values except in annual mode, where [ReportsViewModel] provides monthly buckets. */
@Composable
private fun TrendChart(values: List<ReportTrendValue>, yearly: Boolean, type: ReportTypeFilter) {
    val displayValues = if (type == ReportTypeFilter.EXPENSE) {
        values.map { it.copy(amountGrosze = it.amountGrosze.abs()) }
    } else values
    val formatter = if (yearly) DateTimeFormatter.ofPattern("LLL", reportsLocale) else reportsDateFormatter
    val summary = displayValues.joinToString("; ") { "${it.date.format(formatter)}: ${it.amountGrosze.signedCurrency()}" }
    val description = stringResource(R.string.reports_trend_chart_description, if (yearly) stringResource(R.string.reports_trend_monthly) else stringResource(R.string.reports_trend_daily), summary)
    Text(stringResource(if (yearly) R.string.reports_trend_monthly else R.string.reports_trend_daily), style = MaterialTheme.typography.titleLarge, modifier = Modifier.semantics { heading() })
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
    val label = listOfNotNull(item.categoryName, entry.normalizedTitle, entry.amountGrosze.signedCurrency()).joinToString(", ")
    val openDescription = stringResource(R.string.reports_open_entry, label)
    Card(
        modifier = Modifier.fillMaxWidth().clickable { onOpen(entry.id) }
            .testTag("report-entry-${entry.id}").semantics { contentDescription = openDescription },
    ) {
        Column(Modifier.padding(12.dp)) {
            Text(item.categoryName, style = MaterialTheme.typography.labelLarge)
            entry.normalizedTitle?.let { Text(it) }
            Text("${entry.date.format(reportsDateFormatter)} · ${entry.amountGrosze.signedCurrency()}")
        }
    }
}

private fun BigInteger.currency(): String = NumberFormat.getCurrencyInstance(reportsLocale).format(BigDecimal(this, 2))
private fun BigInteger.signedCurrency(): String = (if (signum() > 0) "+" else "") + currency()
private fun Long.signedCurrency(): String = (if (this > 0) "+" else "") + BigInteger.valueOf(this).currency()
