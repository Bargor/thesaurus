# Issue #80 — cumulative report balance trend

Real, unedited API 31 DEV emulator screenshots with synthetic local Firebase data.

Captured on 2026-10-03 with Android CLI from `thesaurus_api31`, at native
1080 × 2400 / 420 dpi, light theme and normal font scale. The DEV household
contains the existing 100 synthetic entries across August–October 2026; no real
accounts, production Firebase data, or credentials were used. Capture files are
raw PNGs, not cropped, composed, or edited. Accounting periods end at today.

## Visual checks

- [Before](before-month.png): on master `540f1e1`, the category legend is followed
  immediately by the report entry list.
- [Monthly balance](after-month.png): the daily global chart follows the category
  legend and precedes the entries. October 1–3 carries **+16 557,60 zł** from
  August–September and ends at **+17 855,00 zł**, not the October-only report
  balance of +1 297,40 zł. Visible axes show PLN amounts, zero, and October dates.
- [Yearly balance](after-year.png): January 1–October 3 uses calendar-month
  buckets, carries the empty earlier months, and ends at **+17 855,00 zł**.
  The existing non-cumulative monthly trend remains below it.
- [Expense filter](expense-only.png): the same year with only expenses selected
  still shows the identical global **+17 855,00 zł** curve. The donut, entries,
  and existing annual trend remain expense-filtered. This is deliberately not
  an expense-only cumulative balance.

These screenshots reflect the user's follow-up: global history rather than a
filtered period total, labeled axes, and no balance legend or details button.
The two obsolete details-dialog screenshots were removed from this revision;
they remain recoverable in Git history.

Navigation was performed through the real report filter dialog,
then filters and the emulator's original animation settings were restored.
The local data audit still reports 100 entries and zero missing fixture entries.
Android CLI's layout provider returned an unrecognized instrumentation response,
so manual visual inspection used screenshots and grounded ADB taps/swipes.
This was manual QA, not an XML journey or a claim of manual TalkBack verification.

## Automated verification

Root reran `testDebugUnitTest lintDebug assembleDebug assembleDebugAndroidTest`
and the complete `pl.bargor.thesaurus.ui.reports` instrumentation package after
reviewing the implementation and tests: **167 JVM tests and 33 API 31 tests
passed**, with no lint errors. `assembleDevDebug` passed separately.

Coverage includes exact signed arithmetic beyond Long totals, filled empty buckets,
leap days and year transitions, Warsaw DST and timezone-boundary defaults,
bounded extreme ranges, historical opening balances, household isolation,
filter/sorting invariance, pending out-of-period offline Firestore writes,
tombstones, saved state, chart placement and accessibility semantics.
New narrow-layout tests check
text layout for clipping/ellipsis at 320 dp with 1.6× light and 1.8× dark fonts,
including very large exact amounts. They inspect the actual measured Canvas
tick labels and rendered foreground glyph pixels in light/dark themes, rather
than merely asserting that an axis container exists. No details controls remain.

The PR workflow additionally runs all instrumentation tests, Firestore Rules
tests, and hosting tests in its isolated CI emulators.
