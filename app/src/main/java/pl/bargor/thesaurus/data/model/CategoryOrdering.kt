package pl.bargor.thesaurus.data.model

import java.time.Instant

/** Per-user category order within one household. Category documents remain shared taxonomy data. */
data class CategoryOrder(
    val householdId: String,
    val userId: String,
    val categoryIds: List<String>,
    val updatedAt: Instant? = null,
) {
    init {
        require(householdId.isNotBlank() && householdId.length <= 128)
        require(userId.isNotBlank() && userId.length <= 128)
        require(categoryIds.size <= MAX_CATEGORIES)
        require(categoryIds.distinct().size == categoryIds.size)
        require(categoryIds.all { it.isNotBlank() && it.length <= 128 })
    }

    companion object {
        const val MAX_CATEGORIES = 200
    }
}

/**
 * Applies a possibly stale preference without mutating taxonomy data.
 * Missing categories are ignored and newly created categories are appended in repository order.
 */
fun List<Category>.orderedBy(preference: CategoryOrder?): List<Category> {
    if (isEmpty() || preference == null) return this
    val byId = associateBy(Category::id)
    val preferred = preference.categoryIds.mapNotNull(byId::get)
    val preferredIds = preferred.asSequence().map(Category::id).toHashSet()
    return preferred + filterNot { it.id in preferredIds }
}

/** Reorders active categories while keeping archived categories in their saved slots. */
fun List<Category>.moveActiveCategory(categoryId: String, targetCategoryId: String): List<Category> {
    if (categoryId == targetCategoryId) return this
    val activeSlots = indices.filter { !this[it].archived }
    val active = activeSlots.map(::get).toMutableList()
    val from = active.indexOfFirst { it.id == categoryId }
    val to = active.indexOfFirst { it.id == targetCategoryId }
    if (from < 0 || to < 0) return this
    val moved = active.removeAt(from)
    active.add(to, moved)
    val reordered = toMutableList()
    activeSlots.forEachIndexed { index, slot -> reordered[slot] = active[index] }
    return reordered
}
