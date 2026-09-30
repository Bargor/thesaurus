# Issue #60 — report chart palette and category legend

These are unedited screenshots from the API 31 Android emulator, using the DEV
application and the existing 50 synthetic entries from September 2026. No real
Google account or production household was used. The device is in portrait at
1080 × 2400 pixels, density 420, with the default font scale.

## Visual comparison

### Compact legend after review

- `compact-report.png`: all entries, with color/name, right-aligned exact amount, and
  right-aligned percentage sharing one line per category.
- `compact-expenses-chart.png`: the expense chart and compact legend.
- `compact-expenses-legend.png`: the scrolled compact legend showing all nine
  expense categories and the start of the entry list.

Normal-size content fits the viewport. If unusually long names, amounts, or font
scaling need more width, one shared horizontal scroller preserves single lines
and full text without clipping or ellipsis. Shared amount and percentage columns
sit next to each other on the right, with right-aligned values across every row.

### Original implementation before the compact-layout review

- `before-report.png`: the previous report, with category amounts in a continuous
  paragraph and no color swatches.
- `after-report.png`: the same month and all-entry filter after the change, showing
  the donut and the beginning of its combined legend/category detail list.
- `after-expenses-chart.png`: expenses only, showing nine more evenly sized
  segments and the matching category rows below the chart.
- `after-expenses-legend.png`: the scrolled expense legend with separate category
  names, exact PLN amounts, percentages, and matching color swatches.
- `after-expenses-legend-end.png`: the remaining categories, including the final
  row, and the transition to the existing report entry list.

The legend and detail list are one component, so they cannot disagree about order
or colors. Amount order remains largest first. Resolving duplicate or similar
chart colors does not change stored category colors or entry-card accents.

## Automated coverage and limitations

JVM tests exercise palettes below, at, and above the 20-color base, up to 100
categories. Compose tests compare actual donut pixels with swatch pixels, verify
ordering and recomposition stability, and exercise a 35-category list. Separate
portrait fixtures cover long names and large currency amounts at font scale 1.8,
including dark theme and increased display density.
The compact-layout test uses all ten actual starter categories at 411 dp width,
checks exact PLN amounts in a right-aligned column beside the shares,
one text baseline per row, and a ten-row height of at most 320 dp. Overflow tests
exercise vertical and horizontal reachability separately.

Accessibility tests check category/amount/segment descriptions; a manual spoken
TalkBack session was not performed. The existing synthetic household has ten
categories, so these screenshots are not evidence of a 35-category household.
At very large category counts, colors inevitably become less distinguishable;
each category is also identified by text and its exact amount.
