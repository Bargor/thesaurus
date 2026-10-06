# Issue #94 — structural refactor evidence

Captured by `ResponsibilitySplitVisualEvidenceTest` on the disposable CI API 31 emulator,
using the real screens and household navigation with deterministic synthetic September 2026
data. No real authentication, Firebase household or personal financial data is shown.

Source code commit: `f91da37`. [Passing CI run](https://github.com/Bargor/thesaurus/actions/runs/37532851659)
completed all 178 instrumentation tests without failures. JVM/lint, 28 Rules tests, invitation
hosting, 10 watchdog tests and APK assembly also passed. No local test execution was used.

Frames are the original 320 × 640 PNGs. This is the CI device's viewport, not a fixed application
resolution. Reports and category contents are scrollable; the frames show the specified position,
not a claim that the whole report fits on screen. These are after-refactor evidence, not a
pixel-diff baseline. Moved rendering bodies, existing UI regressions and navigation assertions
cover behavior preservation.

| Frame | Verified view |
| --- | --- |
| [Entries](entries.png) | Category styling, signed amounts, fixed add action and shared footer/navigation. |
| [Reports](reports.png) | Top of the scrollable report, exact total cards, category chart and settings/filter actions. |
| [Report filters](report-filters.png) | Existing period/type/taxonomy controls and fixed Cancel/Apply footer. |
| [Settings](settings.png) | Household settings navigation from Reports. |
| [Expanded categories](categories-expanded.png) | Expanded category scrolled to its Supermarket subcategory. |
| [Category editor](category-editor.png) | Existing title/type/color editor and save/cancel actions. |

The test publishes only its synthetic captures via MediaStore. The CI harness pulls the exact
`Pictures/ThesaurusTestEvidence/issue94` directory before destroying the emulator. This avoids
losing app-specific files when the test runner uninstalls the application.
