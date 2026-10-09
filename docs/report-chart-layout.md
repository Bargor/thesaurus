# Report chart width

Issue [#105](https://github.com/Bargor/thesaurus/issues/105) changes the shared amount-chart presentation used by **Saldo w czasie** and **Trend miesięczny**. Repository queries, aggregation, signed balances, monthly expense magnitudes, calendar spacing, and selected report periods are unchanged.

## Width allocation

Previously, measuring every Y-axis label with both minimum and maximum width set to 38% of the chart forced that entire width to be reserved even for short amounts. The chart now reserves the width required by its measured visible Y labels, plus the small label-to-plot gap. The rest of the available width belongs to the data plot, with a small right inset protecting endpoint markers.

Compact PLN notation remains a fallback for large amounts in constrained layouts; the accessibility description retains exact signed amounts. The label budget is an upper limit, not an allocated fixed fraction. Both chart types share the same plot boundaries for drawing and X-axis date placement. Endpoint dates remain visible, with intermediate dates shown only when there is room without overlap.

Measurements respond to the actual parent width, density, text style, and font scale. The implementation follows Compose's [measure-and-place contract](https://developer.android.com/develop/ui/compose/layouts/custom); it does not hard-code the user's emulator pixel resolution into the application.

## Verification

JVM tests exercise shared geometry. Android tests render both real chart components in synthetic narrow, current-emulator-equivalent, wide, and landscape viewports, including enlarged text and large signed monetary values. They check measured label layouts, plot allocation, date placement, and painted pixels, not only semantics. Geometry-dependent pixel probes in the existing monthly-trend test follow the measured plot instead of assuming the old 38% margin, while retaining the calendar-spacing assertion.

The normal full CI suites remain authoritative. No local tests or interactive DEV data are changed. The CI evidence collector retrieves only `Pictures/ThesaurusTestEvidence/issue105/` from its disposable emulator; reviewed screenshots will be linked from the PR with their exact source commit and run.

Pixel checks recognize antialiased ink as the requested foreground or line color blended with the known surface, rather than requiring fully opaque color cores in small scaled glyphs and dots. Coverage and RGB residual are bounded; controls reject blank surfaces and the other chart color, including partial blends. Required pixel counts and geometry/value assertions remain unchanged. Outside-plot checks sample only pixel cells fully within their logical crop, preventing boundary-straddling cells from being misclassified. Initial diagnostic frames remain pending and are not published as validated evidence.
