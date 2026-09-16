package pl.bargor.thesaurus.ui.taxonomy

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class TaxonomyValidationTest {
    @Test
    fun trimsAValidName() {
        assertEquals("Jedzenie domowe", TaxonomyValidation.nameOrNull("  Jedzenie domowe  "))
    }

    @Test
    fun rejectsBlankAndTooLongNames() {
        assertNull(TaxonomyValidation.nameOrNull(" \t "))
        assertNull(TaxonomyValidation.nameOrNull("a".repeat(TaxonomyValidation.MAX_NAME_LENGTH + 1)))
    }
}
