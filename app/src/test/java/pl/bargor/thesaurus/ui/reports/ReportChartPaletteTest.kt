package pl.bargor.thesaurus.ui.reports

import java.math.BigInteger
import kotlin.math.pow
import kotlin.math.sqrt
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import pl.bargor.thesaurus.data.model.CategoryPalette

class ReportChartPaletteTest {
    @Test fun duplicatePreferredColorsAreResolvedBeforeThePaletteCanCycle() {
        listOf(5, ReportChartPalette.baseColors.size, ReportChartPalette.baseColors.size + 15, 100).forEach { count ->
            val categories = (0 until count).associate { "category-${it.toString().padStart(3, '0')}" to "blue" }
            val assigned = ReportChartPalette.assign(categories)
            assertEquals(categories.keys, assigned.keys)
            assertEquals("Every category needs its own RGB value at count $count", count, assigned.values.toSet().size)
            assertTrue(assigned.values.all { it in 0L..0xFFFFFFL })
        }
    }

    @Test fun extensionIsDeterministicAndIndependentOfInputOrder() {
        val categories = (0 until ReportChartPalette.baseColors.size + 15).associate {
            "category-${it.toString().padStart(3, '0')}" to if (it % 2 == 0) "rose" else null
        }
        val first = ReportChartPalette.assign(categories)
        assertEquals(first, ReportChartPalette.assign(categories))
        assertEquals(first, ReportChartPalette.assign(categories.entries.reversed().associate { it.toPair() }))
        assertTrue("More categories than base colors must extend the palette", first.values.any { it !in ReportChartPalette.baseColors })
    }

    @Test fun distinguishablePersistedColorsArePreservedAndReplacementDoesNotStealThem() {
        val categories = linkedMapOf("a" to "rose", "b" to "rose", "c" to "blue", "d" to "green")
        val assigned = ReportChartPalette.assign(categories)
        listOf("a", "c", "d").forEach { id ->
            assertEquals(CategoryPalette.forCategory(id, categories[id]).hex, assigned[id])
        }
        assertEquals(4, assigned.values.toSet().size)
        assertEquals(categories, linkedMapOf("a" to "rose", "b" to "rose", "c" to "blue", "d" to "green"))
    }

    @Test fun initiallyCollidingCategoriesArePerceptuallySeparatedRatherThanNearbyRgbVariants() {
        val colors = ReportChartPalette.assign((0 until 8).associate { "category-$it" to "blue" }).values.toList()
        colors.forEachIndexed { index, color ->
            colors.drop(index + 1).forEach { other ->
                val separation = deltaE(color, other)
                assertTrue("Assigned colors must be perceptually separated: $color / $other, ΔE=$separation", separation >= 22.0)
            }
        }
    }

    @Test fun nearbyPersistedColorClustersAreResolvedForTheChart() {
        val categories = linkedMapOf("a" to "amber", "b" to "gold", "c" to "orange", "d" to "peach", "e" to "green", "f" to "emerald")
        val assigned = ReportChartPalette.assign(categories)
        val colors = assigned.values.toList()
        colors.forEachIndexed { index, color ->
            colors.drop(index + 1).forEach { other ->
                assertTrue("Nearby persisted tones need a perceptible chart separation", deltaE(color, other) >= 22.0)
            }
        }
        assertEquals(CategoryPalette.byToken("amber").hex, assigned["a"])
    }

    @Test fun emptyInputHasNoSyntheticCategories() {
        assertTrue(ReportChartPalette.assign(emptyMap()).isEmpty())
    }

    @Test fun hugeAmountsHaveFiniteProportionalChartShares() {
        val unit = BigInteger.TEN.pow(400)
        assertEquals(.25f, reportCategoryShare(unit, unit * BigInteger.valueOf(4)), .000001f)
        assertEquals(1f, reportCategoryShare(unit, unit), .000001f)
        assertEquals(0f, reportCategoryShare(BigInteger.ZERO, unit), 0f)
        assertEquals(0f, reportCategoryShare(unit, BigInteger.ZERO), 0f)
    }

    // CIE76 measures perceptual distance, rather than only checking unequal RGB integers.
    private fun deltaE(first: Long, second: Long): Double {
        fun lab(rgb: Long): DoubleArray {
            fun linear(shift: Int): Double {
                val channel = ((rgb shr shift) and 255) / 255.0
                return if (channel <= .04045) channel / 12.92 else ((channel + .055) / 1.055).pow(2.4)
            }
            val r = linear(16)
            val g = linear(8)
            val b = linear(0)
            fun curve(v: Double) = if (v > .008856) v.pow(1.0 / 3) else 7.787 * v + 16.0 / 116
            val x = curve((.4124564 * r + .3575761 * g + .1804375 * b) / .95047)
            val y = curve(.2126729 * r + .7151522 * g + .0721750 * b)
            val z = curve((.0193339 * r + .1191920 * g + .9503041 * b) / 1.08883)
            return doubleArrayOf(116 * y - 16, 500 * (x - y), 200 * (y - z))
        }
        val a = lab(first)
        val b = lab(second)
        return sqrt(a.indices.sumOf { (a[it] - b[it]).pow(2) })
    }
}
