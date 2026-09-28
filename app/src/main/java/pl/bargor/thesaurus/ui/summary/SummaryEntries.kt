package pl.bargor.thesaurus.ui.summary

import java.time.Instant
import pl.bargor.thesaurus.data.model.Category
import pl.bargor.thesaurus.data.model.LedgerEntry
import pl.bargor.thesaurus.data.model.Subcategory
import pl.bargor.thesaurus.data.model.SummaryPeriod

enum class SummaryEntrySort { DATE, AMOUNT, CATEGORY, SUBCATEGORY }
enum class SummarySortDirection { ASCENDING, DESCENDING }

data class SummaryEntryItem(
    val entry: LedgerEntry,
    val categoryName: String,
    val subcategoryName: String?,
)

/** A single selection is used by both calendar modes and every sort/filter combination. */
internal fun selectSummaryEntries(
    entries: List<LedgerEntry>,
    period: SummaryPeriod,
    categories: List<Category>,
    subcategories: Map<String, List<Subcategory>>,
    categoryId: String?,
    subcategoryId: String?,
    tag: String?,
    sort: SummaryEntrySort,
    direction: SummarySortDirection,
): List<SummaryEntryItem> {
    val categoryNames = categories.associate { it.id to it.name }
    val subcategoryNames = subcategories.mapValues { (_, values) -> values.associate { it.id to it.name } }
    val filtered = entries.asSequence()
        .filterNot { it.deleted }
        .filter { !it.date.isBefore(period.from) && !it.date.isAfter(period.to) }
        .filter { categoryId == null || it.categoryId == categoryId }
        .filter { subcategoryId == null || it.subcategoryId == subcategoryId }
        .filter { tag == null || tag in it.normalizedTags }
        .map { entry ->
            SummaryEntryItem(
                entry,
                categoryNames[entry.categoryId] ?: entry.categoryId,
                entry.subcategoryId?.let { subcategoryNames[entry.categoryId]?.get(it) ?: it },
            )
        }.toList()
    val comparator = when (sort) {
        SummaryEntrySort.DATE -> compareBy<SummaryEntryItem> { it.entry.date }
            .thenBy { it.entry.createdAt ?: Instant.MAX }
        SummaryEntrySort.AMOUNT -> compareBy { it.entry.amountGrosze }
        SummaryEntrySort.CATEGORY -> compareBy(String.CASE_INSENSITIVE_ORDER) { it.categoryName }
        SummaryEntrySort.SUBCATEGORY -> compareBy(nullsLast(String.CASE_INSENSITIVE_ORDER)) { it.subcategoryName }
    }
    // Id is a stable tie-breaker in either direction, independent of listener order.
    val ordered = filtered.sortedWith(comparator.thenBy { it.entry.id })
    return if (direction == SummarySortDirection.DESCENDING) ordered.asReversed() else ordered
}
