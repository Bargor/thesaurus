package pl.bargor.thesaurus.data.model

import java.math.BigInteger

/** Strict signed Polish PLN input, optionally grouping thousands with spaces. */
fun parseSignedPlnGrosze(input: String): Long {
    val value = input.trim().replace('\u00a0', ' ').replace('\u202f', ' ')
    require(Regex("[+-]?(?:[0-9]+|[0-9]{1,3}(?: [0-9]{3})+)(?:[,.][0-9]{1,2})?").matches(value)) {
        "Podaj prawidłową kwotę z najwyżej dwoma miejscami po przecinku."
    }
    val normalized = value.replace(" ", "").replace(',', '.')
    val negative = normalized.startsWith('-')
    val parts = normalized.removePrefix("-").removePrefix("+").split('.')
    val magnitude = BigInteger(parts[0]) * BigInteger.valueOf(100L) +
        BigInteger(parts.getOrElse(1) { "" }.padEnd(2, '0'))
    return (if (negative) -magnitude else magnitude).longValueExact()
}

/** Exact accumulation avoids overflow even when intermediate sums exceed Long. */
fun signedLedgerTotal(entries: Iterable<LedgerEntry>): BigInteger = entries
    .filterNot { it.deleted }
    .fold(BigInteger.ZERO) { total, entry -> total + BigInteger.valueOf(entry.amountGrosze) }

fun deriveOpeningBalance(currentBalanceGrosze: Long, entries: Iterable<LedgerEntry>): Long =
    (BigInteger.valueOf(currentBalanceGrosze) - signedLedgerTotal(entries)).longValueExact()

fun absoluteAccountBalance(openingBalanceGrosze: Long, entries: Iterable<LedgerEntry>): BigInteger =
    BigInteger.valueOf(openingBalanceGrosze) + signedLedgerTotal(entries)
