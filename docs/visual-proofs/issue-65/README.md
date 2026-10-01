# Issue #65 — Report category scope and minimal Summary

These are unedited screenshots of the real Thesaurus DEV application on the
Android 12 / API 31 emulator: 1080 × 2400, 420 dpi, portrait, font scale 1.0.
The existing synthetic September 2026 household was preserved, not reseeded.
No real account, production database, or production credentials were used.
Every screenshot was visually inspected immediately after capture.

## Screenshots

| State | Evidence |
| --- | --- |
| Merged #68 baseline: old Summary content | [Before Summary](before-summary.png) |
| Merged #68 baseline: Reports without category controls | [Before Reports](before-reports.png) |
| Minimal Summary, month navigation | [Month shell](minimal-summary-month.png) |
| Minimal Summary, retained month/year toggle | [Year shell](minimal-summary-year.png) |
| September report, all categories | [All entries](reports-all.png) |
| September report, Dom only | [Category scope](reports-category.png) |
| Dom-only subcategory choices | [Dependent menu](reports-subcategory-options.png) |
| Dom → Elektronika and scoped totals | [Subcategory scope](reports-subcategory.png) |
| Matching scoped legend and single entry | [Chart and entries](reports-scoped-chart-entries.png) |
| Date/amount only, with direction and clear icons | [Sort options](reports-sort-options.png) |

## Observed walkthrough

1. Open **Podsumowanie**. Only the shared period navigator remains; no metrics,
   filters, loading text, empty-data message, or entry list remains. Tap its
   heading to switch from October 2026 to 2026 without losing navigation.
2. Open **Raporty** and select September 2026 with the previous-period arrow.
   All-category totals are income **19,062.50 PLN**, expense **5,347.85 PLN**,
   signed net **+13,714.65 PLN**. Subcategory selection is disabled without a
   parent category.
3. Select **Dom**. Expense and net become **523.80 PLN** and **−523.80 PLN**;
   the chart contains only Dom. Its subcategory menu offers only Dom children.
4. Select **Elektronika**. Expense is **17.36 PLN**, net is **−17.36 PLN**;
   the legend shows Dom at **17.36 PLN / 100%**, and the only entry is
   Dom → Elektronika, dated 28 September, for **−17.36 PLN**.
5. Open sorting. Only **Data księgowania** and **Kwota** are offered. No
   category/subcategory/tag sorting is offered, and no tag filter remains.
6. Dismiss the menu and tap the clear icon. All-category amounts and chart
   segments return, the subcategory selector is disabled, and the default
   descending date sort returns. The current period and entry-type choice
   are intentionally preserved.

The screenshot of all-category totals also exposes the existing large-balance
currency wrapping in the unchanged report cards. This issue does not redesign
those cards. Screenshots are not cropped or altered to conceal it.

## Automated coverage and boundaries

New coverage checks one dataset for entries/totals/charts/trends, inclusive date
boundaries, deleted entries, parent and household isolation, selected-empty
scopes, clearing/changing scope, signed date/amount sorting in both directions,
stable ties, obsolete saved tag/sort state, cached errors and pending writes.
The real Firebase Emulator integration test covers repository-backed scoped
reports through offline writes, taxonomy rename, reconnection, and tombstones.
Compose coverage additionally checks dependent menus, full accessibility
descriptions, and 48 dp targets at 320 dp width with font scale 1.8.

Physical-device, API 37, and spoken TalkBack testing are not claimed.

## Local verification

- 118 JVM tests passed.
- Debug and DEV APK assembly passed.
- Android lint passed with zero errors, 32 warnings, and one informational hint.
- 27 Firestore Rules tests and one hosting fallback test passed.
- Final report-controls instrumentation tests passed 4/4, including the
  large-font target regression and selected-empty result regression.
- The complete final API 31 instrumentation run passed 97/97 with zero
  failures, errors, or skipped tests, including all seven repository-backed
  Firebase integration tests. Local Auth and Firestore emulators were used.

An additional target-size assertion initially reproduced a narrow-font layout
failure; the responsive dropdown-width fix passed the same assertion without
weakening it.
