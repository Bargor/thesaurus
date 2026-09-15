package pl.bargor.thesaurus.data.onboarding

import org.junit.Assert.assertEquals
import org.junit.Test

class StarterTaxonomyTest {
    @Test
    fun `starter taxonomy is complete ordered and uses its approved entry types`() {
        assertEquals(
            listOf(
                "jedzenie|Jedzenie|EXPENSE|supermarket=Supermarket,restauracja=Restauracja,codzienne=Codzienne,cukiernia-lody=Cukiernia / Lody,inne=Inne",
                "dom|Dom|EXPENSE|prad=Prąd,woda=Woda,internet=Internet,szambo=Szambo,telefon=Telefon,telewizja-streaming=Telewizja/Streaming,meble=Meble,agd=AGD,naprawy=Naprawy,ubezpieczenie=Ubezpieczenie,elektronika=Elektronika,inne=Inne",
                "odziez|Odzież|EXPENSE|buty=Buty,ubrania=Ubrania,bielizna=Bielizna,bizuteria=Biżuteria,inne=Inne",
                "zdrowie-i-uroda|Zdrowie i uroda|EXPENSE|lekarz=Lekarz,kosmetyki=Kosmetyki,leki=Leki,fryzjer=Fryzjer,inne=Inne",
                "dzieci|Dzieci|EXPENSE|zabawki=Zabawki,szkola=Szkoła,kieszonkowe=Kieszonkowe",
                "samochod|Samochód|EXPENSE|paliwo=Paliwo,parking=Parking,naprawy=Naprawy,ubezpieczenie=Ubezpieczenie,przeglad=Przegląd,inne=Inne",
                "czas-wolny|Czas wolny|EXPENSE|hobby=Hobby,gry=Gry,urlop=Urlop,bilety=Bilety,inne=Inne",
                "wplywy|Wpływy|INCOME|wyplata=Wypłata,premia=Premia,800-plus=800+,inwestycje=Inwestycje,inne=Inne",
                "darowizny|Darowizny|EXPENSE|taca=Taca,charytatywne=Charytatywne,inne=Inne",
                "inne|Inne|EXPENSE|",
            ),
            StarterTaxonomy.categories.map { category ->
                buildString {
                    append(category.id)
                    append('|')
                    append(category.name)
                    append('|')
                    append(category.defaultEntryType)
                    append('|')
                    append(category.subcategories.joinToString(",") { "${it.id}=${it.name}" })
                }
            },
        )
    }

    @Test
    fun `starter ids are unique at both taxonomy levels`() {
        assertEquals(
            StarterTaxonomy.categories.size,
            StarterTaxonomy.categories.map { it.id }.toSet().size,
        )
        StarterTaxonomy.categories.forEach { category ->
            assertEquals(
                category.subcategories.size,
                category.subcategories.map { it.id }.toSet().size,
            )
        }
    }
}
