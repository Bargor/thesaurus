package pl.bargor.thesaurus.data.model

/** Stable Firestore values. Localized names and rendered tones stay outside the data contract. */
data class CategorySwatch(val token: String, val hex: Long)

object CategoryPalette {
    val swatches = listOf(
        CategorySwatch("amber", 0xF59E0B),
        CategorySwatch("blue", 0x3B82F6),
        CategorySwatch("violet", 0x8B5CF6),
        CategorySwatch("pink", 0xEC4899),
        CategorySwatch("cyan", 0x06B6D4),
        CategorySwatch("slate", 0x64748B),
        CategorySwatch("teal", 0x14B8A6),
        CategorySwatch("green", 0x22C55E),
        CategorySwatch("red", 0xEF4444),
        CategorySwatch("gray", 0x9CA3AF),
        CategorySwatch("orange", 0xF97316),
        CategorySwatch("yellow", 0xEAB308),
        CategorySwatch("lime", 0x84CC16),
        CategorySwatch("emerald", 0x10B981),
        CategorySwatch("sky", 0x0EA5E9),
        CategorySwatch("indigo", 0x6366F1),
        CategorySwatch("purple", 0xA855F7),
        CategorySwatch("rose", 0xF43F5E),
        CategorySwatch("brown", 0xA16207),
        CategorySwatch("mint", 0x2DD4BF),
        CategorySwatch("navy", 0x1D4ED8),
        CategorySwatch("lavender", 0xC084FC),
        CategorySwatch("coral", 0xFB7185),
        CategorySwatch("peach", 0xFB923C),
        CategorySwatch("gold", 0xD97706),
        CategorySwatch("olive", 0x65A30D),
        CategorySwatch("forest", 0x15803D),
        CategorySwatch("ocean", 0x0891B2),
        CategorySwatch("plum", 0x9333EA),
        CategorySwatch("wine", 0xBE123C),
        CategorySwatch("steel", 0x475569),
        CategorySwatch("sand", 0xCA8A04),
    )

    private val byToken = swatches.associateBy { it.token }
    private val starterTokens = mapOf(
        "jedzenie" to "amber", "dom" to "blue", "odziez" to "violet",
        "zdrowie-i-uroda" to "pink", "dzieci" to "cyan", "samochod" to "slate",
        "czas-wolny" to "teal", "wplywy" to "green", "darowizny" to "red",
        "inne" to "gray",
    )

    fun isToken(value: String?): Boolean = value != null && value in byToken

    val defaultToken: String = swatches.first().token

    fun starterToken(id: String): String = requireNotNull(starterTokens[id])

    fun normalizedToken(value: String?): String? = value?.takeIf(::isToken)

    /** Missing or obsolete color fields retain a stable appearance without a Firestore migration. */
    fun forCategory(category: Category): CategorySwatch = byToken[category.color]
        ?: starterTokens[category.id]?.let(byToken::getValue)
        ?: swatches[Math.floorMod(category.id.hashCode(), swatches.size)]

    fun byToken(token: String): CategorySwatch = requireNotNull(byToken[token])
}
