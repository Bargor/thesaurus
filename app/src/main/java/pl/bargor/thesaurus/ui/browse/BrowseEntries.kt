package pl.bargor.thesaurus.ui.browse

import java.math.BigInteger
import java.time.Instant
import pl.bargor.thesaurus.data.model.Category
import pl.bargor.thesaurus.data.model.LedgerEntry
import pl.bargor.thesaurus.data.model.Subcategory
import pl.bargor.thesaurus.data.model.SummaryPeriod

/** Null identifies the explicit no-subcategory bucket; it cannot collide with a taxonomy id. */
data class BrowseSubcategoryKey(val categoryId: String, val subcategoryId: String?)

data class BrowseSubcategoryGroup(
    val key: BrowseSubcategoryKey,
    val subcategory: Subcategory?,
    val netGrosze: BigInteger,
    val entries: List<LedgerEntry>,
)

data class BrowseCategoryGroup(
    val categoryId: String,
    val category: Category?,
    val netGrosze: BigInteger,
    val entryCount: Int,
    val subcategories: List<BrowseSubcategoryGroup>,
)

/** Taxonomy order is preserved, followed by stable ids for historical missing taxonomy. */
internal fun groupBrowseEntries(
    entries: List<LedgerEntry>,
    householdId: String,
    period: SummaryPeriod,
    categories: List<Category>,
    subcategories: Map<String, List<Subcategory>>,
): List<BrowseCategoryGroup> = bindBrowseTaxonomy(
    prepareBrowseEntries(entries, householdId, period), householdId, categories, subcategories,
)

/** Filtering, chronological sorting and exact sums run only when ledger data or period changes. */
internal fun prepareBrowseEntries(
    entries: List<LedgerEntry>,
    householdId: String,
    period: SummaryPeriod,
): List<BrowseCategoryGroup> {
    val grouped = linkedMapOf<String, MutableMap<String?, MutableList<LedgerEntry>>>()
    entries.forEach { entry ->
        if (entry.householdId == householdId && !entry.deleted &&
            !entry.date.isBefore(period.from) && !entry.date.isAfter(period.to)
        ) {
            grouped.getOrPut(entry.categoryId) { linkedMapOf() }
                .getOrPut(entry.subcategoryId) { mutableListOf() }.add(entry)
        }
    }
    val entryOrder = compareByDescending<LedgerEntry> { it.date }
        .thenByDescending { it.createdAt ?: Instant.MIN }.thenBy { it.id }
    return grouped.map { (categoryId, buckets) ->
        val groups = buckets.map { (subcategoryId, bucket) ->
            val selected = bucket.sortedWith(entryOrder)
            BrowseSubcategoryGroup(
                BrowseSubcategoryKey(categoryId, subcategoryId), null,
                selected.fold(BigInteger.ZERO) { total, entry -> total + BigInteger.valueOf(entry.amountGrosze) },
                selected,
            )
        }
        BrowseCategoryGroup(
            categoryId, null,
            groups.fold(BigInteger.ZERO) { total, group -> total + group.netGrosze },
            groups.sumOf { it.entries.size }, groups,
        )
    }
}

/** Rebinding names and ordering reuses prepared entry lists and totals without touching history. */
internal fun bindBrowseTaxonomy(
    prepared: List<BrowseCategoryGroup>,
    householdId: String,
    categories: List<Category>,
    subcategories: Map<String, List<Subcategory>>,
): List<BrowseCategoryGroup> {
    val categoryById = categories.filter { it.householdId == householdId }.associateBy { it.id }
    val byId = prepared.associateBy { it.categoryId }
    val categoryIds = categoryById.keys.filter { it in byId } +
        byId.keys.filterNot { it in categoryById }.sorted()
    return categoryIds.map { categoryId ->
        val group = byId.getValue(categoryId)
        val buckets = group.subcategories.associateBy { it.key.subcategoryId }
        val subcategoryById = subcategories[categoryId].orEmpty()
            .filter { it.householdId == householdId && it.categoryId == categoryId }.associateBy { it.id }
        val subcategoryIds = subcategoryById.keys.filter { it in buckets } +
            buckets.keys.filterNotNull().filterNot { it in subcategoryById }.sorted()
        val orderedKeys: List<String?> = subcategoryIds + if (null in buckets) listOf(null) else emptyList()
        group.copy(category = categoryById[categoryId], subcategories = orderedKeys.map { id ->
            buckets.getValue(id).copy(subcategory = subcategoryById[id])
        })
    }
}
