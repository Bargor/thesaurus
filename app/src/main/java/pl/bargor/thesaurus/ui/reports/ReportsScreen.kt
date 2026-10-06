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
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Checkbox
import androidx.compose.material.icons.filled.FilterList
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.compose.runtime.DisposableEffect
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.semantics.Role
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowDownward
import androidx.compose.material.icons.filled.ArrowUpward
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
import pl.bargor.thesaurus.data.model.PlnMoney
import pl.bargor.thesaurus.data.model.PlnSign
import java.time.format.DateTimeFormatter
import java.util.Locale
import kotlin.math.ceil
import pl.bargor.thesaurus.R
import pl.bargor.thesaurus.data.model.ReportCategoryValue
import pl.bargor.thesaurus.data.model.ReportTrendValue
import pl.bargor.thesaurus.data.model.ReportTypeFilter
import pl.bargor.thesaurus.data.model.SummaryPeriod
import pl.bargor.thesaurus.data.model.SyncState
import pl.bargor.thesaurus.ui.categoryAccentColor
import pl.bargor.thesaurus.ui.categoryContainer
import pl.bargor.thesaurus.ui.settings.SettingsAction

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
    onOpenFilters: () -> Unit = {},
    onDismissFilters: () -> Unit = {},
    onApplyFilters: () -> Unit = {},
    onResetFilters: () -> Unit = {},
    onSelectMembers: (Set<String>?) -> Unit = {},
    onOpenSettings: (() -> Unit)? = null,
) {
    // Dialog drafts belong to this screen instance and are discarded when it leaves composition.
    DisposableEffect(Unit) { onDispose { onDismissFilters() } }
    state.filterDraft?.let { draft ->
        ReportFilterDialog(state, draft, onSelectPeriodMode, onPreviousPeriod, onNextPeriod,
            onSelectType, onCustomFromChange, onCustomToChange, onSelectCategory,
            onSelectSubcategory, onSelectMembers, onSelectSort, onToggleSortDirection,
            onDismissFilters, onApplyFilters, onResetFilters)
    }
    Column(
        modifier = modifier.fillMaxSize().padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Text(stringResource(R.string.navigation_reports), style = MaterialTheme.typography.titleLarge,
                modifier = Modifier.weight(1f).semantics { heading() })
            val description = stringResource(R.string.reports_open_filters)
            val active = stringResource(if (state.hasActiveFilters) R.string.reports_filters_active else R.string.reports_filters_default)
            IconButton(onClick = onOpenFilters, modifier = Modifier.size(48.dp).testTag("reports-open-filters")
                .semantics { contentDescription = description; stateDescription = active }) {
                Box {
                    Icon(Icons.Filled.FilterList, contentDescription = null)
                    if (state.hasActiveFilters) Box(Modifier.align(Alignment.TopEnd).size(8.dp)
                        .background(MaterialTheme.colorScheme.primary, androidx.compose.foundation.shape.CircleShape)
                        .testTag("reports-filters-active"))
                }
            }
            SettingsAction(onOpenSettings)
        }
        Column(Modifier.weight(1f).verticalScroll(rememberScrollState()),
            verticalArrangement = Arrangement.spacedBy(12.dp)) content@ {
            Text(when (state.mode) {
                ReportPeriodMode.MONTH -> state.month.format(reportsMonthFormatter)
                ReportPeriodMode.YEAR -> state.year.toString()
                ReportPeriodMode.CUSTOM -> "${state.customFromInput} – ${state.customToInput}"
            }, modifier = Modifier.testTag("reports-period"))
            if (state.isLoading) {
                CircularProgressIndicator(Modifier.testTag("reports-loading"))
                return@content
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
            if (state.hasError && state.aggregation.totals.isEmpty) return@content
            ReportsTotalCards(state.aggregation.totals)
            if (state.aggregation.totals.isEmpty) {
                Text(stringResource(R.string.empty_reports), Modifier.testTag("reports-empty"))
                state.balanceTrend?.let { ReportsBalanceChart(it) }
                return@content
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
            state.balanceTrend?.let { ReportsBalanceChart(it) }
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
}

@Composable
private fun ReportSortControls(
    draft: ReportFilterDraft,
    onSort: (ReportEntrySort) -> Unit,
    onDirection: () -> Unit,
) {
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(4.dp)) {
        ReportChoice(
            stringResource(R.string.reports_sort), reportSortLabel(draft.sort),
            ReportEntrySort.entries.map { it.name to reportSortLabel(it) }, "reports-sort",
            modifier = Modifier.weight(1f),
            onSelect = { name -> name?.let { onSort(ReportEntrySort.valueOf(it)) } },
        )
        val directionDescription = stringResource(if (draft.direction == ReportSortDirection.DESCENDING)
            R.string.reports_sort_descending else R.string.reports_sort_ascending)
        val directionState = stringResource(if (draft.direction == ReportSortDirection.DESCENDING)
            R.string.reports_sort_direction_descending else R.string.reports_sort_direction_ascending)
        IconButton(onClick = onDirection, modifier = Modifier.size(48.dp).testTag("reports-sort-direction")
            .semantics { contentDescription = directionDescription; stateDescription = directionState }) {
            Icon(if (draft.direction == ReportSortDirection.DESCENDING) Icons.Filled.ArrowDownward
                else Icons.Filled.ArrowUpward, contentDescription = null)
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
private fun ReportFilterDialog(
    state: ReportsUiState,
    draft: ReportFilterDraft,
    onMode: (ReportPeriodMode) -> Unit,
    previous: () -> Unit,
    next: () -> Unit,
    onType: (ReportTypeFilter) -> Unit,
    onFrom: (String) -> Unit,
    onTo: (String) -> Unit,
    onCategory: (String?) -> Unit,
    onSubcategory: (String?) -> Unit,
    onMembers: (Set<String>?) -> Unit,
    onSort: (ReportEntrySort) -> Unit,
    onDirection: () -> Unit,
    dismiss: () -> Unit,
    apply: () -> Unit,
    reset: () -> Unit,
) {
    val presentation = state.copy(mode = draft.mode, month = draft.month, year = draft.year,
        typeFilter = draft.typeFilter, customFromInput = draft.customFromInput,
        customToInput = draft.customToInput, customDateError = draft.customDateError,
        selectedCategoryId = draft.selectedCategoryId, selectedSubcategoryId = draft.selectedSubcategoryId)
    val subcategories = state.allSubcategories.filter { it.categoryId == draft.selectedCategoryId }.sortedBy { it.name.lowercase() }
    val categoryOptions = state.categories.map { it.id to it.name }.toMutableList()
    listOfNotNull(state.selectedCategoryId, draft.selectedCategoryId).distinct()
        .filter { id -> categoryOptions.none { it.first == id } }.forEach { id ->
        categoryOptions.add(id to stringResource(R.string.reports_missing_category, id))
    }
    val subcategoryOptions = subcategories.map { it.id to it.name }.toMutableList()
    listOfNotNull(draft.selectedSubcategoryId, state.selectedSubcategoryId.takeIf {
        draft.selectedCategoryId != null && draft.selectedCategoryId == state.selectedCategoryId
    }).distinct().filter { id -> subcategoryOptions.none { it.first == id } }.forEach { id ->
        subcategoryOptions.add(id to stringResource(R.string.reports_missing_subcategory, id))
    }
    Dialog(onDismissRequest = dismiss, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        BoxWithConstraints(Modifier.fillMaxSize().pointerInput(dismiss) {
            detectTapGestures { dismiss() }
        }.padding(16.dp), contentAlignment = Alignment.Center) {
            Surface(shape = MaterialTheme.shapes.extraLarge, tonalElevation = 6.dp,
                modifier = Modifier.widthIn(max = 560.dp).fillMaxWidth().heightIn(max = maxHeight)
                    .testTag("reports-filter-dialog").pointerInput(Unit) { detectTapGestures { } }) {
                Column(Modifier.padding(20.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    Text(stringResource(R.string.reports_filter_title), style = MaterialTheme.typography.headlineSmall,
                        modifier = Modifier.semantics { heading() })
                    Column(Modifier.weight(1f, fill = false).verticalScroll(rememberScrollState())
                        .testTag("reports-filter-content"), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                        ReportChoice(stringResource(R.string.reports_filter_period), stringResource(draft.mode.labelRes()),
                            ReportPeriodMode.entries.map { it.name to stringResource(it.labelRes()) }, "reports-period-selector",
                            onSelect = { it?.let { name -> onMode(ReportPeriodMode.valueOf(name)) } })
                        if (draft.mode == ReportPeriodMode.CUSTOM) {
                            CustomPeriodFields(presentation, onFrom, onTo)
                        } else PeriodNavigator(presentation, previous, next)
                        ReportChoice(stringResource(R.string.reports_filter_type), stringResource(draft.typeFilter.labelRes()),
                            ReportTypeFilter.entries.map { it.name to stringResource(it.labelRes()) }, "reports-type-selector",
                            onSelect = { it?.let { name -> onType(ReportTypeFilter.valueOf(name)) } })
                        ReportSortControls(draft, onSort, onDirection)
                        ReportChoice(stringResource(R.string.reports_filter_category),
                            categoryOptions.firstOrNull { it.first == draft.selectedCategoryId }?.second ?: stringResource(R.string.reports_all_categories),
                            listOf(null to stringResource(R.string.reports_all_categories)) + categoryOptions,
                            "reports-filter-category", onSelect = onCategory)
                        ReportChoice(stringResource(R.string.reports_filter_subcategory),
                            subcategoryOptions.firstOrNull { it.first == draft.selectedSubcategoryId }?.second ?: stringResource(R.string.reports_all_subcategories),
                            listOf(null to stringResource(R.string.reports_all_subcategories)) + subcategoryOptions,
                            "reports-filter-subcategory", enabled = draft.selectedCategoryId != null, onSelect = onSubcategory)
                        MemberSectionHeader { onMembers(emptySet()) }
                        MemberFilterRow(stringResource(R.string.reports_members_all), draft.selectedMemberIds == null,
                            "reports-members-all") { selected -> onMembers(if (selected) null else emptySet()) }
                        state.members.forEach { member ->
                            val label = if (member.former) stringResource(R.string.reports_member_former, member.name) else member.name
                            MemberFilterRow(label, draft.selectedMemberIds == null || member.id in draft.selectedMemberIds,
                                "reports-member-${member.id}") { selected ->
                                val ids = draft.selectedMemberIds ?: state.members.mapTo(mutableSetOf()) { it.id }
                                onMembers(if (selected) ids + member.id else ids - member.id)
                            }
                        }
                    }
                    val resetDescription = stringResource(R.string.reports_reset_filters_description)
                    TextButton(onClick = reset, modifier = Modifier.testTag("reports-reset-filters")
                        .semantics { contentDescription = resetDescription }) {
                        Text(stringResource(R.string.reports_reset_filters))
                    }
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        OutlinedButton(onClick = dismiss, modifier = Modifier.weight(1f).testTag("reports-cancel-filters")) {
                            Text(stringResource(R.string.reports_cancel_filters))
                        }
                        Button(onClick = apply, modifier = Modifier.weight(1f).testTag("reports-apply-filters")) {
                            Text(stringResource(R.string.reports_apply_filters))
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun MemberSectionHeader(clear: () -> Unit) {
    val fontScale = LocalDensity.current.fontScale
    BoxWithConstraints(Modifier.fillMaxWidth()) {
        if (maxWidth < 360.dp || fontScale > 1.3f) {
            Column(Modifier.fillMaxWidth()) {
                Text(stringResource(R.string.reports_filter_members), style = MaterialTheme.typography.titleMedium,
                    modifier = Modifier.semantics { heading() })
                TextButton(onClick = clear, modifier = Modifier.align(Alignment.End).heightIn(min = 48.dp)
                    .testTag("reports-members-clear")) { Text(stringResource(R.string.reports_members_clear)) }
            }
        } else {
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(stringResource(R.string.reports_filter_members), style = MaterialTheme.typography.titleMedium,
                    modifier = Modifier.weight(1f).semantics { heading() })
                TextButton(onClick = clear, modifier = Modifier.heightIn(min = 48.dp)
                    .testTag("reports-members-clear")) { Text(stringResource(R.string.reports_members_clear)) }
            }
        }
    }
}

@Composable
private fun MemberFilterRow(label: String, selected: Boolean, tag: String, change: (Boolean) -> Unit) {
    Row(Modifier.fillMaxWidth().heightIn(min = 48.dp).testTag(tag)
        .toggleable(value = selected, role = Role.Checkbox, onValueChange = change),
        verticalAlignment = Alignment.CenterVertically) {
        Checkbox(checked = selected, onCheckedChange = null)
        Text(label, modifier = Modifier.weight(1f).padding(start = 8.dp))
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

@Composable
private fun CustomPeriodFields(
    state: ReportsUiState,
    onFrom: (String) -> Unit,
    onTo: (String) -> Unit,
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

private fun BigInteger.currency(): String = PlnMoney.currency(this)
private fun BigInteger.signedCurrency(): String = PlnMoney.currency(this, PlnSign.EXPLICIT_POSITIVE)
private fun Long.signedCurrency(): String = PlnMoney.currency(this, PlnSign.EXPLICIT_POSITIVE)
