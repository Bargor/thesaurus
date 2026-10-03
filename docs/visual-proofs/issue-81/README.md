# Issue #81 — persistent global account balance

These raw screenshots come from the API 31 `thesaurus_api31` emulator (1080 × 2400 pixels, 420 dpi), using the DEV app and its existing local Firebase Emulator household. No production account or data is used. The 100 synthetic entries span August–October 2026; no entries were added, modified, or removed for these captures.

## Before

`before-reports.png` was captured on master `d217cf7`, before installing this change. The main navigation has no persistent account balance.

## After

- `after-entries.png`: the fixed add-entry button clears the compact footer and navigation.
- `after-browse.png`: category totals are for October, while the footer retains the all-time household balance.
- `after-summary.png`: separate monthly summary cards retain their period totals; the footer remains global.
- `after-reports.png`: October's net is +1 297,40 zł, independently of the footer's +17 855,00 zł.
- `after-form-ime.png`: the unsaved empty form is scrolled to its complete save button with the numeric keyboard actually visible. The footer/navigation remain above the keyboard. No save was attempted.

## Verification scope

The shared footer sums all undeleted household entries, rather than the selected screen's period or filters. The ordinary layout is approximately 28 dp high, directly adjoining the main navigation. Full signed amounts, Polish currency formatting, and accessible balance status are retained.

A read-only audit verified the original 100 active entries (40 in August, 40 in September, 20 in October) and an exact signed sum of 1,785,500 grosze: **+17 855,00 zł**. No test operation cleared the DEV household.

Automated coverage includes arbitrary-precision aggregation, household/account isolation, local pending writes and cached offline state, edits and tombstones in a real local Firestore Emulator, all four main destinations and nested routes, the last scrolled list action, horizontal safe-area insets, narrow/wide viewports, font/display scaling, and an actually visible software keyboard. Accessibility semantics are asserted automatically; this is not a claim of manual TalkBack testing. Wide viewports are simulated in Compose tests, not a claim of a formal device-rotation journey.

Screenshots are captured with Android CLI and visually inspected individually. They are not cropped, composited, or otherwise edited.

## Local checks

- 177 JVM tests passed; lint and debug/DEV APK assembly passed.
- The complete 156-test Android run included all 9 new balance tests passing. Two existing screenshot-capture tests timed out in Compose's `forceRedraw` (not in value/layout assertions); both passed unchanged on individual reruns.
- Earlier new fixture failures were corrected before this run. An intermediate emulator system-service watchdog restart was resolved without clearing app data or the DEV household.
