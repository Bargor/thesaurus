package pl.bargor.thesaurus.data.model

import java.math.BigDecimal
import java.math.BigInteger
import java.text.NumberFormat
import java.time.LocalDate
import java.util.Locale
import java.util.concurrent.Callable
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import org.junit.Assert.*
import org.junit.Test

class PlnMoneyTest {
    @Test fun entryAndBalancePoliciesRemainDistinct() {
        for (input in listOf("12,50", "12.50", " 12,5 ")) {
            assertEquals(1250L, PlnMoney.parseEntryGrosze(input, EntryType.INCOME))
            assertEquals(-1250L, PlnMoney.parseEntryGrosze(input, EntryType.EXPENSE))
            assertEquals(1250L, PlnMoney.parseBalanceGrosze(input))
        }
        for (input in listOf("0", "-0,00", "+12", "-12", "1 234,50", "1\u00a0234,50", "1\u202f234,50")) {
            assertNull(input, PlnMoney.parseEntryGrosze(input, EntryType.EXPENSE))
            PlnMoney.parseBalanceGrosze(input)
        }
        for (input in listOf("", ".5", "1,", "12,345", "1,2.3", "1e2", "9,99 zł", "1 23", "１２", "--1")) {
            assertNull(input, PlnMoney.parseEntryGrosze(input, EntryType.EXPENSE))
            assertThrows(IllegalArgumentException::class.java) { PlnMoney.parseBalanceGrosze(input) }
        }
        assertEquals(0L, PlnMoney.parseBalanceGrosze("-0,00"))
    }

    @Test fun signIsAppliedBeforeLongRangeCheckAndFormValuesRoundTrip() {
        assertEquals(Long.MAX_VALUE, PlnMoney.parseEntryGrosze("92233720368547758.07", EntryType.INCOME))
        assertEquals(Long.MIN_VALUE, PlnMoney.parseEntryGrosze("92233720368547758,08", EntryType.EXPENSE))
        assertNull(PlnMoney.parseEntryGrosze("92233720368547758,08", EntryType.INCOME))
        assertNull(PlnMoney.parseEntryGrosze("92233720368547758,09", EntryType.EXPENSE))
        assertThrows(ArithmeticException::class.java) { PlnMoney.parseBalanceGrosze("92233720368547758,08") }
        assertThrows(ArithmeticException::class.java) { PlnMoney.parseBalanceGrosze("-92233720368547758,09") }
        assertEquals("92233720368547758,08", PlnMoney.entryInput(Long.MIN_VALUE))
        assertEquals("12", PlnMoney.entryInput(-1200))
        assertEquals("12,50", PlnMoney.entryInput(-1250))
        for (value in listOf(Long.MIN_VALUE, Long.MAX_VALUE, -1L, 1L, -1200L, 1250L)) {
            val type = if (value > 0) EntryType.INCOME else EntryType.EXPENSE
            assertEquals(value, PlnMoney.parseEntryGrosze(PlnMoney.entryInput(value), type))
            assertEquals(value, PlnMoney.parseBalanceGrosze(PlnMoney.balanceInput(value)))
        }
    }

    @Test fun exactCurrencyRetainsPolishFormatterAndScreenSignConventions() {
        val values = listOf(BigInteger.ZERO, BigInteger.ONE, BigInteger.valueOf(Long.MIN_VALUE),
            BigInteger.valueOf(Long.MAX_VALUE) * BigInteger.valueOf(10_000))
        for (value in values) {
            val legacy = NumberFormat.getCurrencyInstance(Locale.forLanguageTag("pl-PL")).format(BigDecimal(value, 2))
            assertEquals(legacy, PlnMoney.currency(value))
            assertEquals((if (value.signum() > 0) "+" else "") + legacy,
                PlnMoney.currency(value, PlnSign.EXPLICIT_POSITIVE))
            assertEquals(PlnMoney.currency(value.abs()), PlnMoney.currency(value, PlnSign.MAGNITUDE))
        }
        val tick = BigDecimal("92233720368547758.087")
        assertEquals(NumberFormat.getNumberInstance(Locale.forLanguageTag("pl-PL"))
            .apply { maximumFractionDigits = 2 }.format(tick), PlnMoney.chartNumber(tick))
    }

    @Test fun concurrentCurrencyCallsRemainDeterministic() {
        val values = listOf(Long.MIN_VALUE, Long.MAX_VALUE, 0L, 12345L, -67890L).map { BigInteger.valueOf(it) }
        val expected = values.associateWith { PlnMoney.currency(it, PlnSign.EXPLICIT_POSITIVE) }
        val executor = Executors.newFixedThreadPool(8)
        try {
            val results = executor.invokeAll((0 until 400).map { index -> Callable {
                val value = values[index % values.size]
                assertEquals(expected.getValue(value), PlnMoney.currency(value, PlnSign.EXPLICIT_POSITIVE))
            } }, 10, TimeUnit.SECONDS)
            results.forEach { it.get() }
        } finally {
            executor.shutdownNow()
        }
    }

    @Test fun summariesAndReportsShareExactTotalsAndEmptyContract() {
        val date = LocalDate.of(2026, 9, 1)
        fun entry(id: String, amount: Long) = LedgerEntry(id, "home", amount, date,
            categoryId = "food", authorId = "actor", updatedById = "actor")
        val entries = listOf(entry("a", Long.MAX_VALUE), entry("b", Long.MAX_VALUE), entry("c", Long.MIN_VALUE))
        val period = SummaryPeriod(date, date)
        val summary = aggregateEntries(entries, period)
        val report = aggregateReportEntries(entries, period, ReportTypeFilter.ALL)
        assertEquals(summary, report.totals)
        assertEquals(BigInteger.valueOf(Long.MAX_VALUE) * BigInteger.valueOf(2), summary.incomeGrosze)
        assertEquals(BigInteger.valueOf(Long.MIN_VALUE).abs(), summary.expenseGrosze)
        assertEquals(summary.incomeGrosze - summary.expenseGrosze, summary.netGrosze)
        assertEquals(3, summary.entryCount)
        assertFalse(summary.isEmpty)
        assertTrue(aggregateEntries(emptyList(), period).isEmpty)
        assertEquals(SummaryTotals(), aggregateReportEntries(emptyList(), period, ReportTypeFilter.ALL).totals)
        assertThrows(ArithmeticException::class.java) { LedgerTotals(entryCount = Int.MAX_VALUE).add(1) }
    }
}
