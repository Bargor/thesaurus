package pl.bargor.thesaurus.ui.browse

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ExpandLess
import androidx.compose.material.icons.filled.ExpandMore
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import java.math.BigDecimal
import java.math.BigInteger
import java.text.NumberFormat
import java.time.LocalDate
import java.util.Locale
import pl.bargor.thesaurus.R
import pl.bargor.thesaurus.data.model.LedgerEntry
import pl.bargor.thesaurus.data.model.SyncState
import pl.bargor.thesaurus.ui.categoryAccentColor
import pl.bargor.thesaurus.ui.categoryContainer
import pl.bargor.thesaurus.ui.entries.EntryCard
import pl.bargor.thesaurus.ui.entries.EntryListItem
import pl.bargor.thesaurus.ui.entries.PolishDateFormatter
import pl.bargor.thesaurus.ui.summary.SummaryPeriodHeader
import pl.bargor.thesaurus.ui.summary.SummaryPeriodMode

private sealed interface BrowseRow {
    val key: String
    data class Category(val group: BrowseCategoryGroup) : BrowseRow {
        override val key = "category-${group.categoryId}"
    }
    data class Subcategory(val category: BrowseCategoryGroup, val group: BrowseSubcategoryGroup) : BrowseRow {
        override val key = "subcategory-${group.key.categoryId.length}:${group.key.categoryId}:" +
            (group.key.subcategoryId?.let { "${it.length}:$it" } ?: "null")
    }
    data class DateHeading(val date: LocalDate, val firstEntryId: String) : BrowseRow {
        override val key = "date-$firstEntryId"
    }
    data class Entry(val entry: LedgerEntry, val item: EntryListItem) : BrowseRow {
        override val key = "entry-${entry.id}"
    }
}

private fun subcategoryTag(key: BrowseSubcategoryKey): String =
    "browse-subcategory-${key.categoryId}-${key.subcategoryId ?: "none"}"

private fun browseRows(state: BrowseUiState): List<BrowseRow> = buildList {
    state.categories.forEach { category ->
        add(BrowseRow.Category(category))
        if (category.categoryId in state.expandedCategoryIds) category.subcategories.forEach { subcategory ->
            add(BrowseRow.Subcategory(category, subcategory))
            if (subcategory.key in state.expandedSubcategories) {
                var previousDate: LocalDate? = null
                subcategory.entries.forEach { entry ->
                    if (entry.date != previousDate) add(BrowseRow.DateHeading(entry.date, entry.id))
                    add(BrowseRow.Entry(entry, state.entryItems[entry.id] ?: EntryListItem(
                        entry, category.category?.name, subcategory.subcategory?.name, entry.authorId,
                        categoryColor = category.category?.color,
                    )))
                    previousDate = entry.date
                }
            }
        }
    }
}

@Composable
fun BrowseScreen(
    state: BrowseUiState,
    onSelectPeriodMode: (SummaryPeriodMode) -> Unit,
    onPreviousPeriod: () -> Unit,
    onNextPeriod: () -> Unit,
    onToggleCategory: (String) -> Unit,
    onToggleSubcategory: (BrowseSubcategoryKey) -> Unit,
    onRetry: () -> Unit,
    modifier: Modifier = Modifier,
    onOpenEntry: (String) -> Unit = {},
) {
    val rows = remember(state) { browseRows(state) }
    val loadingLabel = stringResource(R.string.accessibility_loading)
    Column(modifier = modifier.fillMaxSize().padding(horizontal = 16.dp)) {
        Box(Modifier.padding(vertical = 16.dp)) {
            SummaryPeriodHeader(state.month, state.year, state.mode, onSelectPeriodMode,
                onPreviousPeriod, onNextPeriod, testTagPrefix = "browse")
        }
        LazyColumn(
            modifier = Modifier.weight(1f).fillMaxWidth().testTag("browse-list"),
            contentPadding = PaddingValues(bottom = 16.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            if (state.isLoading) item(key = "loading") {
                CircularProgressIndicator(Modifier.testTag("browse-loading").semantics {
                    contentDescription = loadingLabel
                })
            }
            if (state.hasError) item(key = "error") {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text(stringResource(R.string.browse_load_error), color = MaterialTheme.colorScheme.error,
                        modifier = Modifier.testTag("browse-error"))
                    Button(onClick = onRetry, modifier = Modifier.testTag("browse-retry")) {
                        Text(stringResource(R.string.auth_retry))
                    }
                }
            }
            if (state.syncState == SyncState.PENDING) item(key = "pending") {
                Text(stringResource(R.string.summary_sync_pending), modifier = Modifier.testTag("browse-pending"))
            }
            if (!state.isLoading && !state.hasError && state.categories.isEmpty()) item(key = "empty") {
                Text(stringResource(if (state.mode == SummaryPeriodMode.MONTH) R.string.empty_summary
                    else R.string.empty_summary_year), modifier = Modifier.testTag("browse-empty"))
            }
            items(rows, key = { it.key }, contentType = {
                when (it) {
                    is BrowseRow.Category -> "category"
                    is BrowseRow.Subcategory -> "subcategory"
                    is BrowseRow.DateHeading -> "date"
                    is BrowseRow.Entry -> "entry"
                }
            }) { row ->
                when (row) {
                    is BrowseRow.Category -> BrowseTile(
                        name = row.group.category?.name ?: stringResource(R.string.entries_unknown_category),
                        amount = row.group.netGrosze,
                        categoryId = row.group.categoryId,
                        categoryColor = row.group.category?.color,
                        expanded = row.group.categoryId in state.expandedCategoryIds,
                        isCategory = true,
                        tag = "browse-category-${row.group.categoryId}",
                        onClick = { onToggleCategory(row.group.categoryId) },
                    )
                    is BrowseRow.Subcategory -> BrowseTile(
                        name = row.group.subcategory?.name ?: stringResource(
                            if (row.group.key.subcategoryId == null) R.string.browse_no_subcategory
                            else R.string.browse_unknown_subcategory),
                        amount = row.group.netGrosze,
                        categoryId = row.category.categoryId,
                        categoryColor = row.category.category?.color,
                        expanded = row.group.key in state.expandedSubcategories,
                        isCategory = false,
                        tag = subcategoryTag(row.group.key),
                        onClick = { onToggleSubcategory(row.group.key) },
                    )
                    is BrowseRow.DateHeading -> Text(
                        row.date.format(PolishDateFormatter), style = MaterialTheme.typography.labelLarge,
                        fontWeight = FontWeight.SemiBold,
                        modifier = Modifier.padding(start = 16.dp).testTag("browse-${row.key}").semantics { heading() },
                    )
                    is BrowseRow.Entry -> Box(Modifier.padding(start = 16.dp)) {
                        val description = browseEntryDescription(row.item)
                        EntryCard(row.item, onOpenEntry, modifier = Modifier.semantics(mergeDescendants = true) {
                            contentDescription = description
                        })
                    }
                }
            }
        }
    }
}

