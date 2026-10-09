package pl.bargor.thesaurus.ui.reports

import java.time.LocalDate

internal data class ReportAxisTickSize(val width: Int, val height: Int, val centerY: Float)
internal data class ReportAxisTickPlacement(val index: Int, val top: Float)
internal data class ReportAxisLayout(val width: Int, val ticks: List<ReportAxisTickPlacement>)

/** Candidates are in drawing priority order (zero first). Only painted labels reserve width. */
internal fun reportAxisLayout(ticks: List<ReportAxisTickSize>, height: Float, gap: Float): ReportAxisLayout {
    val visible = mutableListOf<ReportAxisTickPlacement>()
    ticks.forEachIndexed { index, tick ->
        val top = (tick.centerY - tick.height / 2f).coerceIn(0f, (height - tick.height).coerceAtLeast(0f))
        val bottom = top + tick.height
        if (visible.none { placed ->
                top < placed.top + ticks[placed.index].height + gap && bottom > placed.top - gap
            }) {
            visible += ReportAxisTickPlacement(index, top)
        }
    }
    return ReportAxisLayout(visible.maxOfOrNull { ticks[it.index].width } ?: 0, visible)
}

/** Daily steps change at the end of the day; monthly points retain actual calendar spacing. */
internal fun reportDateFraction(date: LocalDate, from: LocalDate, to: LocalDate, step: Boolean): Float {
    val span = to.toEpochDay() - from.toEpochDay()
    val elapsed = date.toEpochDay() - from.toEpochDay()
    return when {
        step -> ((elapsed + 1).toDouble() / (span + 1)).toFloat()
        span == 0L -> .5f
        else -> (elapsed.toDouble() / span).toFloat()
    }
}
