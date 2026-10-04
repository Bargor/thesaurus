package pl.bargor.thesaurus.data.model

import java.math.BigInteger
import java.time.LocalDate
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test

class AccountBalanceTest {
    @Test fun signedInputUsesExactGroszeIncludingZeroAndLongEndpoints() {
        assertEquals(0L, parseSignedPlnGrosze("-0,00"))
        assertEquals(123450L, parseSignedPlnGrosze("+1\u00a0234,5"))
        assertEquals(-123L, parseSignedPlnGrosze("-1.23"))
        assertEquals(Long.MAX_VALUE, parseSignedPlnGrosze("92233720368547758,07"))
        assertEquals(Long.MIN_VALUE, parseSignedPlnGrosze("-92233720368547758,08"))
        assertThrows(ArithmeticException::class.java) { parseSignedPlnGrosze("92233720368547758,08") }
        for (invalid in listOf("", "1,234", "1 23", "1e3", "NaN", "1,2.3", "--1")) {
            assertThrows(IllegalArgumentException::class.java) { parseSignedPlnGrosze(invalid) }
        }
    }

    @Test fun derivationIncludesAllDatesAndIgnoresTombstonesWithoutIntermediateOverflow() {
        val entries = listOf(entry(Long.MAX_VALUE), entry(Long.MAX_VALUE), entry(-Long.MAX_VALUE),
            entry(-25L, deleted = true))
        assertEquals(BigInteger.valueOf(Long.MAX_VALUE), signedLedgerTotal(entries))
        assertEquals(0L, deriveOpeningBalance(Long.MAX_VALUE, entries))
        assertEquals(BigInteger.valueOf(Long.MAX_VALUE) + BigInteger.ONE, absoluteAccountBalance(1L, entries))
        assertThrows(ArithmeticException::class.java) { deriveOpeningBalance(-2L, entries) }
    }

    private fun entry(amount: Long, deleted: Boolean = false) = LedgerEntry(
        id = amount.toString(), householdId = "home", amountGrosze = amount,
        date = LocalDate.of(2050, 1, 1), categoryId = "category", authorId = "owner", updatedById = "owner",
        deleted = deleted, deletedById = if (deleted) "owner" else null,
    )
}
