package pl.bargor.thesaurus.ui.reports

import pl.bargor.thesaurus.data.model.CategoryPalette
import kotlin.math.pow
import kotlin.math.roundToInt
import kotlin.math.sqrt

/** RGB values only: chart assignment is local, deterministic, and usable in plain JVM tests. */
internal object ReportChartPalette {
    val baseColors: List<Long> = listOf(
        0x3B82F6, 0xF59E0B, 0x22C55E, 0xEC4899, 0x8B5CF6,
        0x06B6D4, 0xEF4444, 0x64748B, 0xA16207, 0x84CC16,
        0x1D4ED8, 0xBE123C, 0x15803D, 0xC084FC, 0xFB923C,
        0x2DD4BF, 0xCA8A04, 0x475569, 0x9333EA, 0x9CA3AF,
    )
    private const val preferredSeparation = 22.0
    private data class Candidate(val rgb: Long, val lab: DoubleArray = lab(rgb))
    private val base = baseColors.map(::Candidate)
    private val extended by lazy {
        buildList {
            for (value in listOf(.95, .75, .55)) {
                for (saturation in listOf(.9, .7, .5)) {
                    for (hue in 0 until 360 step 3) add(Candidate(hsv(hue, saturation, value)))
                }
            }
        }.distinctBy { it.rgb }
    }

    fun assign(categories: Map<String, String?>): Map<String, Long> {
        val assigned = linkedMapOf<String, Long>()
        val used = mutableListOf<Candidate>()
        val pending = mutableListOf<String>()
        // Reserve distinguishable preferred colors first; a replacement must not steal one.
        categories.toSortedMap().forEach { (id, token) ->
            val preferred = Candidate(CategoryPalette.forCategory(id, token).hex)
            if (distanceToUsed(preferred, used) >= preferredSeparation) {
                assigned[id] = preferred.rgb
                used += preferred
            } else pending += id
        }
        pending.forEach { id ->
            val fromBase = farthest(base, used)
            val selected = if (fromBase != null && distanceToUsed(fromBase, used) >= preferredSeparation) {
                fromBase
            } else {
                // Never cycle the base: extend with the most separated unused hue/tone.
                val fromExtension = farthest(extended, used)
                listOfNotNull(fromBase, fromExtension).maxByOrNull { distanceToUsed(it, used) }
                    ?: fallback(used)
            }
            assigned[id] = selected.rgb
            used += selected
        }
        return assigned
    }

    private fun farthest(candidates: List<Candidate>, used: List<Candidate>) = candidates
        .asSequence().filter { candidate -> used.none { it.rgb == candidate.rgb } }
        .maxByOrNull { distanceToUsed(it, used) }

    private fun distanceToUsed(candidate: Candidate, used: List<Candidate>): Double =
        used.minOfOrNull { other ->
            sqrt(candidate.lab.indices.sumOf { index -> (candidate.lab[index] - other.lab[index]).pow(2) })
        } ?: Double.POSITIVE_INFINITY

    private fun fallback(used: List<Candidate>): Candidate {
        // Exhaustion of the hue grid still cannot repeat an RGB color (up to the RGB gamut).
        val existing = used.mapTo(hashSetOf()) { it.rgb }
        for (index in 0L..0xFFFFFFL) {
            val rgb = (index * 0x9E3779L + 0x345678L) and 0xFFFFFFL
            if (rgb !in existing) return Candidate(rgb)
        }
        error("The RGB gamut is exhausted")
    }

    private fun hsv(hue: Int, saturation: Double, value: Double): Long {
        val chroma = value * saturation
        val x = chroma * (1 - kotlin.math.abs((hue / 60.0) % 2 - 1))
        val (r, g, b) = when (hue / 60) {
            0 -> Triple(chroma, x, 0.0)
            1 -> Triple(x, chroma, 0.0)
            2 -> Triple(0.0, chroma, x)
            3 -> Triple(0.0, x, chroma)
            4 -> Triple(x, 0.0, chroma)
            else -> Triple(chroma, 0.0, x)
        }
        val m = value - chroma
        fun byte(component: Double) = ((component + m) * 255).roundToInt().toLong()
        return (byte(r) shl 16) or (byte(g) shl 8) or byte(b)
    }

    private fun lab(rgb: Long): DoubleArray {
        fun linear(shift: Int): Double {
            val channel = ((rgb shr shift) and 255) / 255.0
            return if (channel <= .04045) channel / 12.92 else ((channel + .055) / 1.055).pow(2.4)
        }
        val r = linear(16); val g = linear(8); val b = linear(0)
        fun f(value: Double) = if (value > .008856) value.pow(1.0 / 3) else 7.787 * value + 16.0 / 116
        val x = f((r * .4124564 + g * .3575761 + b * .1804375) / .95047)
        val y = f(r * .2126729 + g * .7151522 + b * .0721750)
        val z = f((r * .0193339 + g * .1191920 + b * .9503041) / 1.08883)
        return doubleArrayOf(116 * y - 16, 500 * (x - y), 200 * (y - z))
    }
}
