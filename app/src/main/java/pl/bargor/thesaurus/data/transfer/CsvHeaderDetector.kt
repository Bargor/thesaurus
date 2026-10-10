package pl.bargor.thesaurus.data.transfer

import java.util.Locale

enum class CsvColumn { DATE, AMOUNT, TYPE, TITLE, CATEGORY, SUBCATEGORY, TAGS, AUTHOR }

data class CsvMappingDetection(
    val mapping: CsvColumnMapping,
    val missingRequired: Set<CsvColumn>,
    val ambiguousColumns: Set<CsvColumn>,
) {
    val requiresManualMapping: Boolean get() = missingRequired.isNotEmpty() || ambiguousColumns.isNotEmpty()
}

/** Neutral English headers plus Polish, German and French aliases; callers may add aliases.
 * Normalization trims and lowercases with ROOT only. Collisions, including optional fields,
 * remain ambiguous; custom aliases cannot take ownership of a neutral header.
 */
object CsvHeaderDetector {
    private val aliases = mapOf(
        CsvColumn.DATE to setOf("date", "data", "datum"),
        CsvColumn.AMOUNT to setOf("amount", "kwota", "betrag", "montant"),
        CsvColumn.TYPE to setOf("type", "typ", "art"),
        CsvColumn.TITLE to setOf("title", "tytuł", "tytul", "titel", "titre"),
        CsvColumn.CATEGORY to setOf("category", "kategoria", "kategorie", "catégorie", "categorie"),
        CsvColumn.SUBCATEGORY to setOf("subcategory", "podkategoria", "unterkategorie", "sous-catégorie", "sous-categorie"),
        CsvColumn.TAGS to setOf("tags", "tagi", "schlagwörter", "schlagworter", "étiquettes", "etiquettes"),
        CsvColumn.AUTHOR to setOf("author", "autor", "auteur"),
    )
    internal fun normalize(value: String): String = value.trim().lowercase(Locale.ROOT)
    internal fun matches(value: String): Set<CsvColumn> = aliases.filterValues { normalize(value) in it }.keys

    fun detect(headers: List<String>, extraAliases: Map<CsvColumn, Set<String>> = emptyMap()): CsvMappingDetection {
        val positions = CsvColumn.entries.associateWith { ArrayList<Int>() }
        val ambiguous = linkedSetOf<CsvColumn>()
        headers.forEachIndexed { index, header ->
            val normalized = normalize(header)
            val neutral = CsvColumn.entries.firstOrNull { it.name.lowercase(Locale.ROOT) == normalized }
            val matched = if (neutral != null) setOf(neutral) else matches(header) + extraAliases
                .filterValues { values -> values.any { normalize(it) == normalized } }.keys
            if (matched.size > 1) ambiguous.addAll(matched)
            matched.forEach { positions.getValue(it).add(index) }
        }
        positions.filterValues { it.size > 1 }.keys.forEach { ambiguous.add(it) }
        fun index(column: CsvColumn): Int? = positions.getValue(column).singleOrNull()?.takeIf { column !in ambiguous }
        val mapping = CsvColumnMapping(index(CsvColumn.DATE), index(CsvColumn.AMOUNT), index(CsvColumn.TITLE),
            index(CsvColumn.CATEGORY), index(CsvColumn.SUBCATEGORY), index(CsvColumn.TAGS), index(CsvColumn.TYPE))
        val missing = setOf(CsvColumn.DATE, CsvColumn.AMOUNT, CsvColumn.CATEGORY).filterTo(linkedSetOf()) { index(it) == null }
        return CsvMappingDetection(mapping, missing, ambiguous)
    }
}