@Composable
private fun browseEntryDescription(item: EntryListItem): String {
    val entry = item.entry
    val amount = (if (entry.amountGrosze > 0) "+" else "") +
        NumberFormat.getCurrencyInstance(Locale.forLanguageTag("pl-PL"))
            .format(BigDecimal.valueOf(entry.amountGrosze, 2))
    val parts = mutableListOf(
        stringResource(R.string.browse_entry_description, entry.date.format(PolishDateFormatter), amount),
        stringResource(R.string.browse_entry_category, item.categoryName
            ?: stringResource(R.string.entries_unknown_category)),
        stringResource(R.string.browse_entry_subcategory, item.subcategoryName
            ?: stringResource(if (entry.subcategoryId == null) R.string.browse_no_subcategory
                else R.string.browse_unknown_subcategory)),
    )
    entry.normalizedTitle?.let { parts += stringResource(R.string.browse_entry_title, it) }
    if (entry.normalizedTags.isNotEmpty()) {
        parts += stringResource(R.string.browse_entry_tags, entry.normalizedTags.joinToString(", ") { "#$it" })
    }
    if (item.authorName.isNotBlank()) parts += stringResource(R.string.browse_entry_author, item.authorName)
    return parts.joinToString(". ")
}

@Composable
private fun BrowseTile(
    name: String,
    amount: BigInteger,
    categoryId: String,
    categoryColor: String?,
    expanded: Boolean,
    isCategory: Boolean,
    tag: String,
    onClick: () -> Unit,
) {
    val surface = MaterialTheme.colorScheme.surface
    val dark = surface.luminance() < 0.5f
    val accent = categoryAccentColor(categoryId, categoryColor)
    val formatted = (if (amount.signum() > 0) "+" else "") +
        NumberFormat.getCurrencyInstance(Locale.forLanguageTag("pl-PL")).format(BigDecimal(amount, 2))
    val description = stringResource(if (isCategory) R.string.browse_category_description
        else R.string.browse_subcategory_description, name, formatted)
    val expansion = stringResource(if (expanded) R.string.browse_expanded else R.string.browse_collapsed)
    val amountColor = when {
        amount.signum() < 0 -> if (dark) Color(0xFFFFB4AB) else Color(0xFFB3261E)
        amount.signum() > 0 -> if (dark) Color(0xFF8FDBA1) else Color(0xFF146C2E)
        else -> MaterialTheme.colorScheme.onSurface
    }
    Card(
        onClick = onClick,
        modifier = Modifier.padding(start = if (isCategory) 0.dp else 8.dp)
            .fillMaxWidth().heightIn(min = 48.dp).testTag(tag).semantics {
                contentDescription = description
                stateDescription = expansion
            },
        colors = CardDefaults.cardColors(containerColor = accent.categoryContainer(surface, selected = false, dark = dark)),
        border = BorderStroke(if (isCategory) 2.dp else 1.dp, accent),
    ) {
        FlowRow(
            modifier = Modifier.fillMaxWidth().padding(12.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp, Alignment.End),
            verticalArrangement = Arrangement.spacedBy(4.dp),
            itemVerticalAlignment = Alignment.CenterVertically,
        ) {
            Icon(if (expanded) Icons.Default.ExpandLess else Icons.Default.ExpandMore, contentDescription = null)
            Text(name, style = MaterialTheme.typography.titleMedium, modifier = Modifier.weight(1f))
            Text(formatted, color = amountColor, fontWeight = FontWeight.Bold, textAlign = TextAlign.End,
                modifier = Modifier.testTag("$tag-amount"))
        }
    }
}
