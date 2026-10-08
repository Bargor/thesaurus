package pl.bargor.thesaurus.data.firebase

import pl.bargor.thesaurus.data.model.Category
import pl.bargor.thesaurus.data.model.Subcategory

/** Matches Firestore text/document-ID order, including supplementary Unicode characters.
 * Names and IDs accepted by the decoder fit below Firestore's 1,500-byte comparison limit.
 * https://firebase.google.com/docs/firestore/manage-data/data-types
 */
internal val firestoreStringOrder: Comparator<String> = Comparator { left, right ->
    val leftBytes = left.toByteArray(Charsets.UTF_8)
    val rightBytes = right.toByteArray(Charsets.UTF_8)
    var result = 0
    var index = 0
    while (index < minOf(leftBytes.size, rightBytes.size) && result == 0) {
        result = (leftBytes[index].toInt() and 0xff).compareTo(rightBytes[index].toInt() and 0xff)
        index++
    }
    if (result != 0) result else leftBytes.size.compareTo(rightBytes.size)
}

internal fun orderedCategories(categories: List<Category>): List<Category> = categories.sortedWith(
    compareBy(firestoreStringOrder) { category: Category -> category.name }
        .thenBy(firestoreStringOrder) { it.id },
)

internal fun orderedSubcategories(subcategories: List<Subcategory>): List<Subcategory> = subcategories.sortedWith(
    compareBy(firestoreStringOrder) { subcategory: Subcategory -> subcategory.name }
        .thenBy(firestoreStringOrder) { it.id },
)
