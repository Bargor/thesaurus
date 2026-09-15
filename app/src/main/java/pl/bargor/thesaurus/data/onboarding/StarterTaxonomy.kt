package pl.bargor.thesaurus.data.onboarding

import pl.bargor.thesaurus.data.model.EntryType

/**
 * Stable document ids make retrying initial setup harmless: every approved starter item has one
 * canonical location. User-visible names deliberately stay separate from those ids.
 */
data class StarterCategory(
    val id: String,
    val name: String,
    val defaultEntryType: EntryType = EntryType.EXPENSE,
    val subcategories: List<StarterSubcategory> = emptyList(),
)

data class StarterSubcategory(val id: String, val name: String)

object StarterTaxonomy {
    val categories: List<StarterCategory> = listOf(
        category("jedzenie", "Jedzenie", "supermarket" to "Supermarket", "restauracja" to "Restauracja", "codzienne" to "Codzienne", "cukiernia-lody" to "Cukiernia / Lody", "inne" to "Inne"),
        category("dom", "Dom", "prad" to "Prąd", "woda" to "Woda", "internet" to "Internet", "szambo" to "Szambo", "telefon" to "Telefon", "telewizja-streaming" to "Telewizja/Streaming", "meble" to "Meble", "agd" to "AGD", "naprawy" to "Naprawy", "ubezpieczenie" to "Ubezpieczenie", "elektronika" to "Elektronika", "inne" to "Inne"),
        category("odziez", "Odzież", "buty" to "Buty", "ubrania" to "Ubrania", "bielizna" to "Bielizna", "bizuteria" to "Biżuteria", "inne" to "Inne"),
        category("zdrowie-i-uroda", "Zdrowie i uroda", "lekarz" to "Lekarz", "kosmetyki" to "Kosmetyki", "leki" to "Leki", "fryzjer" to "Fryzjer", "inne" to "Inne"),
        category("dzieci", "Dzieci", "zabawki" to "Zabawki", "szkola" to "Szkoła", "kieszonkowe" to "Kieszonkowe"),
        category("samochod", "Samochód", "paliwo" to "Paliwo", "parking" to "Parking", "naprawy" to "Naprawy", "ubezpieczenie" to "Ubezpieczenie", "przeglad" to "Przegląd", "inne" to "Inne"),
        category("czas-wolny", "Czas wolny", "hobby" to "Hobby", "gry" to "Gry", "urlop" to "Urlop", "bilety" to "Bilety", "inne" to "Inne"),
        category("wplywy", "Wpływy", "wyplata" to "Wypłata", "premia" to "Premia", "800-plus" to "800+", "inwestycje" to "Inwestycje", "inne" to "Inne", defaultEntryType = EntryType.INCOME),
        category("darowizny", "Darowizny", "taca" to "Taca", "charytatywne" to "Charytatywne", "inne" to "Inne"),
        StarterCategory(id = "inne", name = "Inne"),
    )

    private fun category(
        id: String,
        name: String,
        vararg subcategories: Pair<String, String>,
        defaultEntryType: EntryType = EntryType.EXPENSE,
    ) = StarterCategory(
        id = id,
        name = name,
        defaultEntryType = defaultEntryType,
        subcategories = subcategories.map { (subcategoryId, subcategoryName) ->
            StarterSubcategory(subcategoryId, subcategoryName)
        },
    )
}
