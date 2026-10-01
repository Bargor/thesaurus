package pl.bargor.thesaurus.ui.reports

internal enum class ReportTotalsLayout { ROW, STACK }

/** All cards share a row only when its narrowest equal column fits every full value. */
internal fun chooseReportTotalsLayout(
    availableWidthPx: Int,
    requiredCardWidthsPx: List<Int>,
    gapPx: Int,
): ReportTotalsLayout {
    val count = requiredCardWidthsPx.size
    if (count == 0) return ReportTotalsLayout.ROW
    val required = requiredCardWidthsPx.max().toLong() * count + gapPx.toLong() * (count - 1)
    return if (required <= availableWidthPx.toLong()) ReportTotalsLayout.ROW else ReportTotalsLayout.STACK
}
