package pl.bargor.thesaurus.data.model

import java.math.BigDecimal
import java.math.BigInteger
import java.text.NumberFormat
import java.util.Locale

/** Input policies deliberately differ: entries are positive ungrouped magnitudes; balances
 * allow a sign, zero and correctly grouped spaces. Both accept comma/dot and at most two decimals.
 * Persisted money always remains signed Long grosze, while intermediate magnitudes are exact.
 */
object PlnMoney {
    private val entryPattern = Regex("[0-9]+(?:[,.][0-9]{1,2})?")
    private val balancePattern = Regex("[+-]?(?:[0-9]+|[0-9]{1,3}(?: [0-9]{3})+)(?:[,.][0-9]{1,2})?")
    private val hundred = BigInteger.valueOf(100)

    private fun decimalGrosze(value: String): BigInteger {
        val normalized = value.replace(" ", "").replace(',', '.')
        val parts = normalized.removePrefix("-").removePrefix("+").split('.')
        val magnitude = BigInteger(parts[0]) * hundred +
            BigInteger(parts.getOrElse(1) { "" }.padEnd(2, '0'))
        return if (normalized.startsWith('-')) -magnitude else magnitude
    }

    fun parseEntryMagnitude(input: String): BigInteger? {
        val value = input.trim()
        if (!entryPattern.matches(value)) return null
        return decimalGrosze(value).takeIf { it.signum() > 0 }
    }

    /** Applying the sign before Long conversion admits the full Long.MIN_VALUE expense. */
    fun parseEntryGrosze(input: String, type: EntryType): Long? =
        parseEntryMagnitude(input)?.let { magnitude ->
            runCatching { signedEntryGrosze(magnitude, type) }.getOrNull()
        }

    fun signedEntryGrosze(magnitude: BigInteger, type: EntryType): Long {
        require(magnitude.signum() > 0)
        return (if (type == EntryType.EXPENSE) -magnitude else magnitude).longValueExact()
    }

    fun parseBalanceGrosze(input: String): Long {
        val value = input.trim().replace('\u00a0', ' ').replace('\u202f', ' ')
        require(balancePattern.matches(value)) {
            "Podaj prawidłową kwotę z najwyżej dwoma miejscami po przecinku."
        }
        return decimalGrosze(value).longValueExact()
    }

    /** No shared mutable NumberFormat: each call owns its formatter, including concurrent callers. */
    fun currency(grosze: BigInteger, sign: PlnSign = PlnSign.NATURAL): String {
        val value = if (sign == PlnSign.MAGNITUDE) grosze.abs() else grosze
        return (if (sign == PlnSign.EXPLICIT_POSITIVE && value.signum() > 0) "+" else "") +
            NumberFormat.getCurrencyInstance(Locale.forLanguageTag("pl-PL")).format(BigDecimal(value, 2))
    }

    fun currency(grosze: Long, sign: PlnSign = PlnSign.NATURAL): String =
        currency(BigInteger.valueOf(grosze), sign)

    /** Decimal chart ticks retain the existing Polish number rounding and grouping. */
    fun chartNumber(value: BigDecimal): String =
        NumberFormat.getNumberInstance(Locale.forLanguageTag("pl-PL"))
            .apply { maximumFractionDigits = 2 }.format(value)

    fun balanceInput(grosze: Long): String =
        BigDecimal.valueOf(grosze, 2).toPlainString().replace('.', ',')

    /** Preserve existing entry input: whole PLN omit decimals, fractions always use two digits. */
    fun entryInput(grosze: Long): String {
        val (whole, fraction) = BigInteger.valueOf(grosze).abs().divideAndRemainder(hundred)
        return if (fraction.signum() == 0) whole.toString()
        else "$whole,${fraction.toString().padStart(2, '0')}"
    }
}

enum class PlnSign { NATURAL, EXPLICIT_POSITIVE, MAGNITUDE }

/** Income and expense magnitudes, signed net and active-entry count share one contract. */
data class LedgerTotals(
    val incomeGrosze: BigInteger = BigInteger.ZERO,
    val expenseGrosze: BigInteger = BigInteger.ZERO,
    val entryCount: Int = 0,
) {
    val netGrosze: BigInteger get() = incomeGrosze - expenseGrosze
    val isEmpty: Boolean get() = entryCount == 0

    fun add(signedGrosze: Long): LedgerTotals {
        val amount = BigInteger.valueOf(signedGrosze)
        return copy(
            incomeGrosze = incomeGrosze + amount.max(BigInteger.ZERO),
            expenseGrosze = expenseGrosze + (-amount).max(BigInteger.ZERO),
            entryCount = Math.addExact(entryCount, 1),
        )
    }
}
