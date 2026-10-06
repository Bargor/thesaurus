package pl.bargor.thesaurus.ui.entries

import pl.bargor.thesaurus.data.model.Category
import pl.bargor.thesaurus.data.model.LedgerEntry
import pl.bargor.thesaurus.data.model.Member
import pl.bargor.thesaurus.data.model.MemberRole
import pl.bargor.thesaurus.data.model.Subcategory

/** Historical entries retain their IDs as fallbacks when taxonomy/member records are absent. */
data class EntryPresentation(
    val categoryName: String?,
    val categoryColor: String?,
    val subcategoryName: String?,
    val authorName: String,
    val canManage: Boolean,
) {
    fun listItem(entry: LedgerEntry) = EntryListItem(
        entry, categoryName, subcategoryName, authorName, canManage, categoryColor,
    )
}

fun canManageEntry(entry: LedgerEntry, actorId: String, members: Map<String, Member>): Boolean =
    actorId.isNotEmpty() && (entry.authorId == actorId || members[actorId]?.role == MemberRole.OWNER)

fun presentEntry(
    entry: LedgerEntry,
    categories: Map<String, Category>,
    subcategories: Map<String, List<Subcategory>>,
    members: Map<String, Member> = emptyMap(),
    actorId: String = "",
    trimAuthorName: Boolean = false,
): EntryPresentation {
    val category = categories[entry.categoryId]
    val subcategory = subcategories[entry.categoryId].orEmpty().firstOrNull { it.id == entry.subcategoryId }
    val author = members[entry.authorId]
    val name = author?.displayName?.let { if (trimAuthorName) it.trim() else it }
    return EntryPresentation(
        category?.name, category?.color, subcategory?.name,
        name?.takeIf(String::isNotBlank) ?: author?.email ?: entry.authorId,
        canManageEntry(entry, actorId, members),
    )
}
