package pl.bargor.thesaurus.data.model

import java.math.BigInteger

/** Strict signed Polish PLN input, optionally grouping thousands with spaces. */
fun parseSignedPlnGrosze(input: String): Long = PlnMoney.parseBalanceGrosze(input)

/** Exact accumulation avoids overflow even when intermediate sums exceed Long. */
fun signedLedgerTotal(entries: Iterable<LedgerEntry>): BigInteger = entries
    .filterNot { it.deleted }
    .fold(BigInteger.ZERO) { total, entry -> total + BigInteger.valueOf(entry.amountGrosze) }

fun deriveOpeningBalance(currentBalanceGrosze: Long, entries: Iterable<LedgerEntry>): Long =
    (BigInteger.valueOf(currentBalanceGrosze) - signedLedgerTotal(entries)).longValueExact()

fun absoluteAccountBalance(openingBalanceGrosze: Long, entries: Iterable<LedgerEntry>): BigInteger =
    BigInteger.valueOf(openingBalanceGrosze) + signedLedgerTotal(entries)
